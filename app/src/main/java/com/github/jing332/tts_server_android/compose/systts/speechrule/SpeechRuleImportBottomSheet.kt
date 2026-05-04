package com.github.jing332.tts_server_android.compose.systts.speechrule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.jing332.tts_server_android.compose.systts.ConfigImportBottomSheet
import com.github.jing332.tts_server_android.compose.systts.ConfigModel
import com.github.jing332.tts_server_android.compose.systts.SelectImportConfigDialog
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule

private fun safeIdPart(text: String): String {
    return text
        .trim()
        .replace(Regex("[^A-Za-z0-9_\\-.]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
}

private fun makeUniqueRuleId(
    originalRuleId: String,
    version: Any?,
    usedRuleIds: MutableSet<String>,
): String {
    val baseId = originalRuleId.ifBlank { "speech_rule" }
    val versionPart = safeIdPart(version?.toString().orEmpty()).ifBlank { "copy" }

    var candidate = "${baseId}_v$versionPart"
    var index = 2

    while (
        usedRuleIds.contains(candidate) ||
        dbm.speechRuleDao.getByRuleId(candidate) != null
    ) {
        candidate = "${baseId}_v${versionPart}_$index"
        index++
    }

    usedRuleIds.add(candidate)
    return candidate
}

private fun makeConflictRuleName(
    originalName: String,
    index: Int,
): String {
    val baseName = originalName.ifBlank { "未命名朗读规则" }
    return "$baseName($index)"
}

private data class PreparedSpeechRuleImport(
    val rules: List<SpeechRule>,
    val renamedCount: Int,
)

private fun prepareSpeechRulesForImport(
    selectedRules: List<SpeechRule>,
): PreparedSpeechRuleImport {
    val usedRuleIds = mutableSetOf<String>()
    var renamedCount = 0

    val importList = selectedRules.map { rule ->
        val oldRuleId = rule.ruleId
        val hasConflict =
            oldRuleId.isBlank() ||
                    usedRuleIds.contains(oldRuleId) ||
                    dbm.speechRuleDao.getByRuleId(oldRuleId) != null

        if (!hasConflict) {
            usedRuleIds.add(oldRuleId)
            rule
        } else {
            renamedCount++

            val newRuleId = makeUniqueRuleId(
                originalRuleId = oldRuleId,
                version = rule.version,
                usedRuleIds = usedRuleIds
            )

            rule.copy(
                ruleId = newRuleId,
                name = makeConflictRuleName(
                    originalName = rule.name,
                    index = renamedCount + 1
                )
            )
        }
    }

    return PreparedSpeechRuleImport(
        rules = importList,
        renamedCount = renamedCount
    )
}

@Composable
fun SpeechRuleImportBottomSheet(onDismissRequest: () -> Unit) {
    var showSelectDialog by remember { mutableStateOf<List<SpeechRule>?>(null) }

    if (showSelectDialog != null) {
        val list = showSelectDialog!!

        SelectImportConfigDialog(
            onDismissRequest = {
                showSelectDialog = null
            },
            models = list.map { rule ->
                val isConflict =
                    rule.ruleId.isBlank() ||
                            dbm.speechRuleDao.getByRuleId(rule.ruleId) != null

                ConfigModel(
                    isSelected = true,
                    title = if (isConflict) {
                        "${rule.name}  → 自动共存导入"
                    } else {
                        rule.name
                    },
                    subtitle = if (isConflict) {
                        "${rule.author} - v${rule.version}，ruleId 已存在，将自动生成新 ruleId"
                    } else {
                        "${rule.author} - v${rule.version}"
                    },
                    data = rule
                )
            },
            onSelectedList = { selected ->
                val selectedRules = selected.map { it as SpeechRule }

                val prepared = prepareSpeechRulesForImport(
                    selectedRules = selectedRules
                )

                dbm.speechRuleDao.insert(*prepared.rules.toTypedArray())

                prepared.rules.size
            }
        )
    }

    ConfigImportBottomSheet(
        onDismissRequest = onDismissRequest,
        onImport = {
            showSelectDialog = AppConst.jsonBuilder.decodeFromString<List<SpeechRule>>(it)
        }
    )
}