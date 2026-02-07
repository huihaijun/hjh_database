package com.hjh_database.resource

import com.hjh_database.Hjh_database
import com.hjh_database.dz.gui.AdminRecipeListGui
import com.hjh_database.dz.gui.PlayerRecipeListGui
import com.hjh_database.dz.gui.RecipePreviewGui
import com.hjh_database.skill.medical.gui.MedicalEtchGui.EtchHolder
import com.hjh_database.skill.medical.gui.MedicalEtchGui.MainMenuHolder
import com.hjh_database.skill.medical.gui.MedicalEtchGui.SeparateHolder
import com.hjh_database.spawner.gui.SpawnerGui
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.persistence.PersistentDataType

/**
 * 资源监听器 (Kotlin 优化版)
 * 负责在特定时机自动刷新物品属性
 */
class ResourceListener(private val plugin: Hjh_database) : Listener {

    // 本地定义 Key，防止调用其他 Manager 可能出现的空指针或顺序问题
    // 这个 Key 字符串必须和 MedicalManager/ResourceManager 里的一模一样
    private val keyIgnoreRefresh = NamespacedKey(plugin, "hjh_ignore_refresh")

    // 1. 玩家进服刷新
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        updateInventory(event.player.inventory)
    }

    // 2. 打开容器刷新
    @EventHandler
    fun onOpen(event: InventoryOpenEvent) {
        val inv = event.inventory
        val holder = inv.holder

        // 保持您原有的：黑名单界面不刷新
        // 如果打开的是配方预览、刻蚀界面等特殊 GUI，直接跳过
        if (holder is PlayerRecipeListGui ||
            holder is AdminRecipeListGui ||
            holder is RecipePreviewGui ||
            holder is EtchHolder ||
            holder is SeparateHolder ||
            holder is MainMenuHolder ||
            holder is SpawnerGui
        ) {
            return
        }

        // 刷新打开的容器内容 (比如箱子)
        updateInventory(inv)

        // 顺便刷新玩家自己的背包
        if (event.player is Player) {
            updateInventory((event.player as Player).inventory)
        }
    }

    // === 核心刷新逻辑 ===
    private fun updateInventory(inv: Inventory) {
        // 使用 contents 遍历，并进行空安全检查
        for (item in inv.contents) {
            // 基础检查：物品为空、空气或没有 Meta，直接跳过
            if (item == null || item.type == Material.AIR || !item.hasItemMeta()) {
                continue
            }

            val meta = item.itemMeta ?: continue

            // ================= 改动位置 =================
            // 1. 优先检查：是否有“免刷新锁”
            // 如果有这个标记，直接 continue 跳过，保护它的名字和 Lore 不被还原
            // 修复：使用标准的 Bukkit API 调用方式
            if (meta.persistentDataContainer.has(keyIgnoreRefresh, PersistentDataType.INTEGER)) {
                continue
            }

            // 2. 调用 ResourceManager 执行刷新
            // 这里的 resourceManager 是 Hjh_database 中的公开属性
            plugin.resourceManager.refreshItem(item)
        }
    }
}