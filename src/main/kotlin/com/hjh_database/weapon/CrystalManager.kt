package com.hjh_database.weapon

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BannerPatternLayers
import org.bukkit.ChatColor
import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.banner.Pattern
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.io.File

// 新增类：用于存放每个独立槽位的属性配置
class ActivationConfig(val stats: Map<String, Double>)

data class ShieldPatternLayerConfig(val pattern: String, val color: DyeColor)
data class ShieldPatternConfig(val baseColor: DyeColor, val layers: List<ShieldPatternLayerConfig>)

class CrystalData(val id: String, sec: ConfigurationSection) {

    val display: String = sec.getString("display", "&f未知结晶")!!
    val material: Material = Material.valueOf(sec.getString("material", "SHULKER_SHELL")!!)
    val reqLv: Int = sec.getInt("req_lv", 1)
    val reqJob: Int = sec.getInt("req_job", 0)
    val rarity: Int = sec.getInt("rarity", 1)
    val customModelData: Int = sec.getInt("custom_model_data", 0)
    val lore: List<String> = sec.getStringList("lore")
    val shieldPattern: ShieldPatternConfig? = readShieldPattern(sec)

    val skillId: String? = sec.getString("skill_id")
    val maxArrows: Int = if (sec.isConfigurationSection("quiver_data")) sec.getInt("quiver_data.max_arrows", 1024) else 0
    val replenishThreshold: Int = sec.getInt("quiver_data.replenish_threshold", 16)
    val replenishAmount: Int = sec.getInt("quiver_data.replenish_amount", 32)

    val medicalOverflowMaxStorage: Double = sec.getDouble("taolizhi_data.max_storage", 100.0)
    val medicalOverflowTriggerStorage: Double = sec.getDouble("taolizhi_data.trigger_storage", 20.0)
    val medicalOverflowCooldownSeconds: Double = sec.getDouble("taolizhi_data.cooldown", 3.0)
    val medicalOverflowRange: Double = sec.getDouble("taolizhi_data.range", 10.0)
    val medicalOverflowZfMultiplier: Double = sec.getDouble("taolizhi_data.zf_multiplier", 1.5)
    val medicalOverflowStorageMultiplier: Double = sec.getDouble("taolizhi_data.storage_multiplier", 0.5)

    // 【核心改动】支持多槽位与多属性映射表
    val activations = mutableMapOf<String, ActivationConfig>()

    init {
        val actSec = sec.getConfigurationSection("activation")
        if (actSec != null) {
            // 新版解析 logic
            for (slotKey in actSec.getKeys(false)) {
                val statSec = actSec.getConfigurationSection("$slotKey.stats")
                val map = mutableMapOf<String, Double>()
                if (statSec != null) {
                    for (k in statSec.getKeys(false)) {
                        map[k] = statSec.getDouble(k)
                    }
                }
                activations[slotKey] = ActivationConfig(map)
            }
        } else {
            // 兼容旧版的 activate_slot 与 stats 写法
            val oldSlot = sec.getInt("activate_slot", 0)
            val statSec = sec.getConfigurationSection("stats")
            val map = mutableMapOf<String, Double>()
            if (statSec != null) {
                for (k in statSec.getKeys(false)) {
                    map[k] = statSec.getDouble(k)
                }
            }
            activations["accessory_$oldSlot"] = ActivationConfig(map)
        }
    }

    private fun readShieldPattern(sec: ConfigurationSection): ShieldPatternConfig? {
        val patternSec = sec.getConfigurationSection("shield_pattern") ?: return null
        val baseColor = parseDyeColor(patternSec.getString("base_color"), DyeColor.BLACK)
        val layers = patternSec.getMapList("patterns").mapNotNull { layer ->
            val pattern = layer["pattern"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val color = parseDyeColor(layer["color"]?.toString(), DyeColor.WHITE)
            ShieldPatternLayerConfig(pattern, color)
        }
        return ShieldPatternConfig(baseColor, layers)
    }

    private fun parseDyeColor(raw: String?, fallback: DyeColor): DyeColor {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { DyeColor.valueOf(raw.trim().uppercase()) }.getOrDefault(fallback)
    }

    fun isActivated(playerData: PlayerData): Boolean {
        val jobMatch = this.reqJob == -1 || playerData.job == this.reqJob
        return playerData.lv >= this.reqLv && jobMatch
    }
}

class CrystalManager(private val plugin: Hjh_database) {
    val loadedCrystals: MutableMap<String, CrystalData> = HashMap()
    val crystalKey: NamespacedKey = NamespacedKey(plugin, "crystal_id")
    private val resourceKey: NamespacedKey = NamespacedKey(plugin, "resource_id")
    private val accessoryInvKey = NamespacedKey(plugin, "player_accessory_inv")

    init { reload() }

    val allIds: List<String> get() = ArrayList(loadedCrystals.keys)

    fun reload() {
        loadedCrystals.clear()
        val file = File(plugin.dataFolder, "crystals.yml")
        if (!file.exists()) plugin.saveResource("crystals.yml", false)
        val config = YamlConfiguration.loadConfiguration(file)

        val sec = config.getConfigurationSection("crystals") ?: return
        for (key in sec.getKeys(false)) {
            sec.getConfigurationSection(key)?.let {
                loadedCrystals[key] = CrystalData(key, it)
            }
        }
    }

    fun buildItem(id: String): ItemStack? {
        val data = loadedCrystals[id] ?: return null
        val item = ItemStack(data.material)
        val meta = item.itemMeta ?: return item

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', data.display))
        if (data.customModelData != 0) {
            meta.setCustomModelData(data.customModelData)
        }

        val newLore = mutableListOf<String>()
        var colorCode = "§7"
        when (data.rarity) {
            1 -> colorCode = "§f"
            2 -> colorCode = "§a"
            3 -> colorCode = "§9"
            4 -> colorCode = "§d"
            5 -> colorCode = "§e"
            6 -> colorCode = "§c"
        }
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(data.rarity)}")

        for (line in data.lore) {
            val finalLine = line
                .replace("{arrows}", "0")
                .replace("{max_arrows}", data.maxArrows.toString())
                .replace("{threshold}", data.replenishThreshold.toString())
                .replace("{amount}", data.replenishAmount.toString())
                .replace("{stored}", "0")
                .replace("{max_storage}", formatNumber(data.medicalOverflowMaxStorage))
                .replace("{trigger_storage}", formatNumber(data.medicalOverflowTriggerStorage))
            newLore.add(ChatColor.translateAlternateColorCodes('&', finalLine))
        }
        meta.lore = newLore

        meta.persistentDataContainer.set(crystalKey, PersistentDataType.STRING, id)
        meta.persistentDataContainer.set(resourceKey, PersistentDataType.STRING, id)

        meta.addItemFlags(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_ENCHANTS
        )
        meta.attributeModifiers = com.google.common.collect.ArrayListMultimap.create()
        meta.isUnbreakable = true

        item.itemMeta = meta
        applyShieldPattern(item, data)
        return item
    }

    // 获取某个物品在玩家背包中所处的“激活槽位标识符”
    private fun applyShieldPattern(item: ItemStack, data: CrystalData) {
        if (data.material != Material.SHIELD) return
        val shieldPattern = data.shieldPattern ?: return

        item.setData(DataComponentTypes.BASE_COLOR, shieldPattern.baseColor)

        val patterns = shieldPattern.layers.mapNotNull { layer ->
            val key = NamespacedKey.fromString(layer.pattern)
            if (key == null) {
                plugin.logger.warning("Invalid shield banner pattern key for crystal ${data.id}: ${layer.pattern}")
                return@mapNotNull null
            }

            val patternType = Registry.BANNER_PATTERN.get(key)
            if (patternType == null) {
                plugin.logger.warning("Unknown shield banner pattern for crystal ${data.id}: ${layer.pattern}")
                null
            } else {
                Pattern(layer.color, patternType)
            }
        }

        if (patterns.isNotEmpty()) {
            item.setData(DataComponentTypes.BANNER_PATTERNS, BannerPatternLayers.bannerPatternLayers(patterns))
        }
    }

    private fun getInventorySlotKey(slot: Int): String {
        return when (slot) {
            in 0..8 -> "hotbar_$slot"
            40 -> "offhand"
            else -> "none"
        }
    }

    fun refreshPlayerCrystals(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        // 1. 刷新随身背包里的物品 (包含热键栏、副手等)
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue

                val currentSlotKey = getInventorySlotKey(i)
                updateCrystalLore(item, crystalData, data, currentSlotKey)
            }
        }

        // 2. 刷新饰品栏内的结晶
        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY) ?: return
        try {
            var changed = false
            ByteArrayInputStream(savedBytes).use { bais ->
                BukkitObjectInputStream(bais).use { ois ->
                    val size = ois.readInt()
                    if (size <= 0) return

                    val items = arrayOfNulls<ItemStack>(size)
                    for (i in 0 until size) {
                        items[i] = ois.readObject() as? ItemStack
                    }

                    for (i in 0 until size) {
                        val item = items[i] ?: continue
                        val meta = item.itemMeta ?: continue

                        if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                            val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                            val crystalData = loadedCrystals[crystalId] ?: continue

                            val currentSlotKey = "accessory_$i"
                            updateCrystalLore(item, crystalData, data, currentSlotKey)
                            items[i] = item
                            changed = true
                        }
                    }

                    if (changed) {
                        java.io.ByteArrayOutputStream().use { baos ->
                            org.bukkit.util.io.BukkitObjectOutputStream(baos).use { oos ->
                                oos.writeInt(items.size)
                                for (item in items) {
                                    oos.writeObject(item)
                                }
                            }
                            player.persistentDataContainer.set(accessoryInvKey, PersistentDataType.BYTE_ARRAY, baos.toByteArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("刷新玩家 ${player.name} 的结晶 Lore 失败。")
        }
    }

    fun updateCrystalLore(item: ItemStack, crystalData: CrystalData, playerData: PlayerData, currentSlotKey: String) {
        val meta = item.itemMeta ?: return

        // 1. 首先判断当前槽位是否在配置的激活范围内
        var isActive = crystalData.activations.containsKey(currentSlotKey)
        val statusLore = mutableListOf<String>()

        if (crystalData.customModelData != 0) {
            meta.setCustomModelData(crystalData.customModelData)
        } else {
            meta.setCustomModelData(null)
        }

        // 2. 【核心修复点】正确处理无职业限制 (-1) 的情况
        if (isActive) {
            // 如果要求职业不是-1（有特定职业要求），并且玩家职业不符合
            if (crystalData.reqJob != -1 && playerData.job != crystalData.reqJob) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 职业不符")
            } else if (playerData.lv < crystalData.reqLv) {
                isActive = false
                statusLore.add(org.bukkit.ChatColor.RED.toString() + "⚠ 等级不足 (${playerData.lv}/${crystalData.reqLv})")
            }
        }

        // 3. 如果槽位也不对（且不是因为职业等级报错），生成位置提示
        if (!isActive && statusLore.isEmpty()) {
            val reqs = crystalData.activations.keys.joinToString(", ") { key ->
                when {
                    key.startsWith("accessory_") -> "饰品栏[${key.removePrefix("accessory_").toInt() + 1}]"
                    key.startsWith("hotbar_") -> "快捷栏[${key.removePrefix("hotbar_").toInt() + 1}]"
                    key == "offhand" -> "副手"
                    else -> key
                }
            }
            statusLore.add(org.bukkit.ChatColor.GRAY.toString() + "○ 未激活 (支持放入: $reqs)")
        }

        // --- 以下是生成最终 Lore 的逻辑 ---
        val newLore = mutableListOf<String>()

        // 稀有度颜色处理
        var colorCode = "§7"
        when (crystalData.rarity) {
            1 -> colorCode = "§f"; 2 -> colorCode = "§a"; 3 -> colorCode = "§9"
            4 -> colorCode = "§d"; 5 -> colorCode = "§e"; 6 -> colorCode = "§c"
        }
        newLore.add("${colorCode}稀有度: ${WeaponManager.getRarityStars(crystalData.rarity)}")

        // 箭袋占位符处理
        val isQuiver = crystalData.maxArrows > 0
        var currentArrows = 0
        if (isQuiver) {
            val arrowKey = NamespacedKey(plugin, "quiver_arrows")
            currentArrows = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        }

        val medicalOverflowKey = NamespacedKey(plugin, "medical_overflow_stored")
        val currentMedicalOverflow = meta.persistentDataContainer.get(medicalOverflowKey, PersistentDataType.DOUBLE) ?: 0.0

        for (line in crystalData.lore) {
            var finalLine = line
            if (isQuiver) {
                finalLine = finalLine.replace("{arrows}", currentArrows.toString())
                    .replace("{max_arrows}", crystalData.maxArrows.toString())
                    .replace("{threshold}", crystalData.replenishThreshold.toString())
                    .replace("{amount}", crystalData.replenishAmount.toString())
            }
            finalLine = finalLine
                .replace("{stored}", formatNumber(currentMedicalOverflow))
                .replace("{max_storage}", formatNumber(crystalData.medicalOverflowMaxStorage))
                .replace("{trigger_storage}", formatNumber(crystalData.medicalOverflowTriggerStorage))

            if (crystalData.id.startsWith("yuansujiejing") && finalLine.contains("饰品属性")) {
                newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', finalLine))
                val bindUuid = meta.persistentDataContainer.get(NamespacedKey(plugin, "element_bind_uuid"), PersistentDataType.STRING)
                if (bindUuid == playerData.uuid.toString()) {
                    val eData = plugin.elementCrystalManager.getData(playerData.uuid)
                    val hasPoints = eData.getTotalPoints() > 0
                    if (hasPoints) {
                        if (eData.goldPoints > 0) newLore.add("§f进攻属性 +${eData.goldPoints * 1.5}")
                        if (eData.woodPoints > 0) newLore.add("§f最大生命 +${eData.woodPoints * 6.0}")
                        if (eData.waterPoints > 0) newLore.add("§f冷却缩减 +${eData.waterPoints * 2}%")
                        if (eData.firePoints > 0) newLore.add("§f暴击率 +${eData.firePoints * 4}%")
                        if (eData.earthPoints > 0) newLore.add("§f护甲 +${eData.earthPoints * 6.0}")
                    } else {
                        newLore.add("§7尚未分配属性点")
                    }
                } else if (bindUuid != null) {
                    newLore.add("§c已绑定其他玩家，无法激活属性")
                }
                continue
            }

            if (crystalData.id.startsWith("yuansujiejing") && finalLine.contains("饰品技能")) {
                newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', finalLine))
                val bindUuid = meta.persistentDataContainer.get(NamespacedKey(plugin, "element_bind_uuid"), PersistentDataType.STRING)
                if (bindUuid == playerData.uuid.toString()) {
                    val eData = plugin.elementCrystalManager.getData(playerData.uuid)
                    val activeSkills = mutableListOf<String>()
                    if (eData.goldPoints >= 2) {
                        activeSkills.add("&e[金·启示] &f冷却:&c无冷却")
                        activeSkills.add("&f直接造成伤害时获得&b1&f层&b锋芒&f")
                        activeSkills.add("&b[锋芒]&f:每层增加&b5%&f进攻属性,最多&b3&f层")
                        activeSkills.add("&f叠满后将不再叠层和刷新持续时间,&b5&f秒后层数消失")
                    }
                    if (eData.woodPoints >= 2) {
                        activeSkills.add("&a[木·启示] &f冷却:&b30&f秒")
                        activeSkills.add("&f生命低于&b50%&f时,在&b5&f秒内恢复&b20%&f最大生命")
                    }
                    if (eData.waterPoints >= 2) {
                        activeSkills.add("&9[水·启示] &f冷却:&b10&f秒")
                        activeSkills.add("&f释放武器技、医术或阵法后,返还该技能&b15%&f冷却")
                    }
                    if (eData.firePoints >= 2) {
                        activeSkills.add("&c[火·启示] &f冷却:&b6&f秒")
                        activeSkills.add("&f直接伤害命中时附加&b余烬&f")
                        activeSkills.add("&b余烬&f：在&b3&f秒内造成共计&b150%&f进攻属性伤害")
                    }
                    if (eData.earthPoints >= 2) {
                        activeSkills.add("&6[土·启示] &f冷却:&b12&f秒")
                        activeSkills.add("&f受到伤害后获得&b10&f点护甲,持续&b6&f秒")
                    }

                    if (eData.goldPoints >= 4 && playerData.job == 0) {
                        activeSkills.add("&6[战] &e[金·精进] [金戈] &f冷却:&b15&f秒")
                        activeSkills.add("&f普通攻击命中第&b4&f次怪物时,向前方&b12&f格距离")
                        activeSkills.add("&f斩出一道伤害为&b300%&f近战强度的剑气,贯穿路径上的怪物")
                    }
                    if (eData.goldPoints >= 4 && playerData.job == 1) {
                        activeSkills.add("&6[弓] &e[金·精进] [鸣镝] &f冷却:&b10&f秒")
                        activeSkills.add("&f箭矢命中目标后,若与其距离超过&b10&f格")
                        activeSkills.add("&f则对其追加一段&b200%箭矢强度&f的伤害")
                    }
                    if (eData.goldPoints >= 4 && playerData.job == 2) {
                        activeSkills.add("&6[术] &e[金·精进] [金印] &f冷却:&b15&f秒")
                        activeSkills.add("&f元素阵法命中目标&b造成伤害&f后,为目标打下&b[金印]&f标记,持续&b3&f秒")
                        activeSkills.add("&b[金印]&f:持续时间结束后爆炸,对目标&b2&f格范围内怪物造成持有印记期间")
                        activeSkills.add("&f受到的伤害总数的&b50%&f的伤害,最多&b200&f点")
                    }
                    if (eData.goldPoints >= 4 && playerData.job == 3) {
                        activeSkills.add("&6[医] &e[金·精进] [金针] &f冷却:&b15&f秒")
                        activeSkills.add("&f释放医术后,向&b15格&f内离你最近的&b2&f只怪物飞出金针")
                        activeSkills.add("&f造成&b150%阵法强度&f伤害并定身其&b1&f秒")
                    }
                    if (eData.woodPoints >= 4 && playerData.job == 0) {
                        activeSkills.add("&6[战] &a[木·精进] [生根] &f冷却:&b15&f秒")
                        activeSkills.add("&f受到伤害后,向十字方向生长距离为&b8&f格的&b根脉&f持续&b8&f秒")
                        activeSkills.add("&b[根脉]&f:持续减速路径范围的怪物,并每秒回复路径上队友&b4&f点生命")
                    }
                    if (eData.woodPoints >= 4 && playerData.job == 1) {
                        activeSkills.add("&6[弓] &a[木·精进] [藤矢] &f冷却:&b12&f秒")
                        activeSkills.add("&f箭矢命中目标后,对目标附带&b[藤蔓]&f标记持续&b8&f秒")
                        activeSkills.add("&b[藤蔓]&f:减速50%,命中带有此标记的目标后,会为自己恢复&b2&f点生命")
                    }
                    if (eData.woodPoints >= 4 && playerData.job == 2) {
                        activeSkills.add("&6[术] &a[木·精进] [溯生] &f冷却:&b20&f秒")
                        activeSkills.add("&f受到伤害后,在&b1.5&f秒内回复&b70%此伤害值&f的生命")
                    }
                    if (eData.woodPoints >= 4 && playerData.job == 3) {
                        activeSkills.add("&6[医] &a[木·精进] [花语] &f冷却:&b15&f秒")
                        activeSkills.add("&f使用医术回复生命后,将&b50%此次治愈值&f传递给身旁最近的一名队友")
                        activeSkills.add("&f最多以此法传递&b三&f次且无法传递给相同玩家")
                    }
                    if (eData.waterPoints >= 4 && playerData.job == 0) {
                        activeSkills.add("&6[战] &9[水·精进] [潮返] &f冷却:&b12&f秒")
                        activeSkills.add("&f攻击/受到伤害后,在&b2&f秒内依次向周围&b10&f格扩散三道水波")
                        activeSkills.add("&f前两道水波:造成&b80%最大生命&f的伤害,对怪物造成轻微减速&b3&f秒")
                        activeSkills.add("&f第三道水波:造成&b100%最大生命&f的伤害,并小幅击飞怪物")
                    }
                    if (eData.waterPoints >= 4 && playerData.job == 1) {
                        activeSkills.add("&6[弓] &9[水·精进] [水月] &f冷却:&b8&f秒")
                        activeSkills.add("&f箭矢命中目标后,复制一根&b水箭&f,对其附近&b5&f格的最近一名怪物造成同等伤害")
                        activeSkills.add("&b水箭&f命中后,令自己移速增加&b30%&f持续&b3&f秒")
                        activeSkills.add("&f若其身旁没有怪物,则&b水箭&f会攻击原目标")
                    }
                    if (eData.waterPoints >= 4 && playerData.job == 2) {
                        activeSkills.add("&6[术] &9[水·精进] [回潮] &f冷却:&b15&f秒")
                        activeSkills.add("&f释放元素阵法后,从身旁&b5&f格的怪物内吸取灵力")
                        activeSkills.add("&f每1只怪物会为自己额外回复&b2&f点灵力,至多&b20&f点")
                        activeSkills.add("&f并获得&b5&f秒速度提升,每1只怪物延长&b2&f秒,至多&b20&f秒")
                    }
                    if (eData.waterPoints >= 4 && playerData.job == 3) {
                        activeSkills.add("&6[医] &9[水·精进] [净流] &f冷却:&b20&f秒")
                        activeSkills.add("&f释放医术后进入&b[净流]&f状态,持续&b8&f秒")
                        activeSkills.add("&b[净流]&f:医旗回复灵力速度增加&b50%&f,且回复量增加&b20%&f")
                    }
                    if (eData.firePoints >= 4 && playerData.job == 0) {
                        activeSkills.add("&6[战] &c[火·精进] [炎斩] &f冷却:&b15&f秒")
                        activeSkills.add("&f普通攻击造成伤害后,对目标叠加一层&b[炎斩]&f持续&b5&f秒")
                        activeSkills.add("&f叠满三层时,移去所有标记并对其造成&b250%近战强度&f的&b穿甲&f伤害")
                        activeSkills.add("&f并附带其&b最大生命8%&f的斩杀伤害,然后进入冷却")
                    }
                    if (eData.firePoints >= 4 && playerData.job == 1) {
                        activeSkills.add("&6[弓] &c[火·精进] [爆燃] &f冷却:&b15&f秒")
                        activeSkills.add("&f箭矢命中怪物后,以其为中心引发一次半径为&b5&f格的烈火爆炸")
                        activeSkills.add("&f造成&b150%箭矢强度&f的伤害")
                    }
                    if (eData.firePoints >= 4 && playerData.job == 2) {
                        activeSkills.add("&6[术] &c[火·精进] [阵焚] &f冷却:&b20&f秒")
                        activeSkills.add("&f元素阵法造成伤害后,在目标脚底生成焚阵,在&b1&f秒后喷发")
                        activeSkills.add("&b击飞&f&b2&f格范围内怪物并造成&b250%阵法强度&f的伤害")
                    }
                    if (eData.firePoints >= 4 && playerData.job == 3) {
                        activeSkills.add("&6[医] &c[火·精进] [灼脉] &f冷却:&b20&f秒")
                        activeSkills.add("&f释放医术造成伤害后,令目标进入&b[经脉受损]&f持续&b5&f秒")
                        activeSkills.add("&b[经脉受损]&f:移速降低&c50%&f,伤害降低&b30%&f")
                    }
                    if (eData.earthPoints >= 4 && playerData.job == 0) {
                        activeSkills.add("&6[战] &6[土·精进] [崩山] &f冷却:&b15&f秒")
                        activeSkills.add("&f每受到4次伤害时,将引发崩裂,眩晕周围&b10&f格怪物&b0.8&f秒")
                        activeSkills.add("&f同时降低他们&b50%&f护甲持续&b8&f秒")
                        activeSkills.add("&f并获得&b最大生命50%&f的护盾(最多&b40&f点)持续&b15&f秒")
                    }
                    if (eData.earthPoints >= 4 && playerData.job == 1) {
                        activeSkills.add("&6[弓] &6[土·精进] [岩钉] &f冷却:&b15&f秒")
                        activeSkills.add("&f箭矢命中目标后,对目标施加&b[定身]&f持续&b1.5&f秒")
                        activeSkills.add("&b[定身]&f结束时岩钉会爆裂,削弱目标&b30%&f护甲持续&b5&f秒")
                    }
                    if (eData.earthPoints >= 4 && playerData.job == 2) {
                        activeSkills.add("&6[术] &6[土·精进] [镇石] &f冷却:&b20&f秒")
                        activeSkills.add("&f受到伤害后,额外受到&b20%此次伤害值&f的&b真实伤害&f")
                        activeSkills.add("&f(若此伤害让你&c致死&f,则改为体力&c降为1&f)")
                        activeSkills.add("&f然后获得等同于&b200%此次伤害值&f的&b护盾&f,持续&b15&f秒")
                        activeSkills.add("&f护盾消失时,对周围&b4&f格怪物造成&b0.5&f秒晕眩效果")
                    }
                    if (eData.earthPoints >= 4 && playerData.job == 3) {
                        activeSkills.add("&6[医] &6[土·精进] [厚土] &f冷却:&b20&f秒")
                        activeSkills.add("&f释放医术治愈友军时,会为这些友军叠加&b100%阵法强度&f的护盾持续&b5&f秒")
                        activeSkills.add("&f护盾消失时,会令其获得持续&b5&f秒的生命回复效果")
                    }

                    if (activeSkills.isNotEmpty()) {
                        for (skillLine in activeSkills) {
                            newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', skillLine))
                        }
                    } else {
                        newLore.add("§7请于[天机阁]唤醒其元素技能")
                    }
                } else if (bindUuid != null) {
                    newLore.add("§c已绑定其他玩家，无法激活技能")
                } else {
                    newLore.add("§7请于[天机阁]唤醒其元素技能")
                }
                continue
            }

            // 元素结晶：已绑定玩家时，跳过原模板中的占位文本行（属性和技能的占位）
            if (crystalData.id.startsWith("yuansujiejing")) {
                val bindUuid = meta.persistentDataContainer.get(NamespacedKey(plugin, "element_bind_uuid"), PersistentDataType.STRING)
                if (bindUuid != null) {
                    if (finalLine.contains("唤醒其元素属性") || finalLine.contains("唤醒其元素技能")) {
                        continue
                    }
                }
            }

            newLore.add(org.bukkit.ChatColor.translateAlternateColorCodes('&', finalLine))
        }

        newLore.add(" ")

        // 【元素结晶】从 PDC 还原绑定信息到 lore（防止被 ResourceManager 刷新覆盖）
        if (crystalData.id.startsWith("yuansujiejing")) {
            val bindName = meta.persistentDataContainer.get(NamespacedKey(plugin, "element_bind_name"), PersistentDataType.STRING)
            if (bindName != null) {
                newLore.add("§6已绑定玩家：§e$bindName")
            }
        }

        // 4. 【最终显示】只有真正的 isActive 才会显示绿色对勾
        if (isActive) {
            val activeLabel = when {
                currentSlotKey.startsWith("accessory_") -> "饰品栏"
                currentSlotKey.startsWith("hotbar_") -> "快捷栏"
                currentSlotKey == "offhand" -> "副手"
                else -> currentSlotKey
            }
            newLore.add(org.bukkit.ChatColor.GREEN.toString() + "✔ 已在 [$activeLabel] 激活")
        } else {
            // 这里会显示“职业不符”或“等级不足”或“未激活”
            newLore.addAll(statusLore)
        }

        meta.lore = newLore
        item.itemMeta = meta
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }

    fun refreshOpenAccessoryMenu(player: Player, topInv: org.bukkit.inventory.Inventory) {
        val data = plugin.playerManager.getData(player.uniqueId) ?: return

        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, getInventorySlotKey(i))
            }
        }

        for (i in 0 until topInv.size) {
            val item = topInv.getItem(i) ?: continue
            val meta = item.itemMeta ?: continue
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: continue
                updateCrystalLore(item, crystalData, data, "accessory_$i")
            }
        }
    }

    fun calculateCrystalStats(player: Player, data: PlayerData): Map<String, Double> {
        val stats = HashMap<String, Double>()
        var totalRarity = 0.0

        // 内部复用处理逻辑
        fun processItem(item: ItemStack?, slotKey: String) {
            if (item == null) return
            val meta = item.itemMeta ?: return
            if (meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) {
                val crystalId = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING)!!
                val crystalData = loadedCrystals[crystalId] ?: return

// 【核心修复点】直接调用已经封装好的 isActivated 方法，彻底杜绝硬编码遗漏
                // 【元素结晶特殊处理】元素结晶不依赖 activation 配置，只要放在饰品栏第1格就生效
                if (crystalId.startsWith("yuansujiejing") && slotKey == "accessory_0") {
                    data.rarityDetails.add(crystalData.rarity)
                    totalRarity += crystalData.rarity.toDouble()

                    val elementStats = mutableMapOf<String, Double>()
                    val eData = plugin.elementCrystalManager.getData(player.uniqueId)
                    val bindUuid = meta.persistentDataContainer.get(NamespacedKey(plugin, "element_bind_uuid"), PersistentDataType.STRING)
                    if (bindUuid == player.uniqueId.toString()) {
                        elementStats.putAll(plugin.elementCrystalManager.getStats(eData))
                        plugin.elementCrystalManager.checkAndTriggerSkills(player, eData)
                    }

                    elementStats.forEach { (k, v) ->
                        val actualKey = if (k == "power") {
                            when (data.job) {
                                1 -> "archer_damage"
                                2, 3 -> "zf_str"
                                else -> "attack"
                            }
                        } else k
                        stats.merge(actualKey, v) { a, b -> a + b }
                    }
                    return
                }

                // 校验：等级足够、职业匹配，且该槽位在配置文件的激活列表里
                if (crystalData.isActivated(data) && crystalData.activations.containsKey(slotKey)) {

                    data.rarityDetails.add(crystalData.rarity)
                    totalRarity += crystalData.rarity.toDouble()

                    // 读取该特定槽位赋予的属性
                    val slotStats = crystalData.activations[slotKey]?.stats?.toMutableMap() ?: mutableMapOf()

                    slotStats.forEach { (k, v) ->
                        val actualKey = if (k == "power") {
                            when (data.job) {
                                1 -> "archer_damage"
                                2, 3 -> "zf_str"
                                else -> "attack"
                            }
                        } else k
                        stats.merge(actualKey, v) { a, b -> a + b }
                    }
                }
            }
        }

        // 1. 扫描饰品栏
        val savedBytes = player.persistentDataContainer.get(accessoryInvKey, PersistentDataType.BYTE_ARRAY)
        if (savedBytes != null) {
            try {
                BukkitObjectInputStream(ByteArrayInputStream(savedBytes)).use { ois ->
                    val size = ois.readInt()
                    for (i in 0 until size) {
                        processItem(ois.readObject() as? ItemStack, "accessory_$i")
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("读取玩家 ${player.name} 的结晶数据失败。")
            }
        }

        // 2. 扫描副手
        processItem(player.inventory.itemInOffHand, "offhand")

        // 3. 扫描快捷栏 (0-8)
        for (i in 0..8) {
            processItem(player.inventory.getItem(i), "hotbar_$i")
        }

        if (totalRarity > 0) {
            stats["total_rarity"] = totalRarity
        }
        return stats
    }
}
