package com.hjh_database.jianghu

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JianghuXindeManager(private val plugin: Hjh_database) {
    private val file = File(plugin.dataFolder, "jianghu_xinde.yml")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val skillButtonKey = NamespacedKey(plugin, "jianghu_xinde_skill")
    private lateinit var config: YamlConfiguration
    private val lastClickTimes = ConcurrentHashMap<UUID, Long>()

    private var stationWorld = "world"
    private var stationX = 124
    private var stationY = 50
    private var stationZ = -8
    private var kaiwuLevelExpBase = 100

    val guiTitle: String = "§6§l江湖通鉴"

    init {
        reload()
    }

    fun reload() {
        if (!file.exists()) {
            config = YamlConfiguration()
            config.set("station.world", "world")
            config.set("station.x", 124)
            config.set("station.y", 50)
            config.set("station.z", -8)
            config.set("kaiwu_level_exp_base", 100)
            config.save(file)
        }

        config = YamlConfiguration.loadConfiguration(file)
        stationWorld = config.getString("station.world", "world") ?: "world"
        stationX = config.getInt("station.x", 124)
        stationY = config.getInt("station.y", 50)
        stationZ = config.getInt("station.z", -8)
        kaiwuLevelExpBase = config.getInt("kaiwu_level_exp_base", 100).coerceAtLeast(1)
        readKaiwuBaseFromExistingConfig()
    }

    fun ensureStationBlock() {
        val world = Bukkit.getWorld(stationWorld) ?: Bukkit.getWorlds().firstOrNull() ?: return
        val block = world.getBlockAt(stationX, stationY, stationZ)
        if (block.type != Material.ENCHANTING_TABLE) {
            block.type = Material.ENCHANTING_TABLE
        }
    }

    fun isStation(location: Location?): Boolean {
        if (location == null) return false
        return location.blockX == stationX &&
            location.blockY == stationY &&
            location.blockZ == stationZ &&
            location.world?.name == stationWorld
    }

    fun getXindeValue(item: ItemStack?): Int {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return 0
        val id = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING) ?: return 0
        return plugin.resourceManager.getLocalResource(id)?.jianghuXindeValue ?: 0
    }

    fun consumeXindeItem(player: Player, item: ItemStack): Boolean {
        val baseValue = getXindeValue(item)
        if (baseValue <= 0) return false

        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage("§c数据仍在加载中，请稍后再试。")
            return true
        }

        val value = plugin.raceModule.getZhanRace().applyJianghuXindeBonus(player, baseValue)
        data.jianghuXinde += value
        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
        } else {
            item.amount = item.amount - 1
            player.inventory.setItemInMainHand(item)
        }

        player.sendMessage("§8[§b江湖心得§8] §a吸纳成功，获得 §b$value §a点江湖心得。")
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
        plugin.databaseManager.queuePlayerSave(data, 40L)
        return true
    }

    fun openGui(player: Player) {
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage("§c数据仍在加载中，请稍后再试。")
            return
        }

        val inv = Bukkit.createInventory(null, 54, guiTitle)
        inv.setItem(4, createHead(player, data))
        inv.setItem(20, createSkillButton(player, data, SkillType.FORGE))
        inv.setItem(22, createSkillButton(player, data, SkillType.KAIWU))
        inv.setItem(24, createSkillButton(player, data, SkillType.ALCHEMY))
        inv.setItem(38, createSkillButton(player, data, SkillType.XIUSHEN))
        player.openInventory(inv)
    }

    fun handleGuiClick(player: Player, slot: Int, click: ClickType) {
        if (!click.isLeftClick) return
        val skill = when (slot) {
            20 -> SkillType.FORGE
            22 -> SkillType.KAIWU
            24 -> SkillType.ALCHEMY
            38 -> SkillType.XIUSHEN
            else -> return
        }

        val now = System.currentTimeMillis()
        val lastClick = lastClickTimes[player.uniqueId] ?: 0L
        val isDoubleClick = (now - lastClick) < 300L
        lastClickTimes[player.uniqueId] = now

        val amount = if (isDoubleClick) {
            100
        } else if (click.isShiftClick) {
            10
        } else {
            1
        }

        transferToSkill(player, skill, amount)
    }

    private fun transferToSkill(player: Player, skill: SkillType, requested: Int) {
        val data = plugin.playerManager.getData(player.uniqueId)
        if (data == null) {
            player.sendMessage("§c数据仍在加载中，请稍后再试。")
            return
        }

        if (data.jianghuXinde <= 0) {
            player.sendMessage("§c你的江湖心得不足。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        if (skill == SkillType.XIUSHEN) {
            if (data.lv > data.xiushenLastLevel) {
                data.xiushenExpGained = 0
                data.xiushenLastLevel = data.lv
            }
            val maxLimit = (plugin.playerManager.getMaxExpRequired(data.lv) * 0.3).toInt()
            val available = maxLimit - data.xiushenExpGained
            if (available <= 0) {
                player.sendMessage("§c本级修身转化已达上限(当前等级升级所需经验的30%)。")
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                return
            }
            val requestedAmount = requested.coerceAtMost(data.jianghuXinde).coerceAtMost(available)
            if (requestedAmount <= 0) return

            data.jianghuXinde -= requestedAmount
            data.xiushenExpGained += requestedAmount
            plugin.playerManager.giveExp(player, requestedAmount)

            player.sendMessage("§8[§b江湖心得§8] §f已将 §b$requestedAmount §f点江湖心得转化为 §e主等级经验§f。")
        } else {
            val amount = requested.coerceAtMost(data.jianghuXinde)
            data.jianghuXinde -= amount

            when (skill) {
                SkillType.FORGE -> addForgeExp(player, data, amount)
                SkillType.KAIWU -> addKaiwuExp(player, data, amount)
                SkillType.ALCHEMY -> data.addAlchemyExp(amount)
                else -> {}
            }
            player.sendMessage("§8[§b江湖心得§8] §f已将 §b$amount §f点江湖心得转化为 §e${skill.displayName}经验§f。")
        }

        // 叮叮叮音效
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.0f)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
        }, 2L)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f)
        }, 4L)

        plugin.databaseManager.queuePlayerSave(data, 40L)
        openGui(player)
    }

    private fun addForgeExp(player: Player, data: PlayerData, amount: Int) {
        val dzData = plugin.playerManager.getDzData(player.uniqueId)
        if (dzData != null) {
            dzData.addExp(amount, plugin, player)
            data.forgeLevel = dzData.forgeLevel
            data.forgeExp = dzData.forgeExp
            data.forgeLicense = dzData.forgeLicense
        } else {
            data.forgeExp += amount
            while (true) {
                val maxExp = plugin.dzLevelManager.getMaxExp(data.forgeLevel)
                if (maxExp <= 0 || data.forgeExp < maxExp) break
                data.forgeExp -= maxExp
                data.forgeLevel++
            }
        }
    }

    private fun addKaiwuExp(player: Player, data: PlayerData, amount: Int) {
        data.kaiwuExp += amount
        var leveledUp = false
        while (true) {
            val required = data.kaiwuLevel * kaiwuLevelExpBase
            if (required <= 0 || data.kaiwuExp < required) break
            data.kaiwuExp -= required
            data.kaiwuLevel++
            leveledUp = true
        }
        if (leveledUp) {
            player.sendMessage("§8[§6开物术§8] §a你的开物等级提升到了 §eLv.${data.kaiwuLevel}§a！")
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }
    }

    private fun createHead(player: Player, data: PlayerData): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta
        if (meta is SkullMeta) {
            meta.owningPlayer = player
        }
        meta?.setDisplayName("§e§l${player.name}")
        meta?.lore = listOf(
            "§7----------------",
            "§f等级: §eLv.${data.lv} §7(${data.exp} / ${plugin.playerManager.getMaxExpRequired(data.lv)})",
            "§f江湖心得: §b${data.jianghuXinde}",
            "§7----------------"
        )
        head.itemMeta = meta
        return head
    }

    private fun createSkillButton(player: Player, data: PlayerData, skill: SkillType): ItemStack {
        val item = ItemStack(skill.material)
        val meta = item.itemMeta
        meta?.setDisplayName("§e§l${skill.displayName}")
        meta?.lore = skillLore(player, data, skill)
        meta?.persistentDataContainer?.set(skillButtonKey, PersistentDataType.STRING, skill.name)
        item.itemMeta = meta
        return item
    }

    private fun skillLore(player: Player, data: PlayerData, skill: SkillType): List<String> {
        if (skill == SkillType.XIUSHEN) {
            if (data.lv > data.xiushenLastLevel) {
                data.xiushenExpGained = 0
                data.xiushenLastLevel = data.lv
            }
            val maxLimit = (plugin.playerManager.getMaxExpRequired(data.lv) * 0.3).toInt()
            return listOf(
                "§7----------------",
                "§f当前玩家等级: §eLv.${data.lv}",
                "§f当前玩家经验: §b${data.exp} §7/ §b${plugin.playerManager.getMaxExpRequired(data.lv)}",
                "§f本级已转化修身经验: §a${data.xiushenExpGained} §7/ §c$maxLimit",
                "§f可用江湖心得: §b${data.jianghuXinde}",
                "§7----------------",
                "§e[左键] §f转化 1 点经验",
                "§e[Shift+左键] §f转化 10 点经验",
                "§e[双击左键] §f转化 100 点经验"
            )
        }

        val (level, exp, maxExp) = when (skill) {
            SkillType.FORGE -> {
                val dzData = plugin.playerManager.getDzData(player.uniqueId)
                val lv = dzData?.forgeLevel ?: data.forgeLevel
                val current = dzData?.forgeExp ?: data.forgeExp
                val max = plugin.dzLevelManager.getMaxExp(lv)
                Triple(lv, current, if (max <= 0) "MAX" else max.toString())
            }
            SkillType.KAIWU -> Triple(data.kaiwuLevel, data.kaiwuExp, (data.kaiwuLevel * kaiwuLevelExpBase).toString())
            SkillType.ALCHEMY -> Triple(data.alchemyLevel, data.alchemyExp, data.alchemyMaxExp.toString())
            else -> Triple(1, 0, "0")
        }

        return listOf(
            "§7----------------",
            "§f当前等级: §eLv.$level",
            "§f当前经验: §b$exp §7/ §b$maxExp",
            "§f可用江湖心得: §b${data.jianghuXinde}",
            "§7----------------",
            "§e[左键] §f转化 1 点经验",
            "§e[Shift+左键] §f转化 10 点经验",
            "§e[双击左键] §f转化 100 点经验"
        )
    }

    private fun readKaiwuBaseFromExistingConfig() {
        val kaiwuFile = File(plugin.dataFolder, "kaiwu.yml")
        if (!kaiwuFile.exists()) return
        val kaiwuConfig = YamlConfiguration.loadConfiguration(kaiwuFile)
        kaiwuLevelExpBase = kaiwuConfig.getInt("level_exp_base", kaiwuLevelExpBase).coerceAtLeast(1)
    }

    enum class SkillType(val displayName: String, val material: Material) {
        FORGE("锻造等级", Material.ANVIL),
        KAIWU("开物术", Material.IRON_PICKAXE),
        ALCHEMY("冶药法", Material.BREWING_STAND),
        XIUSHEN("修身", Material.EXPERIENCE_BOTTLE)
    }

    fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)
}
