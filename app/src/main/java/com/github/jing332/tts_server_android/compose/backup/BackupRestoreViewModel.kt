package com.github.jing332.tts_server_android.compose.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.drake.net.utils.withIO
import com.github.jing332.common.utils.FileUtils
import com.github.jing332.common.utils.ZipUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.replace.GroupWithReplaceRule
import com.github.jing332.database.entities.systts.GroupWithSystemTts
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

enum class SpeechRuleImportMode {
    // 1. 覆盖更新：同 ruleId 就覆盖旧规则
    OverwriteUpdate,

    // 2. 修改 ID：自动生成一个新 ID，例如 mingwuyan_v78_manual_001
    RenameId,

    // 3. 同基础 ID 共存：自动生成 mingwuyan_v78_dev_001 这种共存 ID
    SameBaseCoexist
}

class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {
    // UI 弹窗接 3 个按钮时，改这个值即可
    var speechRuleImportMode: SpeechRuleImportMode = SpeechRuleImportMode.OverwriteUpdate

    // 导入 speechRules.json 时记录：旧 ruleId -> 新 ruleId
    // 导入 list.json 时用它同步修复 TTS 配置里的 tagRuleId
    private var speechRuleIdImportMap: Map<String, String> = emptyMap()

    // ... /cache/backupRestore
    private val backupRestorePath by lazy {
        application.externalCacheDir!!.absolutePath + File.separator + "backupRestore"
    }

    // /data/data/{package name}
    private val internalDataFile by lazy {
        application.filesDir!!.parentFile!!
    }

    // ... /cache/backupRestore/restore
    private val restorePath by lazy {
        backupRestorePath + File.separator + "restore"
    }

    // ... /cache/backupRestore/restore/shared_prefs
    private val restorePrefsPath by lazy {
        restorePath + File.separator + "shared_prefs"
    }

    suspend fun restore(bytes: ByteArray): Boolean {
        var isRestart = false
        speechRuleIdImportMap = emptyMap()

        val outFileDir = File(restorePath)

        // 每次恢复前清空临时目录，避免上一次解压残留文件被误导入
        outFileDir.deleteRecursively()
        outFileDir.mkdirs()

        ZipUtils.unzipFile(ZipInputStream(ByteArrayInputStream(bytes)), outFileDir)

        if (outFileDir.exists()) {
            // shared_prefs
            val restorePrefsFile = File(restorePrefsPath)
            if (restorePrefsFile.exists()) {
                FileUtils.copyFolder(restorePrefsFile, internalDataFile)
                restorePrefsFile.deleteRecursively()
                isRestart = true
            }

            val jsonFiles = outFileDir
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".json") }

            // 插件先导入，再导入朗读规则，再导入语音列表。
            // list.json 里的 TTS 配置可能引用 pluginId 和 tagRuleId。
            jsonFiles
                .sortedWith(
                    compareBy<File> {
                        when {
                            it.name.endsWith("plugins.json") -> 0
                            it.name.endsWith("speechRules.json") -> 1
                            it.name.endsWith("replaceRules.json") -> 2
                            it.name.endsWith("list.json") -> 3
                            else -> 9
                        }
                    }
                )
                .forEach { file ->
                    importFromJsonFile(file)
                }

            // 只有覆盖更新模式才自动修复后缀。
            // 修改 ID / 共存模式不要自动把 ruleId 改回基础 ID。
            if (speechRuleImportMode == SpeechRuleImportMode.OverwriteUpdate) {
                repairSpeechRuleIdSuffixes()
            }

            SystemTtsService.notifyUpdateConfig()
        }

        return isRestart
    }

    private fun importFromJsonFile(file: File) {
        val jsonStr = file.readText()

        if (file.name.endsWith("list.json")) {
            val list: List<GroupWithSystemTts> = AppConst.jsonBuilder.decodeFromString(jsonStr)

            importSystemTtsListKeepTree(
                list = list,
                speechRuleIdMap = speechRuleIdImportMap
            )
        } else if (file.name.endsWith("speechRules.json")) {
            val list: List<SpeechRule> = AppConst.jsonBuilder.decodeFromString(jsonStr)

            speechRuleIdImportMap = importSpeechRules(
                list = list,
                mode = speechRuleImportMode
            )
        } else if (file.name.endsWith("replaceRules.json")) {
            val list: List<GroupWithReplaceRule> =
                AppConst.jsonBuilder.decodeFromString(jsonStr)

            dbm.replaceRuleDao.insertRuleWithGroup(*list.toTypedArray())
        } else if (file.name.endsWith("plugins.json")) {
            val list: List<Plugin> = AppConst.jsonBuilder.decodeFromString(jsonStr)

            dbm.pluginDao.insertOrUpdate(*list.toTypedArray())
        }
    }

    private fun importSpeechRules(
        list: List<SpeechRule>,
        mode: SpeechRuleImportMode,
    ): Map<String, String> {
        if (list.isEmpty()) return emptyMap()

        val ruleIdMap = mutableMapOf<String, String>()

        when (mode) {
            SpeechRuleImportMode.OverwriteUpdate -> {
                val fixedList = list.mapIndexed { index, rule ->
                    val oldRuleId = rule.ruleId
                    val newRuleId = rule.ruleId

                    ruleIdMap[oldRuleId] = newRuleId

                    val old = dbm.speechRuleDao.getByRuleIdAny(newRuleId)
                    if (old != null) {
                        rule.copy(id = old.id)
                    } else {
                        rule.copy(id = System.currentTimeMillis() + index)
                    }
                }

                dbm.speechRuleDao.insert(*fixedList.toTypedArray())
            }

            SpeechRuleImportMode.RenameId -> {
                val renamedList = list.mapIndexed { index, rule ->
                    val oldRuleId = rule.ruleId
                    val baseRuleId = normalizeSpeechRuleBaseId(oldRuleId)

                    val newRuleId = makeUniqueSpeechRuleId(
                        baseRuleId = baseRuleId,
                        suffix = "manual"
                    )

                    val newVersion = makeNextSpeechRuleVersion(
                        baseRuleId = baseRuleId,
                        importedVersion = rule.version
                    )

                    ruleIdMap[oldRuleId] = newRuleId

                    rule.copy(
                        id = System.currentTimeMillis() + index,
                        ruleId = newRuleId,
                        version = newVersion,
                        code = replaceSpeechRuleJsIdAndVersionOnly(
                            code = rule.code,
                            newRuleId = newRuleId,
                            newVersion = newVersion
                        ),
                        name = "${rule.name}（修改ID）"
                    )
                }

                dbm.speechRuleDao.insert(*renamedList.toTypedArray())
            }

            SpeechRuleImportMode.SameBaseCoexist -> {
                val coexistList = list.mapIndexed { index, rule ->
                    val oldRuleId = rule.ruleId
                    val baseRuleId = normalizeSpeechRuleBaseId(oldRuleId)

                    val newRuleId = makeUniqueSpeechRuleId(
                        baseRuleId = baseRuleId,
                        suffix = "dev"
                    )

                    val newVersion = makeNextSpeechRuleVersion(
                        baseRuleId = baseRuleId,
                        importedVersion = rule.version
                    )

                    ruleIdMap[oldRuleId] = newRuleId

                    rule.copy(
                        id = System.currentTimeMillis() + index,
                        ruleId = newRuleId,
                        version = newVersion,
                        code = replaceSpeechRuleJsIdAndVersionOnly(
                            code = rule.code,
                            newRuleId = newRuleId,
                            newVersion = newVersion
                        ),
                        name = "${rule.name}（共存）"
                    )
                }

                dbm.speechRuleDao.insert(*coexistList.toTypedArray())
            }
        }

        return ruleIdMap
    }

    private fun importSystemTtsListKeepTree(
        list: List<GroupWithSystemTts>,
        speechRuleIdMap: Map<String, String> = emptyMap(),
    ) {
        if (list.isEmpty()) return

        val groupEntryMap = linkedMapOf<Long, GroupWithSystemTts>()

        list.forEach { item ->
            groupEntryMap[item.group.id] = item
        }

        val oldToNewGroupId = mutableMapOf<Long, Long>()
        var idSeed = System.currentTimeMillis()

        groupEntryMap.keys.forEach { oldId ->
            oldToNewGroupId[oldId] = idSeed++
        }

        val insertedGroupIds = mutableSetOf<Long>()

        fun insertGroupRecursive(oldGroupId: Long) {
            if (insertedGroupIds.contains(oldGroupId)) return

            val entry = groupEntryMap[oldGroupId] ?: return
            val oldGroup = entry.group
            val oldParentId = oldGroup.parentGroupId

            val newParentId =
                if (oldParentId != 0L && groupEntryMap.containsKey(oldParentId)) {
                    insertGroupRecursive(oldParentId)
                    oldToNewGroupId[oldParentId] ?: 0L
                } else {
                    0L
                }

            val newGroupId = oldToNewGroupId[oldGroupId] ?: return

            dbm.systemTtsV2.insertGroup(
                oldGroup.copy(
                    id = newGroupId,
                    parentGroupId = newParentId
                )
            )

            insertedGroupIds.add(oldGroupId)
        }

        groupEntryMap.keys.forEach { oldGroupId ->
            insertGroupRecursive(oldGroupId)
        }

        var ttsIdSeed = System.currentTimeMillis() + 100000L

        val newTtsList = list.flatMap { item ->
            val oldGroupId = item.group.id
            val newGroupId = oldToNewGroupId[oldGroupId] ?: 0L

            item.list.map { tts ->
                val config = tts.config as? TtsConfigurationDTO

                val fixedConfig = if (config != null) {
                    val oldTagRuleId = config.speechRule.tagRuleId
                    val newTagRuleId = speechRuleIdMap[oldTagRuleId] ?: oldTagRuleId

                    if (newTagRuleId != oldTagRuleId) {
                        config.copy(
                            speechRule = config.speechRule.copy(
                                tagRuleId = newTagRuleId
                            )
                        )
                    } else {
                        config
                    }
                } else {
                    tts.config
                }

                tts.copy(
                    id = ttsIdSeed++,
                    groupId = newGroupId,
                    config = fixedConfig
                )
            }
        }

        if (newTtsList.isNotEmpty()) {
            dbm.systemTtsV2.insert(*newTtsList.toTypedArray())
        }
    }

    private fun normalizeRuleIdSuffix(ruleId: String): String {
        return normalizeSpeechRuleBaseId(ruleId)
    }

    private fun normalizeSpeechRuleBaseId(ruleId: String): String {
        val value = ruleId.trim()
        if (value.isBlank()) return value

        val regex = Regex(
            pattern = """([._-](db|dev|new|test|debug|bak|backup|manual)(_\d{3})?)$""",
            option = RegexOption.IGNORE_CASE
        )

        return value.replace(regex, "")
    }

    private fun makeUniqueSpeechRuleId(
        baseRuleId: String,
        suffix: String,
    ): String {
        val base = baseRuleId.ifBlank { "speech_rule" }

        var index = 1
        while (true) {
            val newRuleId = "${base}_${suffix}_${index.toString().padStart(3, '0')}"

            if (dbm.speechRuleDao.getByRuleIdAny(newRuleId) == null) {
                return newRuleId
            }

            index++
        }
    }

    /**
     * 只改 JS 里的 id 和 version。
     * 不匹配、不修改 zdfp。
     */
    private fun replaceSpeechRuleJsIdAndVersionOnly(
        code: String,
        newRuleId: String,
        newVersion: Int,
    ): String {
        var result = code

        val idRegex = Regex(
            pattern = """(?m)^(\s*id\s*:\s*["'])[^"']*(["']\s*,?)"""
        )

        idRegex.find(result)?.let { match ->
            result = result.replaceRange(
                match.range,
                match.groupValues[1] + newRuleId + match.groupValues[2]
            )
        }

        val versionRegex = Regex(
            pattern = """(?m)^(\s*version\s*:\s*)\d+(\s*,?)"""
        )

        versionRegex.find(result)?.let { match ->
            result = result.replaceRange(
                match.range,
                match.groupValues[1] + newVersion.toString() + match.groupValues[2]
            )
        }

        return result
    }

    /**
     * 同基础 ruleId 的规则再次导入时，版本号递增：
     * 78 -> 79 -> 80 -> 81
     */
    private fun makeNextSpeechRuleVersion(
        baseRuleId: String,
        importedVersion: Int,
    ): Int {
        val base = normalizeSpeechRuleBaseId(baseRuleId)

        val maxExistingVersion = dbm.speechRuleDao.all
            .filter { normalizeSpeechRuleBaseId(it.ruleId) == base }
            .maxOfOrNull { it.version }
            ?: importedVersion

        return maxOf(maxExistingVersion, importedVersion, 0) + 1
    }

    private fun repairSpeechRuleIdAndTtsReference(
        oldRuleId: String,
        newRuleId: String,
    ): Boolean {
        if (oldRuleId.isBlank()) return false
        if (newRuleId.isBlank()) return false
        if (oldRuleId == newRuleId) return false

        var changed = false

        val oldRule = dbm.speechRuleDao.getByRuleIdAny(oldRuleId)
        val newRule = dbm.speechRuleDao.getByRuleIdAny(newRuleId)

        // 如果只有带后缀的朗读规则，没有无后缀版本，就把朗读规则本身 ruleId 改回来
        if (oldRule != null && newRule == null) {
            dbm.speechRuleDao.updateRuleId(
                oldRuleId = oldRuleId,
                newRuleId = newRuleId
            )
            changed = true
        }

        // 只要无后缀规则存在，或者刚刚已经把旧规则改成无后缀，就同步所有 TTS 的 tagRuleId
        val targetRuleExists = dbm.speechRuleDao.getByRuleIdAny(newRuleId) != null

        if (targetRuleExists) {
            val updatedTtsList = dbm.systemTtsV2.all.mapNotNull { tts ->
                val config = tts.config as? TtsConfigurationDTO ?: return@mapNotNull null
                val speechRule = config.speechRule

                if (speechRule.tagRuleId != oldRuleId) {
                    return@mapNotNull null
                }

                tts.copy(
                    config = config.copy(
                        speechRule = speechRule.copy(
                            tagRuleId = newRuleId
                        )
                    )
                )
            }

            if (updatedTtsList.isNotEmpty()) {
                dbm.systemTtsV2.update(*updatedTtsList.toTypedArray())
                changed = true
            }
        }

        return changed
    }

    private fun repairSpeechRuleIdSuffixes() {
        var changed = false

        // 1. 修复 speech_rules 表里自身带后缀的 ruleId
        dbm.speechRuleDao.all.forEach { rule ->
            val oldRuleId = rule.ruleId
            val newRuleId = normalizeRuleIdSuffix(oldRuleId)

            if (oldRuleId != newRuleId) {
                if (repairSpeechRuleIdAndTtsReference(oldRuleId, newRuleId)) {
                    changed = true
                }
            }
        }

        // 2. 修复 TTS 配置里 tagRuleId 带后缀，但 speech_rules 已经是无后缀的情况
        dbm.systemTtsV2.all.forEach { tts ->
            val config = tts.config as? TtsConfigurationDTO ?: return@forEach
            val speechRule = config.speechRule

            val oldRuleId = speechRule.tagRuleId
            val newRuleId = normalizeRuleIdSuffix(oldRuleId)

            if (oldRuleId == newRuleId) return@forEach

            if (dbm.speechRuleDao.getByRuleIdAny(newRuleId) != null) {
                dbm.systemTtsV2.update(
                    tts.copy(
                        config = config.copy(
                            speechRule = speechRule.copy(
                                tagRuleId = newRuleId
                            )
                        )
                    )
                )

                changed = true
            }
        }

        if (changed) {
            SystemTtsService.notifyUpdateConfig()
        }
    }

    suspend fun backup(_types: List<Type>): ByteArray = withIO {
        File(tmpZipPath).deleteRecursively()
        File(tmpZipPath).mkdirs()

        val types = _types.toMutableList()

        // 只要备份了语音列表，就必须至少备份插件定义。
        // 否则 list.json 里的 TTS 配置会引用 pluginId，但导入后数据库里没有插件。
        if (types.contains(Type.List) &&
            !types.contains(Type.Plugin) &&
            !types.contains(Type.PluginVars)
        ) {
            types.add(Type.Plugin)
        }

        // 如果选择了“插件变量”，就不需要再重复导出“不带变量的插件”
        if (types.contains(Type.PluginVars)) {
            types.remove(Type.Plugin)
        }

        types.distinct().forEach {
            createConfigFile(it)
        }

        val zipFile = File(tmpZipFile)
        ZipUtils.zipFolder(File(tmpZipPath), zipFile)

        return@withIO zipFile.readBytes()
    }

    override fun onCleared() {
        super.onCleared()
        File(backupRestorePath).deleteRecursively()
    }

    // ... /cache/backupRestore/backup
    private val tmpZipPath by lazy {
        backupRestorePath + File.separator + "backup"
    }

    private val tmpZipFile by lazy {
        backupRestorePath + File.separator + "backup.zip"
    }

    private fun createConfigFile(type: Type) {
        when (type) {
            is Type.Preference -> {
                val folder = internalDataFile.absolutePath + File.separator + "shared_prefs"

                FileUtils.copyFilesFromDir(
                    File(folder),
                    File(tmpZipPath + File.separator + "shared_prefs"),
                )
            }

            is Type.List -> {
                encodeJsonAndCopyToTmpZipPath(
                    dbm.systemTtsV2.getAllGroupWithTts(),
                    "list"
                )
            }

            is Type.SpeechRule -> {
                encodeJsonAndCopyToTmpZipPath(
                    dbm.speechRuleDao.all,
                    "speechRules"
                )
            }

            is Type.ReplaceRule -> {
                encodeJsonAndCopyToTmpZipPath(
                    dbm.replaceRuleDao.allGroupWithReplaceRules(),
                    "replaceRules"
                )
            }

            is Type.IPlugin -> {
                val plugins = dbm.pluginDao.all

                if (type.includeVars) {
                    encodeJsonAndCopyToTmpZipPath(
                        plugins,
                        "plugins"
                    )
                } else {
                    // 不直接修改 dbm.pluginDao.all 返回的原对象
                    // 用 JSON 深拷贝一份，再清空 userVars
                    val copiedPlugins: List<Plugin> = AppConst.jsonBuilder.decodeFromString(
                        AppConst.jsonBuilder.encodeToString(plugins)
                    )

                    copiedPlugins.forEach {
                        it.userVars = mutableMapOf()
                    }

                    encodeJsonAndCopyToTmpZipPath(
                        copiedPlugins,
                        "plugins"
                    )
                }
            }
        }
    }

    private inline fun <reified T> encodeJsonAndCopyToTmpZipPath(v: T, name: String) {
        val s = AppConst.jsonBuilder.encodeToString(v)
        File(tmpZipPath + File.separator + name + ".json").writeText(s)
    }
}