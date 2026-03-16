package com.hjh_database.warehouse.gui

import com.hjh_database.warehouse.data.WarehouseData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class WarehouseGUI {

    // 打开一级菜单
    fun openMainMenu(viewer: Player, targetData: WarehouseData) {
        val inv = Bukkit.createInventory(null, 54, "§0个人仓库 - ${targetData.playerName}")
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = glass.itemMeta
        meta?.setDisplayName(" ")
        glass.itemMeta = meta

        // 填充首尾行
        for (i in 0..8) inv.setItem(i, glass)
        for (i in 45..53) inv.setItem(i, glass)

        // 摆放8个子仓库 (第3、4行)
        val slots = intArrayOf(19, 21, 23, 25, 28, 30, 32, 34)
        for (i in 0..7) {
            val chest = ItemStack(Material.CHEST)
            val chestMeta = chest.itemMeta
            chestMeta?.setDisplayName(targetData.categoryNames[i])
            chestMeta?.lore = listOf("§e左键 §7打开子仓库", "§e右键 §7重命名此仓库")
            chest.itemMeta = chestMeta
            inv.setItem(slots[i], chest)
        }
        viewer.openInventory(inv)
        // 播放开箱音效 (音量1.0, 音调1.0)
        viewer.playSound(viewer.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
        viewer.openInventory(inv)
    }

    // 打开子菜单 (翻页)
    fun openSubMenu(viewer: Player, targetData: WarehouseData, subId: Int, page: Int) {
        // 标题可以带上页码，方便玩家识别和 Listener 拦截
        val inv = Bukkit.createInventory(null, 54, "§0仓库: ${targetData.categoryNames[subId]} - 第${page + 1}页")

        // 1. 准备基础装饰物：灰色玻璃
        val glass = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta = glass.itemMeta
        meta?.setDisplayName(" ")
        glass.itemMeta = meta

        // 2. 填充首行 (0-8) 和 尾行 (45-53) 的玻璃
        for (i in 0..8) inv.setItem(i, glass)
        for (i in 45..53) inv.setItem(i, glass)

        // 3. 放置翻页和返回按钮 (覆盖底部的玻璃)
        // 上一页按钮 (放在 45 格，仅当不是第一页时显示)
        if (page > 0) {
            val prevBtn = ItemStack(Material.ARROW)
            val prevMeta = prevBtn.itemMeta
            prevMeta?.setDisplayName("§a上一页")
            prevBtn.itemMeta = prevMeta
            inv.setItem(45, prevBtn)
        }

        // 返回主菜单按钮 (放在中间 49 格)
        val backBtn = ItemStack(Material.OAK_DOOR) // 你可以换成你喜欢的材质
        val backMeta = backBtn.itemMeta
        backMeta?.setDisplayName("§c返回主菜单")
        backBtn.itemMeta = backMeta
        inv.setItem(49, backBtn)

        // 下一页按钮 (放在 53 格，仅当不是最后一页时显示，共3页则最大page是2)
        if (page < 2) {
            val nextBtn = ItemStack(Material.ARROW)
            val nextMeta = nextBtn.itemMeta
            nextMeta?.setDisplayName("§a下一页")
            nextBtn.itemMeta = nextMeta
            inv.setItem(53, nextBtn)
        }

        // 4. 加载玩家存在这个子仓库的物品 (放在中间的 9~44 格)
        val startIndex = page * 36
        for (i in 0..35) {
            val item = targetData.items[subId][startIndex + i]
            inv.setItem(i + 9, item)
        }

        // 最后，打开这个界面
        viewer.openInventory(inv)
        // 同样播放开箱音效
        viewer.playSound(viewer.location, Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f)
        viewer.openInventory(inv)
    }
}