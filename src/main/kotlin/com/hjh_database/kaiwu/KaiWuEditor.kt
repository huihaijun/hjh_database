package com.hjh_database.kaiwu

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayList
import java.util.UUID

class KaiWuEditor(private val plugin: Hjh_database, private val manager: KaiWuManager) {
    private val skipSaveOnClose = mutableSetOf<UUID>()

    // --- 新增：缓存上一次保存的配置，作为新建节点的默认值 ---
    private var lastTimeSeconds = 10.0
    private var lastEnergyCost = 10.0
    private var lastExp = 20.0
    private var lastReqLevel = 1.0
    private var lastCooldownSec = 300.0
    private var lastDepletedSec = 300.0 // 初始占位符，首次打开时读取 config 的默认值

    fun openEditor(player: Player, locKey: String) {
        // 创建 54 格 GUI
        val inv: Inventory = Bukkit.createInventory(null, 54, "§0开物点编辑: $locKey")

        // 获取现有配置（如果有）
        val node = manager.getNode(locKey)

        // 读取全局默认值作为初始显示
        val defaultDepleted = plugin.config.getInt("kaiwu.mining.depleted_duration", 300)
        // 【新增】如果没初始化过，将配置里的默认值赋给缓存
        if (lastDepletedSec < 0) lastDepletedSec = defaultDepleted.toDouble()

        // --- 1. 放置掉落物 (如果有) ---
        if (node != null && node.drops.isNotEmpty()) {
            var i = 0
            while (i < node.drops.size && i < 45) {
                inv.setItem(i, node.drops[i])
                i++
            }
        }

        // --- 2. 底部功能栏 (45-53) ---
        fillBorder(inv)

        // [46] 采集耗时
        inv.setItem(
            46, createSettingIcon(
                Material.CLOCK, "§e采集耗时",
                node?.timeSeconds ?: lastTimeSeconds, "秒", "左键+0.5 / 右键-0.5"
            )
        )

        // [47] 精力消耗
        inv.setItem(
            47, createSettingIcon(
                Material.COOKED_BEEF, "§c精力消耗",
                node?.energyCost ?: lastEnergyCost, "点", "左键+1 / 右键-1"
            )
        )

        // [48] 经验产出
        inv.setItem(
            48, createSettingIcon(
                Material.EXPERIENCE_BOTTLE, "§b获得经验",
                node?.exp?.toDouble() ?: lastExp, "点", "左键+5 / 右键-5"
            )
        )

        // [49] 需求等级
        inv.setItem(
            49, createSettingIcon(
                Material.LADDER, "§6需求等级",
                node?.reqLevel?.toDouble() ?: lastReqLevel, "级", "左键+1 / 右键-1"
            )
        )

        // [50] 彻底冷却 (重生时间)
        inv.setItem(
            50, createSettingIcon(
                Material.COMPASS, "§d重生冷却 (挖空后)",
                node?.cooldownSec?.toDouble() ?: lastCooldownSec, "秒", "左键+10 / 右键-10"
            )
        )

        // [51] 枯竭恢复 (自然回满时间)
        inv.setItem(
            51, createSettingIcon(
                Material.ENCHANTED_BOOK, "§a自然恢复 (枯竭后)",
                node?.depletedSec?.toDouble() ?: lastDepletedSec, "秒", "左键+10 / 右键-10"
            )
        )

        // [53] 删除按钮
        val delete = ItemStack(Material.BARRIER)
        val dm = delete.itemMeta
        if (dm != null) {
            dm.setDisplayName("§c§l删除此资源点")
            delete.itemMeta = dm
        }
        inv.setItem(53, delete)

        player.openInventory(inv)
    }

    // --- GUI 点击处理 ---
    fun handleClick(e: InventoryClickEvent) {
        val title = e.view.title
        if (!title.startsWith("§0开物点编辑: ")) return

        e.isCancelled = false
        val slot = e.rawSlot
        if (slot in 45..53) {
            e.isCancelled = true

            val item = e.currentItem
            if (item == null) return

            val p = e.whoClicked as Player
            val isLeft = e.isLeftClick

            if (item.type == Material.CLOCK) updateVal(item, if (isLeft) 0.5 else -0.5, 0.5, 60.0, "秒")
            else if (item.type == Material.COOKED_BEEF) updateVal(item, if (isLeft) 1.0 else -1.0, 0.0, 1000.0, "点")
            else if (item.type == Material.EXPERIENCE_BOTTLE) updateVal(item, if (isLeft) 5.0 else -5.0, 0.0, 10000.0, "点")
            else if (item.type == Material.LADDER) updateVal(item, if (isLeft) 1.0 else -1.0, 1.0, 100.0, "级")
            else if (item.type == Material.COMPASS) updateVal(item, if (isLeft) 10.0 else -10.0, 10.0, 3600.0, "秒")
            // 【新增】处理 51 格点击
            else if (item.type == Material.ENCHANTED_BOOK) updateVal(item, if (isLeft) 10.0 else -10.0, 5.0, 3600.0, "秒")
            else if (item.type == Material.BARRIER) {
                val locKey = title.replace("§0开物点编辑: ", "")
                skipSaveOnClose += p.uniqueId
                manager.removeNode(p, locKey)
                p.closeInventory()
            }
        }
    }

    // --- GUI 关闭处理 (保存) ---
    fun handleClose(e: InventoryCloseEvent) {
        val title = e.view.title
        if (!title.startsWith("§0开物点编辑: ")) return
        if (skipSaveOnClose.remove(e.player.uniqueId)) return

        val locKey = title.replace("§0开物点编辑: ", "")
        val inv = e.inventory

        val drops: MutableList<ItemStack> = ArrayList()
        for (i in 0..44) {
            val it = inv.getItem(i)
            if (it != null && it.type != Material.AIR) {
                drops.add(it)
            }
        }

        val time = parseVal(inv.getItem(46))
        val energy = parseVal(inv.getItem(47))
        val exp = parseVal(inv.getItem(48)).toInt()
        val reqLv = parseVal(inv.getItem(49)).toInt()
        val cooldown = parseVal(inv.getItem(50)).toInt()
        // 【新增】读取第 51 格
        val depleted = parseVal(inv.getItem(51)).toInt()

        // --- 【新增】每次关闭保存时，更新上一次配置缓存 ---
        lastTimeSeconds = time
        lastEnergyCost = energy
        lastExp = exp.toDouble()
        lastReqLevel = reqLv.toDouble()
        lastCooldownSec = cooldown.toDouble()
        lastDepletedSec = depleted.toDouble()

        manager.saveNodeFromEditor(locKey, drops, time, energy, exp, reqLv, cooldown, depleted)
        e.player.sendMessage("§a[开物术] 资源点配置已保存！")
    }

    // --- 辅助方法 ---
    private fun fillBorder(inv: Inventory) {
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = glass.itemMeta
        if (meta != null) {
            meta.setDisplayName(" ")
            glass.itemMeta = meta
        }
        for (i in 45..53) {
            if (inv.getItem(i) == null) inv.setItem(i, glass)
        }
    }

    private fun createSettingIcon(mat: Material, name: String, val_: Double, unit: String, desc: String): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta!! // Material 构造的 Item 通常都有 Meta
        meta.setDisplayName(name)
        meta.lore = listOf(
            "§f当前值: §a" + String.format("%.1f", val_) + unit,
            "§7$desc"
        )
        meta.persistentDataContainer.set(NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE, val_)
        item.itemMeta = meta
        return item
    }

    private fun updateVal(item: ItemStack, delta: Double, min: Double, max: Double, unit: String) {
        val meta = item.itemMeta ?: return
        var current = meta.persistentDataContainer.get(NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE)
        if (current == null) current = 0.0

        var next = current + delta
        if (next < min) next = min
        if (next > max) next = max

        meta.persistentDataContainer.set(NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE, next)
        val lore = meta.lore ?: ArrayList()
        // Kotlin 的 List 是不可变的，如果 lore 是不可变的需要新建，但 meta.lore 返回通常是可变列表或者我们需要重新 set
        // 为了安全起见，创建一个新的 MutableList
        val newLore: MutableList<String> = ArrayList(lore)
        if (newLore.isEmpty()) {
            newLore.add("") // 防止越界
        }
        newLore[0] = "§f当前值: §a" + String.format("%.1f", next) + unit
        meta.lore = newLore
        item.itemMeta = meta
    }

    private fun parseVal(item: ItemStack?): Double {
        if (item == null || !item.hasItemMeta()) return 0.0
        val d = item.itemMeta!!.persistentDataContainer.get(NamespacedKey(plugin, "val"), PersistentDataType.DOUBLE)
        return d ?: 0.0
    }
}
