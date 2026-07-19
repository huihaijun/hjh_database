package com.hjh_database.baihu_dz.skill

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.impl.AnhuishinuSkill
import com.hjh_database.baihu_dz.skill.impl.CiguheirenSkill
import com.hjh_database.baihu_dz.skill.impl.DuhuozhuSkill
import com.hjh_database.data.PlayerData
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BaihuWeaponSkillManager(private val plugin: Hjh_database) {
    private val skillConfigCache: MutableMap<String, ConfigurationSection> = HashMap()
    private val skillRegistry: MutableMap<String, BaihuWeaponSkill> = HashMap()
    private val globalCooldowns: MutableMap<UUID, Long> = ConcurrentHashMap()

    init {
        registerSkills()
        reload()
    }

    fun reload() {
        skillConfigCache.clear()
        val rootDir = File(plugin.dataFolder, "baihu_dz/weapon_skills")
        if (!rootDir.exists()) rootDir.mkdirs()
        copyIfMissing("baihu_dz/weapon_skills/ciguheiren.yml")
        copyIfMissing("baihu_dz/weapon_skills/anhuishinu.yml")
        copyIfMissing("baihu_dz/weapon_skills/duhuozhu.yml")
        loadSkillFiles(rootDir)
    }

    private fun copyIfMissing(path: String) {
        val file = File(plugin.dataFolder, path)
        if (!file.exists()) {
            plugin.saveResource(path, false)
        }
    }

    private fun loadSkillFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                loadSkillFiles(file)
            } else if (file.name.endsWith(".yml")) {
                val fileName = file.name.removeSuffix(".yml").lowercase(Locale.getDefault())
                skillConfigCache[fileName] = YamlConfiguration.loadConfiguration(file)
            }
        }
    }

    private fun registerSkills() {
        skillRegistry["ciguheiren"] = CiguheirenSkill(plugin, this)
        skillRegistry["anhuishinu"] = AnhuishinuSkill(plugin)
        skillRegistry["duhuozhu"] = DuhuozhuSkill(plugin)
    }

    fun tryCastSkill(player: Player, item: ItemStack, projectile: Entity?) {
        val weaponData = plugin.baihuDzManager.getWeaponDataFromItem(item) ?: return
        val skillId = weaponData.skillId.lowercase(Locale.getDefault())
        val skill = skillRegistry[skillId] ?: run {
            plugin.logger.warning("未找到注册的白虎武器技能 ID: $skillId")
            return
        }
        val config = skillConfigCache[skillId]?.getConfigurationSection("active") ?: return
        if (!config.getBoolean("enable", true)) return

        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        if (!isWeaponActive(player, weaponData, data)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', weaponData.activeLoreLine))
            return
        }
        if (!plugin.baihuDzManager.canUse(player, item, weaponData, true)) return

        val bypassDurability = skill.bypassDurabilityCost(player, data, item, weaponData, config, projectile)
        if (!bypassDurability && !hasEnoughDurability(player, item, weaponData)) return

        if (isOnCooldown(player)) {
            val preciseTime = (globalCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000.0
            val cdMsg = String.format("&c&l武器技处于冷却中，剩余 %.1f 秒", preciseTime)
            sendActionBar(player, cdMsg)
            return
        }

        val result = skill.castActive(player, data, item, weaponData, config, projectile)
        if (!result.success) return

        if (result.consumeDurability && !plugin.baihuDzManager.consumeDurability(player, item, weaponData)) return
        if (result.startCooldown) startCooldown(player, item.type, config, data)

        val successMsg = result.message ?: config.getString("message")
        if (!successMsg.isNullOrEmpty()) {
            sendActionBar(player, successMsg)
        }
    }

    fun startCooldown(player: Player, material: Material, config: ConfigurationSection, data: PlayerData? = null) {
        val baseSeconds = config.getDouble("cooldown", 10.0).coerceAtLeast(0.0)
        val ignoreCoolReduce = config.getBoolean("ignore_cool_reduce", true)
        val reduce = if (ignoreCoolReduce) 0.0 else (data?.coolReduce ?: 0.0).coerceAtMost(0.5)
        val finalSeconds = baseSeconds * (1.0 - reduce)
        val ticks = (finalSeconds * 20.0).toInt().coerceAtLeast(0)

        if (material != Material.BOW && material != Material.CROSSBOW) {
            player.setCooldown(material, ticks)
        }

        globalCooldowns[player.uniqueId] = System.currentTimeMillis() + (finalSeconds * 1000.0).toLong()

        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) return
                if (!isOnCooldown(player)) {
                    player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f)
                    sendActionBar(player, "&a&l武器技冷却完毕！")
                }
            }
        }.runTaskLater(plugin, (ticks + 1).toLong())
    }

    fun isOnCooldown(player: Player): Boolean {
        return (globalCooldowns[player.uniqueId] ?: return false) > System.currentTimeMillis()
    }

    fun deactivate(player: Player) {
        skillRegistry.values.forEach { it.deactivate(player) }
    }

    fun onPlayerDamage(event: EntityDamageEvent) {
        (skillRegistry["ciguheiren"] as? CiguheirenSkill)?.onPlayerDamage(event)
    }

    fun handleArcherShot(event: EntityShootBowEvent): Boolean {
        val player = event.entity as? Player ?: return false
        val bow = event.bow ?: return false
        val weaponData = plugin.baihuDzManager.getWeaponDataFromItem(bow) ?: return false
        if (weaponData.skillId != "anhuishinu") return false
        val data = plugin.playerManager.getData(player.uniqueId) ?: return false
        return (skillRegistry["anhuishinu"] as? AnhuishinuSkill)?.handleShot(event, data) == true
    }

    fun onProjectileDamage(event: EntityDamageByEntityEvent) {
        (skillRegistry["anhuishinu"] as? AnhuishinuSkill)?.onProjectileDamage(event)
    }

    fun handleCrossbowLoad(event: EntityLoadCrossbowEvent): Boolean {
        val weaponData = plugin.baihuDzManager.getWeaponDataFromItem(event.crossbow) ?: return false
        if (weaponData.skillId != "anhuishinu") return false
        return (skillRegistry["anhuishinu"] as? AnhuishinuSkill)?.onCrossbowLoad(event) == true
    }

    fun onMobTarget(event: EntityTargetLivingEntityEvent) {
        (skillRegistry["anhuishinu"] as? AnhuishinuSkill)?.onMobTarget(event)
    }

    private fun isWeaponActive(player: Player, weaponData: BaihuWeaponData, data: PlayerData): Boolean {
        val slot = player.inventory.heldItemSlot
        if (weaponData.activateSlot != -1 && weaponData.activateSlot != slot) return false
        if (weaponData.reqJob != -1 && data.job != weaponData.reqJob) return false
        if (data.lv < weaponData.reqLv) return false
        return true
    }

    private fun hasEnoughDurability(player: Player, item: ItemStack, weaponData: BaihuWeaponData): Boolean {
        if (plugin.baihuDzManager.getDurability(item, weaponData) >= weaponData.durabilityCost) return true
        player.sendMessage("§c这件虎瘴装耐久不足，无法释放技能。")
        return false
    }

    private fun sendActionBar(player: Player, message: String) {
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }
}
