package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import android.os.Bundle
import com.github.jing332.tts_server_android.service.LegadoBridgeKeepAliveService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AudiobookGenerationBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, TaskState>()

    fun prepare(context: Context, extras: Bundle?): Bundle {
        LegadoBridgeKeepAliveService.start(context.applicationContext)
        val task = TaskState(
            taskId = newTaskId(),
            bookName = extras?.getString("bookName").orEmpty(),
            bookUrl = extras?.getString("bookUrl").orEmpty(),
            author = extras?.getString("author").orEmpty(),
            origin = extras?.getString("origin").orEmpty(),
            startChapterIndex = extras?.getInt("startChapterIndex", -1) ?: -1,
            preloadCount = extras?.getInt("preloadCount", 0) ?: 0,
            expectedChapterCount = extras?.getInt("chapterCount", 0) ?: 0,
        )
        tasks[task.taskId] = task
        return task.toBundle("已创建有声书生成任务，等待章节正文")
    }

    fun appendChapter(arg: String?, extras: Bundle?): Bundle {
        val task = findTask(arg, extras) ?: return errorBundle("找不到有声书生成任务")
        val chapter = AudioCacheFactory.AudiobookChapterInput(
            chapterIndex = extras?.getInt("chapterIndex", -1) ?: -1,
            chapterTitle = extras?.getString("chapterTitle").orEmpty(),
            chapterText = extras?.getString("chapterText").orEmpty()
        )
        if (chapter.chapterIndex < 0 || chapter.chapterText.isBlank()) {
            return errorBundle("章节正文为空，TTS 端未接受")
        }
        task.addChapter(chapter)
        return task.toBundle("已接收第 ${chapter.chapterIndex + 1} 章正文")
    }

    fun start(context: Context, arg: String?, extras: Bundle?): Bundle {
        LegadoBridgeKeepAliveService.start(context.applicationContext)
        val task = findTask(arg, extras) ?: return errorBundle("找不到有声书生成任务")
        return task.start(context.applicationContext)
    }

    fun submit(context: Context, extras: Bundle?): Bundle {
        LegadoBridgeKeepAliveService.start(context.applicationContext)
        val chapters = parseChapters(extras?.getString("chaptersJson").orEmpty())
        if (chapters.isEmpty()) return errorBundle("没有可提交的章节正文")

        val task = TaskState(
            taskId = newTaskId(),
            bookName = extras?.getString("bookName").orEmpty(),
            bookUrl = extras?.getString("bookUrl").orEmpty(),
            author = extras?.getString("author").orEmpty(),
            origin = extras?.getString("origin").orEmpty(),
            startChapterIndex = extras?.getInt("startChapterIndex", -1) ?: -1,
            preloadCount = extras?.getInt("preloadCount", chapters.size) ?: chapters.size,
            expectedChapterCount = extras?.getInt("chapterCount", chapters.size) ?: chapters.size,
        )
        chapters.forEach { task.addChapter(it) }
        tasks[task.taskId] = task
        return task.start(context.applicationContext)
    }

    fun query(arg: String?, extras: Bundle?): Bundle {
        val task = findTask(arg, extras) ?: return errorBundle("找不到有声书生成任务")
        return task.toBundle()
    }

    fun cancel(arg: String?, extras: Bundle?): Bundle {
        val task = findTask(arg, extras) ?: return errorBundle("找不到有声书生成任务")
        task.cancel()
        return task.toBundle("已取消有声书生成任务")
    }

    private fun findTask(arg: String?, extras: Bundle?): TaskState? {
        val taskId = extras?.getString("taskId").orEmpty().ifBlank { arg.orEmpty() }
        return tasks[taskId]
    }

    private fun parseChapters(json: String): List<AudioCacheFactory.AudiobookChapterInput> {
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val text = obj.optString("chapterText", obj.optString("text", ""))
                    if (text.isBlank()) continue
                    add(
                        AudioCacheFactory.AudiobookChapterInput(
                            chapterIndex = obj.optInt("chapterIndex", -1),
                            chapterTitle = obj.optString("chapterTitle", obj.optString("title", "")),
                            chapterText = text
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun errorBundle(message: String): Bundle {
        return Bundle().apply {
            putBoolean("ok", false)
            putString("error", message)
            putString("message", message)
        }
    }

    private fun newTaskId(): String {
        return "audiobook-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    private class TaskState(
        val taskId: String,
        val bookName: String,
        val bookUrl: String,
        val author: String,
        val origin: String,
        val startChapterIndex: Int,
        val preloadCount: Int,
        val expectedChapterCount: Int,
    ) {
        private val chapters = mutableListOf<AudioCacheFactory.AudiobookChapterInput>()

        @Volatile
        private var job: Job? = null

        @Volatile
        private var status: String = "pending"

        @Volatile
        private var message: String = "等待开始"

        @Volatile
        private var totalChapters: Int = expectedChapterCount

        @Volatile
        private var readyChapters: Int = 0

        @Volatile
        private var failedChapters: Int = 0

        @Volatile
        private var totalItems: Int = 0

        @Volatile
        private var readyItems: Int = 0

        @Volatile
        private var failedItems: Int = 0

        @Synchronized
        fun addChapter(chapter: AudioCacheFactory.AudiobookChapterInput) {
            chapters.removeAll { it.chapterIndex == chapter.chapterIndex }
            chapters.add(chapter)
            chapters.sortBy { it.chapterIndex }
            totalChapters = chapters.size.coerceAtLeast(expectedChapterCount)
            message = "已接收 ${chapters.size} 章正文"
        }

        @Synchronized
        fun start(context: Context): Bundle {
            if (chapters.isEmpty()) return errorBundle("没有可生成的章节正文")
            val activeJob = job
            if (activeJob != null && activeJob.isActive) {
                return toBundle("有声书生成任务正在运行")
            }

            status = "pending"
            message = "TTS 已接收 ${chapters.size} 章，准备生成"
            totalChapters = chapters.size
            val submittedChapters = chapters.toList()
            job = scope.launch {
                try {
                    update(
                        AudioCacheFactory.AudiobookGenerationProgress(
                            status = "caching_audio",
                            message = "正在生成有声书音频",
                            totalChapters = submittedChapters.size,
                            readyChapters = 0,
                            failedChapters = 0,
                            totalItems = 0,
                            readyItems = 0,
                            failedItems = 0
                        )
                    )
                    val final = AudioCacheFactory.generateAudiobookChapters(
                        context = context.applicationContext,
                        bookName = bookName,
                        bookUrl = bookUrl,
                        chapters = submittedChapters,
                        onProgress = ::update,
                        isCancelled = { status == "cancelled" }
                    )
                    update(final)
                } catch (e: CancellationException) {
                    status = "cancelled"
                    message = "已取消有声书生成任务"
                } catch (e: Throwable) {
                    status = "failed"
                    message = "有声书生成失败：${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
            return toBundle("TTS 已接收 ${chapters.size} 章，开始生成有声书缓存")
        }

        @Synchronized
        fun cancel() {
            status = "cancelled"
            message = "已取消有声书生成任务"
            job?.cancel()
            job = null
        }

        @Synchronized
        fun update(progress: AudioCacheFactory.AudiobookGenerationProgress) {
            if (status == "cancelled") return
            status = progress.status
            message = progress.message
            totalChapters = progress.totalChapters
            readyChapters = progress.readyChapters
            failedChapters = progress.failedChapters
            totalItems = progress.totalItems
            readyItems = progress.readyItems
            failedItems = progress.failedItems
        }

        @Synchronized
        fun toBundle(overrideMessage: String? = null): Bundle {
            return Bundle().apply {
                putBoolean("ok", true)
                putString("taskId", taskId)
                putString("status", status)
                putString("message", overrideMessage ?: message)
                putString("bookName", bookName)
                putString("bookUrl", bookUrl)
                putString("author", author)
                putString("origin", origin)
                putInt("startChapterIndex", startChapterIndex)
                putInt("preloadCount", preloadCount)
                putInt("acceptedChapters", chapters.size)
                putInt("chapterCount", totalChapters)
                putInt("totalChapters", totalChapters)
                putInt("readyChapters", readyChapters)
                putInt("failedChapters", failedChapters)
                putInt("totalItems", totalItems)
                putInt("readyItems", readyItems)
                putInt("failedItems", failedItems)
                putLong("time", System.currentTimeMillis())
            }
        }
    }
}
