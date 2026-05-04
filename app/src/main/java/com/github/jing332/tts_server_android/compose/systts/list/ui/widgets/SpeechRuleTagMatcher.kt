package com.github.jing332.tts_server_android.compose.systts.list.ui.widgets

import com.github.jing332.database.entities.SpeechRule

object SpeechRuleTagMatcher {
    fun normalize(raw: String?): String {
        var s = raw.orEmpty()
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .trim()

        // 去掉普通外包标签：【女/女青年02】 -> 女/女青年02
        // 注意：只处理首尾完整包裹，不影响中间内容
        if (s.startsWith("【") && s.endsWith("】") && s.length > 2) {
            s = s.substring(1, s.length - 1).trim()
        }

        return s
    }

    fun normalizeLoose(raw: String?): String {
        var s = normalize(raw)

        // 如果是 “【女/女青年02】 温柔治愈女青年”
        // 提取中括号里的核心标签
        val bracketRegex = Regex("""【([^】]+)】""")
        val match = bracketRegex.find(raw.orEmpty())
        if (match != null) {
            s = normalize(match.groupValues[1])
        }

        return s
    }

    fun findTagKey(
        speechRule: SpeechRule,
        rawTag: String?
    ): String? {
        val target = normalizeLoose(rawTag)

        if (target.isBlank()) return null

        return speechRule.tags.entries.firstOrNull { entry ->
            val key = normalizeLoose(entry.key)
            val value = normalizeLoose(entry.value)

            key == target ||
                    value == target ||
                    entry.value.contains(target) ||
                    target.contains(key) ||
                    target.contains(value)
        }?.key
    }
}