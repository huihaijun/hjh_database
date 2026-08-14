package com.hjh_database.skill.medical.spell

import com.hjh_database.Hjh_database
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.spawner.impl.NorthWetnessSkill
import com.hjh_database.skill.medical.spell.impl.BaZhenJueSpell
import com.hjh_database.skill.medical.spell.impl.BingQingYuSpell
import com.hjh_database.skill.medical.spell.impl.DuSuZhenSpell
import com.hjh_database.skill.medical.spell.impl.HuShenZhouSpell
import com.hjh_database.skill.medical.spell.impl.HuanShengYuSpell
import com.hjh_database.skill.medical.spell.impl.HuiChunYuSpell
import com.hjh_database.skill.medical.spell.impl.HunLingYouSpell
import com.hjh_database.skill.medical.spell.impl.JiangTianGuangSpell
import com.hjh_database.skill.medical.spell.impl.LingCaoJueSpell
import com.hjh_database.skill.medical.spell.impl.MingXiangSpell
import com.hjh_database.skill.medical.spell.impl.MuChunYuSpell
import com.hjh_database.skill.medical.spell.impl.NianQiJinSpell
import com.hjh_database.skill.medical.spell.impl.NingQiBaoSpell
import com.hjh_database.skill.medical.spell.impl.TianYouSpell
import com.hjh_database.skill.medical.spell.impl.TuiDiSpell
import com.hjh_database.skill.medical.spell.impl.WanXiangSuSpell
import com.hjh_database.skill.medical.spell.impl.XingHuaYuSpell
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell
import com.hjh_database.skill.medical.spell.impl.ZhangQiSanSpell
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MedicalSpellManager(private val plugin: Hjh_database) {
    private val spells: MutableMap<String, MedicalSpell> = HashMap()

    // 【新增】存储每个技能对应的冷却组 Key (自动生成)
    private val spellGroupKeys: MutableMap<String, NamespacedKey> = HashMap()

    private val cooldowns: MutableMap<UUID, MutableMap<String, Long>> = ConcurrentHashMap()
    private val spellConfigs: MutableMap<String, ConfigurationSection> = HashMap()

    init {
        reload()
    }

    fun reload() {
        spells.clear()
        spellGroupKeys.clear() // 清除旧 Key
        spellConfigs.clear()
        //===================================注册医术============================
        registerSpell("yuhehua", YuHeHuaSpell(plugin))
        registerSpell("tuidi", TuiDiSpell(plugin))
        registerSpell("muchunyu", MuChunYuSpell(plugin))
        registerSpell("lingcaojue", LingCaoJueSpell(plugin))
        registerSpell("hushenzhou", HuShenZhouSpell(plugin))
        registerSpell("dusuzhen", DuSuZhenSpell(plugin))
        registerSpell("mingxiang", MingXiangSpell(plugin))
        registerSpell("bingqingyu", BingQingYuSpell(plugin))
        registerSpell("huichunyu", HuiChunYuSpell(plugin))
        registerSpell("xinghuayu", XingHuaYuSpell(plugin))
        registerSpell("nianqijin", NianQiJinSpell(plugin))
        registerSpell("hunlingyou", HunLingYouSpell(plugin))
        registerSpell("tianyou", TianYouSpell(plugin))
        registerSpell("jiangtianguang", JiangTianGuangSpell (plugin))
        registerSpell("wanxiangsu", WanXiangSuSpell (plugin))
        registerSpell("huanshengyu", HuanShengYuSpell (plugin))
        registerSpell("bazhenjue", BaZhenJueSpell (plugin))
        registerSpell("ningqibao", NingQiBaoSpell (plugin))
        registerSpell("zhangqisan", ZhangQiSanSpell (plugin))
        loadConfig()
    }

    private fun registerSpell(id: String, spell: MedicalSpell) {
        spells[id] = spell
        // 【核心】自动为每个技能生成唯一的冷却组 Key
        // 例如: hjh_database:medical_yuhehua
        spellGroupKeys[id] = NamespacedKey(plugin, "medical_${id.lowercase()}")
    }

    private fun loadConfig() {
        val file = File(plugin.dataFolder, "medical_items.yml")
        if (!file.exists()) return
        val config = YamlConfiguration.loadConfiguration(file)
        val items = config.getConfigurationSection("items")
        if (items != null) {
            for (key in items.getKeys(false)) {
                val sec = items.getConfigurationSection(key)
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
        val data = plugin.playerManager.getPlayerData(player)!!

        val skillFullName = plugin.medicalManager.getSkillName(skillId)

        // 1. 冷却检查
        val baseCd = config?.getDouble("cooldown", 1.0) ?: 1.0
        val cdMillis = (baseCd * (1.0 - data.coolReduce) * 1000L).toLong()

        if (isOnCooldown(player, skillId)) {
            val remainingMillis = getCooldownEndTime(player, skillId) - System.currentTimeMillis()
            val remainingSeconds = (remainingMillis / 1000) + 1

            val rawName = ChatColor.stripColor(skillFullName)
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

        if (!skillId.equals("bingqingyu", ignoreCase = true) && NorthWetnessSkill.tryInterruptSkill(player) {
                setCooldown(player, skillId, 5_000L)
                setVisualCooldown(player, skillId, 100)
            }
        ) {
            return
        }

        // 3. 释放
        if (spell.cast(player, data, config)) {
            data.lingli = data.lingli - manaCost
            if (manaCost > 0.0) {
                val costText = if (manaCost % 1.0 == 0.0) {
                    manaCost.toInt().toString()
                } else {
                    String.format(Locale.US, "%.1f", manaCost)
                }
                val manaMessage = "&6☯当前灵力值：&b${String.format(Locale.US, "%.1f", data.lingli)} &c(-$costText) &6/ &b${String.format(Locale.US, "%.0f", data.maxLingli)} &6☯"
                player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    TextComponent(ChatColor.translateAlternateColorCodes('&', manaMessage))
                )
            }

            // A. 设置逻辑冷却 (插件内部判断用)
            setCooldown(player, skillId, cdMillis)

            plugin.server.pluginManager.callEvent(MedicalCastEvent(player, skillId))

            // Trigger Water skill for cooldown refund
            plugin.elementCrystalManager.triggerWaterSkill(player, "medical", skillId, cdMillis / 1000.0)

            // 冥想的提示由技能自身按“开始/结束”状态发送，避免与通用医术释放字幕重复。
            if (!skillId.equals("mingxiang", ignoreCase = true)) {
                // Fix #2: 释放消息与 YML 一致
                var msg = config?.getString("cast_message")
                if (!plugin.passiveSubtitleManager.showCombatEvent(player, "doctor.cast.${skillId.lowercase()}")) {
                    if (msg != null) {
                        msg = msg.replace("%player%", player.name)
                        if (msg.contains("%skill%")) {
                            msg = msg.replace("%skill%", skillFullName)
                        }
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg))
                    } else {
                        player.sendMessage("§e" + player.name + " §f释放了 §e" + skillFullName)
                    }
                }
            }

            // B. 【核心修改】设置独立的视觉冷却 (物品栏转圈圈)
            // 只有手持物品不是空气时才设置
            val hand = player.inventory.itemInMainHand
            if (hand.type != Material.AIR) {
                // 计算 ticks (1秒 = 20 ticks)
                val cooldownTicks = (cdMillis / 50).toInt()
                // 调用下方的视觉冷却方法
                setVisualCooldown(player, skillId, cooldownTicks)
            }
        }
    }

    fun applyMedicalHeal(caster: Player, target: LivingEntity, amount: Double, spellId: String? = null): Double {
        if (amount <= 0.0 || target.isDead) return 0.0

        val adjustedAmount = amount * plugin.queqiaoyinSkill.healingMultiplier(target)
        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return 0.0
        val oldHealth = target.health
        val actualHeal = adjustedAmount.coerceAtMost(maxHealth - oldHealth).coerceAtLeast(0.0)
        val overflowHeal = (adjustedAmount - actualHeal).coerceAtLeast(0.0)

        if (actualHeal > 0.0) {
            target.health = (oldHealth + actualHeal).coerceAtMost(maxHealth)
        }

        val event = MedicalHealEvent(caster, target, spellId, adjustedAmount, actualHeal, overflowHeal)
        plugin.server.pluginManager.callEvent(event)
        return actualHeal
    }

    fun applyMedicalDamage(
        caster: Player,
        target: LivingEntity,
        amount: Double,
        spellId: String? = null,
        triggersMastery: Boolean = true
    ): Double {
        val actualDamage = FormationMagicDamage.deal(plugin, caster, target, amount)
        if (actualDamage > 0.0) {
            plugin.server.pluginManager.callEvent(
                MedicalDamageEvent(caster, target, spellId, amount, actualDamage, triggersMastery)
            )
        }
        return actualDamage
    }

    /**
     * 【新功能】设置医术的独立视觉冷却
     */
    private fun setVisualCooldown(player: Player, skillId: String, ticks: Int) {
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return

        // 获取该技能对应的 Group Key
        val groupKey = spellGroupKeys[skillId] ?: return

        try {
            // 1. 使用 Paper API 修改物品的冷却组
            // 注意：默认时间必须 > 0.0，否则报错，所以用 0.1f 占位
            val cooldownComponent = UseCooldown.useCooldown(0.1f)
                .cooldownGroup(Key.key(groupKey.toString()))
                .build()

            item.setData(DataComponentTypes.USE_COOLDOWN, cooldownComponent)
            player.inventory.setItemInMainHand(item)

            // 2. 发送冷却数据包 (反射，直到 Bukkit 支持 Key 参数)
            sendPacketCooldown(player, groupKey, ticks)

        } catch (e: Exception) {
            plugin.logger.warning("医术视觉冷却设置失败 [$skillId]: ${e.message}")
            // 降级处理
            player.setCooldown(item.type, ticks)
        }
    }

    /**
     * 发送 ClientboundCooldownPacket
     */
    private fun sendPacketCooldown(player: Player, key: NamespacedKey, ticks: Int) {
        try {
            val craftPlayerMethod = player.javaClass.getMethod("getHandle")
            val nmsPlayer = craftPlayerMethod.invoke(player)
            val connectionField = nmsPlayer.javaClass.fields.firstOrNull {
                it.type.name.contains("ServerGamePacketListenerImpl") || it.name == "c" || it.name == "connection"
            } ?: throw NoSuchFieldException("No connection field")
            val connection = connectionField.get(nmsPlayer)

            val resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation")
            val parseMethod = resourceLocationClass.getMethod("parse", String::class.java)
            val nmsKey = parseMethod.invoke(null, key.toString())

            val packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundCooldownPacket")
            val packetConstructor = packetClass.getConstructor(resourceLocationClass, Int::class.javaPrimitiveType)
            val packet = packetConstructor.newInstance(nmsKey, ticks)

            val sendMethod = connection.javaClass.getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
            sendMethod.invoke(connection, packet)

        } catch (e: Exception) {
            // 静默失败或调试输出
        }
    }

    fun isOnCooldown(player: Player, skillId: String): Boolean {
        if (!cooldowns.containsKey(player.uniqueId)) return false
        return cooldowns[player.uniqueId]!!.getOrDefault(skillId, 0L) > System.currentTimeMillis()
    }

    fun getCooldownEndTime(player: Player, skillId: String): Long {
        if (!cooldowns.containsKey(player.uniqueId)) return 0L
        return cooldowns[player.uniqueId]!!.getOrDefault(skillId, 0L)
    }

    private fun setCooldown(player: Player, skillId: String, durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        cooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[skillId] = endTime
    }

    fun reduceCooldown(player: Player, skillId: String, seconds: Double) {
        val playerCds = cooldowns[player.uniqueId] ?: return
        val currentEnd = playerCds[skillId] ?: return
        val now = System.currentTimeMillis()
        if (currentEnd <= now) return

        val newEnd = currentEnd - (seconds * 1000.0).toLong()
        if (newEnd <= now) {
            playerCds.remove(skillId)
            setVisualCooldown(player, skillId, 0)
        } else {
            playerCds[skillId] = newEnd
            val remainingTicks = ((newEnd - now) / 50L).toInt()
            setVisualCooldown(player, skillId, remainingTicks)
        }
    }
}
