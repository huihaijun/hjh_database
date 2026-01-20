package com.hjh_database.dz.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.Arrays

class CategoryGui(
    private val plugin: Hjh_database,
    private val player: Player,
    job: Int? // 即使没用到，为了保持原签名也保留
) : InventoryHolder, Listener {

    private val inv: Inventory
    private val settings: FileConfiguration

    // 【核心修复】1.21.3 替代 localizedName 的方案
    private val categoryKey = NamespacedKey(plugin, "gui_category_id")

    init {
        // 1. 【核心修复】读取 forge_settings.yml
        val file = File(plugin.dataFolder, "forge_settings.yml")
        this.settings = if (file.exists()) {
            YamlConfiguration.loadConfiguration(file)
        } else {
            YamlConfiguration() // 空配置，将触发兜底逻辑
        }

        val title = settings.getString("gui.title", "锻造台 - 选择分类")
        // createInventory title 不能为 null，虽然 default 不会为 null，但为了安全
        this.inv = Bukkit.createInventory(this, 27, title ?: "锻造台 - 选择分类")

        setupGui()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun setupGui() {
        // 背景
        val bg = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val m = bg.itemMeta
        if (m != null) {
            m.setDisplayName(" ")
            bg.itemMeta = m
        }
        for (i in 0 until 27) inv.setItem(i, bg)

        // 2. 读取分类配置
        val catSec = settings.getConfigurationSection("categories")

        // 如果配置文件没生成或者没写对，使用【硬编码兜底】，保证不出现屏障
        if (catSec == null || catSec.getKeys(false).isEmpty()) {
            addCategoryItem(10, Material.IRON_SWORD, "weapon", "§c§l[武器锻造]", "§7打造各种神兵利器")
            addCategoryItem(12, Material.IRON_CHESTPLATE, "armor", "§9§l[防具锻造]", "§7打造坚固的盔甲")
            addCategoryItem(14, Material.NETHER_STAR, "artifact", "§6§l[法宝锻造]", "§7打造特殊的法宝")
            addCategoryItem(16, Material.CHEST, "misc", "§e§l[杂项锻造]", "§7打造材料与其他物品")
        } else {
            // 正常读取配置 (槽位如果不配，自己算一个简单的排列)
            val slots = intArrayOf(10, 12, 14, 16, 11, 13, 15)
            var index = 0

            for (key in catSec.getKeys(false)) {
                if (index >= slots.size) break
                val name = catSec.getString("$key.name", key)
                val iconMat = catSec.getString("$key.icon", "BARRIER")
                val lore = catSec.getStringList("$key.lore")

                var mat = Material.getMaterial(iconMat!!) // Kotlin 需处理可能的 null，虽然 getString 有 default 但 iconMat 变量本身可能被推断
                if (mat == null) mat = Material.BARRIER

                val item = ItemStack(mat)
                val meta = item.itemMeta
                if (meta != null) {
                    meta.setDisplayName(name)
                    meta.lore = lore

                    // 【核心修复】使用 PDC 存 ID
                    // 原代码: meta.setLocalizedName(key);
                    meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, key)

                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                    item.itemMeta = meta
                }

                inv.setItem(slots[index++], item)
            }
        }
    }

    private fun addCategoryItem(slot: Int, mat: Material, id: String, name: String, lore: String) {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = Arrays.asList(lore, "", "§e点击进入")

            // 【核心修复】使用 PDC 存 ID
            // 原代码: meta.setLocalizedName(id);
            meta.persistentDataContainer.set(categoryKey, PersistentDataType.STRING, id)

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
        }
        inv.setItem(slot, item)
    }

    fun open() {
        player.openInventory(inv)
    }

    override fun getInventory(): Inventory {
        return inv
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory == inv) {
            HandlerList.unregisterAll(this)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.inventory != inv) return
        event.isCancelled = true

        val currentItem = event.currentItem
        if (currentItem == null) return

        val meta = currentItem.itemMeta ?: return

        // 【核心修复】从 PDC 取 ID
        // 原代码: String catId = meta.getLocalizedName();
        if (!meta.persistentDataContainer.has(categoryKey, PersistentDataType.STRING)) return

        val catId = meta.persistentDataContainer.get(categoryKey, PersistentDataType.STRING)

        if (!catId.isNullOrEmpty()) {
            player.closeInventory()
            if (player.isOp && player.inventory.itemInMainHand.type == Material.WOODEN_HOE) {
                AdminRecipeListGui(plugin, player, catId).open()
            } else {
                // 假设 PlayerRecipeListGui 已经/将会被重构为 Kotlin
                PlayerRecipeListGui(plugin, player, catId).open()
            }
        }
    }
}