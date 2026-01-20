package com.hjh_database.skill.medical.spell

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.spell.impl.TuiDiSpell
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MedicalSpellManager(private val plugin: Hjh_database) {
    private val spells: MutableMap<String, MedicalSpell> = HashMap()
    private val cooldowns: MutableMap<UUID, MutableMap<String, Long>> = ConcurrentHashMap()
    private val spellConfigs: MutableMap<String, ConfigurationSection> = HashMap()

    init {
        reload()
    }

    fun reload() {
        spells.clear()
        spellConfigs.clear()
        registerSpell("yuhehua", YuHeHuaSpell(plugin))
        // 【新增】注册退敌
        registerSpell("tuidi", TuiDiSpell(plugin))
        loadConfig()
    }

    private fun registerSpell(id: String, spell: MedicalSpell) {
        spells[id] = spell
    }

    private fun loadConfig() {
        val file = File(plugin.dataFolder, "medical_items.yml")
        if (!file.exists()) return
        val config = YamlConfiguration.loadConfiguration(file)
        val items = config.getConfigurationSection("items")
        if (items != null) {
            for (key in items.getKeys(false)) {
                val sec = items.getConfigurationSection(key)
                // sec 可能为 null，需安全处理，但此处逻辑我们假定存在
                if (sec != null) {
                    val skillId = sec.getString("skill_id", key)!!
                    spellConfigs[skillId] = sec
                }
            }
        }
    }

    fun castSpell(player: Player, skillId: String) {
        val spell = spells[skillId]
        val config = spellConfigs[skillId]

        if (spell == null) return
        // 【关键】使用 !! 断言，解决 PlayerData? 到 PlayerData 的类型不匹配
        val data = plugin.playerManager.getPlayerData(player)!!

        val skillFullName = plugin.medicalManager.getSkillName(skillId)

        // 1. 冷却检查
        val baseCd = config?.getDouble("cooldown", 1.0) ?: 1.0
        val cdMillis = (baseCd * (1.0 - data.coolReduce) * 1000L).toLong()

        if (isOnCooldown(player, skillId)) {
            val remainingMillis = getCooldownEndTime(player, skillId) - System.currentTimeMillis()
            val remainingSeconds = (remainingMillis / 1000) + 1 // 向上取整显示

            // Fix #1: 红色加粗 ActionBar，只读名字不读颜色
            val rawName = ChatColor.stripColor(skillFullName) // 去除颜色代码
            val barMsg = "§c§l$rawName 正在冷却中，剩余 $remainingSeconds 秒"

            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(barMsg))
            return
        }

        // 2. 灵力检查
        val manaCost = config?.getDouble("mana_cost", 0.0) ?: 0.0
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，无法施展 $skillFullName！")
            return
        }

        // 3. 释放
        if (spell.cast(player, data, config)) {
            data.lingli = data.lingli - manaCost
            setCooldown(player, skillId, cdMillis)

            // Fix #2: 释放消息与 YML 一致
            // 读取 YML 中的 cast_message
            var msg = config?.getString("cast_message")

            if (msg != null) {
                // 处理变量
                msg = msg.replace("%player%", player.name)
                // 如果 YML 里写了 %skill%，我们再替换；如果没写，保留原样
                if (msg.contains("%skill%")) {
                    msg = msg.replace("%skill%", skillFullName)
                }
                // 最后统一处理颜色代码，确保显示正确
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg))
            } else {
                // 默认消息 (兜底)
                player.sendMessage("§e" + player.name + " §f释放了 §e" + skillFullName)
            }

            // 原版物品冷却 (转圈圈)
            val hand = player.inventory.itemInMainHand
            if (hand.type != Material.AIR) {
                val cooldownTicks = (cdMillis / 50).toInt()
                player.setCooldown(hand.type, cooldownTicks)
            }
        }
    }

    fun isOnCooldown(player: Player, skillId: String): Boolean {
        if (!cooldowns.containsKey(player.uniqueId)) return false
        return cooldowns[player.uniqueId]!!.getOrDefault(skillId, 0L) > System.currentTimeMillis()
    }

    // 获取冷却结束时间戳
    fun getCooldownEndTime(player: Player, skillId: String): Long {
        if (!cooldowns.containsKey(player.uniqueId)) return 0L
        return cooldowns[player.uniqueId]!!.getOrDefault(skillId, 0L)
    }

    private fun setCooldown(player: Player, skillId: String, durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        cooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[skillId] = endTime
    }
}