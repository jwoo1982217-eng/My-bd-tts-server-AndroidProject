package com.github.jing332.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @get:Query("SELECT * FROM plugin ORDER BY `order` ASC, pluginId ASC, version DESC, id DESC")
    val all: List<Plugin>

    @get:Query("SELECT * FROM plugin WHERE isEnabled = 1 ORDER BY `order` ASC, pluginId ASC, version DESC, id DESC")
    val allEnabled: List<Plugin>

    @Query("SELECT * FROM plugin ORDER BY `order` ASC, pluginId ASC, version DESC, id DESC")
    fun flowAll(): Flow<List<Plugin>>

    @get:Query("SELECT count(*) FROM plugin")
    val count: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg data: Plugin)

    @Delete
    fun delete(vararg data: Plugin)

    @Update
    fun update(vararg data: Plugin)

    /**
     * 兼容旧调用：
     * 如果同一个 pluginId 有多个版本，默认返回版本号最高的那个。
     */
    @Query(
        """
        SELECT * FROM plugin 
        WHERE pluginId = :pluginId 
        ORDER BY version DESC, id DESC 
        LIMIT 1
        """
    )
    fun getByPluginId(pluginId: String): Plugin?

    /**
     * 新增：精确查找同源同版本插件。
     * 用于导入备份时判断“更新同版本”还是“新增不同版本”。
     */
    @Query(
        """
        SELECT * FROM plugin 
        WHERE pluginId = :pluginId AND version = :version 
        ORDER BY id DESC 
        LIMIT 1
        """
    )
    fun getByPluginIdAndVersion(pluginId: String, version: Int): Plugin?

    /**
     * 兼容旧调用：
     * 如果同一个 pluginId 有多个启用版本，默认用版本号最高的那个。
     *
     * 注意：如果以后想让不同语音分别绑定不同版本插件，
     * TTS 配置里还需要保存 pluginVersion。
     */
    @Query(
        """
        SELECT * FROM plugin 
        WHERE pluginId = :pluginId AND isEnabled = 1 
        ORDER BY version DESC, id DESC 
        LIMIT 1
        """
    )
    fun getEnabled(pluginId: String): Plugin?

    /**
     * 修复点：
     * 旧逻辑只按 pluginId 判断，导致同源不同版本互相覆盖或跳过。
     *
     * 新逻辑：
     * 1. pluginId + version 都相同：更新原记录
     * 2. pluginId 相同但 version 不同：新增一条
     * 3. pluginId 不存在：新增一条
     */
    fun insertOrUpdate(vararg args: Plugin) {
        for (v in args) {
            val oldSameVersion = getByPluginIdAndVersion(
                pluginId = v.pluginId,
                version = v.version
            )

            val oldLatestSamePluginId = getByPluginId(v.pluginId)

            if (oldSameVersion == null) {
                val mergedUserVars =
                    if (v.userVars.isEmpty()) {
                        oldLatestSamePluginId?.userVars ?: v.userVars
                    } else {
                        (oldLatestSamePluginId?.userVars ?: emptyMap()) + v.userVars
                    }

                val mergedIsEnabled = oldLatestSamePluginId?.isEnabled ?: v.isEnabled
                val mergedOrder = oldLatestSamePluginId?.order ?: v.order

                insert(
                    v.copy(
                        id = 0,
                        userVars = mergedUserVars,
                        isEnabled = mergedIsEnabled,
                        order = mergedOrder
                    )
                )
            } else {
                val mergedUserVars =
                    if (v.userVars.isEmpty()) {
                        oldSameVersion.userVars
                    } else {
                        oldSameVersion.userVars + v.userVars
                    }

                update(
                    v.copy(
                        id = oldSameVersion.id,
                        userVars = mergedUserVars,
                        isEnabled = oldSameVersion.isEnabled,
                        order = oldSameVersion.order
                    )
                )
            }
        }
    }
}