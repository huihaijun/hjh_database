package com.hjh_database.skill.weapon

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.job_0.NoviceSwordSkill
import com.hjh_database.skill.weapon.job_1.NoviceBowSkill
import com.hjh_database.weapon.WeaponManager
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WeaponSkillManager(private val plugin: Hjh_database) {
    private val skillConfigCache: MutableMap<String, ConfigurationSection> = HashMap()
    private val skillRegistry: MutableMap<String, WeaponSkill> = HashMap()
    private val globalCooldowns: MutableMap<UUID, Long> = ConcurrentHashMap()

    init {
        registerSkills()
        reload()
    }

    fun reload() {
        skillConfigCache.clear()
        val rootDir = File(plugin.dataFolder, "weapon_skills")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            createDefaultSkillFile(rootDir)
        }
        loadSkillFiles(rootDir)
    }

    private fun loadSkillFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                loadSkillFiles(file)
            } else if (file.name.endsWith(".yml")) {
                val fileName = file.name.replace(".yml", "").lowercase()
//                val fileName = file.name.replace(".yml", "")
                val yml = YamlConfiguration.loadConfiguration(file)
                skillConfigCache[fileName] = yml
            }
        }
    }

    private fun createDefaultSkillFile(root: File) {
        // 暂时省略创建默认yml 因为已经有了
    }

    private fun registerSkills() {
        skillRegistry["novice_sword"] = NoviceSwordSkill()
        skillRegistry["novice_bow"] = NoviceBowSkill()
    }

    fun tryCastSkill(player: Player, rawWeaponId: String, item: ItemStack, projectile: Entity?) {
        // 1. 【修复】强制转为小写，确保 "Novice_Bow" 能匹配到 "novice_bow"
        val weaponId = rawWeaponId.lowercase()

        // 2. 检查注册表中是否有这个技能
        if (!skillRegistry.containsKey(weaponId)) {
            // 可选：加个调试日志，如果以后还按不出来，取消注释这一行就能看到
            plugin.logger.warning("未找到注册的技能 ID: $weaponId (原始ID: $rawWeaponId)")
            return
        }

        val config = skillConfigCache[weaponId]
        if (config == null || !config.getBoolean("active.enable", false)) return

        val weaponData = plugin.playerManager.weaponManager.getWeaponData(weaponId)
        if (weaponData == null) {
            // 可选：调试日志
            plugin.logger.warning("未找到武器数据配置: $weaponId")
            return
        }

        // 【关键】必须显式使用 !! 断言将其转换为非空类型
        val data = plugin.playerManager.getData(player.uniqueId)!!

        if (!isWeaponActive(player, item, weaponData, data)) {
            player.sendMessage(ChatColor.RED.toString() + weaponData.activeLoreLine)
            return
        }

        // 1. 冷却检查 (红色提示)
        if (isOnCooldown(player)) {
            // 【关键】Map 获取可能为空，但逻辑上 isOnCooldown 保证了它存在，使用 !! 断言
            val preciseTime = (globalCooldowns[player.uniqueId]!! - System.currentTimeMillis()) / 1000.0
            // 【修改】改为红色提示
            val cdMsg = String.format("&c&l武器技处于冷却中，剩余 %.1f 秒", preciseTime)
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent(ChatColor.translateAlternateColorCodes('&', cdMsg))
            )
            return
        }

        val skill = skillRegistry[weaponId]
        // 【关键】ConfigurationSection 获取可能为空，使用 !! 模拟 Java 的直接调用行为
        val activeConfig = config.getConfigurationSection("active")!!

        // 注意：projectile 在 Kotlin 中可能需要处理 nullable，取决于 WeaponSkill 接口定义，此处传入原值
        if (skill!!.castActive(player, data, activeConfig, projectile)) {
            applyCooldown(player, data, item.type, activeConfig.getDouble("cooldown", 10.0))

            val successMsg = activeConfig.getString("message")
            if (successMsg != null && !successMsg.isEmpty()) {
                player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    TextComponent(ChatColor.translateAlternateColorCodes('&', successMsg))
                )
            }
        }
    }

    private fun isWeaponActive(player: Player, item: ItemStack, weaponData: WeaponManager.WeaponData, data: PlayerData): Boolean {
        val slot = player.inventory.heldItemSlot
        if (weaponData.activateSlot != -1 && weaponData.activateSlot != slot) return false
        if (data.job != weaponData.reqJob) return false
        if (data.lv < weaponData.reqLv) return false
        return true
    }

    private fun applyCooldown(player: Player, data: PlayerData, mat: Material, baseSeconds: Double) {
        var reduce = data.coolReduce
        if (reduce > 0.5) reduce = 0.5
        val finalSeconds = baseSeconds * (1.0 - reduce)
        val ticks = (finalSeconds * 20).toInt()

        // 如果不是弓/弩，才设置视觉冷却，防止弓无法拉开
        if (mat != Material.BOW && mat != Material.CROSSBOW) {
            player.setCooldown(mat, ticks)
        }

        val endTime = System.currentTimeMillis() + (finalSeconds * 1000).toLong()
        globalCooldowns[player.uniqueId] = endTime

        // 【新增】冷却结束后的提示任务
        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) return
                // 只有当玩家当前确实不在冷却中时（防止重复提示），发送提示
                if (!isOnCooldown(player)) {
                    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f)
                    player.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        TextComponent(ChatColor.translateAlternateColorCodes('&', "&a&l武器技冷却完毕！"))
                    )
                }
            }
        }.runTaskLater(plugin, (ticks + 1).toLong()) // 延迟 1 tick 确保状态已过
    }

    private fun isOnCooldown(player: Player): Boolean {
        if (!globalCooldowns.containsKey(player.uniqueId)) return false
        // 【关键】使用 !! 断言
        return globalCooldowns[player.uniqueId]!! > System.currentTimeMillis()
    }

    // 提供给外部获取管理器的方法
    fun getPlugin(): Hjh_database {
        return plugin
    }
}