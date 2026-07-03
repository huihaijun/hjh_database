package com.hjh_database.spawner.impl

import com.hjh_database.Hjh_database
import com.hjh_database.spawner.MobAffix
import com.hjh_database.spawner.MobAffixSupport
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ThreadLocalRandom

/**
 * 恶土之炎 技能实现
 * 效果：攻击概率使玩家进入 6s 燃烧，每秒造成 50% 已损生命值的真实伤害。
 * 灭火：入水/药水/下蹲8次。
 */
object DesertSouthSkill : Listener {

    private lateinit var plugin: Hjh_database
    private val SNEAKS_KEY by lazy { NamespacedKey(plugin, "desert_south_sneaks") }

    /**
     * 在插件主类 (onEnable) 中调用此方法进行初始化
     */
    fun init(plugin: Hjh_database) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
        startGlobalMonitorTask()
    }

    /**
     * 触发技能：给玩家打上恶土之炎烙印
     */
    fun trigger(player: Player) {
        val pdc = player.persistentDataContainer

        // 如果玩家已经在状态中，不重复触发
        if (pdc.has(SNEAKS_KEY, PersistentDataType.INTEGER)) return

        // 1. 设置原版着火 6秒 (120 ticks)
        player.fireTicks = 120

        // 2. 写入持久化数据：剩余下蹲次数
        pdc.set(SNEAKS_KEY, PersistentDataType.INTEGER, 4)

        // 3. 提示
        player.sendMessage("§c你被焱砂之火点燃了……")
        player.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAffixMobDamagePlayer(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val attacker = MobAffixSupport.realAttacker(event.damager) ?: return
        if (attacker is Player) return
        if (!MobAffixSupport.hasAffix(attacker, MobAffix.DESERT_SOUTH)) return
        if (ThreadLocalRandom.current().nextDouble() <= 0.6) {
            trigger(player)
        }
    }

    /**
     * 全局监控任务：处理扣血和状态清理
     */
    private fun startGlobalMonitorTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                if (player.isDead) continue

                val pdc = player.persistentDataContainer
                if (pdc.has(SNEAKS_KEY, PersistentDataType.INTEGER)) {

                    // 【判定灭火】只要火灭了（跳水、药水时间到、原版灭火），就移除烙印
                    if (player.fireTicks <= 0) {
                        pdc.remove(SNEAKS_KEY)
                        continue
                    }

                    // 每秒 (20 ticks) 触发一次伤害
                    if (player.ticksLived % 20 == 0) {
                        applyCustomFireDamage(player)
                    }
                }
            }
        }, 0L, 1L)
    }

    /**
     * 计算并应用已损生命值的伤害
     */
    private fun applyCustomFireDamage(player: Player) {
        // 尊重抗火药水
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE)) return
        // 1. 获取玩家当前生命值
        val currentHp = player.health
        // 2. 计算伤害：当前生命的 10%
        var damage = currentHp * 0.1
        // 3. 设置伤害上限为 10 点 (5颗心)
        if (damage > 10.0) {
            damage = 10.0
        }
        // 4. 执行伤害逻辑
        if (damage > 0) {
            // 标记为法术伤害（复用你的 CombatListener 逻辑）
            player.setMetadata("HJH_MAGIC_DAMAGE", org.bukkit.metadata.FixedMetadataValue(plugin, damage))
            player.damage(damage)
        }
    }

    /**
     * 监听下蹲事件：每次下蹲减少次数
     */
    @EventHandler
    fun onPlayerSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return // 只监听按下，不监听松开

        val player = event.player
        val pdc = player.persistentDataContainer

        val left = pdc.get(SNEAKS_KEY, PersistentDataType.INTEGER) ?: return
        val nextValue = left - 1

        if (nextValue <= 0) {
            // 成功灭火
            pdc.remove(SNEAKS_KEY)
            player.fireTicks = 0
            player.sendMessage("§a你通过剧烈的翻滚扑灭了身上的火焰！")
            player.world.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f)
        } else {
            // 更新次数
            pdc.set(SNEAKS_KEY, PersistentDataType.INTEGER, nextValue)
            player.playSound(player.location, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 2.0f)
        }
    }
}
