package com.hjh_database.spawner.gui

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.ItemSerializer
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobDrop
import com.hjh_database.spawner.SpawnerData
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.CreatureSpawner
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class SpawnerGui(
    private val plugin: Hjh_database,
    private val player: Player,
    private val spawner: CreatureSpawner
) : Listener, InventoryHolder {

    private val manager = plugin.spawnerBlockManager
    private var data: SpawnerData

    // 状态控制
    private var isSwitchingGui = false // 是否正在切换菜单（防止触发 Close 逻辑）
    private var isEditingName = false  // 是否正在聊天栏输入名字
    private var isClosed = false       // 标记 GUI 是否已彻底关闭（防止重复注销）

    private var lastClickTime: Long = 0

    // 怪物类型中文映射
    private val mobNames = mapOf(
        EntityType.ZOMBIE to "僵尸",
        EntityType.SKELETON to "骷髅",
        EntityType.SPIDER to "蜘蛛",
        EntityType.CAVE_SPIDER to "洞穴蜘蛛",
        EntityType.BLAZE to "烈焰人",
        EntityType.ZOMBIFIED_PIGLIN to "僵尸猪灵",
        EntityType.MAGMA_CUBE to "岩浆怪",
        EntityType.SLIME to "史莱姆",
        EntityType.WITHER_SKELETON to "凋零骷髅",
        EntityType.STRAY to "流浪者",
        EntityType.VINDICATOR to "卫道士",
        EntityType.PILLAGER to "掠夺者",
        EntityType.WITCH to "女巫",
        EntityType.ENDERMAN to "末影人"
    )

    // 允许轮换的列表
    private val allowedMobs = mobNames.keys.toList()

    init {
        // 读取数据，若为空则新建
        data = manager.getSpawnerData(spawner) ?: SpawnerData()
        // 注册监听器
        plugin.server.pluginManager.registerEvents(this, plugin)
        openMainMenu()
    }

    /**
     * 彻底销毁 GUI 实例，注销监听器，防止内存泄漏和崩服
     */
    private fun destroy() {
        if (!isClosed) {
            HandlerList.unregisterAll(this)
            isClosed = true
        }
    }

    override fun getInventory(): Inventory {
        // 防止引用失效，尽量返回当前打开的，或者新建一个临时的
        return player.openInventory.topInventory
    }

    // ================= 菜单页面 =================

    fun openMainMenu() {
        isSwitchingGui = true // 标记：正在切换
        val inv = Bukkit.createInventory(this, 54, "§0刷怪笼编辑器 - 主页")

        // 4: 怪物设置 (显示中文)
        val cnName = mobNames[data.mobType] ?: data.mobType.name
        val nameStr = if (data.mobName != "&c未知怪物") data.mobName else "§7默认"

        inv.setItem(4, createItem(Material.SPAWNER, "§e当前怪物: §f$cnName", listOf(
            "§7内部ID: ${data.mobType}",
            "§7显示名称: §r$nameStr",
            "",
            "§b[左键] §7切换怪物类型",
            "§b[右键] §7修改名字(支持颜色)"
        )))

        // 8: 开关状态
        val statusMat = if (data.isEnabled) Material.LIME_DYE else Material.GRAY_DYE
        val statusText = if (data.isEnabled) "§a[已启用]" else "§c[已禁用]"
        val statusLore = if(data.isEnabled)
            listOf("§7当前: §a正在刷怪", "§e点击禁用 (停止生成)")
        else
            listOf("§7当前: §c停止刷怪", "§e点击启用 (点击保存后生效)")

        inv.setItem(8, createItem(statusMat, "§f刷怪状态: $statusText", statusLore))

        // 属性区
        inv.setItem(19, createItem(Material.RED_DYE, "§c血量: §f${data.health}", listOf("§7左键+1, 右键-1", "§7Shift+左 +10")))
        inv.setItem(20, createItem(Material.IRON_SWORD, "§c攻击力: §f${data.damage}", listOf("§7左键+1, 右键-1")))
        inv.setItem(21, createItem(Material.IRON_CHESTPLATE, "§c护甲: §f${data.armor}", listOf("§7左键+1, 右键-1")))
        inv.setItem(22, createItem(Material.FEATHER, "§b速度: §f${String.format("%.2f", data.speed)}", listOf("§7左键+0.05, 右键-0.05")))

        // 规则区
        inv.setItem(28, createItem(Material.CLOCK, "§e冷却: §f${data.cooldown/20}秒", listOf("§7当前: ${data.cooldown} ticks", "§7左键+1s, 右键-1s")))
        inv.setItem(29, createItem(Material.COMPASS, "§e范围: §f${data.checkRange}格", listOf("§7检测玩家范围", "§7左键+1, 右键-1")))
        inv.setItem(30, createItem(Material.ZOMBIE_HEAD, "§e上限: §f${data.maxNearby}只", listOf("§7范围内最大怪物数", "§7左键+1, 右键-1")))

        // 坐标
        val locStatus = if (data.targetLocationStr == null) "§7默认(上方)" else "§a已绑定"
        inv.setItem(31, createItem(Material.BEACON, "§b刷新坐标", listOf("§7状态: $locStatus", "§e点击重置为默认")))

        // 功能区
        inv.setItem(46, createItem(Material.ENCHANTED_BOOK, "§d词缀管理", listOf("§7已选: §f${data.affixes.size}个", "§e点击编辑")))
        inv.setItem(47, createItem(Material.DIAMOND_CHESTPLATE, "§b装备管理", listOf("§7点击编辑装备")))
        inv.setItem(48, createItem(Material.CHEST, "§6掉落物管理", listOf("§7点击编辑掉落")))

        // 53: 保存
        inv.setItem(53, createItem(Material.EMERALD_BLOCK, "§a§l保存并生效", listOf("§7将配置写入方块", "§c注意: 会覆盖原版刷怪笼逻辑")))

        player.openInventory(inv)
        isSwitchingGui = false // 切换完成
    }

    private fun openDropMenu() {
        isSwitchingGui = true
        val inv = Bukkit.createInventory(this, 36, "§0掉落物管理")

        for (i in 0 until 9) {
            if (i < data.drops.size) {
                val drop = data.drops[i]
                // ★★★ 修复点：优先从 Base64 恢复完整物品 ★★★
                val item = if (drop.itemBase64 != null) {
                    try {
                        ItemSerializer.fromBase64(drop.itemBase64)
                    } catch (e: Exception) {
                        ItemStack(drop.material, drop.amount) // 如果解析失败，回退到普通物品
                    }
                } else {
                    ItemStack(drop.material, drop.amount) // 兼容旧数据
                }
                // 确保数量正确
                item.amount = drop.amount

                val meta = item.itemMeta
                if (drop.modelData != 0) meta?.setCustomModelData(drop.modelData)
                val lore = meta?.lore ?: ArrayList()
                lore.add("§8---------------")
                lore.add("§7当前掉率: §e${(drop.chance * 100).toInt()}%")
                meta?.lore = lore
                item.itemMeta = meta
                inv.setItem(i, item)

                val pct = (drop.chance * 100).toInt()
                inv.setItem(i + 9, createItem(Material.LIME_STAINED_GLASS_PANE, "§a概率控制 #${i+1}", listOf(
                    "§7当前: §e$pct%",
                    "§b左键+1% / Shift+左+10%",
                    "§c右键-1% / Shift+右-10%"
                )))
            } else {
                inv.setItem(i + 9, createItem(Material.GRAY_STAINED_GLASS_PANE, "§7空槽位", listOf("§7上方放入物品自动激活")))
            }
        }
        inv.setItem(31, createItem(Material.BOOK, "§e说明", listOf("§7第一行(0-8)放入物品", "§7第二行点击调整概率")))
        inv.setItem(35, createItem(Material.ARROW, "§a返回主页", null))

        player.openInventory(inv)
        isSwitchingGui = false
    }

    private fun openEquipMenu() {
        isSwitchingGui = true
        val inv = Bukkit.createInventory(this, 9, "§0装备编辑 (手/副/头/胸/腿/鞋)")

        val slots = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        val currentEquips = data.getEquipmentMap()

        slots.forEachIndexed { index, slot ->
            val item = currentEquips[slot]
            if (item != null && item.type != Material.AIR) {
                inv.setItem(index, item.clone())
            }
        }

        inv.setItem(6, createItem(Material.BLACK_STAINED_GLASS_PANE, "§7分隔符", null))
        inv.setItem(7, createItem(Material.BLACK_STAINED_GLASS_PANE, "§7分隔符", null))
        inv.setItem(8, createItem(Material.ARROW, "§a保存并返回", null))
        player.openInventory(inv)
        isSwitchingGui = false
    }

    private fun openAffixMenu() {
        isSwitchingGui = true
        val inv = Bukkit.createInventory(this, 36, "§0词缀选择")
        MobAffix.values().forEachIndexed { index, affix ->
            if (index < 36) {
                val has = data.affixes.contains(affix)
                val icon = if (has) Material.ENCHANTED_BOOK else Material.BOOK
                val name = if (has) "§a${affix.displayName} [已启用]" else "§7${affix.displayName} [未启用]"
                inv.setItem(index, createItem(icon, name, listOf("§7${affix.description}", "§e点击切换")))
            }
        }
        inv.setItem(35, createItem(Material.ARROW, "§a返回主页", null))
        player.openInventory(inv)
        isSwitchingGui = false
    }

    // ================= 事件处理 =================

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.inventory.holder != this) return
        val title = e.view.title

        // 1. 主页和词缀页面：完全禁止拖拽
        if (title.contains("主页") || title.contains("词缀")) {
            e.isCancelled = true
            return
        }

        // 2. 装备和掉落物页面：只允许拖拽到特定格子和玩家背包
        // e.rawSlots 是涉及到的所有格子索引
        for (slot in e.rawSlots) {
            val isTopInv = slot < e.inventory.size
            if (isTopInv) {
                if (title.contains("掉落物")) {
                    // 掉落物页面只允许 0-8
                    if (slot !in 0..8) {
                        e.isCancelled = true
                        return
                    }
                } else if (title.contains("装备")) {
                    // 装备页面只允许 0-5
                    if (slot !in 0..5) {
                        e.isCancelled = true
                        return
                    }
                }
            }
        }
        // 如果只在玩家背包里拖拽，或者拖拽到允许的格子，则不取消
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        if (e.inventory.holder != this) return
        if (e.whoClicked.uniqueId != player.uniqueId) return

        // 禁止点击空区域报错
        if (e.clickedInventory == null) return

        val title = e.view.title
        val slot = e.rawSlot
        val click = e.click

        // 区分是上方界面还是玩家背包
        val isClickingTop = slot < e.inventory.size

        // ★★★ 核心修复：移除全局防抖，只在按钮操作时防抖，否则会影响物品交互 ★★★
        val now = System.currentTimeMillis()
        fun checkDebounce(): Boolean {
            if (now - lastClickTime < 200) return false
            lastClickTime = now
            return true
        }

        // 1. 主页逻辑
        if (title.contains("主页")) {
            e.isCancelled = true // 主页全禁止拿取
            if (!isClickingTop) return

            // 只有点击按钮才检查防抖
            if (!checkDebounce()) return

            when (slot) {
                4 -> { // 切换怪
                    if (click.isLeftClick) {
                        val idx = allowedMobs.indexOf(data.mobType)
                        val next = if (idx == -1 || idx >= allowedMobs.size - 1) 0 else idx + 1
                        data.mobType = allowedMobs[next]
                        playClick()
                        openMainMenu()
                    } else if (click.isRightClick) {
                        isEditingName = true
                        player.closeInventory()
                        player.sendMessage("§e[编辑] 请输入显示名称 (输入 cancel 取消):")
                    }
                }
                8 -> { // 开关
                    data.isEnabled = !data.isEnabled
                    playClick()
                    openMainMenu()
                }
                19 -> {
                    val change = if (click.isShiftClick) 10.0 else 1.0
                    data.health = (data.health + (if (click.isRightClick) -change else change)).coerceAtLeast(1.0)
                    playClick(); openMainMenu()
                }
                20 -> {
                    data.damage = (data.damage + (if (click.isRightClick) -1.0 else 1.0)).coerceAtLeast(0.0)
                    playClick(); openMainMenu()
                }
                21 -> {
                    data.armor = (data.armor + (if (click.isRightClick) -1.0 else 1.0)).coerceAtLeast(0.0)
                    playClick(); openMainMenu()
                }
                22 -> {
                    data.speed = (data.speed + (if (click.isRightClick) -0.05 else 0.05)).coerceIn(0.0, 1.0)
                    playClick(); openMainMenu()
                }
                28 -> {
                    val change = if (click.isShiftClick) 200 else 20
                    data.cooldown = (data.cooldown + (if (click.isRightClick) -change else change)).coerceAtLeast(20)
                    playClick(); openMainMenu()
                }
                29 -> {
                    data.checkRange = (data.checkRange + (if (click.isRightClick) -1 else 1)).coerceAtLeast(1)
                    playClick(); openMainMenu()
                }
                30 -> {
                    data.maxNearby = (data.maxNearby + (if (click.isRightClick) -1 else 1)).coerceAtLeast(1)
                    playClick(); openMainMenu()
                }
                31 -> { data.targetLocationStr = null; player.sendMessage("§a坐标已重置。"); playClick(); openMainMenu() }

                46 -> { playClick(); openAffixMenu() }
                47 -> { playClick(); openEquipMenu() }
                48 -> { playClick(); openDropMenu() }
                53 -> { saveToBlock(); player.closeInventory() } // 保存并退出
            }
        }

        // 2. 掉落物逻辑
        else if (title.contains("掉落物")) {
            // 返回按钮
            if (slot == 35) {
                e.isCancelled = true
                if (!checkDebounce()) return
                saveDropsFromGui(e.inventory) // 保存当前状态
                playClick()
                openMainMenu()
                return
            }

            // ★★★ 核心修复：允许操作物品槽(0-8)和玩家背包(非Top) ★★★
            // 如果点击的是 0-8 或者 玩家背包，允许操作，并且不进行防抖检测
            if (slot in 0..8 || !isClickingTop) {
                e.isCancelled = false
                return
            }

            // 其他区域（概率调整按钮、空位等）禁止拿取
            e.isCancelled = true

            if (slot in 9..17) {
                if (!checkDebounce()) return
                val index = slot - 9
                // 保存物品，防止刷新丢失
                saveDropsFromGui(e.inventory)

                if (index < data.drops.size) {
                    val drop = data.drops[index]
                    var change = if (click.isShiftClick) 0.1 else 0.01
                    if (click.isRightClick) change = -change

                    val newChance = (drop.chance + change).coerceIn(0.0, 1.0)
                    data.drops[index] = drop.copy(chance = newChance)

                    playClick()
                    openDropMenu()
                }
            }
        }

        // 3. 词缀逻辑
        else if (title.contains("词缀")) {
            e.isCancelled = true
            if (!checkDebounce()) return

            if (slot == 35) { playClick(); openMainMenu(); return }

            if (isClickingTop && slot < MobAffix.values().size) {
                val affix = MobAffix.values()[slot]
                if (data.affixes.contains(affix)) data.affixes.remove(affix) else data.affixes.add(affix)
                playClick()
                openAffixMenu()
            }
        }

        // 4. 装备逻辑
        else if (title.contains("装备")) {
            if (slot == 8) { // 保存返回
                e.isCancelled = true
                if (!checkDebounce()) return
                saveEquipmentFromGui(e.inventory, sanitize = true)
                playClick()
                openMainMenu()
                return
            }

            // ★★★ 核心修复：允许操作装备槽(0-5)和玩家背包 ★★★
            if (slot in 0..5 || !isClickingTop) {
                e.isCancelled = false
            } else {
                e.isCancelled = true
            }
        }
    }

    // 聊天监听 (独立处理，因为在聊天时 GUI 已经关闭)
    @EventHandler
    fun onChat(e: AsyncChatEvent) {
        if (!isEditingName || e.player.uniqueId != player.uniqueId) return
        e.isCancelled = true
        val msg = LegacyComponentSerializer.legacySection().serialize(e.message())

        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!msg.equals("cancel", true)) {
                data.mobName = msg.replace("&", "§")
                player.sendMessage("§a名字已设置。")
            } else {
                player.sendMessage("§e已取消。")
            }
            isEditingName = false

//            // 重新注册监听并打开菜单
//            plugin.server.pluginManager.registerEvents(this, plugin)
//            isClosed = false
            openMainMenu()
        })
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        if (e.inventory.holder != this) return

        // 如果正在切换 GUI，或者【正在编辑名字】，都不要销毁监听器
        if (isSwitchingGui || isEditingName) return

        val title = e.view.title
        val inv = e.inventory

        // === 1. 非主页关闭：尝试返回主页 ===
        // 如果玩家在子菜单按 ESC，应该保存临时数据并返回主页
        if (title.contains("掉落物管理")) {
            saveDropsFromGui(inv)
            reopenMainMenuDelay()
            return
        }

        if (title.contains("装备编辑")) {
            // 按 ESC 退出时，只保存，不净化（保持原样）
            saveEquipmentFromGui(inv, sanitize = false)
            reopenMainMenuDelay()
            return
        }

        if (title.contains("词缀")) {
            reopenMainMenuDelay()
            return
        }

        // === 2. 主页关闭：彻底退出 ===
        if (title.contains("主页")) {
            player.sendMessage("§7[HJH] 已退出刷怪笼编辑器。")
            destroy()
        }
    }

    // 延迟一帧打开主菜单
    private fun reopenMainMenuDelay() {
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (player.isOnline) {
                openMainMenu()
            } else {
                destroy()
            }
        })
    }

    // ================= 辅助方法 =================

    private fun saveDropsFromGui(inv: Inventory) {
        val newDrops = ArrayList<MobDrop>()
        for (i in 0 until 9) {
            val item = inv.getItem(i)
            if (item != null && item.type != Material.AIR) {
                // 保持原有的概率逻辑
                var chance = 0.5
                if (i < data.drops.size) {
                    chance = data.drops[i].chance
                }

                // 1. 克隆物品，避免直接修改界面上的物体
                val cleanItem = item.clone()
                val meta = cleanItem.itemMeta
                val lore = meta?.lore

                // 2. 剔除 GUI 自动添加的脏数据
                if (lore != null && lore.isNotEmpty()) {
                    // 使用 removeIf 移除掉所有匹配“分割线”或“掉率提示”的行
                    // 这样保存进 Base64 的就是纯净的原始物品数据
                    lore.removeIf { line ->
                        line == "§8---------------" || line.contains("当前掉率")
                    }
                    meta.lore = lore
                    cleanItem.itemMeta = meta
                }

                // 3. 使用净化后的 cleanItem 生成 Base64
                val base64 = ItemSerializer.toBase64(cleanItem)
                // ================== 修复结束 ==================

                val modelData = if (item.itemMeta?.hasCustomModelData() == true) item.itemMeta.customModelData else 0

                // 存入数据
                newDrops.add(MobDrop(item.type, item.amount, chance, modelData, base64))
            }
        }
        data.drops = newDrops
    }

    private fun saveEquipmentFromGui(inv: Inventory, sanitize: Boolean) {
        val slots = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

        slots.forEachIndexed { index, eqSlot ->
            val rawItem = inv.getItem(index)

            if (rawItem != null && rawItem.type != Material.AIR) {
                if (sanitize) {
                    // === 净化逻辑：只保留外观 ===
                    val cosmeticItem = ItemStack(rawItem.type)
                    val meta = cosmeticItem.itemMeta
                    // 复制 CustomModelData
                    if (rawItem.itemMeta?.hasCustomModelData() == true) {
                        meta?.setCustomModelData(rawItem.itemMeta!!.customModelData)
                    }
                    // 复制皮革颜色
                    if (rawItem.itemMeta is org.bukkit.inventory.meta.LeatherArmorMeta) {
                        (meta as org.bukkit.inventory.meta.LeatherArmorMeta).setColor((rawItem.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta).color)
                    }
                    // 设置无限耐久和隐藏标签
                    meta?.isUnbreakable = true
                    meta?.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE)

                    // ★★★ 彻底清除属性修改器 ★★★
                    // 这会移除装备自带的护甲、攻击力等，使其变成纯装饰品
                    meta?.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()

                    cosmeticItem.itemMeta = meta
                    data.setEquipment(eqSlot, cosmeticItem)
                } else {
                    // 原样保存
                    data.setEquipment(eqSlot, rawItem)
                }
            } else {
                data.setEquipment(eqSlot, null)
            }
        }
    }

    private fun saveToBlock() {
        try {
            manager.setSpawnerData(spawner, data)

            // 阉割原版刷怪笼
            spawner.spawnCount = 0
            spawner.minSpawnDelay = 200
            spawner.maxSpawnDelay = 800
            spawner.update()

            player.sendMessage("§a[HJH] 配置已保存！")
            player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1f, 1f)

            // 正常保存退出
            destroy()

        } catch (ex: Exception) {
            player.sendMessage("§c[错误] 保存失败！请检查后台报错。")
            ex.printStackTrace()
            destroy()
        }
    }

    private fun playClick() {
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
    }

    private fun createItem(mat: Material, name: String, lore: List<String>?): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }
}