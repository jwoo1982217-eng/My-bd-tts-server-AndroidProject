package com.github.jing332.tts_server_android.compose.systts.list

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.AbstractListGroup.Companion.DEFAULT_GROUP_ID
import com.github.jing332.database.entities.systts.GroupWithSystemTts
import com.github.jing332.database.entities.systts.SystemTtsGroup
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.conf.SystemTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ItemPosition
import java.util.Collections

data class GroupTreeNode(
    val group: SystemTtsGroup,
    val list: List<SystemTtsV2>,
    val children: List<GroupTreeNode> = emptyList()
) {
    fun allTts(): List<SystemTtsV2> {
        return list + children.flatMap { it.allTts() }
    }
}

class ListManagerViewModel : ViewModel() {
    companion object {
        const val TAG = "ListManagerViewModel"
    }

    private val _list =
        MutableStateFlow<List<GroupTreeNode>>(
            emptyList()
        )
    val list: StateFlow<List<GroupTreeNode>> get() = _list

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dbm.systemTtsV2.updateAllOrder()
            dbm.systemTtsV2.flowAllGroupWithTts().conflate().collect {
                Log.d(TAG, "update list: ${it.size}")
                _list.tryEmit(it.toTree())
            }
        }
    }

    private fun List<GroupWithSystemTts>.toTree(): List<GroupTreeNode> {
        val groupIdSet = this.map { it.group.id }.toSet()
        val childrenMap = this.groupBy { it.group.parentGroupId }

        fun buildNode(item: GroupWithSystemTts): GroupTreeNode {
            val children = childrenMap[item.group.id]
                .orEmpty()
                .filter { it.group.id != item.group.id }
                .sortedBy { it.group.order }
                .map { buildNode(it) }

            return GroupTreeNode(
                group = item.group,
                list = item.list.sortedBy { it.order },
                children = children
            )
        }

        return this
            .filter {
                it.group.parentGroupId == 0L || it.group.parentGroupId !in groupIdSet
            }
            .sortedBy { it.group.order }
            .map { buildNode(it) }
    }

    fun updateTtsEnabled(
        item: SystemTtsV2,
        enabled: Boolean,
    ) {
        if (!SystemTtsConfig.isVoiceMultipleEnabled.value && enabled) {
            val itemConfig = (item.config as? TtsConfigurationDTO)
            if (itemConfig != null)
                dbm.systemTtsV2.allEnabled.forEach { systts ->
                    if (systts.config is TtsConfigurationDTO) {
                        val config = systts.config as TtsConfigurationDTO
                        if (config.speechRule.target == itemConfig.speechRule.target) {
                            if (config.speechRule.tagRuleId == itemConfig.speechRule.tagRuleId
                                && config.speechRule.tag == itemConfig.speechRule.tag
                                && config.speechRule.tagName == itemConfig.speechRule.tagName
                                && config.speechRule.isStandby == itemConfig.speechRule.isStandby
                            )
                                dbm.systemTtsV2.update(systts.copy(isEnabled = false))
                        }
                    }
                }
        }

        dbm.systemTtsV2.update(item.copy(isEnabled = enabled))
    }

    fun updateGroupEnable(
        item: GroupTreeNode,
        enabled: Boolean,
    ) {
        val targetList = item.allTts()

        if (!SystemTtsConfig.isGroupMultipleEnabled.value && enabled) {
            list.value.forEach { root ->
                root.allTts().forEach { systts ->
                    if (systts.isEnabled)
                        dbm.systemTtsV2.update(systts.copy(isEnabled = false))
                }
            }
        }

        dbm.systemTtsV2.update(
            *targetList
                .filter { it.isEnabled != enabled }
                .map { it.copy(isEnabled = enabled) }
                .toTypedArray()
        )
    }

    fun updateGroupExpanded(
        group: SystemTtsGroup,
        expanded: Boolean,
    ) {
        dbm.systemTtsV2.updateGroup(group.copy(isExpanded = expanded))
    }

    fun addGroup(
        name: String,
        parentGroupId: Long = 0L,
    ) {
        val order = dbm.systemTtsV2.getGroupCountByParent(parentGroupId)

        dbm.systemTtsV2.insertGroup(
            SystemTtsGroup(
                name = name,
                order = order,
                parentGroupId = parentGroupId
            )
        )
    }
    fun wrapRootGroupsIntoParent(parentName: String) {
        val allGroups = dbm.systemTtsV2.allGroup
        val roots = allGroups
            .filter { it.parentGroupId == 0L }
            .sortedBy { it.order }

        if (roots.isEmpty()) return

        // 防止已经归过一次后重复套娃
        if (roots.size == 1 && allGroups.any { it.parentGroupId == roots.first().id }) {
            return
        }

        val parentId = System.currentTimeMillis()

        dbm.systemTtsV2.insertGroup(
            SystemTtsGroup(
                id = parentId,
                name = parentName,
                order = 0,
                isExpanded = true,
                parentGroupId = 0L
            )
        )

        roots.forEachIndexed { index, group ->
            dbm.systemTtsV2.updateGroup(
                group.copy(
                    parentGroupId = parentId,
                    order = index
                )
            )
        }
    }
    fun moveGroupToRoot(group: SystemTtsGroup) {
        if (group.parentGroupId == 0L) return

        val order = dbm.systemTtsV2.getGroupCountByParent(0L)

        dbm.systemTtsV2.updateGroup(
            group.copy(
                parentGroupId = 0L,
                order = order
            )
        )
    }

    fun moveGroupToParent(
        group: SystemTtsGroup,
        parentGroupId: Long,
    ) {
        if (group.id == parentGroupId) return

        if (parentGroupId == 0L) {
            moveGroupToRoot(group)
            return
        }

        val allGroups = dbm.systemTtsV2.allGroup

        fun isDescendant(
            targetId: Long,
            parentId: Long,
        ): Boolean {
            val children = allGroups.filter { it.parentGroupId == parentId }
            return children.any { child ->
                child.id == targetId || isDescendant(targetId, child.id)
            }
        }

        if (isDescendant(parentGroupId, group.id)) return

        val order = dbm.systemTtsV2.getGroupCountByParent(parentGroupId)

        dbm.systemTtsV2.updateGroup(
            group.copy(
                parentGroupId = parentGroupId,
                order = order
            )
        )
    }
    fun reorder(from: ItemPosition, to: ItemPosition) {
        if (from.key is String && to.key is String) {
            val fromKey = from.key as String
            val toKey = to.key as String

            if (fromKey.startsWith("g") && toKey.startsWith("g")) {
                val fromId = fromKey.substring(2).toLong()
                val toId = toKey.substring(2).toLong()

                val fromGroup = findNodeByGroupId(fromId)?.group ?: return
                val toGroup = findNodeByGroupId(toId)?.group ?: return

                if (fromGroup.parentGroupId != toGroup.parentGroupId) return

                val mList = flattenNodes()
                    .map { it.group }
                    .filter { it.parentGroupId == fromGroup.parentGroupId }
                    .sortedBy { it.order }
                    .toMutableList()

                val fromIndex = mList.indexOfFirst { it.id == fromId }
                val toIndex = mList.indexOfFirst { it.id == toId }

                try {
                    Collections.swap(mList, fromIndex, toIndex)
                } catch (_: IndexOutOfBoundsException) {
                    return
                }

                mList.forEachIndexed { index, systemTtsGroup ->
                    if (systemTtsGroup.order != index)
                        dbm.systemTtsV2.updateGroup(systemTtsGroup.copy(order = index))
                }
            } else if (!fromKey.startsWith("g") && !toKey.startsWith("g")) {
                val (fromGId, fromId) = fromKey.split("_").map { it.toLong() }
                val (toGId, toId) = toKey.split("_").map { it.toLong() }
                if (fromGId != toGId) return

                val listInGroup = findListInGroup(fromGId).toMutableList()
                val fromIndex = listInGroup.indexOfFirst { it.id == fromId }
                val toIndex = listInGroup.indexOfFirst { it.id == toId }
                Log.d(TAG, "fromIndex: $fromIndex, toIndex: $toIndex")

                try {
                    Collections.swap(listInGroup, fromIndex, toIndex)
                } catch (_: IndexOutOfBoundsException) {
                    return
                }

                listInGroup.forEachIndexed { index, systts ->
                    Log.d(TAG, "$index ${systts.displayName}")
                    if (systts.order != index)
                        dbm.systemTtsV2.update(systts.copy(order = index))
                }
            }
        }
    }

    private fun flattenNodes(
        nodes: List<GroupTreeNode> = list.value,
    ): List<GroupTreeNode> {
        return nodes.flatMap { node ->
            listOf(node) + flattenNodes(node.children)
        }
    }

    private fun findNodeByGroupId(
        groupId: Long,
        nodes: List<GroupTreeNode> = list.value,
    ): GroupTreeNode? {
        nodes.forEach { node ->
            if (node.group.id == groupId) return node

            val found = findNodeByGroupId(groupId, node.children)
            if (found != null) return found
        }

        return null
    }

    private fun findListInGroup(groupId: Long): List<SystemTtsV2> {
        return findNodeByGroupId(groupId)?.list?.sortedBy { it.order }
            ?: emptyList()
    }

    fun checkListData(context: Context) {
        dbm.systemTtsV2.getGroup(DEFAULT_GROUP_ID) ?: kotlin.run {
            dbm.systemTtsV2.insertGroup(
                SystemTtsGroup(
                    DEFAULT_GROUP_ID,
                    context.getString(R.string.default_group),
                    dbm.systemTtsV2.groupCount
                )
            )
        }

        if (dbm.systemTtsV2.count == 0)
            importDefaultListData(context)
    }

    private fun importDefaultListData(context: Context) {
//        val json = context.assets.open("defaultData/list.json").readAllText()
//        val list =
//            AppConst.jsonBuilder.decodeFromString<List<GroupWithSystemTts>>(
//                json
//            )
//        viewModelScope.launch(Dispatchers.IO) {
//            dbm.systemTtsV2.insertGroupWithTts(*list.toTypedArray())
//        }
    }
}