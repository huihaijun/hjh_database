package com.hjh_database.ui

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.ArrayList

class MenuManager(private val plugin: Hjh_database) {
    companion object {
        const val TIANJI_TOKEN_RESOURCE_ID = "tianjiling"
        const val SUICIDE_BUTTON_SLOT = 47
        const val PORTABLE_WAREHOUSE_BUTTON_SLOT = 51
    }

    private lateinit var file: File
    private lateinit var config: FileConfiguration
    private val tokenKey: NamespacedKey = NamespacedKey(plugin, "hjh_token_item")
    private val resourceIdKey: NamespacedKey = NamespacedKey(plugin, "resource_id")

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
        startDynamicMenuUpdates()
    }

    // === 枚举定义 ===
    enum class ElementType(
        val displayName: String,
        val resourceId: String, // 对应 drops.yml 配置的 ID (如: metal)
        val material: Material,
        val modelData: Int
    ) {
        METAL("金元素", "metal", Material.GOLD_NUGGET, 10004),
        WOOD("木元素", "wood", Material.GOLD_NUGGET, 10005),
        WATER("水元素", "water", Material.GOLD_NUGGET, 10006),
        FIRE("火元素", "fire", Material.GOLD_NUGGET, 10007),
        EARTH("土元素", "earth", Material.GOLD_NUGGET, 10008),
        RELIVE("重生石", "relive_stone", Material.GOLD_NUGGET, 10019); // 假设重生石在资源库的ID是 relive_stone
    }

    fun reload() {
        file = File(plugin.dataFolder, "menus.yml")
        if (!file.exists()) {
            plugin.saveResource("menus.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(file)
    }

    fun getTianjiToken(): ItemStack {
        val resourceToken = try {
            plugin.resourceManager.getItem(TIANJI_TOKEN_RESOURCE_ID)
        } catch (_: UninitializedPropertyAccessException) {
            null
        }

        if (resourceToken != null) {
            val meta = resourceToken.itemMeta
            if (meta != null) {
                meta.persistentDataContainer.set(tokenKey, PersistentDataType.STRING, "true")
                resourceToken.itemMeta = meta
            }
            return resourceToken
        }

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
                inv.setItem(slot, createConfiguredIcon(player, data, itemSec))
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

        // === Slot 32: 个人饰品栏 ===
        val accessoryButton = ItemStack(Material.SHULKER_SHELL)
        val accessoryMeta = accessoryButton.itemMeta
        accessoryMeta?.setDisplayName("§6✦ 饰品栏 ✦")
        val accessoryLore: MutableList<String> = ArrayList()
        accessoryLore.add("§7装备强大的饰品")
        accessoryLore.add("")
        accessoryLore.add("")
        accessoryLore.add("§b▶ 点击打开")
        accessoryMeta?.lore = accessoryLore
        accessoryButton.itemMeta = accessoryMeta
        inv.setItem(32, accessoryButton)

        inv.setItem(SUICIDE_BUTTON_SLOT, createSuicideButton())
        inv.setItem(PORTABLE_WAREHOUSE_BUTTON_SLOT, createMenuButton(Material.ENDER_CHEST, "§b随身宝箱", listOf(
            "§7§o天机阁巧匠以须弥芥子之术,将钱庄库房藏入此令",
            "§7§o持令者无论身在何方,皆可随心存取,不受时空所限",
            "§7§o踏入§b§o[秘境]§7§o之中,此力便不可施展"
        )))

        inv.setItem(
            TianjiUtilityMenus.DUSTBIN_BUTTON_SLOT,
            createMenuButton(Material.COMPOSTER, "§7§l归尘匣", listOf(
                "§7将不再需要的物品投入匣中",
                "§7确认之后，它们便会归于尘土",
                "",
                "§b▶ 点击打开"
            ))
        )
        inv.setItem(
            TianjiUtilityMenus.SHOWCASE_BUTTON_SLOT,
            createMenuButton(Material.MUSIC_DISC_13, "§e§l世尘镜", listOf(
                "§7映照你的背包与快捷栏",
                "§7点击镜中物品即可向众人展示",
                "",
                "§b▶ 点击打开"
            )).apply {
                val mirrorMeta = itemMeta
                mirrorMeta?.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                if (mirrorMeta?.hasJukeboxPlayable() == true) {
                    val jukeboxPlayable = mirrorMeta.jukeboxPlayable
                    jukeboxPlayable.isShowInTooltip = false
                    mirrorMeta.setJukeboxPlayable(jukeboxPlayable)
                }
                itemMeta = mirrorMeta
            }
        )

        player.openInventory(inv)
    }

    private fun startDynamicMenuUpdates() {
        // 仅每秒刷新正在查看天机令的玩家，避免为未打开菜单的玩家创建物品。
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val section = config.getConfigurationSection("gui.items.personal_info") ?: return@Runnable
            val slot = section.getInt("slot", 13)
            for (player in Bukkit.getOnlinePlayers()) {
                if (!isMainMenuTitle(player.openInventory.title)) continue
                val data = plugin.playerManager.getData(player.uniqueId) ?: continue
                player.openInventory.topInventory.setItem(slot, createConfiguredIcon(player, data, section))
            }
        }, 20L, 20L)
    }

    private fun createConfiguredIcon(player: Player, data: PlayerData, section: ConfigurationSection): ItemStack {
        val materialName = section.getString("material", "STONE") ?: "STONE"
        val material = Material.getMaterial(materialName) ?: Material.STONE
        val icon = ItemStack(material)
        val meta = icon.itemMeta
        if (material == Material.PLAYER_HEAD && meta is SkullMeta) meta.owningPlayer = player
        if (meta != null) {
            meta.setDisplayName(format(replacePlaceholders(section.getString("name", "Button") ?: "Button", player, data)))
            meta.lore = section.getStringList("lore").map { configuredLine ->
                // 兼容服务器数据目录中的旧 menus.yml，无需删除配置也能显示倒计时。
                val line = if (configuredLine.contains("%kaiwu_max_energy%") &&
                    !configuredLine.contains("%kaiwu_full_countdown%")
                ) {
                    "$configuredLine &7(%kaiwu_full_countdown%)"
                } else configuredLine
                format(replacePlaceholders(line, player, data))
            }
            meta.persistentDataContainer.set(tokenKey, PersistentDataType.STRING, "gui_item")
            icon.itemMeta = meta
        }
        return icon
    }

    private fun createMenuButton(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        meta?.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun createSuicideButton(): ItemStack {
        val item = ItemStack(Material.POTION)
        val meta = item.itemMeta
        meta?.setDisplayName(format("&4自尽"))
        meta?.lore = listOf(
            format("&7&o服下鹤顶丹自尽，随时随地就义"),
            format("&c[双击确认]")
        )
        if (meta is PotionMeta) {
            meta.color = Color.BLACK
        }
        meta?.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        item.itemMeta = meta
        return item
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
    // 修改 MenuManager.kt 中的 getPanlingItem 方法
    fun getPanlingItem(type: ElementType, count: Int): ItemStack {
        // 直接从 ResourceManager 获取标准物品
        val item = plugin.resourceManager.getItem(type.resourceId)
            ?: ItemStack(type.material) // 兜底
        item.amount = count
        // 【删除了这里原本补全 uid, rank, type 的逻辑】
        // 现在的 item 完美等同于 /resourceitem 生成的物品，可以直接堆叠
        return item
    }

    // === 3. 替换物品判断方法 ===
    // 改为通过 ResourceManager 赋予的 resource_id 标签来判断，而不是检测文本或旧版NBT
    fun isPanlingItem(item: ItemStack?, type: ElementType): Boolean {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false
        // 直接读取 ResourceManager 统一打上的 resource_id 标签
        val keyResourceId = NamespacedKey(plugin, "resource_id")
        if (meta.persistentDataContainer.has(keyResourceId, PersistentDataType.STRING)) {
            val id = meta.persistentDataContainer.get(keyResourceId, PersistentDataType.STRING)
            return id == type.resourceId
        }
        return false
    }

    fun isTianjiToken(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || item.itemMeta == null) return false
        val pdc = item.itemMeta!!.persistentDataContainer
        if (pdc.has(tokenKey, PersistentDataType.STRING)) return true
        return pdc.get(resourceIdKey, PersistentDataType.STRING) == TIANJI_TOKEN_RESOURCE_ID
    }

    fun isMainMenuTitle(title: String): Boolean {
        return title == format(config.getString("gui.title", "&6&l天机令")!!)
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
        if (result.contains("%kaiwu_full_countdown%")) {
            result = result.replace("%kaiwu_full_countdown%", formatDuration(plugin.kaiWuManager.getMillisUntilEnergyFull(data)))
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

        // 冶药法变量替换
        result = result.replace("%alchemy_level%", data.alchemyLevel.toString())
        result = result.replace("%alchemy_exp%", data.alchemyExp.toString())
        result = result.replace("%alchemy_max_exp%", data.alchemyMaxExp.toString())

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
                    3 -> "&f阵法强度: &b" + String.format("%.1f", data.zfStr)
                    else -> "&f主属性: &7未知"
                }
            }
            result = result.replace("%main_stat_line%", replacement)
        }
        return result
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "已回满"
        val totalSeconds = kotlin.math.ceil(millis / 1000.0).toLong()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
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
