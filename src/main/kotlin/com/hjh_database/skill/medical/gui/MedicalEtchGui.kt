package com.hjh_database.skill.medical.gui

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.MedicalManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.UUID

class MedicalEtchGui(private val plugin: Hjh_database) : Listener {
    private val manager: MedicalManager = plugin.medicalManager
    private val forgetConfirm = HashMap<UUID, Long>()
    private val stationFile = File(plugin.dataFolder, "medical_stations.yml")
    private val stationLocations = HashSet<String>()
    private val keyGuiSkillId = NamespacedKey(plugin, "medical_gui_skill_id")

    class EtchHolder(val owner: UUID, var rarity: Int) : InventoryHolder {
        lateinit var backingInventory: Inventory
        override fun getInventory(): Inventory = backingInventory
    }

    companion object {
        private const val TITLE = "§d医术绘制台"
        private const val SLOT_BANNER = 1
        private const val SLOT_FORGET_ALL = 2
        private const val SLOT_CLEAN_BANNER = 20
        private const val SLOT_PREVIOUS = 8
        private const val SLOT_NEXT = 26
        private val SKILL_SLOTS = intArrayOf(4, 5, 6, 13, 14, 15, 22, 23, 24)
    }

    init {
        loadStationLocations()
    }

    fun openMainMenu(player: Player) = openEtchGui(player)

    fun openEtchGui(player: Player, rarity: Int = 1) {
        if (!canUseMedicalStation(player, true)) return
        val holder = EtchHolder(player.uniqueId, rarity.coerceIn(1, 5))
        val inventory = Bukkit.createInventory(holder, 27, TITLE)
        holder.backingInventory = inventory
        render(inventory, holder, player)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val held = player.inventory.itemInMainHand
        val skillId = held.itemMeta?.persistentDataContainer
            ?.get(manager.keySkillId, PersistentDataType.STRING)
        if (skillId != null && !held.type.name.endsWith("_BANNER") && manager.getSkillBook(skillId) != null) {
            event.isCancelled = true
            learnFromBook(player, held, skillId)
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.END_PORTAL_FRAME) return
        if (locToString(block.location) !in stationLocations) return

        event.isCancelled = true
        if (!canUseMedicalStation(player, true)) return
        openEtchGui(player)
        player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f)
    }

    @EventHandler
    fun onStationPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type != Material.END_PORTAL_FRAME || !item.hasItemMeta()) return
        val isStation = item.itemMeta?.persistentDataContainer
            ?.has(manager.keyMedicalStation, PersistentDataType.STRING) == true
        if (!isStation) return
        stationLocations.add(locToString(event.blockPlaced.location))
        saveStationLocations()
        event.player.sendMessage("§a成功放置医术绘制台。")
    }

    @EventHandler
    fun onStationBreak(event: BlockBreakEvent) {
        if (stationLocations.remove(locToString(event.block.location))) saveStationLocations()
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? EtchHolder ?: return
        val player = event.whoClicked as? Player ?: return
        if (holder.owner != player.uniqueId) {
            event.isCancelled = true
            return
        }
        if (!canUseMedicalStation(player, false)) {
            event.isCancelled = true
            player.closeInventory()
            player.sendMessage("§c只有医师职业可以使用医术绘制台。")
            return
        }

        val top = event.clickedInventory == event.inventory
        if (!top) {
            if (event.isShiftClick) {
                event.isCancelled = true
                moveOneBannerIntoInput(player, event.inventory, event.currentItem)
            }
            return
        }

        event.isCancelled = true
        when (event.rawSlot) {
            SLOT_BANNER -> handleBannerSlotClick(event, player)
            SLOT_FORGET_ALL -> handleForgetAll(player, event.inventory, holder)
            SLOT_CLEAN_BANNER -> handleCleanBanner(player, event.inventory, holder)
            SLOT_PREVIOUS -> changePage(player, event.inventory, holder, -1)
            SLOT_NEXT -> changePage(player, event.inventory, holder, 1)
            in SKILL_SLOTS -> handleSkillClick(player, event.inventory, holder, event.currentItem)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder !is EtchHolder) return
        if (event.rawSlots.any { it < event.inventory.size }) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? EtchHolder ?: return
        val player = event.player as? Player ?: return
        if (holder.owner != player.uniqueId) return
        returnItem(player, event.inventory.getItem(SLOT_BANNER))
        event.inventory.setItem(SLOT_BANNER, null)
        forgetConfirm.remove(player.uniqueId)
    }

    private fun render(inventory: Inventory, holder: EtchHolder, player: Player) {
        val banner = inventory.getItem(SLOT_BANNER)?.clone()
        val data = plugin.playerManager.getPlayerData(player)
        val filler = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7")
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
        inventory.setItem(SLOT_BANNER, banner)

        val activeSkills = data?.getMedicalLoadout().orEmpty()
        val forgetLore = mutableListOf(
            "§7清空你当前启用的所有医术",
            "§7不会遗忘灵智中已领悟的医术",
            "§8----------------",
            "§e当前装配（${activeSkills.size}/5）："
        )
        if (activeSkills.isEmpty()) {
            forgetLore.add("§7暂无已装配医术")
        } else {
            activeSkills.forEach { skillId -> forgetLore.add("§7- ${manager.getSkillName(skillId)}") }
        }
        forgetLore.add("§c三秒内连续点击两次确认")
        inventory.setItem(
            SLOT_FORGET_ALL,
            createItem(
                Material.RED_WOOL,
                "§c§l遗忘全部已装配医术",
                *forgetLore.toTypedArray()
            )
        )
        inventory.setItem(
            SLOT_CLEAN_BANNER,
            createItem(
                Material.ORANGE_WOOL,
                "§6§l洗去当前医旗的医术",
                "§7只清洗左上角放入的这把医旗",
                "§7同时卸下你当前启用的对应医术",
                "§e点击立即清洗"
            )
        )
        inventory.setItem(SLOT_PREVIOUS, createItem(Material.ARROW, "§f上一阶", "§7当前：${holder.rarity}阶"))
        inventory.setItem(SLOT_NEXT, createItem(Material.ARROW, "§f下一阶", "§7当前：${holder.rarity}阶"))

        manager.getSkillIdsByRarity(holder.rarity).take(SKILL_SLOTS.size).forEachIndexed { index, skillId ->
            val learned = data?.hasLearnedMedicalSkill(skillId) == true
            val requiredTrial = manager.getRequiredTrial(skillId)
            val trialPassed = requiredTrial == null || data?.completedMedicalTrials?.contains(requiredTrial) == true
            val displayUnlocked = if (requiredTrial != null) trialPassed else learned
            if (holder.rarity >= 3 && !displayUnlocked) {
                inventory.setItem(
                    SKILL_SLOTS[index],
                    createItem(Material.RED_STAINED_GLASS_PANE, "§c此医术暂未通过医术试炼解锁")
                )
                return@forEachIndexed
            }

            val display = manager.getSkillBook(skillId)?.clone() ?: return@forEachIndexed
            display.amount = 1
            val meta = display.itemMeta ?: return@forEachIndexed
            val lore = (meta.lore ?: emptyList()).toMutableList()
            lore.removeAll { it.contains("右键领悟") || it.contains("放入绘制台") }
            lore.add("§8----------------")
            val active = data?.getMedicalLoadout()?.contains(skillId) == true
            lore.add(if (learned) "§a灵智：已领悟" else "§c灵智：尚未领悟")
            if (requiredTrial != null) lore.add(if (trialPassed) "§a试炼：已完成" else "§c试炼：尚未完成")
            lore.add(if (active) "§e状态：当前已启用" else "§7状态：未启用")
            lore.add(if (learned && trialPassed) "§e点击绘制或切换" else "§8暂不可绘制")
            meta.lore = lore
            meta.persistentDataContainer.set(keyGuiSkillId, PersistentDataType.STRING, skillId)
            meta.persistentDataContainer.set(manager.keyIgnoreRefresh, PersistentDataType.INTEGER, 1)
            if (active) meta.setEnchantmentGlintOverride(true)
            display.itemMeta = meta
            inventory.setItem(SKILL_SLOTS[index], display)
        }
    }

    private fun handleBannerSlotClick(event: InventoryClickEvent, player: Player) {
        val inventory = event.inventory
        val current = inventory.getItem(SLOT_BANNER)
        val cursor = event.cursor
        if (isEmpty(cursor)) {
            if (!isEmpty(current)) {
                event.setCursor(current!!)
                inventory.setItem(SLOT_BANNER, null)
                refresh(player, inventory)
            }
            return
        }
        if (!manager.isMedicalFlag(cursor)) {
            player.sendMessage("§c这里只能放入医师职业的医旗。")
            return
        }
        if (!isEmpty(current)) {
            player.sendMessage("§c请先取走左上角已有的医旗。")
            return
        }
        val placed = cursor.clone()
        placed.amount = 1
        inventory.setItem(SLOT_BANNER, placed)
        cursor.amount -= 1
        event.setCursor(if (cursor.amount <= 0) ItemStack(Material.AIR) else cursor)
        refresh(player, inventory)
    }

    private fun moveOneBannerIntoInput(player: Player, inventory: Inventory, item: ItemStack?) {
        if (isEmpty(item) || !manager.isMedicalFlag(item)) return
        if (!isEmpty(inventory.getItem(SLOT_BANNER))) {
            player.sendMessage("§c请先取走左上角已有的医旗。")
            return
        }
        val placed = item!!.clone()
        placed.amount = 1
        inventory.setItem(SLOT_BANNER, placed)
        item.amount -= 1
        refresh(player, inventory)
    }

    private fun handleSkillClick(player: Player, inventory: Inventory, holder: EtchHolder, item: ItemStack?) {
        val skillId = item?.itemMeta?.persistentDataContainer
            ?.get(keyGuiSkillId, PersistentDataType.STRING) ?: return
        val result = manager.etchLearnedSkill(player, inventory.getItem(SLOT_BANNER), skillId) ?: return
        inventory.setItem(SLOT_BANNER, result)
        render(inventory, holder, player)
    }

    private fun handleCleanBanner(player: Player, inventory: Inventory, holder: EtchHolder) {
        val banner = inventory.getItem(SLOT_BANNER)
        if (isEmpty(banner)) {
            player.sendMessage("§c请先在左上角放入需要清洗的医旗。")
            return
        }
        if (manager.getSkillIdFromBanner(banner) == null) {
            player.sendMessage("§c这把医旗上没有可以洗去的医术。")
            return
        }
        val result = manager.cleanCurrentBanner(player, banner) ?: return
        inventory.setItem(SLOT_BANNER, result)
        render(inventory, holder, player)
    }

    private fun handleForgetAll(player: Player, inventory: Inventory, holder: EtchHolder) {
        val now = System.currentTimeMillis()
        val previous = forgetConfirm[player.uniqueId]
        if (previous != null && now - previous <= 3_000L) {
            forgetConfirm.remove(player.uniqueId)
            if (manager.forgetAllActiveSkills(player)) {
                player.sendMessage("§c你已遗忘医旗上当前启用的全部医术。")
                player.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f)
            } else {
                player.sendMessage("§7你当前没有启用任何医术。")
            }
            render(inventory, holder, player)
            return
        }
        forgetConfirm[player.uniqueId] = now
        player.sendMessage("§c三秒内再次点击红色羊毛，确认卸下全部已启用医术。")
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f)
    }

    private fun changePage(player: Player, inventory: Inventory, holder: EtchHolder, delta: Int) {
        val target = (holder.rarity + delta).coerceIn(1, 5)
        if (target == holder.rarity) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.7f)
            return
        }
        holder.rarity = target
        render(inventory, holder, player)
        player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.1f)
    }

    private fun refresh(player: Player, inventory: Inventory) {
        val holder = inventory.holder as? EtchHolder ?: return
        render(inventory, holder, player)
    }

    private fun learnFromBook(player: Player, book: ItemStack, skillId: String) {
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            player.sendMessage("§c玩家数据尚未加载完成，请稍后再试。")
            return
        }
        if (data.job != 3) {
            player.sendMessage("§c只有医师能够领悟医术。")
            return
        }
        if (data.hasLearnedMedicalSkill(skillId)) {
            player.sendMessage("§7你的灵智中早已领悟了这门医术。")
            return
        }
        if (!manager.learnSkill(player, skillId)) return
        if (book.amount <= 1) player.inventory.setItemInMainHand(null) else book.amount -= 1
        player.sendMessage("§a你消耗了一卷秘籍，将 ${manager.getSkillName(skillId)} §a存入灵智。")
        val requiredTrial = manager.getRequiredTrial(skillId)
        if (requiredTrial != null && requiredTrial !in data.completedMedicalTrials) {
            player.sendMessage("§e你尚未通过对应的医术试炼，暂时无法绘制或施展这门医术。")
        }
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f)
    }

    private fun canUseMedicalStation(player: Player, notify: Boolean): Boolean {
        val data = plugin.playerManager.getPlayerData(player)
        if (data == null) {
            if (notify) player.sendMessage("§c玩家数据尚未加载完成，请稍后再试。")
            return false
        }
        if (data.job == 3) return true
        if (notify) {
            player.sendMessage("§c只有医师职业可以使用医术绘制台。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.8f, 1f)
        }
        return false
    }

    private fun createItem(material: Material, name: String, vararg lore: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        if (lore.isNotEmpty()) meta.lore = lore.toList()
        meta.persistentDataContainer.set(manager.keyIgnoreRefresh, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    private fun returnItem(player: Player, item: ItemStack?) {
        if (isEmpty(item)) return
        player.inventory.addItem(item!!).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type == Material.AIR

    private fun loadStationLocations() {
        if (!stationFile.exists()) return
        stationLocations.clear()
        stationLocations.addAll(YamlConfiguration.loadConfiguration(stationFile).getStringList("stations"))
    }

    private fun saveStationLocations() {
        val config = YamlConfiguration()
        config.set("stations", stationLocations.toList())
        config.save(stationFile)
    }

    private fun locToString(location: org.bukkit.Location): String =
        "${location.world?.name},${location.blockX},${location.blockY},${location.blockZ}"
}
