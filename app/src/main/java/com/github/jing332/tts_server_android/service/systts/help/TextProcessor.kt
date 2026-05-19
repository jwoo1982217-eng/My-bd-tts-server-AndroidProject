package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import android.util.Log
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.database.constants.ReplaceExecution
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.systts.SpeechRuleInfo
import com.github.jing332.tts.ConfigType
import com.github.jing332.tts.error.TextProcessorError
import com.github.jing332.tts.synthesizer.ITextProcessor
import com.github.jing332.tts.synthesizer.TextSegment
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts_server_android.conf.SystemTtsConfig
import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleEngine
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class TextProcessor : ITextProcessor {
    private var isMultiVoice: Boolean = false

    private val isSplitSentence: Boolean
        get() = SystemTtsConfig.isSplitEnabled.value

    private val isReplaceEnabled: Boolean
        get() = SystemTtsConfig.isReplaceEnabled.value

    private lateinit var appContext: Context
    private lateinit var engine: SpeechRuleEngine

    private val textReplacer = TextReplacer()

    private var configs: List<TtsConfiguration> = emptyList()
    private var speechRules: List<SpeechRuleInfo> = emptyList()
    private var moduleEngines: List<Pair<SpeechRule, SpeechRuleEngine>> = emptyList()
    private var lastModuleCtxJson: String = ""
    private val random by lazy { Random(System.currentTimeMillis()) }

    private fun appendSpeechRuleLog(context: Context, message: String) {
        Log.i("朗读规则", message)
    }

    private fun normalizeSpeechRuleLookupId(ruleId: String): String {
        val value = ruleId.trim()
        if (value.isBlank()) return value

        val regex = Regex(
            pattern = """([._-](db|dev|new|test|debug|bak|backup|manual)(_\d{3})?)$""",
            option = RegexOption.IGNORE_CASE
        )

        return value.replace(regex, "")
    }

    private fun findSpeechRuleByIdWithBaseFallback(ruleId: String): SpeechRule? {
        val exact = dbm.speechRuleDao.getByRuleId(ruleId)

        if (exact != null) {
            if (!exact.isModule) {
                return exact
            }

            val mainRule = dbm.speechRuleDao.all.firstOrNull { rule ->
                rule.projectId == exact.projectId &&
                    !rule.isModule &&
                    (
                        rule.moduleId == "main" ||
                            rule.moduleType == "main" ||
                            rule.moduleType == "pipeline_entry"
                    )
            } ?: dbm.speechRuleDao.all.firstOrNull { rule ->
                rule.projectId == exact.projectId && !rule.isModule
            }

            if (mainRule != null) {
                appendSpeechRuleLog(
                    context = appContext,
                    message = "当前配置引用的是模块：${exact.moduleName.ifBlank { exact.name }}，已自动切换到项目入口规则：${mainRule.name}"
                )

                return mainRule
            }
        }

        val baseId = normalizeSpeechRuleLookupId(ruleId)

        val fallbackRule = dbm.speechRuleDao.allEnabled.firstOrNull { rule ->
            !rule.isModule && normalizeSpeechRuleLookupId(rule.ruleId) == baseId
        }

        if (fallbackRule != null) {
            return fallbackRule
        }

        val fallbackModule = dbm.speechRuleDao.all.firstOrNull { rule ->
            rule.isModule && normalizeSpeechRuleLookupId(rule.ruleId) == baseId
        }

        if (fallbackModule != null) {
            return dbm.speechRuleDao.all.firstOrNull { rule ->
                rule.projectId == fallbackModule.projectId &&
                    !rule.isModule &&
                    (
                        rule.moduleId == "main" ||
                            rule.moduleType == "main" ||
                            rule.moduleType == "pipeline_entry"
                    )
            } ?: dbm.speechRuleDao.all.firstOrNull { rule ->
                rule.projectId == fallbackModule.projectId && !rule.isModule
            }
        }

        return null
    }

    private fun loadEnabledModuleEngines(
        context: Context,
        mainRule: SpeechRule,
    ): List<Pair<SpeechRule, SpeechRuleEngine>> {
        val projectId = mainRule.projectId.trim()
        if (projectId.isBlank()) return emptyList()

        val modules = dbm.speechRuleDao.all
            .asSequence()
            .filter { it.projectId == projectId }
            .filter { it.isModule }
            .filter { it.isEnabled }
            .filter { it.code.isNotBlank() }
            .sortedWith(
                compareBy<SpeechRule> { it.moduleOrder }
                    .thenBy { it.order }
            )
            .toList()

        if (modules.isNotEmpty()) {
            appendSpeechRuleLog(
                context = context,
                message = "模块执行链已加载：${modules.size} 个模块"
            )
        }

        return modules.mapNotNull { module ->
            runCatching {
                val moduleEngine = SpeechRuleEngine(context, module).apply {
                    console.addLogListener { entry ->
                        appendSpeechRuleLog(
                            context = appContext,
                            message = "[模块:${module.moduleName.ifBlank { module.name }}] ${entry.message}"
                        )
                    }

                    eval()
                }

                module to moduleEngine
            }.onFailure {
                appendSpeechRuleLog(
                    context = context,
                    message = "模块加载失败：${module.moduleName.ifBlank { module.name }}，${it.message}"
                )
            }.getOrNull()
        }
    }

    private fun buildModuleCtxJson(text: String): String {
        val readerChapter = ReaderChapterBridgeClient.fetchCurrentChapterJson()
        val readerChapterJson = readerChapter.toString()
        val readerChapterWindow = ReaderChapterBridgeClient.fetchChapterWindowJson()
        val readerChapterWindowJson = readerChapterWindow.toString()

        val metaJson = JSONObject()
            .put("readerChapterJson", readerChapterJson)
            .put("readerChapterJsonLen", readerChapterJson.length)
            .put("readerChapterWindowJson", readerChapterWindowJson)
            .put("readerChapterWindowJsonLen", readerChapterWindowJson.length)
            .put("readerChapterBridgeMark", "reader-bridge-meta-v1")

        return JSONObject()
            .put("version", 1)
            .put("text", text)
            .put("segments", JSONArray())
            .put("readerChapter", readerChapter)
            .put("readerChapterJson", readerChapterJson)
            .put("readerChapterJsonLen", readerChapterJson.length)
            .put("readerChapterWindow", readerChapterWindow)
            .put("readerChapterWindowJson", readerChapterWindowJson)
            .put("readerChapterWindowJsonLen", readerChapterWindowJson.length)
            .put("readerChapterBridgeMark", "reader-bridge-v2")
            .put("roles", JSONObject())
            .put("emotions", JSONObject())
            .put("route", JSONObject())
            .put("meta", metaJson)
            .toString()
    }

    private fun readTextFromModuleCtx(ctxJson: String, fallback: String): String {
        return runCatching {
            val obj = JSONObject(ctxJson)

            if (obj.has("text")) {
                obj.optString("text", fallback)
            } else {
                fallback
            }
        }.getOrDefault(fallback)
    }

    private fun writeTextToModuleCtx(ctxJson: String, text: String): String {
        return runCatching {
            JSONObject(ctxJson)
                .put("text", text)
                .toString()
        }.getOrElse {
            buildModuleCtxJson(text)
        }
    }

    private fun processTextByModules(text: String): String {
        var currentText = text
        var currentCtxJson = buildModuleCtxJson(text)

        val preModuleEngines = moduleEngines.filterNot { isPostModule(it.first) }

        if (preModuleEngines.isEmpty()) {
            lastModuleCtxJson = currentCtxJson
            return text
        }

        preModuleEngines.forEach { pair ->
            val module = pair.first
            val moduleEngine = pair.second

            runCatching {
                currentCtxJson = injectModuleRuntimeMeta(currentCtxJson, module)
                val beforeCtxJson = currentCtxJson
                val afterCtxJson = moduleEngine.processCtxIfExists(currentCtxJson)

                if (afterCtxJson != beforeCtxJson) {
                    currentCtxJson = afterCtxJson
                    currentText = readTextFromModuleCtx(currentCtxJson, currentText)
                } else {
                    currentText = moduleEngine.processTextIfExists(currentText)
                    currentCtxJson = writeTextToModuleCtx(currentCtxJson, currentText)
                }
            }.onFailure {
                appendSpeechRuleLog(
                    context = appContext,
                    message = "模块执行失败：${module.moduleName.ifBlank { module.name }}，${it.message}"
                )
            }
        }

        lastModuleCtxJson = currentCtxJson
        return currentText
    }


    private fun isPostModule(module: SpeechRule): Boolean {
        val type = module.moduleType.trim().lowercase()

        if (
            type == "post" ||
            type == "after" ||
            type == "after_rule" ||
            type == "postprocess" ||
            type == "post_processor" ||
            type == "emotion_post"
        ) {
            return true
        }

        val code = module.code
        return code.contains("moduleStage: \"post\"") ||
            code.contains("moduleStage:'post'") ||
            code.contains("stage: \"post\"") ||
            code.contains("stage:'post'") ||
            code.contains("POST_MODULE")
    }

    private fun isEmotionTargetTag(tag: String): Boolean {
        val value = tag.trim()
        if (value.isBlank()) return false

        val lower = value.lowercase()

        if (lower == "narration" || lower == "narrator") return false
        if (value == "旁白") return false
        if (lower.startsWith("localsound")) return false
        if (lower.startsWith("sound")) return false
        if (value.contains("音效")) return false
        if (value.contains("括号")) return false

        return true
    }


    private fun hasSpeakableContent(text: String): Boolean {
        val clean = text
            .replace(
                Regex("\\[\\[(emo|emotion):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex("[\\s\"“”'‘’。，！？!?；;：:、（）()【】\\[\\]{}<>《》〈〉「」『』…—\\-]+"),
                ""
            )
            .trim()

        return clean.isNotBlank()
    }

    private fun applyEmotionToEverySentence(text: String, emotion: String): String {
        val emo = emotion.trim()
        if (text.isBlank() || emo.isBlank()) return text

        // 没有真正可朗读内容的片段，例如 ”、”、[[emo:tension]]” 等，不加情绪
        if (!hasSpeakableContent(text)) return text

        if (
            text.contains("[[emo:", ignoreCase = true) ||
            text.contains("[[emotion:", ignoreCase = true)
        ) {
            return text
        }

        val regex = Regex("([^。！？!?；;\\n]+[。！？!?；;]*)")
        var matched = false

        val result = regex.replace(text) { match ->
            val part = match.value

            if (!hasSpeakableContent(part)) {
                part
            } else {
                matched = true
                "[[emo:$emo]]$part"
            }
        }

        return if (matched) result else "[[emo:$emo]]$text"
    }

    private fun buildPostModuleCtxJson(
        fullText: String,
        fragments: List<SpeechRuleEngine.TextWithTag>,
    ): String {
        val arr = JSONArray()

        fragments.forEachIndexed { index, item ->
            arr.put(
                JSONObject()
                    .put("index", index)
                    .put("text", item.text)
                    .put("tag", item.tag)
                    .put("id", item.id)
                    .put("isDialogue", isEmotionTargetTag(item.tag))
            )
        }

        val baseObj = runCatching {
            if (lastModuleCtxJson.isNotBlank()) JSONObject(lastModuleCtxJson) else JSONObject()
        }.getOrElse {
            JSONObject()
        }

        baseObj.put("text", fullText)
        baseObj.put("segments", arr)

        if (!baseObj.has("meta")) {
            baseObj.put("meta", JSONObject())
        }

        return baseObj.toString()
    }

    private fun readFragmentsFromPostModuleCtxJson(
        ctxJson: String,
        original: List<SpeechRuleEngine.TextWithTag>,
    ): List<SpeechRuleEngine.TextWithTag> {
        return runCatching {
            val obj = JSONObject(ctxJson)
            val arr = obj.optJSONArray("segments") ?: return original

            original.mapIndexed { index, old ->
                val seg = arr.optJSONObject(index)

                if (seg == null) {
                    old
                } else {
                    var newText = seg.optString("text", old.text)
                    val emotion = seg.optString("emotion", "").trim()

                    if (emotion.isNotBlank() && isEmotionTargetTag(old.tag)) {
                        newText = applyEmotionToEverySentence(newText, emotion)
                    }

                    old.copy(text = newText)
                }
            }
        }.getOrElse {
            original
        }
    }


    private fun injectModuleRuntimeMeta(
        ctxJson: String,
        module: SpeechRule,
    ): String {
        return runCatching {
            val obj = if (ctxJson.isNotBlank()) JSONObject(ctxJson) else JSONObject()
            val meta = obj.optJSONObject("meta") ?: JSONObject()

            meta.put("moduleId", module.id)
            meta.put("moduleName", module.moduleName.ifBlank { module.name })
            meta.put("moduleApiFile", "module_api_${module.id}.txt")

            val readerChapter = ReaderChapterBridgeClient.fetchCurrentChapterJson()
            val readerChapterJson = readerChapter.toString()
            val readerChapterWindow = ReaderChapterBridgeClient.fetchChapterWindowJson()
            val readerChapterWindowJson = readerChapterWindow.toString()

            obj.put("readerChapter", readerChapter)
            obj.put("readerChapterJson", readerChapterJson)
            obj.put("readerChapterJsonLen", readerChapterJson.length)
            obj.put("readerChapterWindow", readerChapterWindow)
            obj.put("readerChapterWindowJson", readerChapterWindowJson)
            obj.put("readerChapterWindowJsonLen", readerChapterWindowJson.length)
            obj.put("readerChapterBridgeMark", "reader-bridge-runtime-v1")

            meta.put("readerChapterJson", readerChapterJson)
            meta.put("readerChapterJsonLen", readerChapterJson.length)
            meta.put("readerChapterWindowJson", readerChapterWindowJson)
            meta.put("readerChapterWindowJsonLen", readerChapterWindowJson.length)
            meta.put("readerChapterBridgeMark", "reader-bridge-runtime-v1")

            obj.put("meta", meta)
            obj.toString()
        }.getOrElse {
            ctxJson
        }
    }


    private fun processFragmentsByPostModules(
        fullText: String,
        fragments: List<SpeechRuleEngine.TextWithTag>,
    ): List<SpeechRuleEngine.TextWithTag> {
        val postModuleEngines = moduleEngines.filter { isPostModule(it.first) }

        if (postModuleEngines.isEmpty() || fragments.isEmpty()) {
            return fragments
        }

        var currentCtxJson = buildPostModuleCtxJson(fullText, fragments)

        postModuleEngines.forEach { pair ->
            val module = pair.first
            val moduleEngine = pair.second

            runCatching {
                currentCtxJson = injectModuleRuntimeMeta(currentCtxJson, module)
                val beforeCtxJson = currentCtxJson
                val afterCtxJson = moduleEngine.processCtxIfExists(currentCtxJson)

                if (afterCtxJson != beforeCtxJson) {
                    currentCtxJson = afterCtxJson
                }
            }.onFailure {
                appendSpeechRuleLog(
                    context = appContext,
                    message = "后置模块执行失败：${module.moduleName.ifBlank { module.name }}，${it.message}"
                )
            }
        }

        lastModuleCtxJson = currentCtxJson

        return readFragmentsFromPostModuleCtxJson(currentCtxJson, fragments)
    }


    override fun init(
        context: Context,
        configs: Map<Long, TtsConfiguration>,
    ): Result<Unit, TextProcessorError> {
        appContext = context.applicationContext
        isMultiVoice = SystemTtsConfig.isMultiVoiceEnabled.value

        if (isMultiVoice) {
            val ruleId = configs.values.toList().component1().speechInfo.tagRuleId
            val speechRule =
                findSpeechRuleByIdWithBaseFallback(ruleId)
                    ?: return Err(TextProcessorError.MissingRule(ruleId))

            engine = SpeechRuleEngine(context, speechRule).apply {
                console.addLogListener { entry ->
                    appendSpeechRuleLog(
                        context = appContext,
                        message = entry.message.toString()
                    )
                }

                eval()
            }

            moduleEngines = loadEnabledModuleEngines(context, speechRule)

            this.configs =
                configs.entries.map {
                    it.value.copy(
                        speechInfo = it.value.speechInfo.copy(configId = it.key)
                    )
                }

            speechRules = this.configs.map { it.speechInfo }
        } else {
            moduleEngines = emptyList()
            this.configs = configs.values.toList()
            if (this.configs.isEmpty()) {
                return Err(TextProcessorError.MissingConfig(ConfigType.SINGLE_VOICE))
            }
        }

        loadReplacer()
        return Ok(Unit)
    }

    fun loadReplacer() {
        textReplacer.load()
    }


    private val jttsSplitBridgePrefixRegex = Regex(
        "^\\s*(\\[\\[(?:emo|emotion|mimo_ctx|mimo_context|mimo_director|mimo_nl|mimo_tag|mimo_mode)[^\\]]*\\]\\]\\s*)+",
        RegexOption.IGNORE_CASE
    )

    private val jttsSingleBridgePrefixRegex = Regex(
        "^\\s*\\[\\[(?:emo|emotion|mimo_ctx|mimo_context|mimo_director|mimo_nl|mimo_tag|mimo_mode)[^\\]]*\\]\\]",
        RegexOption.IGNORE_CASE
    )

    private fun jttsExtractSplitBridgePrefix(text: String): String {
        val match = jttsSplitBridgePrefixRegex.find(text) ?: return ""
        return match.value.trim()
    }

    private fun jttsCopyBridgePrefixToSplitParts(originalText: String, parts: List<String>): List<String> {
        val prefix = jttsExtractSplitBridgePrefix(originalText)
        if (prefix.isBlank() || parts.isEmpty()) return parts

        return parts.map { part ->
            val trimmed = part.trimStart()
            if (trimmed.isBlank()) {
                part
            } else if (jttsSingleBridgePrefixRegex.containsMatchIn(trimmed)) {
                part
            } else {
                prefix + trimmed
            }
        }
    }


    private fun splitText(text: String): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        // 接上右上角“分割长句”开关：
        // 关闭时不强制切短；开启时才执行下面的 55 字 + 标点切分。
        if (!isSplitSentence) return listOf(clean)

        // 保护后置情绪模块生成的提示词前缀：
        // 1. [[emo:coldness]](冷淡，语调平直)
        // 2. (急促｜气息发紧)
        // 3. （急促｜气息发紧）
        // 这些前缀不能被逗号/顿号切开，并且切分后的每段都要继承。
        val leadingStylePrefixRegex = Regex("""^\s*(?:(?:\[\[emo:[^\]]+\]\]\s*(?:[（(][^）)]{1,80}[）)]))|(?:[（(][^）)]{1,80}[）)]))+\s*""")
        val styleKeywordRegex = Regex("""情绪|语调|气息|急促|发紧|冷淡|平直|紧张|平静|低声|高声|兴奋|悲伤|愤怒|温柔|严肃|疑惑|焦急|喘|沉稳|轻声|压低|克制|慌张""")
        val inlineStyleRegex = Regex("""(?:\[\[emo:[^\]]+\]\]\s*(?:[（(][^）)]{1,80}[）)]))|(?:[（(][^）)]{1,80}[）)])""")

        val leadingMatch = leadingStylePrefixRegex.find(clean)
        val leadingCandidate = leadingMatch?.value?.trim().orEmpty()

        val hasStylePrefix = leadingCandidate.isNotBlank() &&
                (
                        leadingCandidate.contains("[[emo:") ||
                                leadingCandidate.contains("｜") ||
                                leadingCandidate.contains("|") ||
                                styleKeywordRegex.containsMatchIn(leadingCandidate)
                        )

        val stylePrefix = if (hasStylePrefix) leadingCandidate else ""

        val bodyText = if (stylePrefix.isNotBlank() && leadingMatch != null) {
            clean.substring(leadingMatch.range.last + 1).trimStart()
        } else {
            clean
        }

        if (bodyText.isBlank()) return listOf(clean)

        val maxLen = 55
        val minPunctuationCutLen = 18

        fun forceShortSplit(piece: String): List<String> {
            val result = mutableListOf<String>()
            val buf = StringBuilder()
            var bufStartIndex = 0

            val protectedRanges = inlineStyleRegex.findAll(piece)
                .mapNotNull { match ->
                    val value = match.value
                    val shouldProtect =
                        value.contains("[[emo:") ||
                                value.contains("｜") ||
                                value.contains("|") ||
                                styleKeywordRegex.containsMatchIn(value)
                    if (shouldProtect) match.range else null
                }
                .toList()

            fun isProtected(index: Int): Boolean {
                return protectedRanges.any { index in it }
            }

            fun flush(nextStartIndex: Int) {
                val value = buf.toString().trim()
                if (value.isNotBlank()) result.add(value)
                buf.clear()
                bufStartIndex = nextStartIndex
            }

            fun isCutPunctuation(ch: Char): Boolean {
                return ch == '。' || ch == '！' || ch == '？' ||
                        ch == '；' || ch == ';' ||
                        ch == '，' || ch == ',' ||
                        ch == '、' ||
                        ch == '\n' || ch == '\r'
            }

            piece.forEachIndexed { index, ch ->
                if (buf.length == 0) bufStartIndex = index
                buf.append(ch)

                if (!isProtected(index) && isCutPunctuation(ch) && buf.length >= minPunctuationCutLen) {
                    flush(index + 1)
                    return@forEachIndexed
                }

                if (!isProtected(index) && buf.length >= maxLen) {
                    val current = buf.toString()
                    val candidates = listOf(
                        current.lastIndexOf("，"),
                        current.lastIndexOf(","),
                        current.lastIndexOf("、"),
                        current.lastIndexOf("；"),
                        current.lastIndexOf(";"),
                        current.lastIndexOf(" ")
                    ).filter { pos ->
                        pos >= 12 && !isProtected(bufStartIndex + pos)
                    }

                    val cutAt = candidates.maxOrNull() ?: -1

                    if (cutAt > 0 && cutAt < current.length - 1) {
                        val head = current.substring(0, cutAt + 1).trim()
                        val tailRaw = current.substring(cutAt + 1)
                        val dropped = tailRaw.length - tailRaw.trimStart().length
                        val tail = tailRaw.trimStart()

                        if (head.isNotBlank()) result.add(head)
                        buf.clear()
                        buf.append(tail)
                        bufStartIndex = bufStartIndex + cutAt + 1 + dropped
                    } else {
                        flush(index + 1)
                    }
                }
            }

            val last = buf.toString().trim()
            if (last.isNotBlank()) result.add(last)

            return result
        }

        val pieces = forceShortSplit(bodyText)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (stylePrefix.isBlank()) return pieces

        // 每个切出来的小段都继承完整情绪/语气前缀，避免后半段丢提示词。
        return pieces.map { part ->
            val cleanPart = part.trimStart()
            if (cleanPart.startsWith(stylePrefix)) {
                cleanPart
            } else {
                stylePrefix + cleanPart
            }
        }
    }

    private fun replace(text: String, @ReplaceExecution execution: Int): String {
        return if (isReplaceEnabled) {
            textReplacer.replace(text, execution)
        } else {
            text
        }
    }

    override fun process(
        text: String,
        presetConfig: TtsConfiguration?,
    ): Result<List<TextSegment>, TextProcessorError> {
        val resultList = mutableListOf<TextSegment>()
        val beforeRuleText = replace(text, ReplaceExecution.BEFORE)
        val replacedText = if (isMultiVoice) {
            processTextByModules(beforeRuleText)
        } else {
            beforeRuleText
        }

        fun add(vararg fragments: TextSegment) {
            fragments.forEach { f ->
                resultList.add(
                    TextSegment(
                        text = replace(f.text, ReplaceExecution.AFTER),
                        tts = f.tts
                    )
                )
            }
        }

        fun splitAndAdd(text: String, config: TtsConfiguration) {
            jttsCopyBridgePrefixToSplitParts(text, splitText(text)).forEach { segment ->
                var checkText = segment.trim()

                // 去掉开头的情绪/语气提示词，例如 [开心]、【低声】、（旁白）、(生气)
                while (true) {
                    val before = checkText
                    checkText = checkText
                        .replace(
                            Regex("""^\s*[\[【（(][^\]】）)]{1,20}[\]】）)]\s*[:：，,、。.!！?？…—-]*\s*"""),
                            ""
                        )
                        .trim()

                    if (checkText == before) break
                }

                // 去掉情绪/语气前缀后，正文必须至少包含一个真实可朗读字符
                // 只有标点、引号、括号、破折号、省略号时不送 TTS
                val hasRealText = checkText.any { ch ->
                    ch.isLetterOrDigit()
                }

                if (hasSpeakableContent(segment) && hasRealText) {
                    add(TextSegment(text = segment, tts = config))
                }
            }
        }

        fun stripLeadingStylePrefixForSpeakableCheck(value: String): String {
            var textValue = value.trim()

            // 去掉开头的情绪/语气提示词：
            // [[emo:tension]](急促｜气息发紧)
            // (急促｜气息发紧)
            // （急促｜气息发紧）
            val prefixRegex = Regex("""^\s*(?:(?:\[\[emo:[^\]]+\]\]\s*)?(?:[（(][^）)]{1,80}[）)])|\[\[emo:[^\]]+\]\])+\s*""")

            while (true) {
                val match = prefixRegex.find(textValue) ?: break
                if (match.range.first != 0) break

                val next = textValue.substring(match.range.last + 1).trimStart()
                if (next == textValue) break
                textValue = next
            }

            return textValue.trim()
        }



        try {
            if (presetConfig != null) {
                splitAndAdd(replacedText, presetConfig)
            } else if (isMultiVoice) {
                val fragments = engine.handleText(
                    replacedText,
                    speechRules,
                    lastModuleCtxJson
                )
                  val postProcessedFragments = processFragmentsByPostModules(
                      fullText = replacedText,
                      fragments = fragments
                  )

                  postProcessedFragments.forEach { txtWithTag ->
                    if (txtWithTag.text.isNotBlank()) {
                        val sameTagList = configs.filter {
                            !it.speechInfo.isStandby && it.speechInfo.tag == txtWithTag.tag
                        }

                        val configFromId =
                            sameTagList.find { it.speechInfo.configId == txtWithTag.id }

                        val config = configFromId
                            ?: sameTagList.randomOrNull(random)
                            ?: configs.randomOrNull(random)
                            ?: return Err(
                                TextProcessorError.MissingConfig(
                                    ConfigType.TAG,
                                    "tag=${txtWithTag.tag}, id=${txtWithTag.id}"
                                )
                            )

                        splitAndAdd(txtWithTag.text, config)
                    }
                }
            } else {
                val singleVoice = configs.randomOrNull(random)
                    ?: return Err(
                        TextProcessorError.MissingConfig(
                            ConfigType.SINGLE_VOICE,
                            "single voice"
                        )
                    )

                splitAndAdd(replacedText, singleVoice)
            }
        } catch (e: UninitializedPropertyAccessException) {
            return Err(TextProcessorError.Initialization)
        } catch (e: Exception) {
            return Err(TextProcessorError.HandleText(e))
        }

        return Ok(resultList)
    }
}
