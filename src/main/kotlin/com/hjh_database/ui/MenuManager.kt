package com.hjh_database.ui

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.ArrayList

class MenuManager(private val plugin: Hjh_database) {
    private lateinit var file: File
    private lateinit var config: FileConfiguration
    private val tokenKey: NamespacedKey = NamespacedKey(plugin, "hjh_token_item")

    // 用于 PDC 识别的 Key
    private val keyId = NamespacedKey(plugin, "id")
    private val keyUid = NamespacedKey(plugin, "uid")
    private val keyRank = NamespacedKey(plugin, "element_rank")
    private val keyType = NamespacedKey(plugin, "element_type")

    // 职业和种族名称映射
    private val JOB_NAMES = arrayOf("战士", "弓箭手", "术士", "医师")
    private val RACE_NAMES = arrayOf("神", "仙", "人", "战神", "妖")

    init {
        reload()
    }

    // === 枚举定义 ===
    enum class ElementType(
        val displayName: String,
        val nbtId: String,
        val material: Material,
        val modelData: Int,
        val translatableKey: String
    ) {
        METAL("金元素", "panling:metal", Material.GOLD_NUGGET, 10004, "pl.item.name.metal"),
        WOOD("木元素", "panling:wood", Material.GOLD_NUGGET, 10005, "pl.item.name.wood"),
        WATER("水元素", "panling:water", Material.GOLD_NUGGET, 10006, "pl.item.name.water"),
        FIRE("火元素", "panling:fire", Material.GOLD_NUGGET, 10007, "pl.item.name.fire"),
        EARTH("土元素", "panling:earth", Material.GOLD_NUGGET, 10008, "pl.item.name.earth"),
        RELIVE("重生石", "panling:relive_stone", Material.GOLD_NUGGET, 10019, "pl.item.name.relife_stone");
    }

    fun reload() {
        file = File(plugin.dataFolder, "menus.yml")
        if (!file.exists()) {
            plugin.saveResource("menus.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file)
    }

    fun getTianjiToken(): ItemStack {
        val matStr = config.getString("token_item.material", "CLOCK")
        var mat = Material.getMaterial(matStr!!)
        if (mat == null) mat = Material.CLOCK

        val item = ItemStack(mat!!)
        val meta = item.itemMeta

        if (meta != null) {
            meta.setDisplayName(format(config.getString("token_item.name", "&6&l天机令")!!))
            val lore = config.getStringList("token_item.lore")
            val coloredLore: MutableList<String> = ArrayList()
            for (s in lore) coloredLore.add(format(s))
            meta.lore = coloredLore
            meta.persistentDataContainer.set(tokenKey, PersistentDataType.STRING, "true")
            item.itemMeta = meta
        }
        return item
    }

    fun openMainMenu(player: Player) {
        plugin.playerManager.updateStats(player)

        val title = format(config.getString("gui.title", "天机阁")!!)
        val size = config.getInt("gui.size", 54)
        val inv = Bukkit.createInventory(null, size, title)

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage(ChatColor.RED.toString() + "数据加载中，请稍后再试...")
            return
        }

        // 加载配置文件中的物品
        val itemsSec = config.getConfigurationSection("gui.items")
        if (itemsSec != null) {
            for (key in itemsSec.getKeys(false)) {
                val itemSec = itemsSec.getConfigurationSection(key) ?: continue
                val slot = itemSec.getInt("slot", 0)
                val matStr = itemSec.getString("material", "STONE")
                var mat = Material.getMaterial(matStr!!)
                if (mat == null) mat = Material.STONE
                val name = itemSec.getString("name", "Button")
                val lore = itemSec.getStringList("lore")
                val icon = ItemStack(mat!!)
                val meta = icon.itemMeta

                if (matStr == "PLAYER_HEAD" && meta is SkullMeta) {
                    meta.owningPlayer = player
                }
                if (meta != null) {
                    meta.setDisplayName(format(replacePlaceholders(name!!, player, data)))
                    val finalLore: MutableList<String> = ArrayList()
                    for (line in lore) {
                        val replacedLine = replacePlaceholders(line, player, data)
                        finalLore.add(format(replacedLine))
                    }
                    meta.lore = finalLore
                    meta.persistentDataContainer.set(tokenKey, PersistentDataType.STRING, "gui_item")
                    icon.itemMeta = meta
                }
                inv.setItem(slot, icon)
            }
        }

        // === 【新增】Slot 30: 任务记录 ===
        val questBook = ItemStack(Material.WRITABLE_BOOK)
        val questMeta = questBook.itemMeta
        questMeta?.setDisplayName("§e§l任务记录")
        val questLore: MutableList<String> = ArrayList()
        questLore.add("§7点击查看当前任务进度")
        questLore.add("§8主线/支线/赏金/挑战")
        questMeta?.lore = questLore
        questBook.itemMeta = questMeta
        inv.setItem(30, questBook)

        // === Slot 31: 道天图录 ===
        val book = ItemStack(Material.BOOK)
        val meta = book.itemMeta
        meta?.setDisplayName("§b§l道天图录")
        val lore: MutableList<String> = ArrayList()
        lore.add("§7点击打开道天图录管理元素")
        meta?.lore = lore
        book.itemMeta = meta
        inv.setItem(31, book)

        player.openInventory(inv)
    }

    fun openDaoTianMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 54, "§8§l道天图录 - 元素仓库")
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        inv.setItem(20, createGuiItem(ElementType.METAL, data.metal ?: 0))
        inv.setItem(21, createGuiItem(ElementType.WOOD, data.wood ?: 0))
        inv.setItem(22, createGuiItem(ElementType.WATER, data.water ?: 0))
        inv.setItem(23, createGuiItem(ElementType.FIRE, data.fire ?: 0))
        inv.setItem(24, createGuiItem(ElementType.EARTH, data.earth ?: 0))
        inv.setItem(31, createGuiItem(ElementType.RELIVE, data.reliveStone ?: 0))

        val back = ItemStack(Material.ARROW)
        val backMeta = back.itemMeta
        backMeta?.setDisplayName("§c返回")
        back.itemMeta = backMeta
        inv.setItem(49, back)
        player.openInventory(inv)
    }

    private fun createGuiItem(type: ElementType, amount: Int): ItemStack {
        val item = ItemStack(type.material)
        val meta = item.itemMeta
        meta?.setDisplayName("§e" + type.displayName)
        meta?.setCustomModelData(type.modelData)

        val lore: MutableList<String> = ArrayList()
        lore.add("§7----------------")
        lore.add("§f当前库存: §a$amount")
        lore.add("§7----------------")
        lore.add("§e[左键] §f存入背包内所有此物品")
        lore.add("§e[右键] §f取出一个")
        lore.add("§e[Shift+右键] §f取出一组 (64个)")
        meta?.lore = lore
        meta?.persistentDataContainer?.set(NamespacedKey(plugin, "btn_element"), PersistentDataType.STRING, type.name)
        item.itemMeta = meta
        return item
    }

    @Suppress("DEPRECATION")
    fun getPanlingItem(type: ElementType, count: Int): ItemStack {
        val itemId = type.material.key.toString()
        val fullItemString = StringBuilder()
        fullItemString.append(itemId)
        fullItemString.append("[")
        fullItemString.append("minecraft:custom_model_data=${type.modelData},")
        fullItemString.append("minecraft:max_stack_size=99,")
        fullItemString.append("minecraft:rarity=common,")
        fullItemString.append("minecraft:repair_cost=0,")
        fullItemString.append("minecraft:custom_name='{\"translate\":\"${type.translatableKey}\"}',")
        if (type == ElementType.RELIVE) {
            fullItemString.append("minecraft:enchantment_glint_override=true,")
        }
        if (fullItemString.endsWith(",")) {
            fullItemString.setLength(fullItemString.length - 1)
        }
        fullItemString.append("]")

        var resultItem: ItemStack
        try {
            val baseItem = ItemStack(type.material)
            resultItem = Bukkit.getUnsafe().modifyItemStack(baseItem, fullItemString.toString())
        } catch (e: Exception) {
            plugin.logger.warning("物品组件生成失败，回退到普通物品: " + type.displayName)
            e.printStackTrace()
            resultItem = ItemStack(type.material)
        }

        val meta = resultItem.itemMeta
        if (meta != null) {
            val pdc = meta.persistentDataContainer
            pdc.set(keyId, PersistentDataType.STRING, type.nbtId)
            pdc.set(keyUid, PersistentDataType.INTEGER, type.modelData)
            if (type != ElementType.RELIVE) {
                pdc.set(keyRank, PersistentDataType.INTEGER, 1)
                pdc.set(keyType, PersistentDataType.INTEGER, 1)
            }
            resultItem.itemMeta = meta
        }
        resultItem.amount = count
        return resultItem
    }

    fun isPanlingItem(item: ItemStack?, type: ElementType): Boolean {
        if (item == null || item.type != type.material) return false
        val meta = item.itemMeta ?: return false
        if (meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) {
            val id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
            if (id == type.nbtId) return true
        }
        try {
            val itemStr = item.toString()
            if (itemStr.contains("id:\"${type.nbtId}\"") || itemStr.contains("id=\"${type.nbtId}\"")) {
                return true
            }
        } catch (ignored: Exception) {}
        return false
    }

    fun isTianjiToken(item: ItemStack?): Boolean {
        if (item == null || item.itemMeta == null) return false
        return item.itemMeta!!.persistentDataContainer.has(tokenKey, PersistentDataType.STRING)
    }

    private fun format(msg: String): String {
        return ChatColor.translateAlternateColorCodes('&', msg)
    }

    private fun replacePlaceholders(text: String, player: Player, data: PlayerData): String {
        var result = text
        result = result.replace("%player_name%", player.name)
        result = result.replace("%lv%", data.lv.toString())

        if (result.contains("forge")) {
            val dzData = plugin.playerManager.getDzData(player.uniqueId)
            if (dzData != null) {
                result = result.replace("%forge_level%", dzData.forgeLevel.toString())
                result = result.replace("%forge_exp%", dzData.forgeExp.toString())
                val maxForgeExp = plugin.dzLevelManager.getMaxExp(dzData.forgeLevel!!)
                val maxExpStr = if (maxForgeExp == -1) "MAX" else maxForgeExp.toString()
                result = result.replace("%forge_max_exp%", maxExpStr)
                val licName = plugin.dzLevelManager.getLicenseName(dzData.forgeLicense!!)
                result = result.replace("%forge_license_name%", licName)
            } else {
                result = result.replace("%forge_level%", "1")
                result = result.replace("%forge_exp%", "0")
                result = result.replace("%forge_max_exp%", "-")
                result = result.replace("%forge_license_name%", "无数据")
            }
        }

        if (result.contains("%kaiwu_level%")) result = result.replace("%kaiwu_level%", data.kaiwuLevel.toString())
        if (result.contains("%kaiwu_exp%")) result = result.replace("%kaiwu_exp%", data.kaiwuExp.toString())
        if (result.contains("%kaiwu_next_exp%")) result = result.replace("%kaiwu_next_exp%", data.kaiWuNextLevelExp.toString())
        if (result.contains("%kaiwu_energy%")) result = result.replace("%kaiwu_energy%", String.format("%.1f", data.kaiwuEnergy))
        if (result.contains("%kaiwu_max_energy%")) result = result.replace("%kaiwu_max_energy%", String.format("%.1f", data.maxKaiWuEnergy))

        if (result.contains("%exp%") || result.contains("%max_exp%") || result.contains("%exp_percent%")) {
            val currentExp = data.exp
            val maxExp = plugin.playerManager.getMaxExpRequired(data.lv!!)
            result = result.replace("%exp%", currentExp.toString())
            if (maxExp <= 0) {
                result = result.replace("%max_exp%", "MAX")
                result = result.replace("%exp_percent%", "100%")
            } else {
                result = result.replace("%max_exp%", maxExp.toString())
                val percent = ((currentExp.toDouble() / maxExp) * 100).toInt()
                result = result.replace("%exp_percent%", "$percent%")
            }
        }

        var jobName = "无"
        val job = data.job
        if (job != null && job >= 0 && job < JOB_NAMES.size) jobName = JOB_NAMES[job]
        result = result.replace("%job%", jobName)

        var raceName = "未知"
        val race = data.race
        if (race != null && race >= 0 && race < RACE_NAMES.size) raceName = RACE_NAMES[race]
        result = result.replace("%race%", raceName)

        if (result.contains("%rarity_display%")) result = result.replace("%rarity_display%", getRarityDisplayString(player, data))

        result = result.replace("%max_health%", String.format("%.1f", data.maxHealth))
        result = result.replace("%current_health%", String.format("%.1f", player.health))
        result = result.replace("%attack%", String.format("%.1f", data.attack))
        result = result.replace("%archer_damage%", String.format("%.1f", data.archerDamage))
        result = result.replace("%armor%", String.format("%.1f", data.armor))
        result = result.replace("%money%", String.format("%.1f", data.money))
        result = result.replace("%crit_chance%", String.format("%.1f%%", (data.critChance ?: 0.0) * 100))
        result = result.replace("%CoolReduce%", String.format("%.1f%%", (data.coolReduce ?: 0.0) * 100))

        val lingliDisplay = "${(data.lingli ?: 0.0).toInt()}/${data.maxLingli.toInt()}"
        result = result.replace("%lingli%", lingliDisplay)
        result = result.replace("%knock_back_res%", String.format("%.0f%%", (data.knockBackRes ?: 0.0) * 100))

        if (result.contains("%main_stat_line%")) {
            var replacement = "&f主属性: &7暂无职业"
            if (job != null) {
                replacement = when (job) {
                    0 -> "&f近战强度: &b" + String.format("%.1f", data.attack)
                    1 -> "&f箭矢强度: &b" + String.format("%.1f", data.archerDamage)
                    2 -> "&f阵法强度: &b" + String.format("%.1f", data.zfStr)
                    3 -> "&f医术: &b" + String.format("%.1f", data.zfStr)
                    else -> "&f主属性: &7未知"
                }
            }
            result = result.replace("%main_stat_line%", replacement)
        }
        return result
    }

    private fun getRarityDisplayString(player: Player, data: PlayerData): String {
        val total = data.totalRarity
        val details = data.rarityDetails
        val strList = ArrayList<String>()
        for (i in details) strList.add(i.toString())
        var detailStr = java.lang.String.join("+", strList)
        if (detailStr.isEmpty()) detailStr = "0"
        return "$total / ($detailStr)"
    }
}