package com.github.jing332.tts_server_android.compose.systts.plugin

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jing332.common.utils.longToast
import com.github.jing332.compose.rememberLazyListReorderCache
import com.github.jing332.compose.widgets.ShadowedDraggableItem
import com.github.jing332.compose.widgets.TextFieldDialog
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.AppDefaultProperties
import com.github.jing332.tts_server_android.compose.LocalNavController
import com.github.jing332.tts_server_android.compose.SharedViewModel
import com.github.jing332.tts_server_android.compose.systts.ConfigDeleteDialog
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.service.systts.SystemTtsService
import com.github.jing332.tts_server_android.utils.MyTools
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.encodeToString
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PluginManagerScreen(sharedVM: SharedViewModel, onFinishActivity: () -> Unit) {
    var showImportConfig by remember { mutableStateOf(false) }
    if (showImportConfig) {
        PluginImportBottomSheet(onDismissRequest = { showImportConfig = false })
    }

    var showExportConfig by remember { mutableStateOf<List<Plugin>?>(null) }
    if (showExportConfig != null) {
        val pluginList = showExportConfig!!
        PluginExportBottomSheet(
            fileName = if (pluginList.size == 1) {
                "ttsrv-plugin-${pluginList[0].name}.json"
            } else {
                "ttsrv-plugins.json"
            },
            onDismissRequest = { showExportConfig = null }
        ) { isExportVars ->
            if (isExportVars) {
                AppConst.jsonBuilder.encodeToString(pluginList)
            } else {
                AppConst.jsonBuilder.encodeToString(
                    pluginList.map { it.copy(userVars = mutableMapOf()) }
                )
            }
        }
    }

    var showDeleteDialog by remember { mutableStateOf<Plugin?>(null) }
    if (showDeleteDialog != null) {
        val plugin = showDeleteDialog!!
        ConfigDeleteDialog(
            onDismissRequest = { showDeleteDialog = null },
            content = plugin.name
        ) {
            dbm.pluginDao.delete(plugin)
            showDeleteDialog = null
        }
    }

    var showVarsSettings by remember { mutableStateOf<Plugin?>(null) }
    if (showVarsSettings != null) {
        var plugin by remember { mutableStateOf(showVarsSettings!!) }

        if (plugin.defVars.isEmpty()) {
            showVarsSettings = null
        }

        PluginVarsBottomSheet(
            onDismissRequest = {
                dbm.pluginDao.update(plugin)
                showVarsSettings = null
            },
            plugin = plugin
        ) {
            plugin = it
        }
    }

    val navController = LocalNavController.current
    val context = LocalContext.current

    val flowAllTts = remember { dbm.systemTtsV2.flowAllGroupWithTts().conflate() }
    val allTtsGroups by flowAllTts.collectAsStateWithLifecycle(emptyList())

    fun allTtsList(): List<SystemTtsV2> {
        return allTtsGroups.flatMap { it.list }
    }

    fun pluginTtsList(plugin: Plugin): List<SystemTtsV2> {
        return allTtsList().filter { systts ->
            val config = systts.config as? TtsConfigurationDTO ?: return@filter false
            val source = config.source as? PluginTtsSource ?: return@filter false
            source.pluginId == plugin.pluginId
        }
    }

    var showAudioParamsPlugin by remember { mutableStateOf<Plugin?>(null) }
    if (showAudioParamsPlugin != null) {
        val plugin = showAudioParamsPlugin!!
        val matchedList = pluginTtsList(plugin)

        PluginAudioParamsDialog(
            pluginName = plugin.name,
            count = matchedList.size,
            speed = plugin.pluginAudioSpeed(),
            volume = plugin.pluginAudioVolume(),
            pitch = plugin.pluginAudioPitch(),
            onDismissRequest = {
                showAudioParamsPlugin = null
            },
            onConfirm = { speed, volume, pitch ->
                val updatedPlugin = plugin.withPluginAudioParams(
                    speed = speed,
                    volume = volume,
                    pitch = pitch
                )

                dbm.pluginDao.update(updatedPlugin)

                SystemTtsService.notifyUpdateConfig()
                showAudioParamsPlugin = null
            }
        )
    }

    var showMissingPluginMatches by remember { mutableStateOf<List<MissingPluginMatch>?>(null) }
    if (showMissingPluginMatches != null) {
        MissingPluginAdaptDialog(
            matches = showMissingPluginMatches!!,
            onDismissRequest = {
                showMissingPluginMatches = null
            },
            onConfirm = { match ->
                val count = adaptMissingPluginIdToTarget(
                    missingPluginId = match.missingPluginId,
                    targetPluginId = match.targetPlugin.pluginId
                )

                showMissingPluginMatches = null

                if (count > 0) {
                    context.longToast(
                        "已适配 $count 个音色：${match.missingPluginId} → ${match.targetPlugin.pluginId}"
                    )
                } else {
                    context.longToast("没有找到可适配的音色")
                }
            }
        )
    }

    fun onEdit(plugin: Plugin = Plugin()) {
        sharedVM.put(NavRoutes.PluginEdit.KEY_DATA, plugin)
        navController.navigate(NavRoutes.PluginEdit.id)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(id = R.string.plugin_manager)) },
                navigationIcon = {
                    IconButton(onClick = onFinishActivity) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(id = R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onEdit()
                    }) {
                        Icon(Icons.Default.Add, stringResource(id = R.string.add_config))
                    }

                    var showOptions by remember { mutableStateOf(false) }
                    IconButton(onClick = {
                        showOptions = true
                    }) {
                        Icon(Icons.Default.MoreVert, stringResource(id = R.string.more_options))

                        DropdownMenu(
                            expanded = showOptions,
                            onDismissRequest = { showOptions = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.import_config)) },
                                onClick = {
                                    showOptions = false
                                    showImportConfig = true
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Input, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("检测失效插件适配") },
                                onClick = {
                                    showOptions = false

                                    val matches = findMissingPluginMatches(
                                        allTts = allTtsList(),
                                        installedPlugins = dbm.pluginDao.allEnabled
                                    )

                                    if (matches.isEmpty()) {
                                        context.longToast("没有检测到需要适配的失效插件音色")
                                    } else {
                                        showMissingPluginMatches = matches
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AppShortcut, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.export_config)) },
                                onClick = {
                                    showOptions = false
                                    showExportConfig = dbm.pluginDao.allEnabled
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Output, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.desktop_shortcut)) },
                                onClick = {
                                    showOptions = false
                                    MyTools.addShortcut(
                                        context,
                                        context.getString(R.string.plugin_manager),
                                        "plugin",
                                        R.drawable.ic_shortcut_plugin,
                                        Intent(context, PluginManagerActivity::class.java)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.AppShortcut, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        val flowAll = remember { dbm.pluginDao.flowAll().conflate() }
        val list by flowAll.collectAsStateWithLifecycle(emptyList())

        data class PluginUiGroup(
            val key: String,
            val name: String,
            val plugins: List<Plugin>,
        )

        var showAllPlugins by remember { mutableStateOf(false) }
        var lastPluginListSize by remember { mutableStateOf(list.size) }
        var expandedPluginGroupKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var movePluginGroupDialog by remember { mutableStateOf<Plugin?>(null) }
        var createPluginGroupDialog by remember { mutableStateOf<Plugin?>(null) }
        var createEmptyPluginGroupDialog by remember { mutableStateOf(false) }
        var renamePluginGroupDialog by remember { mutableStateOf<PluginUiGroup?>(null) }
        var deletePluginGroupDialog by remember { mutableStateOf<PluginUiGroup?>(null) }

        fun defaultPluginGroupName(plugin: Plugin): String {
            val text = "${plugin.name} ${plugin.pluginId}".lowercase()

            return when {
                "呱呱" in text -> "呱呱"
                "mimo" in text -> "MIMO"
                "角色管理" in text -> "角色管理"
                else -> plugin.name.trim().ifBlank {
                    plugin.pluginId.trim().ifBlank { "其它插件" }
                }
            }
        }

        fun safePluginGroupId(name: String): String {
            return name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_\\-.\u4e00-\u9fa5]+"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "plugin_group" }
        }

        fun List<Plugin>.toPluginUiGroups(): List<PluginUiGroup> {
            return groupBy {
                it.pluginGroupId.ifBlank {
                    safePluginGroupId(
                        it.pluginGroupName.ifBlank {
                            defaultPluginGroupName(it)
                        }
                    )
                }
            }.map { (key, plugins) ->
                val sortedPlugins = plugins.sortedWith(
                    compareBy<Plugin> { it.order }
                        .thenBy { it.name }
                        .thenByDescending { it.version }
                )

                val first = sortedPlugins.first()

                PluginUiGroup(
                    key = key,
                    name = first.pluginGroupName.ifBlank { defaultPluginGroupName(first) },
                    plugins = sortedPlugins
                )
            }.sortedBy { group ->
                group.plugins.minOfOrNull { it.order } ?: Int.MAX_VALUE
            }
        }

        val pluginGroupPrefs = remember {
            context.getSharedPreferences("plugin_group_ui", android.content.Context.MODE_PRIVATE)
        }

        var customPluginGroupNames by remember {
            mutableStateOf(
                pluginGroupPrefs
                    .getStringSet("custom_group_names", emptySet())
                    ?.toSet()
                    ?: emptySet()
            )
        }

        var pluginGroupOrderKeys by remember {
            mutableStateOf(
                pluginGroupPrefs
                    .getString("group_order_keys", "")
                    .orEmpty()
                    .split("|")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            )
        }

        fun savePluginGroupOrderKeys(keys: List<String>) {
            pluginGroupOrderKeys = keys
            pluginGroupPrefs
                .edit()
                .putString("group_order_keys", keys.joinToString("|"))
                .apply()
        }

        fun saveCustomPluginGroupNames(names: Set<String>) {
            customPluginGroupNames = names
            pluginGroupPrefs
                .edit()
                .putStringSet("custom_group_names", names)
                .apply()
        }

        val pluginGroups: List<PluginUiGroup> = remember(
            list,
            customPluginGroupNames,
            pluginGroupOrderKeys
        ) {
            val realGroups = list.toPluginUiGroups()
            val realGroupKeys = realGroups.map { it.key }.toSet()

            val emptyGroups = customPluginGroupNames
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { name ->
                    PluginUiGroup(
                        key = safePluginGroupId(name),
                        name = name,
                        plugins = emptyList()
                    )
                }
                .filter { it.key !in realGroupKeys }

            val orderIndex = pluginGroupOrderKeys
                .mapIndexed { index, key -> key to index }
                .toMap()

            (realGroups + emptyGroups).sortedWith(
                compareBy<PluginUiGroup> {
                    orderIndex[it.key] ?: Int.MAX_VALUE
                }.thenBy {
                    it.plugins.minOfOrNull { plugin -> plugin.order } ?: Int.MAX_VALUE
                }.thenBy {
                    it.name
                }
            )
        }

        if (createEmptyPluginGroupDialog) {
            var groupName by remember { mutableStateOf("") }

            TextFieldDialog(
                title = "新建分组",
                text = groupName,
                onTextChange = { groupName = it },
                onDismissRequest = { createEmptyPluginGroupDialog = false }
            ) {
                val name = groupName.trim().ifBlank { "新分组" }

                saveCustomPluginGroupNames(customPluginGroupNames + name)
                expandedPluginGroupKeys = expandedPluginGroupKeys + safePluginGroupId(name)
                createEmptyPluginGroupDialog = false
            }
        }

        renamePluginGroupDialog?.let { group ->
            var groupName by remember(group.key) { mutableStateOf(group.name) }

            TextFieldDialog(
                title = "修改分组名",
                text = groupName,
                onTextChange = { groupName = it },
                onDismissRequest = { renamePluginGroupDialog = null }
            ) {
                val newName = groupName.trim().ifBlank { group.name }
                val newId = safePluginGroupId(newName)

                group.plugins.forEach { plugin ->
                    dbm.pluginDao.update(
                        plugin.copy(
                            pluginGroupId = newId,
                            pluginGroupName = newName
                        )
                    )
                }

                if (group.plugins.isEmpty()) {
                    saveCustomPluginGroupNames(
                        customPluginGroupNames - group.name + newName
                    )
                } else {
                    saveCustomPluginGroupNames(
                        customPluginGroupNames - group.name
                    )
                }

                expandedPluginGroupKeys =
                    expandedPluginGroupKeys - group.key + newId

                renamePluginGroupDialog = null
            }
        }

        deletePluginGroupDialog?.let { group ->
            AlertDialog(
                onDismissRequest = {
                    deletePluginGroupDialog = null
                },
                title = {
                    Text("删除分组")
                },
                text = {
                    Text(
                        if (group.plugins.isEmpty()) {
                            "确定删除空分组「${group.name}」吗？"
                        } else {
                            "确定删除分组「${group.name}」吗？\n\n不会删除插件，只会把这个分组里的插件移动到「其它插件」。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val fallbackName = "其它插件"
                            val fallbackId = safePluginGroupId(fallbackName)

                            group.plugins.forEach { plugin ->
                                dbm.pluginDao.update(
                                    plugin.copy(
                                        pluginGroupId = fallbackId,
                                        pluginGroupName = fallbackName
                                    )
                                )
                            }

                            saveCustomPluginGroupNames(customPluginGroupNames - group.name)
                            expandedPluginGroupKeys = expandedPluginGroupKeys - group.key
                            deletePluginGroupDialog = null
                        }
                    ) {
                        Text("确定删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletePluginGroupDialog = null }) {
                        Text("取消")
                    }
                }
            )
        }

        movePluginGroupDialog?.let { plugin ->
            AlertDialog(
                onDismissRequest = {
                    movePluginGroupDialog = null
                },
                title = {
                    Text("移动分组")
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        pluginGroups.forEach { group ->
                            TextButton(
                                onClick = {
                                    dbm.pluginDao.update(
                                        plugin.copy(
                                            pluginGroupId = group.key,
                                            pluginGroupName = group.name
                                        )
                                    )
                                    movePluginGroupDialog = null
                                }
                            ) {
                                Text("📦 ${group.name}")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            createPluginGroupDialog = plugin
                            movePluginGroupDialog = null
                        }
                    ) {
                        Text("新建分组")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { movePluginGroupDialog = null }) {
                        Text("取消")
                    }
                }
            )
        }

        createPluginGroupDialog?.let { plugin ->
            var groupName by remember(plugin.id) { mutableStateOf("") }

            TextFieldDialog(
                title = "新建分组",
                text = groupName,
                onTextChange = { groupName = it },
                onDismissRequest = { createPluginGroupDialog = null }
            ) {
                val name = groupName.trim().ifBlank { "新分组" }

                dbm.pluginDao.update(
                    plugin.copy(
                        pluginGroupId = safePluginGroupId(name),
                        pluginGroupName = name
                    )
                )

                expandedPluginGroupKeys = expandedPluginGroupKeys + safePluginGroupId(name)
                createPluginGroupDialog = null
            }
        }

        LaunchedEffect(list.size) {
            if (list.size > lastPluginListSize) {
                showAllPlugins = false
            }
            lastPluginListSize = list.size
        }

        LaunchedEffect(list) {
            list.filter {
                it.pluginGroupId.isBlank() || it.pluginGroupName.isBlank()
            }.forEach { plugin ->
                val groupName = defaultPluginGroupName(plugin)
                dbm.pluginDao.update(
                    plugin.copy(
                        pluginGroupName = groupName,
                        pluginGroupId = safePluginGroupId(groupName)
                    )
                )
            }
        }


        val cache = rememberLazyListReorderCache(list)

        val reorderState = rememberReorderableLazyListState(
            onMove = { from, to ->
                if (showAllPlugins) {
                    cache.move(from.index, to.index)
                }
            },
            onDragEnd = { _, _ ->
                if (showAllPlugins) {
                    cache.list.forEachIndexed { index, plugin ->
                        if (index != plugin.order) {
                            dbm.pluginDao.update(plugin.copy(order = index))
                        }
                    }
                }
            }
        )

        val groupCache = rememberLazyListReorderCache(pluginGroups)
        val groupReorderState = rememberReorderableLazyListState(
            listState = reorderState.listState,
            onMove = { from, to ->
                if (!showAllPlugins && expandedPluginGroupKeys.isEmpty()) {
                    val fromIndex = from.index - 1
                    val toIndex = to.index - 1

                    if (
                        fromIndex in groupCache.list.indices &&
                        toIndex in groupCache.list.indices
                    ) {
                        groupCache.move(fromIndex, toIndex)
                    }
                }
            },
            onDragEnd = { _, _ ->
                if (!showAllPlugins && expandedPluginGroupKeys.isEmpty()) {
                    savePluginGroupOrderKeys(groupCache.list.map { it.key })
                    groupCache.ended()
                }
            }
        )

        LazyColumn(
            state = groupReorderState.listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .then(
                    if (showAllPlugins) {
                        Modifier.reorderable(reorderState)
                    } else {
                        Modifier
                    }
                )
        ) {
            item(key = "plugin_group_tools") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            expandedPluginGroupKeys = pluginGroups.map { it.key }.toSet()
                            showAllPlugins = false
                        }
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "全部展开")
                    }

                    IconButton(
                        onClick = {
                            expandedPluginGroupKeys = emptySet()
                            showAllPlugins = false
                        }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "全部折叠")
                    }

                    IconButton(
                        onClick = {
                            showAllPlugins = !showAllPlugins
                        }
                    ) {
                        Icon(
                            imageVector = if (showAllPlugins) {
                                Icons.Default.Folder
                            } else {
                                Icons.Default.ViewList
                            },
                            contentDescription = if (showAllPlugins) "显示分组" else "显示全部插件"
                        )
                    }

                    IconButton(
                        onClick = {
                            createEmptyPluginGroupDialog = true
                            showAllPlugins = false
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建分组")
                    }
                }
            }

            if (showAllPlugins) {
                itemsIndexed(cache.list, key = { _, item -> item.id }) { _, item ->
                val desc = remember(item) { "${item.author} - v${item.version}" }

                ShadowedDraggableItem(reorderableState = reorderState, key = item.id) {
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                        .detectReorderAfterLongPress(reorderState)

                    Item(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .detectReorderAfterLongPress(reorderState),
                        hasDefVars = item.defVars.isNotEmpty(),
                        needSetVars = item.defVars.isNotEmpty() && item.userVars.isEmpty(),
                        name = item.name,
                        desc = desc,
                        iconUrl = item.iconUrl,
                        isEnabled = item.isEnabled,
                        onEnabledChange = { enabled ->
                            dbm.pluginDao.update(item.copy(isEnabled = enabled))

                            // 关闭某个插件后，立刻检测是否有配置列表音色失效
                            if (!enabled) {
                                val matches = findMissingPluginMatches(
                                    allTts = allTtsList(),
                                    installedPlugins = dbm.pluginDao.allEnabled
                                )

                                if (matches.isNotEmpty()) {
                                    showMissingPluginMatches = matches
                                }
                            }
                        },
                        onEdit = { onEdit(item) },
                        onMoveGroup = { movePluginGroupDialog = item },
                                onSetVars = { showVarsSettings = item },
                        onEditAudioParams = { showAudioParamsPlugin = item },
                        onDelete = { showDeleteDialog = item },
                        onClear = {
                            PluginManager(item).clearCache()
                            context.longToast(R.string.clear_cache_ok)
                        },
                        onExport = { showExportConfig = listOf(item) }
                    )
                }
            }

            } else {
                pluginGroups.forEach { group ->
                    val expanded = expandedPluginGroupKeys.contains(group.key)

                    item(key = "plugin_group_${group.key}") {
                        ShadowedDraggableItem(
                            reorderableState = groupReorderState,
                            key = "plugin_group_${group.key}"
                        ) {
                            TextButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                onClick = {
                                    expandedPluginGroupKeys =
                                        if (expanded) {
                                            expandedPluginGroupKeys - group.key
                                        } else {
                                            expandedPluginGroupKeys + group.key
                                        }
                                }
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (expanded) "▼" else "▶"
                                )

                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )

                                Text(
                                    text = group.name,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = {
                                        val keys = pluginGroups.map { it.key }.toMutableList()
                                        val index = keys.indexOf(group.key)

                                        if (index > 0) {
                                            val previous = keys[index - 1]
                                            keys[index - 1] = keys[index]
                                            keys[index] = previous
                                            savePluginGroupOrderKeys(keys)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移分组")
                                }

                                IconButton(
                                    onClick = {
                                        val keys = pluginGroups.map { it.key }.toMutableList()
                                        val index = keys.indexOf(group.key)

                                        if (index >= 0 && index < keys.lastIndex) {
                                            val next = keys[index + 1]
                                            keys[index + 1] = keys[index]
                                            keys[index] = next
                                            savePluginGroupOrderKeys(keys)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移分组")
                                }

                                TextButton(
                                    onClick = {
                                        renamePluginGroupDialog = group
                                    }
                                ) {
                                    Text("改名")
                                }

                                TextButton(
                                    onClick = {
                                        deletePluginGroupDialog = group
                                    }
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }

                    }
                    if (expanded) {
                        itemsIndexed(
                            group.plugins,
                            key = { _, item -> "plugin_${item.id}" }
                        ) { _, item ->
                            val desc = remember(item) { "${item.author} - v${item.version}" }

                            Item(
                                modifier = Modifier
                                    .padding(horizontal = 18.dp, vertical = 4.dp),
                                hasDefVars = item.defVars.isNotEmpty(),
                                needSetVars = item.defVars.isNotEmpty() && item.userVars.isEmpty(),
                                name = item.name,
                                desc = desc,
                                iconUrl = item.iconUrl,
                                isEnabled = item.isEnabled,
                                onEnabledChange = { enabled ->
                                    dbm.pluginDao.update(item.copy(isEnabled = enabled))

                                    if (!enabled) {
                                        val matches = findMissingPluginMatches(
                                            allTts = allTtsList(),
                                            installedPlugins = dbm.pluginDao.allEnabled
                                        )

                                        if (matches.isNotEmpty()) {
                                            showMissingPluginMatches = matches
                                        }
                                    }
                                },
                                onEdit = { onEdit(item) },
                                onMoveGroup = { movePluginGroupDialog = item },
                                onSetVars = { showVarsSettings = item },
                                onEditAudioParams = { showAudioParamsPlugin = item },
                                onDelete = { showDeleteDialog = item },
                                onClear = {
                                    PluginManager(item).clearCache()
                                    context.longToast(R.string.clear_cache_ok)
                                },
                                onExport = { showExportConfig = listOf(item) }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.padding(bottom = AppDefaultProperties.LIST_END_PADDING))
            }
        }
    }
}

private const val KEY_PLUGIN_AUDIO_SPEED = "plugin_audio_speed"
private const val KEY_PLUGIN_AUDIO_VOLUME = "plugin_audio_volume"
private const val KEY_PLUGIN_AUDIO_PITCH = "plugin_audio_pitch"

private data class MissingPluginMatch(
    val missingPluginId: String,
    val targetPlugin: Plugin,
    val count: Int,
)

private fun Plugin.pluginAudioSpeed(): Float {
    return userVars[KEY_PLUGIN_AUDIO_SPEED]?.toFloatOrNull() ?: 1f
}

private fun Plugin.pluginAudioVolume(): Float {
    return userVars[KEY_PLUGIN_AUDIO_VOLUME]?.toFloatOrNull() ?: 1f
}

private fun Plugin.pluginAudioPitch(): Float {
    return userVars[KEY_PLUGIN_AUDIO_PITCH]?.toFloatOrNull() ?: 1f
}

private fun Plugin.withPluginAudioParams(
    speed: Float,
    volume: Float,
    pitch: Float,
): Plugin {
    val vars = userVars.toMutableMap()

    vars[KEY_PLUGIN_AUDIO_SPEED] = speed.toString()
    vars[KEY_PLUGIN_AUDIO_VOLUME] = volume.toString()
    vars[KEY_PLUGIN_AUDIO_PITCH] = pitch.toString()

    return copy(
        userVars = vars.toMutableMap()
    )
}

private fun pluginIdSegments(id: String): List<String> {
    return id.trim()
        .split(".")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun commonPrefixLength(a: String, b: String): Int {
    val x = pluginIdSegments(a)
    val y = pluginIdSegments(b)

    var count = 0
    val max = minOf(x.size, y.size)

    for (i in 0 until max) {
        if (x[i] != y[i]) break
        count++
    }

    return count
}

private fun normalizePluginVersionId(pluginId: String): String {
    val value = pluginId.trim()
    if (value.isBlank()) return value

    val suffixRegex = Regex(
        pattern = """([._-](db|dev|new|test|debug|bak|backup|manual)(_\d{3})?)$""",
        option = RegexOption.IGNORE_CASE
    )

    val versionRegex = Regex(
        pattern = """([._-]v?\d+)$""",
        option = RegexOption.IGNORE_CASE
    )

    return value
        .replace(suffixRegex, "")
        .replace(versionRegex, "")
}

private fun isLikelySameSourcePluginId(
    oldPluginId: String,
    newPluginId: String,
): Boolean {
    val oldId = oldPluginId.trim()
    val newId = newPluginId.trim()

    if (oldId.isBlank() || newId.isBlank()) return false
    if (oldId == newId) return true

    if (oldId.startsWith("$newId.") || newId.startsWith("$oldId.")) {
        return true
    }

    val oldBaseId = normalizePluginVersionId(oldId)
    val newBaseId = normalizePluginVersionId(newId)

    if (oldBaseId == newBaseId) return true

    if (oldBaseId.startsWith("$newBaseId.") || newBaseId.startsWith("$oldBaseId.")) {
        return true
    }

    return false
}

private fun findMissingPluginMatches(
    allTts: List<SystemTtsV2>,
    installedPlugins: List<Plugin>,
): List<MissingPluginMatch> {
    val installedPluginIds = installedPlugins
        .map { it.pluginId }
        .filter { it.isNotBlank() }
        .toSet()

    val missingPluginIdCounts = allTts
        .mapNotNull { systts ->
            val config = systts.config as? TtsConfigurationDTO ?: return@mapNotNull null
            val source = config.source as? PluginTtsSource ?: return@mapNotNull null

            source.pluginId
                .trim()
                .takeIf { it.isNotBlank() && it !in installedPluginIds }
        }
        .groupingBy { it }
        .eachCount()

    return missingPluginIdCounts.flatMap { entry ->
        val missingPluginId = entry.key
        val count = entry.value

        installedPlugins
            .filter { plugin ->
                isLikelySameSourcePluginId(
                    oldPluginId = missingPluginId,
                    newPluginId = plugin.pluginId
                )
            }
            .map { plugin ->
                MissingPluginMatch(
                    missingPluginId = missingPluginId,
                    targetPlugin = plugin,
                    count = count
                )
            }
    }.sortedWith(
        compareBy<MissingPluginMatch> { it.missingPluginId }
            .thenBy { it.targetPlugin.pluginId }
    )
}

private fun adaptMissingPluginIdToTarget(
    missingPluginId: String,
    targetPluginId: String,
): Int {
    val updatedList = dbm.systemTtsV2.all.mapNotNull { systts ->
        val config = systts.config as? TtsConfigurationDTO ?: return@mapNotNull null
        val source = config.source as? PluginTtsSource ?: return@mapNotNull null

        if (source.pluginId != missingPluginId) {
            return@mapNotNull null
        }

        systts.copy(
            config = config.copy(
                source = source.copy(
                    pluginId = targetPluginId
                )
            )
        )
    }

    if (updatedList.isNotEmpty()) {
        dbm.systemTtsV2.update(*updatedList.toTypedArray())

        if (updatedList.any { it.isEnabled }) {
            SystemTtsService.notifyUpdateConfig()
        }
    }

    return updatedList.size
}

@Composable
private fun PluginAudioParamsDialog(
    pluginName: String,
    count: Int,
    speed: Float,
    volume: Float,
    pitch: Float,
    onDismissRequest: () -> Unit,
    onConfirm: (speed: Float, volume: Float, pitch: Float) -> Unit,
) {
    var speedValue by remember(pluginName, speed) { mutableFloatStateOf(speed) }
    var volumeValue by remember(pluginName, volume) { mutableFloatStateOf(volume) }
    var pitchValue by remember(pluginName, pitch) { mutableFloatStateOf(pitch) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(text = "音频参数")
                Text(
                    text = "$pluginName · 共 $count 个声音",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column {
                AudioParamSlider(
                    label = "语速",
                    value = speedValue,
                    onValueChange = { speedValue = it }
                )

                Spacer(Modifier.height(12.dp))

                AudioParamSlider(
                    label = "音量",
                    value = volumeValue,
                    onValueChange = { volumeValue = it }
                )

                Spacer(Modifier.height(12.dp))

                AudioParamSlider(
                    label = "音高",
                    value = pitchValue,
                    onValueChange = { pitchValue = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        roundParam(speedValue),
                        roundParam(volumeValue),
                        roundParam(pitchValue)
                    )
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MissingPluginAdaptDialog(
    matches: List<MissingPluginMatch>,
    onDismissRequest: () -> Unit,
    onConfirm: (MissingPluginMatch) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text("检测到失效插件音色")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("以下配置列表音色找不到原插件，可以适配到检测到的同源新插件版本：")

                Spacer(Modifier.height(8.dp))

                matches.forEach { match ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onConfirm(match)
                        }
                    ) {
                        Text(
                            text = "${match.missingPluginId} → ${match.targetPlugin.pluginId}（${match.count} 个）"
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AudioParamSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "$label：${formatParam(value)}",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onValueChange((value - 0.05f).coerceIn(0.1f, 3.0f))
                }
            ) {
                Text("－")
            }

            Slider(
                value = value.coerceIn(0.1f, 3.0f),
                onValueChange = {
                    onValueChange(it.coerceIn(0.1f, 3.0f))
                },
                valueRange = 0.1f..3.0f,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = {
                    onValueChange((value + 0.05f).coerceIn(0.1f, 3.0f))
                }
            ) {
                Text("＋")
            }
        }
    }
}

private fun roundParam(value: Float): Float {
    return (value * 100f).roundToInt() / 100f
}

private fun formatParam(value: Float): String {
    return "%.2f".format(roundParam(value))
}

@Composable
private fun Item(
    modifier: Modifier,
    hasDefVars: Boolean,
    needSetVars: Boolean,
    name: String,
    desc: String,
    iconUrl: String?,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onMoveGroup: () -> Unit,
    onEdit: () -> Unit,
    onSetVars: () -> Unit,
    onEditAudioParams: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current

    ElevatedCard(
        modifier = modifier.semantics {
            customActions =
                listOf(
                    CustomAccessibilityAction("编辑") {
                        onEdit()
                        true
                    },
                    CustomAccessibilityAction("设置变量") {
                        onSetVars()
                        true
                    },
                    CustomAccessibilityAction("导出") {
                        onExport()
                        true
                    },
                    CustomAccessibilityAction("清空缓存") {
                        onClear()
                        true
                    },
                    CustomAccessibilityAction("删除") {
                        onDelete()
                        true
                    },
                )
        },
        onClick = {
            if (hasDefVars) {
                onSetVars()
            }
        }
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        context
                            .getString(
                                if (isEnabled) {
                                    R.string.plugin_enabled_desc
                                } else {
                                    R.string.plugin_disabled_desc
                                },
                                name
                            )
                            .let {
                                contentDescription = it
                                stateDescription = it
                            }
                    }
                )

                PluginImage(model = iconUrl, name = name)

                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start
                    )
                }

                Row {
                    IconButton(onClick = onMoveGroup) {
                        Text("🗂️")
                    }

                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(id = R.string.edit_desc, name))
                    }

                    var showOptions by remember { mutableStateOf(false) }
                    IconButton(onClick = { showOptions = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            stringResource(id = R.string.more_options_desc, name)
                        )

                        DropdownMenu(
                            expanded = showOptions,
                            onDismissRequest = { showOptions = false }
                        ) {
                            if (hasDefVars) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(id = R.string.plugin_set_vars)) },
                                    onClick = {
                                        showOptions = false
                                        onSetVars()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.EditNote, null)
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.export_config)) },
                                onClick = {
                                    showOptions = false
                                    onExport()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Output, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("音频参数") },
                                onClick = {
                                    showOptions = false
                                    onEditAudioParams()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.VolumeUp, null)
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_cache)) },
                                onClick = {
                                    showOptions = false
                                    onClear()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CleaningServices, null)
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(id = R.string.delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showOptions = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (needSetVars) {
                Text(
                    text = stringResource(id = R.string.systts_plugin_please_set_vars),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
