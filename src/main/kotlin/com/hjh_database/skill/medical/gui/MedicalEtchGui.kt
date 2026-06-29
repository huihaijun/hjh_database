package com.hjh_database.skill.medical.gui

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.MedicalManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
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
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture

class MedicalEtchGui(private val plugin: Hjh_database) : Listener {
    private val manager: MedicalManager = plugin.medicalManager
    private val deleteConfirm: MutableMap<UUID, Long> = HashMap()
    private val stationFile = File(plugin.dataFolder, "medical_stations.yml")
    private val stationLocations: MutableSet<String> = HashSet()

    // 内部 Holder 类
    class MainMenuHolder : InventoryHolder { override fun getInventory(): Inventory = null!! }
    class EtchHolder : InventoryHolder { override fun getInventory(): Inventory = null!! }
    class SeparateHolder : InventoryHolder { override fun getInventory(): Inventory = null!! }

    companion object {
        private const val TITLE_MAIN = "§0医术台 - 主菜单"
        private const val TITLE_ETCH = "§0医术绘制"
        private const val TITLE_SEPARATE = "§0医术分离"

        private const val SLOT_ETCH_BANNER = 10
        private const val SLOT_ETCH_BOOK = 12
        private const val SLOT_ETCH_BUTTON = 14
        private const val SLOT_ETCH_RESULT = 16

        private const val SLOT_SEP_INPUT = 13
        private const val SLOT_SEP_BUTTON = 22
        private const val SLOT_SEP_OUT_BANNER = 30
        private const val SLOT_SEP_OUT_BOOK = 32
    }

    private val etchInteractiveSlots: Set<Int> = HashSet(Arrays.asList(SLOT_ETCH_BANNER, SLOT_ETCH_BOOK, SLOT_ETCH_RESULT))
    private val separateInteractiveSlots: Set<Int> = HashSet(Arrays.asList(SLOT_SEP_INPUT, SLOT_SEP_OUT_BANNER, SLOT_SEP_OUT_BOOK))

    init {
        loadStationLocations()
    }

    fun openMainMenu(p: Player) {
        val inv = Bukkit.createInventory(MainMenuHolder(), 27, TITLE_MAIN)
        inv.setItem(11, createItem(Material.LOOM, "§a§l绘制医术", "§7将医术绘制到旗帜上", "§e点击进入"))
        inv.setItem(13, createItem(Material.GRINDSTONE, "§b§l医术分离", "§7将已绘制的旗帜还原", "§7分为: 空白旗 + 秘籍", "§c需要消耗记忆！", "§e点击进入"))
        // --- 修改部分开始 ---
        // 动态构建遗忘医术按钮的 Lore
        val lore = mutableListOf("§c慎用！", "§7清空所有已学会的医术记录", "§e双击确认", "§8----------------")
        val data = plugin.playerManager.getData(p.uniqueId)

        if (data != null && data.medicalSkills.isNotEmpty()) {
            lore.add("§e当前已掌握的医术:")
            for (skillId in data.medicalSkills) {
                // 通过 MedicalManager 获取医术的真实名称
                val skillName = manager.getSkillName(skillId)
                lore.add("§7- §a$skillName")
            }
        } else {
            lore.add("§7当前未掌握任何医术")
        }

        inv.setItem(15, createItem(Material.BARRIER, "§c§l遗忘所有医术", *lore.toTypedArray()))
        // --- 修改部分结束 ---
        fillGlass(inv, 27)
        p.openInventory(inv)
    }

    fun openEtchGui(p: Player) {
        val inv = Bukkit.createInventory(EtchHolder(), 27, TITLE_ETCH)
        fillGlass(inv, 27)
        inv.setItem(SLOT_ETCH_BANNER, null)
        inv.setItem(SLOT_ETCH_BOOK, null)
        inv.setItem(SLOT_ETCH_RESULT, null)

        val session = manager.getLoomSession(p)
        if (session != null) {
            if (session[0] != null) inv.setItem(SLOT_ETCH_BANNER, session[0])
            if (session[1] != null) inv.setItem(SLOT_ETCH_BOOK, session[1])
        }

        inv.setItem(SLOT_ETCH_BUTTON, createItem(Material.LIME_DYE, "§a§l点击绘制", "§7放入 旗帜 + 秘籍"))
        inv.setItem(26, createItem(Material.OAK_DOOR, "§7返回主菜单"))
        p.openInventory(inv)
    }

    fun openSeparateGui(p: Player) {
        val inv = Bukkit.createInventory(SeparateHolder(), 45, TITLE_SEPARATE)
        fillGlass(inv, 45)
        inv.setItem(SLOT_SEP_INPUT, null)
        inv.setItem(SLOT_SEP_OUT_BANNER, null)
        inv.setItem(SLOT_SEP_OUT_BOOK, null)
        inv.setItem(SLOT_SEP_BUTTON, createItem(Material.ANVIL, "§e§l点击分离", "§7放入已绘制的旗帜", "§7点击后判断是否拥有此医术"))
        inv.setItem(44, createItem(Material.OAK_DOOR, "§7返回主菜单"))
        p.openInventory(inv)
    }

    @EventHandler
    fun onBlockInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        if (e.clickedBlock == null) return
        val block = e.clickedBlock!!
        if (block.type != Material.END_PORTAL_FRAME) return
        if (!stationLocations.contains(locToString(block.location))) return

        e.isCancelled = true
        openMainMenu(e.player)
        e.player.playSound(e.player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f)
    }

    @EventHandler
    fun onStationPlace(e: BlockPlaceEvent) {
        val item = e.itemInHand
        if (item.type != Material.END_PORTAL_FRAME || !item.hasItemMeta()) return
        val hasStationKey = item.itemMeta?.persistentDataContainer
            ?.has(manager.keyMedicalStation, PersistentDataType.STRING) == true
        if (!hasStationKey) return

        stationLocations.add(locToString(e.blockPlaced.location))
        saveStationLocations()
        e.player.sendMessage("§a成功放置医术绘制台。")
    }

    @EventHandler
    fun onStationBreak(e: BlockBreakEvent) {
        val loc = locToString(e.block.location)
        if (stationLocations.remove(loc)) {
            saveStationLocations()
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val inv = e.inventory
        val p = e.player as Player

        if (inv.holder is EtchHolder) {
            val content = arrayOfNulls<ItemStack>(3)
            content[0] = inv.getItem(SLOT_ETCH_BANNER)
            content[1] = inv.getItem(SLOT_ETCH_BOOK)
            manager.saveLoomSession(p, content)
            returnItem(p, inv.getItem(SLOT_ETCH_RESULT))
        } else if (inv.holder is SeparateHolder) {
            returnItem(p, inv.getItem(SLOT_SEP_INPUT))
            returnItem(p, inv.getItem(SLOT_SEP_OUT_BANNER))
            returnItem(p, inv.getItem(SLOT_SEP_OUT_BOOK))
        }
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val inv = e.inventory
        val holder = inv.holder ?: return
        if (holder !is MainMenuHolder && holder !is EtchHolder && holder !is SeparateHolder) return

        val p = e.whoClicked as Player
        val slot = e.rawSlot
        val isTopInv = (e.clickedInventory == inv)

        if (isTopInv) {
            e.isCancelled = true
        } else {
            if (e.isShiftClick) {
                e.isCancelled = true
                if (holder is EtchHolder) {
                    val curr = e.currentItem
                    if (curr != null) {
                        if (curr.type.name.endsWith("_BANNER")) tryPut(inv, SLOT_ETCH_BANNER, curr)
                        else if (curr.type == Material.PAPER || curr.type == Material.BOOK) tryPut(inv, SLOT_ETCH_BOOK, curr)
                    }
                } else if (holder is SeparateHolder) {
                    if (e.currentItem != null && e.currentItem!!.type.name.endsWith("_BANNER")) {
                        tryPut(inv, SLOT_SEP_INPUT, e.currentItem!!)
                    }
                }
            }
            return
        }

        if (isTopInv) {
            if (holder is EtchHolder && etchInteractiveSlots.contains(slot)) {
                e.isCancelled = false
            }
            if (holder is SeparateHolder && separateInteractiveSlots.contains(slot)) {
                e.isCancelled = false
            }
        }

        if (isTopInv) {
            if (holder is MainMenuHolder) {
                if (slot == 11) openEtchGui(p)
                else if (slot == 13) openSeparateGui(p)
                else if (slot == 15) handleForgetButton(p)
            } else if (holder is EtchHolder) {
                if (slot == SLOT_ETCH_BUTTON) {
                    val res = manager.etchSkill(p, inv.getItem(SLOT_ETCH_BANNER), inv.getItem(SLOT_ETCH_BOOK))
                    if (res != null) {
                        consumeItem(inv, SLOT_ETCH_BANNER)
                        consumeItem(inv, SLOT_ETCH_BOOK)
                        inv.setItem(SLOT_ETCH_RESULT, res)
                    }
                } else if (slot == 26) openMainMenu(p)
            } else if (holder is SeparateHolder) {
                if (slot == SLOT_SEP_BUTTON) {
                    if (!isEmpty(inv.getItem(SLOT_SEP_OUT_BANNER)) || !isEmpty(inv.getItem(SLOT_SEP_OUT_BOOK))) {
                        p.sendMessage("§c请先清空输出槽位！")
                        return
                    }
                    val res = manager.separateSkill(p, inv.getItem(SLOT_SEP_INPUT))
                    if (res != null) {
                        consumeItem(inv, SLOT_SEP_INPUT)
                        inv.setItem(SLOT_SEP_OUT_BANNER, res[0])
                        inv.setItem(SLOT_SEP_OUT_BOOK, res[1])
                    }
                } else if (slot == 44) openMainMenu(p)
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val inv = e.inventory
        val holder = inv.holder
        if (holder !is EtchHolder && holder !is SeparateHolder) return

        val slots = e.rawSlots
        for (slot in slots) {
            if (slot < inv.size) {
                var isAllowed = false
                if (holder is EtchHolder && etchInteractiveSlots.contains(slot)) isAllowed = true
                if (holder is SeparateHolder && separateInteractiveSlots.contains(slot)) isAllowed = true

                if (!isAllowed) {
                    e.isCancelled = true
                    return
                }
            }
        }
    }

    private fun handleForgetButton(p: Player) {
        val uuid = p.uniqueId
        val now = System.currentTimeMillis()

        if (deleteConfirm.containsKey(uuid) && (now - deleteConfirm[uuid]!! < 3000)) {
            // 【关键点】显式使用 !! 断言，将 PlayerData? 转为 PlayerData
            val data = plugin.playerManager.getPlayerData(p)!!

            data.clearMedicalSkills()

            // 【核心修复】即时保存到数据库
            CompletableFuture.runAsync { plugin.databaseManager.saveMedicalData(data) }

            p.sendMessage("§c§l[警告] §7你已遗忘所有医术！")
            p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
            deleteConfirm.remove(uuid)
        } else {
            deleteConfirm[uuid] = now
            p.sendMessage("§c§l[警告] §7这将清空你所有的医术记忆！")
            p.sendMessage("§c§l[警告] §7请在3秒内再次点击以确认！")
            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f)
        }
    }

    private fun tryPut(inv: Inventory, slot: Int, item: ItemStack) {
        if (isEmpty(inv.getItem(slot))) {
            val toPut = item.clone()
            toPut.amount = 1
            inv.setItem(slot, toPut)
            item.amount = item.amount - 1
        }
    }

    private fun consumeItem(inv: Inventory, slot: Int) {
        val item = inv.getItem(slot)
        if (item != null) {
            item.amount = item.amount - 1
            inv.setItem(slot, item)
        }
    }

    private fun isEmpty(item: ItemStack?): Boolean {
        return item == null || item.type == Material.AIR
    }

    private fun fillGlass(inv: Inventory, size: Int) {
        val glass = createItem(Material.GRAY_STAINED_GLASS_PANE, "§7")
        for (i in 0 until size) {
            if (inv.getItem(i) == null || inv.getItem(i)!!.type == Material.AIR) {
                inv.setItem(i, glass)
            }
        }
    }

    private fun returnItem(p: Player, item: ItemStack?) {
        if (!isEmpty(item)) {
            p.inventory.addItem(item!!).values.forEach { i -> p.world.dropItem(p.location, i) }
        }
    }

    private fun createItem(mat: Material, name: String, vararg lore: String): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta!!.setDisplayName(name)
        if (lore.isNotEmpty()) meta.lore = Arrays.asList(*lore)
        item.itemMeta = meta
        return item
    }

    private fun loadStationLocations() {
        if (!stationFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(stationFile)
        stationLocations.clear()
        stationLocations.addAll(config.getStringList("stations"))
    }

    private fun saveStationLocations() {
        val config = YamlConfiguration()
        config.set("stations", stationLocations.toList())
        config.save(stationFile)
    }

    private fun locToString(loc: org.bukkit.Location): String {
        return "${loc.world?.name},${loc.blockX},${loc.blockY},${loc.blockZ}"
    }
}
