package com.hjh_database.quest.gui

import com.hjh_database.Hjh_database
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.QuestType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * 任务 GUI 管理 (独立文件)
 * 负责处理任务的二级菜单显示
 */
class QuestGui(private val plugin: Hjh_database) : Listener {

    // === 1. 一级菜单：选择任务类型 ===
    fun openCategoryMenu(player: Player) {
        val inv = Bukkit.createInventory(CategoryHolder(), 27, "§8任务列表")

        //放置四个分类按钮
        inv.setItem(10, createIcon(Material.BOOK, "§b§l云游志", listOf("§7查看主线任务")))
        inv.setItem(12, createIcon(Material.PAPER, "§4§l奇遇记", listOf("§7查看支线任务")))
        inv.setItem(14, createIcon(Material.MAP, "§e§l赏金簿", listOf("§7查看赏金任务")))
        inv.setItem(16, createIcon(Material.NETHER_STAR, "§c§l征伐书", listOf("§7查看挑战任务")))

        player.openInventory(inv)
    }

    // === 2. 二级菜单：具体任务列表 ===
    fun openQuestListMenu(player: Player, type: QuestType, requestedPage: Int = 0) {
        val data = plugin.playerManager.getPlayerData(player) ?: return

        // 获取所有任务并筛选
        // 1. 拿到所有任务
        // 2. 筛选类型 (主线/支线...)
        // 3. 筛选状态 (LOCKED 不显示)
        // 4. 排序 (主线优先 order 排序)
        val quests = plugin.questManager.getAllQuests().filter { quest ->
            if (quest.type != type) return@filter false

            // 检查状态，默认 LOCKED
            val status = data.questStatuses[quest.id] ?: QuestStatus.LOCKED

            // 如果你希望显示未解锁的任务(灰色)，就把这行注释掉
            // 但根据你的要求，我们通常只显示 进行中 和 已完成
            if (status == QuestStatus.LOCKED) return@filter false

            // 职业/等级不再匹配的未完成任务不显示，避免弓箭手看到战士专属支线等情况。
            status == QuestStatus.COMPLETED || quest.canAccept(player, data)
        }.sortedBy { it.order }

        val totalPages = maxOf(1, (quests.size + TASKS_PER_PAGE - 1) / TASKS_PER_PAGE)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val inv = Bukkit.createInventory(
            ListHolder(type, page),
            54,
            "§8${type.displayName} - 进度查询 §7(${page + 1}/$totalPages)"
        )

        // 遍历当前页任务并生成图标
        for ((index, quest) in quests.drop(page * TASKS_PER_PAGE).take(TASKS_PER_PAGE).withIndex()) {

            val status = data.questStatuses[quest.id] ?: QuestStatus.LOCKED
            val progress = data.questProgress[quest.id] ?: 0

            // === 【你的要求】设置材质：已完成=黄绿染料，未完成=粉色染料 ===
            val material = when (status) {
                QuestStatus.COMPLETED -> Material.LIME_DYE
                else -> Material.PINK_DYE
            }

            val icon = ItemStack(material)
            val meta = icon.itemMeta!!

            // 标题
            val statusStr = if (status == QuestStatus.COMPLETED) "§a[已完成]" else "§d[进行中]"
            meta.setDisplayName("$statusStr §f${quest.title}")

            // Lore: 简介 + 进度
            // 注意：这里调用的是 QuestBase.getDisplayLore(progress: Int)
            val lore = quest.getDisplayLore(progress)
            meta.lore = lore

            icon.itemMeta = meta
            inv.setItem(index, icon)
        }

        if (canRefresh(type)) {
            inv.setItem(48, createIcon(Material.NETHER_STAR, "§e§l刷新/接取新任务", listOf(
                "§7当版本更新增加了新${type.displayName}后",
                "§7如果后续任务没显示，请点击此处",
                "",
                "§b▶ 点击检测并接取新任务"
            )))
        }

        if (page > 0) {
            inv.setItem(45, createIcon(Material.ARROW, "§e§l上一页", listOf("§7前往第 $page 页")))
        }
        inv.setItem(47, createIcon(Material.MAP, "§f§l第 ${page + 1} / $totalPages 页", listOf("§7共 ${quests.size} 个任务")))
        if (page + 1 < totalPages) {
            inv.setItem(53, createIcon(Material.ARROW, "§e§l下一页", listOf("§7前往第 ${page + 2} 页")))
        }

        // 底部返回按钮
        inv.setItem(49, createIcon(Material.ARROW, "§f返回上一级", listOf()))

        player.openInventory(inv)
    }

    private fun createIcon(mat: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta!!
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    // === 监听器逻辑 ===
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val inv = e.inventory
        val holder = inv.holder
        val player = e.whoClicked as? Player ?: return

        // 1. 处理分类菜单点击
        if (holder is CategoryHolder) {
            e.isCancelled = true
            when (e.rawSlot) {
                10 -> openQuestListMenu(player, QuestType.MAIN)
                12 -> openQuestListMenu(player, QuestType.SIDE)
                14 -> openQuestListMenu(player, QuestType.BOUNTY)
                16 -> openQuestListMenu(player, QuestType.CHALLENGE)
            }
            if (e.rawSlot in listOf(10, 12, 14, 16)) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            }
        }
        // 2. 处理任务列表点击
        else if (holder is ListHolder) {
            e.isCancelled = true

            val type = holder.type
            val page = holder.page

            if (e.rawSlot == 45 && page > 0) {
                openQuestListMenu(player, type, page - 1)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                return
            }

            if (e.rawSlot == 53) {
                openQuestListMenu(player, type, page + 1)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
                return
            }

            if (e.rawSlot == 48 && canRefresh(type)) {
                refreshQuests(player, type)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f)
                openQuestListMenu(player, type, page)
                return
            }

            // 返回按钮
            if (e.rawSlot == 49) {
                openCategoryMenu(player)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
            }
            // 这里以后可以加：点击具体任务图标，进行任务追踪导航
        }
    }

    private fun canRefresh(type: QuestType): Boolean {
        return type == QuestType.MAIN || type == QuestType.SIDE
    }

    private fun refreshQuests(player: Player, type: QuestType) {
        val unlockCount = plugin.questManager.refreshAvailableQuests(player, type)
        if (unlockCount > 0) {
            player.sendMessage("§a[任务系统] 刷新成功！检测并接取了 §f$unlockCount §a个新任务。")
        } else {
            player.sendMessage("§7[任务系统] 刷新完毕，暂无满足接取条件的新任务。")
        }
    }

    // 占位符类，用于识别 GUI
    class CategoryHolder : InventoryHolder { override fun getInventory(): Inventory = Bukkit.createInventory(null, 9) }
    class ListHolder(val type: QuestType, val page: Int) : InventoryHolder { override fun getInventory(): Inventory = Bukkit.createInventory(null, 9) }

    private companion object {
        const val TASKS_PER_PAGE = 45
    }
}
