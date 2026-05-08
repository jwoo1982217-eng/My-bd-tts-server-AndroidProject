package com.github.jing332.tts_server_android.model.rhino.speech_rule

import android.content.Context
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.TagsDataMap
import com.github.jing332.database.entities.systts.SpeechRuleInfo

import com.github.jing332.script.runtime.console.Console
import com.github.jing332.script.simple.SimpleScriptEngine
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.script.withRhinoContext
import com.github.jing332.tts_server_android.R
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Context as RhinoContext

class SpeechRuleEngine(
    val context: Context,
    private val rule: SpeechRule
) {
    companion object {
        const val OBJ_JS = "SpeechRuleJS"

        const val FUNC_GET_TAG_NAME = "getTagName"
        const val FUNC_HANDLE_TEXT = "handleText"
        const val FUNC_SPLIT_TEXT = "splitText"
        const val FUNC_PROCESS_TEXT = "processText"
        const val FUNC_PROCESS_CTX = "processCtx"
        const val FUNC_PREPARE_CHAPTER_AUDIO_QUEUE = "prepareChapterAudioQueue"

        fun getTagName(context: Context, speechRule: SpeechRule, info: SpeechRuleInfo): String {
            val engine = SpeechRuleEngine(context, speechRule)
            engine.eval()

            val tagName = try {
                engine.getTagName(info.tag, info.tagData)
            } catch (_: NoSuchMethodException) {
                speechRule.tags[info.tag] ?: ""
            }

            return tagName
        }
    }

    val engine = SimpleScriptEngine(context, rule.ruleId)
    var console: Console
        get() = engine.runtime.console
        set(value) {
            engine.runtime.console = value
        }


    private val objJS
        get() = engine.get(OBJ_JS) as NativeObject

    fun eval() {
        engine.execute(rule.code.toScriptSource())
    }

    @Suppress("UNCHECKED_CAST")
    fun evalInfo() {
        eval()
        objJS.apply {
            rule.name = get("name").toString()
            rule.ruleId = get("id").toString()
            rule.author = get("author").toString()

            rule.tags = get("tags") as Map<String, String>

            rule.tagsData =
                getOrDefault(
                    "tagsData",
                    emptyMap<String, Map<String, Map<String, String>>>()
                ) as TagsDataMap

            runCatching {
                rule.version = ScriptRuntime.toNumber(get("version")).toInt()
            }.onFailure {
                throw NumberFormatException(context.getString(R.string.plugin_bad_format))
            }
        }
    }

    fun getTagName(tag: String, tagMap: Map<String, String>): String {
        return engine.invokeMethod(objJS, FUNC_GET_TAG_NAME, tag, tagMap).toString()
    }

    fun processTextIfExists(text: String): String {
        return try {
            engine.invokeMethod(objJS, FUNC_PROCESS_TEXT, text)?.toString() ?: text
        } catch (_: NoSuchMethodException) {
            text
        }
    }

    fun processCtxIfExists(ctxJson: String): String {
        return try {
            engine.invokeMethod(objJS, FUNC_PROCESS_CTX, ctxJson)?.toString() ?: ctxJson
        } catch (_: NoSuchMethodException) {
            ctxJson
        }
    }

    fun prepareChapterAudioQueueIfExists(chapter: Map<String, Any?>): List<Map<String, Any?>> {
        return try {
            val result = engine.invokeMethod(objJS, FUNC_PREPARE_CHAPTER_AUDIO_QUEUE, toNativeObject(chapter))
            normalizeAudioQueueResult(result)
        } catch (_: NoSuchMethodException) {
            emptyList()
        }
    }

    private fun normalizeAudioQueueResult(value: Any?): List<Map<String, Any?>> {
        val normalized = jsValueToKotlin(value)
        val rawList = when (normalized) {
            is List<*> -> normalized
            is Map<*, *> -> {
                val queue = normalized["audioQueue"]
                    ?: normalized["queue"]
                    ?: normalized["items"]
                    ?: normalized["data"]
                queue as? List<*> ?: emptyList<Any?>()
            }

            else -> emptyList()
        }

        return rawList.mapNotNull { item ->
            (jsValueToKotlin(item) as? Map<*, *>)?.entries?.associate { entry ->
                entry.key.toString() to entry.value
            }
        }
    }

    private fun toNativeObject(values: Map<String, Any?>): ScriptableObject {
        return withRhinoContext { cx ->
            val obj = cx.newObject(engine.globalScope) as ScriptableObject
            values.forEach { (key, value) ->
                ScriptableObject.putProperty(obj, key, toJsValue(cx, value))
            }
            obj
        }
    }

    private fun toJsValue(cx: RhinoContext, value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val obj = cx.newObject(engine.globalScope) as ScriptableObject
                value.forEach { (key, child) ->
                    if (key != null) {
                        ScriptableObject.putProperty(obj, key.toString(), toJsValue(cx, child))
                    }
                }
                obj
            }

            is Iterable<*> -> cx.newArray(
                engine.globalScope,
                value.map { toJsValue(cx, it) }.toTypedArray()
            )

            is Array<*> -> cx.newArray(
                engine.globalScope,
                value.map { toJsValue(cx, it) }.toTypedArray()
            )

            else -> RhinoContext.javaToJS(value, engine.globalScope)
        }
    }

    private fun jsValueToKotlin(value: Any?): Any? {
        return when (value) {
            null, Undefined.instance, Scriptable.NOT_FOUND -> null
            is NativeJavaObject -> jsValueToKotlin(value.unwrap())
            is NativeArray -> (0 until value.length.toInt()).map { index ->
                jsValueToKotlin(value.get(index, value))
            }

            is NativeObject -> value.ids.associate { id ->
                val key = id.toString()
                val child = when (id) {
                    is Number -> value.get(id.toInt(), value)
                    else -> value.get(key, value)
                }
                key to jsValueToKotlin(child)
            }

            is Map<*, *> -> value.entries.associate { entry ->
                entry.key.toString() to jsValueToKotlin(entry.value)
            }

            is Iterable<*> -> value.map { jsValueToKotlin(it) }
            is Array<*> -> value.map { jsValueToKotlin(it) }
            else -> value
        }
    }

    data class TagData(val id: String, val value: String)

    fun handleText(
        text: String,
        list: List<SpeechRuleInfo> = emptyList(),
        ctxJson: String = ""
    ): List<TextWithTag> {
        val tagsDataMap: MutableMap<String, MutableMap<String, MutableList<Map<String, String>>>> =
            mutableMapOf()
        list.forEach { info ->
            if (tagsDataMap[info.tag] == null)
                tagsDataMap[info.tag] = mutableMapOf()

            info.tagData.forEach {
                if (tagsDataMap[info.tag]!![it.key] == null)
                    tagsDataMap[info.tag]!![it.key] = mutableListOf()

                tagsDataMap[info.tag]!![it.key]!!.add(
                    mapOf(
                        "id" to info.configId.toString(),
                        "value" to it.value
                    )
                )
            }
        }
        return handleText(text, tagsDataMap, ctxJson)
    }

    /** ['dialogue']['role']= List<TagData>
     *@param tagsDataSet 例： key: dialogue, value: map(key: role, value: [{tagDataId:111, 张三, 李四])
     */
    fun handleText(
        text: String,
        tagsDataMap: Map<String, Map<String, List<Map<String, String>>>>,
        ctxJson: String = ""
    ): List<TextWithTag> {
        val resultList: MutableList<TextWithTag> = mutableListOf()
        engine.invokeMethod(objJS, FUNC_HANDLE_TEXT, text, tagsDataMap, ctxJson)
            ?.run { this as List<*> }
            ?.let { list ->
                list.forEach {
                    if (it is Map<*, *>) {
                        resultList.add(
                            TextWithTag(
                                it["text"].toString(),
                                it["tag"].toString(),
                                it.getOrDefault("id", 0).toString().toLong(),
                            )
                        )
                    }
                }

            }
        return resultList
    }

    @Suppress("UNCHECKED_CAST")
    fun splitText(text: String): List<CharSequence> {
        return engine.invokeMethod(
            objJS,
            FUNC_SPLIT_TEXT,
            text
        ) as List<CharSequence>
    }

    data class TextWithTag(val text: String, val tag: String, val id: Long)
}
