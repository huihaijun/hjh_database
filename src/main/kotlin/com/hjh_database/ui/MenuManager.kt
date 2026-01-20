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

    // 用于 PDC 识别的 Key (对应 components 中的 custom_data -> id)
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
    // 删除了 Color 字段，保留 translatableKey 用于生成组件数据
    enum class ElementType(
        val displayName: String,
        val nbtId: String,
        val material: Material,
        val modelData: Int,
        val translatableKey: String
    ) {
        // 顺序：金10004 -> 木10005 -> 水10006 -> 火10007 -> 土10008
        METAL("金元素", "panling:metal", Material.GOLD_NUGGET, 10004, "pl.item.name.metal"),
        WOOD("木元素", "panling:wood", Material.GOLD_NUGGET, 10005, "pl.item.name.wood"),
        WATER("水元素", "panling:water", Material.GOLD_NUGGET, 10006, "pl.item.name.water"),
        FIRE("火元素", "panling:fire", Material.GOLD_NUGGET, 10007, "pl.item.name.fire"),
        EARTH("土元素", "panling:earth", Material.GOLD_NUGGET, 10008, "pl.item.name.earth"),

        // 重生石 10019
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

    // GUI 显示用的图标，仅设置 Meta 即可，不需要复杂的组件
    private fun createGuiItem(type: ElementType, amount: Int): ItemStack {
        val item = ItemStack(type.material)
        val meta = item.itemMeta
        meta?.setDisplayName("§e" + type.displayName)
        meta?.setCustomModelData(type.modelData) // 确保 GUI 里也能看到材质变化

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

    /**
     * 【最终修复版】生成组件物品
     * 1. 自动拼接物品ID，满足 1.21.3 解析器格式要求 (Item ID + Components)
     * 2. 完美还原 JSON 数据结构
     */
    @Suppress("DEPRECATION")
    fun getPanlingItem(type: ElementType, count: Int): ItemStack {
        // 1. 获取物品的标准 ID
        val itemId = type.material.key.toString()

        // 2. 构建基础组件字符串 (移除 custom_data 部分，改用 PDC 设置)
        // 这样可以避免 modifyItemStack 对数据类型进行错误的自动压缩
        val fullItemString = StringBuilder()
        fullItemString.append(itemId)
        fullItemString.append("[")

        // 视觉与基础属性
        fullItemString.append("minecraft:custom_model_data=${type.modelData},")
        fullItemString.append("minecraft:max_stack_size=99,")
        fullItemString.append("minecraft:rarity=common,")
        fullItemString.append("minecraft:repair_cost=0,")

        // 翻译名
        fullItemString.append("minecraft:custom_name='{\"translate\":\"${type.translatableKey}\"}',")

        // 仅重生石发光
        if (type == ElementType.RELIVE) {
            fullItemString.append("minecraft:enchantment_glint_override=true,")
        }

        // 移除末尾逗号并闭合
        if (fullItemString.endsWith(",")) {
            fullItemString.setLength(fullItemString.length - 1)
        }
        fullItemString.append("]")

        var resultItem: ItemStack
        try {
            // 第一步：生成带视觉效果的物品
            val baseItem = ItemStack(type.material)
            resultItem = Bukkit.getUnsafe().modifyItemStack(baseItem, fullItemString.toString())
        } catch (e: Exception) {
            plugin.logger.warning("物品组件生成失败，回退到普通物品: " + type.displayName)
            e.printStackTrace()
            resultItem = ItemStack(type.material)
        }

        // 第二步：使用 PDC 注入强类型数据 (解决 Int 变 Short/Byte 的问题)
        val meta = resultItem.itemMeta
        if (meta != null) {
            val pdc = meta.persistentDataContainer

            // 字符串类型
            pdc.set(keyId, PersistentDataType.STRING, type.nbtId)

            // 整数类型 (强制 Integer，绝对不会变成 s 或 b)
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

        // 1. 优先检查 PDC (这是我们新生成的格式，最准确)
        // 检查 ID
        if (meta.persistentDataContainer.has(keyId, PersistentDataType.STRING)) {
            val id = meta.persistentDataContainer.get(keyId, PersistentDataType.STRING)
            // 只有 ID 匹配还不够，为了保险，我们也可以检查 UID 是否为 Integer
            // 但通常 ID 唯一即可
            if (id == type.nbtId) return true
        }

        // 2. 【兼容旧数据】如果 PDC 里没找到，尝试解析字符串 (针对旧物品)
        // 如果你之前生成的物品没有 PDC，这个逻辑能让它们继续被识别
        try {
            val itemStr = item.toString()
            if (itemStr.contains("id:\"${type.nbtId}\"") || itemStr.contains("id=\"${type.nbtId}\"")) {
                // 这里可以顺便把旧物品更新一下吗？
                // 如果是在 MenuListener 里调用，不好直接改。
                // 暂时只做识别。
                return true
            }
        } catch (ignored: Exception) {
        }

        return false
    }

    fun isTianjiToken(item: ItemStack?): Boolean {
        if (item == null || item.itemMeta == null) return false
        return item.itemMeta!!.persistentDataContainer.has(tokenKey, PersistentDataType.STRING)
    }

    private fun format(msg: String): String {
        return ChatColor.translateAlternateColorCodes('&', msg)
    }

    // =========================================================
    //  ⚡️ 核心替换逻辑
    // =========================================================
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

        if (result.contains("%kaiwu_level%")) {
            result = result.replace("%kaiwu_level%", data.kaiwuLevel.toString())
        }
        if (result.contains("%kaiwu_exp%")) {
            result = result.replace("%kaiwu_exp%", data.kaiwuExp.toString())
        }
        if (result.contains("%kaiwu_next_exp%")) {
            result = result.replace("%kaiwu_next_exp%", data.kaiWuNextLevelExp.toString())
        }
        if (result.contains("%kaiwu_energy%")) {
            result = result.replace("%kaiwu_energy%", String.format("%.1f", data.kaiwuEnergy))
        }
        if (result.contains("%kaiwu_max_energy%")) {
            result = result.replace("%kaiwu_max_energy%", String.format("%.1f", data.maxKaiWuEnergy))
        }

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
        if (job != null && job >= 0 && job < JOB_NAMES.size) {
            jobName = JOB_NAMES[job]
        }
        result = result.replace("%job%", jobName)

        var raceName = "未知"
        val race = data.race
        if (race != null && race >= 0 && race < RACE_NAMES.size) {
            raceName = RACE_NAMES[race]
        }
        result = result.replace("%race%", raceName)

        if (result.contains("%rarity_display%")) {
            result = result.replace("%rarity_display%", getRarityDisplayString(player, data))
        }

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
        for (i in details) {
            strList.add(i.toString())
        }
        var detailStr = java.lang.String.join("+", strList)
        if (detailStr.isEmpty()) {
            detailStr = "0"
        }
        return "$total / ($detailStr)"
    }
}