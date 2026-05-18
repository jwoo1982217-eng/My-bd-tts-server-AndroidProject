package com.github.jing332.tts_server_android.service.systts.help

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CacheAudioQueueTranslator {
    private data class RoleInfo(
        val name: String,
        val voice: String,
    )

    fun translate(
        context: Context,
        bookName: String,
        rawQueue: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val roleMap = loadRoleMap(context, bookName)
        val dialogCache = loadDialogCache(context, bookName)

        return rawQueue.mapIndexed { index, raw ->
            val text = firstString(raw, "text", "content", "line", "sentence", "value", "台词", "内容")
            val rawTag = firstString(raw, "tag", "speechTag", "标签")
            val rawRoleName = firstString(
                raw,
                "roleName", "role", "characterName", "character",
                "speakerName", "speaker", "人物", "角色", "说话人"
            )

            val rawVoice = firstString(
                raw,
                "actualVoice", "voice", "voiceName", "voiceId",
                "speakerVoice", "tts", "ttsName", "音色", "声音"
            )

            val characterInfo = raw["characterInfo"]
                ?: raw["roleInfo"]
                ?: raw["speakerInfo"]

            val characterName = nestedFirstString(
                characterInfo,
                "name", "roleName", "role", "characterName", "speakerName", "speaker", "人物", "角色", "说话人"
            )

            val characterVoice = nestedFirstString(
                characterInfo,
                "voice", "voiceName", "voiceId", "speakerVoice", "tts", "ttsName", "音色", "声音"
            )

            val dialogHit = if (text.isNotBlank()) findDialogHit(dialogCache, text) else null

            val roleName = listOf(
                rawRoleName,
                characterName,
                dialogHit?.name.orEmpty(),
                if (rawTag.isRealRoleTag()) rawTag else "",
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val roleInfo = roleMap[roleName]
            val voice = listOf(
                rawVoice,
                characterVoice,
                dialogHit?.voice.orEmpty(),
                roleInfo?.voice.orEmpty(),
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val finalRole = roleName.ifBlank {
                when {
                    rawTag.equals("duihua", true) -> "duihua"
                    rawTag.equals("narration", true) -> "narration"
                    rawTag.isNotBlank() -> rawTag
                    else -> "narration"
                }
            }

            val finalVoice = voice
            val out = LinkedHashMap<String, Any?>()
            raw.forEach { (k, v) -> out[k] = v }

            out["index"] = firstString(raw, "index").toIntOrNull() ?: index
            out["text"] = text
            out["roleName"] = finalRole
            out["role"] = finalRole
            out["tag"] = if (finalRole.isRealRoleTag()) finalRole else rawTag.ifBlank { finalRole }
            out["voice"] = finalVoice
            out["actualVoice"] = finalVoice
            out["source"] = "apk_cache_audioqueue_translator"

            out["characterInfo"] = JSONObject()
                .put("name", finalRole)
                .put("voice", finalVoice)

            if (firstString(raw, "emotion", "emo", "style", "mood", "情绪", "感情").isBlank()) {
                out["emotion"] = "无"
            }

            out
        }
    }

    private fun loadRoleMap(context: Context, bookName: String): Map<String, RoleInfo> {
        val out = linkedMapOf<String, RoleInfo>()
        val safeBook = bookName.trim().ifBlank { "默认" }

        roleManagerDirs(context).forEach { dir ->
            listOf(
                File(dir, "shuming.$safeBook.json"),
                File(dir, "characterRecords.json"),
                File(dir, "gengxin.json"),
            ).forEach { file ->
                val arr = readJsonArray(file) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    val voice = obj.optString("voice", "").trim()
                    if (name.isNotBlank()) {
                        out[name] = RoleInfo(name, voice)
                    }

                    obj.optString("aliases", "")
                        .split("|", "｜", ",", "，", "/", "、")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { alias ->
                            out[alias] = RoleInfo(name.ifBlank { alias }, voice)
                        }
                }
            }
        }

        return out
    }

    private fun loadDialogCache(context: Context, bookName: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        roleManagerDirs(context).forEach { dir ->
            val file = File(dir, "dialog_cache.json")
            if (!file.exists()) return@forEach

            runCatching {
                val text = file.readText(Charsets.UTF_8)
                when {
                    text.trim().startsWith("[") -> {
                        val arr = JSONArray(text)
                        collectObjects(arr, result)
                    }
                    text.trim().startsWith("{") -> {
                        val obj = JSONObject(text)
                        collectObjects(obj, result)
                    }
                }
            }
        }
        return result
    }

    private fun collectObjects(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                out += value
                value.keys().forEach { key ->
                    collectObjects(value.opt(key), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectObjects(value.opt(i), out)
                }
            }
        }
    }

    private fun findDialogHit(dialogs: List<JSONObject>, text: String): RoleInfo? {
        val target = normalizeText(text)
        if (target.isBlank()) return null

        dialogs.forEach { obj ->
            val t = listOf("text", "content", "line", "sentence", "台词", "内容")
                .asSequence()
                .map { obj.optString(it, "") }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            val normalized = normalizeText(t)
            if (normalized.isBlank()) return@forEach

            val matched = normalized == target ||
                    normalized.contains(target) ||
                    target.contains(normalized)

            if (matched) {
                val role = listOf("roleName", "role", "name", "characterName", "speaker", "tag")
                    .asSequence()
                    .map { obj.optString(it, "") }
                    .firstOrNull { it.isRealRoleTag() }
                    .orEmpty()

                val voice = listOf("actualVoice", "voice", "voiceName", "tts", "音色", "声音")
                    .asSequence()
                    .map { obj.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()

                if (role.isNotBlank() || voice.isNotBlank()) {
                    return RoleInfo(role, voice)
                }
            }
        }

        return null
    }

    private fun readJsonArray(file: File): JSONArray? {
        return runCatching {
            if (!file.exists()) return null
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return null
            JSONArray(text)
        }.getOrNull()
    }

    private fun firstString(raw: Map<String, Any?>, vararg keys: String): String {
        keys.forEach { key ->
            val value = raw[key] ?: return@forEach
            val s = value.toString().trim()
            if (s.isNotBlank() && s != "null") return s
        }
        return ""
    }

    private fun nestedFirstString(value: Any?, vararg keys: String): String {
        if (value == null) return ""

        return when (value) {
            is JSONObject -> {
                keys.asSequence()
                    .map { value.optString(it, "").trim() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
            is Map<*, *> -> {
                keys.asSequence()
                    .map { value[it]?.toString()?.trim().orEmpty() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
            else -> {
                runCatching {
                    val s = value.toString().trim()
                    if (s.startsWith("{") && s.endsWith("}")) {
                        val obj = JSONObject(s)
                        keys.asSequence()
                            .map { obj.optString(it, "").trim() }
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                    } else {
                        ""
                    }
                }.getOrDefault("")
            }
        }
    }

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("\\s+"), "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("！", "")
            .replace("？", "")
            .replace("：", "")
            .replace("；", "")
            .trim()
    }

    private fun String.isRealRoleTag(): Boolean {
        val v = trim()
        if (v.isBlank()) return false
        if (v.equals("duihua", true)) return false
        if (v.equals("narration", true)) return false
        if (v.equals("旁白", true)) return false
        if (v.startsWith("localSound")) return false
        if (v.startsWith("tts.default")) return false
        return true
    }

    private fun roleManagerDirs(context: Context): List<File> {
        val appDir = File(context.filesDir, "plugins/mingwuyan")
        val externalDir = File(
            "/storage/emulated/0/Android/data/${context.packageName}/files/plugins/mingwuyan"
        )
        return listOf(appDir, externalDir).distinctBy { it.absolutePath }
    }
}
