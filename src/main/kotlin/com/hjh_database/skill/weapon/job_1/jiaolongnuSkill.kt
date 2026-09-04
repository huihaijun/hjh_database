package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class jiaolongnuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录玩家主动技能的过期时间 (UUID -> 过期时间戳)
    private val activeBuffs = ConcurrentHashMap<UUID, Long>()
    private val passiveWaterBreathingOwners = ConcurrentHashMap.newKeySet<UUID>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // ==========================================
        // 任务 1：被动效果 (每 5 秒执行一次)
        // ==========================================
        object : BukkitRunnable() {
            override fun run() {
                val weaponKey = NamespacedKey(plugin, "weapon_id")
                for (player in Bukkit.getOnlinePlayers()) {
                    val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return
                    if (!pluginMain.weaponSkillManager.isWeaponActivated(player, "jiaolongnu")) {
                        clearPassiveWaterBreathing(player)
                        continue
                    }
                    val item = player.inventory.itemInMainHand
                    if (!item.hasItemMeta()) {
                        clearPassiveWaterBreathing(player)
                        continue
                    }

                    val meta = item.itemMeta
                    if (meta == null) {
                        clearPassiveWaterBreathing(player)
                        continue
                    }
                    val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)

                    if (weaponId == "jiaolongnu") {
                        // 赋予 10 秒的水下呼吸。最后的三个 false 代表：隐藏信标图标、隐藏粒子效果、隐藏屏幕右上角图标
                        val applied = player.addPotionEffect(
                            PotionEffect(PotionEffectType.WATER_BREATHING, PASSIVE_WATER_BREATHING_TICKS, 0, false, false, false)
                        )
                        if (applied) passiveWaterBreathingOwners.add(player.uniqueId)
                    } else {
                        clearPassiveWaterBreathing(player)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 100L) // 100 ticks = 5 秒

        // ==========================================
        // 任务 2：主动技能状态监控 (Buff 结束时移除移速并给护盾)
        // ==========================================
        object : BukkitRunnable() {
            override fun run() {
                if (activeBuffs.isEmpty()) return
                val now = System.currentTimeMillis()
                val iter = activeBuffs.iterator()

                while (iter.hasNext()) {
                    val entry = iter.next()
                    val uuid = entry.key
                    val expireTime = entry.value

                    // 如果技能持续时间结束
                    if (now > expireTime) {
                        iter.remove()
                        val player = Bukkit.getPlayer(uuid)
                        if (player != null && player.isOnline) {
                            val pluginMain = plugin as com.hjh_database.Hjh_database
                            val pData = pluginMain.playerManager.getData(uuid)

                            if (pData != null) {
                                // 移除 20% 移速
                                pData.tempBonuses.remove(SPEED_BUFF_KEY)
                                pluginMain.playerManager.updateStats(player)

                                // 赋予 15 秒 (300 ticks) 的 8 点黄心护盾 (Absorption 等级 1 = 4 颗心 = 8 点)
                                player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 300, 1))

                                // 水声提示结束
                                player.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 1f, 1f)
                            }
                        }
                    } else {
                        // 技能持续期间，给玩家脚底加一点水花特效，代表“蛟龙出海”的机动性
                        val player = Bukkit.getPlayer(uuid)
                        if (player != null && player.isOnline && Math.random() < 0.4) {
                            player.world.spawnParticle(Particle.SPLASH, player.location.add(0.0, 0.2, 0.0), 5, 0.2, 0.0, 0.2, 0.05)
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    // --- 主动释放 ---
    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || projectile !is AbstractArrow) return false

        val pluginMain = plugin as com.hjh_database.Hjh_database

        // 1. 设置技能持续 7 秒
        activeBuffs[player.uniqueId] = System.currentTimeMillis() + 7000L

        // 2. 增加 20% 移速
        data?.tempBonuses?.set(SPEED_BUFF_KEY, 0.20)
        pluginMain.playerManager.updateStats(player)

        // 3. 提示与音效 (仅保留规定文字，使用波浪音效)
        val message = "&a&l武器技【蛟龙出海】发动！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))
        player.world.playSound(player.location, Sound.ENTITY_DOLPHIN_JUMP, 1f, 1.2f)

        // 因为 castActive 时已经射出了一支箭，我们也要给这第一支箭打上穿透标记
        markPiercingArrow(projectile)

        return true
    }

    // --- 射击监听 (技能持续期间射出的箭赋予无限穿透) ---
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return

        // 只有处于主动技能持续期间才生效
        if (!activeBuffs.containsKey(player.uniqueId)) return

        val bow = event.bow ?: return
        val meta = bow.itemMeta ?: return
        val weaponKey = NamespacedKey(plugin, "weapon_id")
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)

        if (weaponId == "jiaolongnu") {
            val arrow = event.projectile as? AbstractArrow ?: return
            markPiercingArrow(arrow)

            player.world.playSound(player.location, Sound.ITEM_TRIDENT_THROW, 1f, 1.5f)
        }
    }

    // --- 命中监听 (计算穿透增伤) ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return

        // 必须是蛟龙弩的穿透箭
        if (!arrow.hasMetadata("hjh_jiaolong_arrow")) return

        val victim = event.entity as? LivingEntity ?: return
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        // 获取这支箭目前已经穿透了几个敌人
        val pierceCountMeta = arrow.getMetadata("hjh_jiaolong_arrow").firstOrNull() ?: return
        val pierceCount = pierceCountMeta.asInt()

        // 每贯穿一个目标，伤害增加 50% (0.5)，至多增加 300% (3.0)
        val bonusRatio = min(pierceCount * 0.5, 3.0)
        val multiplier = 1.0 + bonusRatio

        // 应用增伤
        event.damage *= multiplier

        // 将穿透次数 +1 并重新写回这支箭，为下一个命中的目标做准备
        arrow.setMetadata("hjh_jiaolong_arrow", FixedMetadataValue(plugin, pierceCount + 1))

        // 清爽的水花视觉效果，替代爆炸
        victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.5f, 1.5f)
        victim.world.spawnParticle(Particle.SPLASH, victim.location.add(0.0, victim.height / 2, 0.0), 15, 0.3, 0.3, 0.3, 0.1)
        victim.world.spawnParticle(Particle.BUBBLE_POP, victim.location.add(0.0, victim.height / 2, 0.0), 5, 0.3, 0.3, 0.3, 0.05)
    }

    // 辅助方法：将箭矢标记为无限穿透
    private fun markPiercingArrow(arrow: AbstractArrow) {
        // 原版 API：设置穿透等级为 127 (Minecraft 允许的最大上限)
        arrow.pierceLevel = 127
        // 记录穿透计数，初始为 0
        arrow.setMetadata("hjh_jiaolong_arrow", FixedMetadataValue(plugin, 0))
    }

    private fun clearPassiveWaterBreathing(player: Player) {
        if (!passiveWaterBreathingOwners.remove(player.uniqueId)) return

        val effect = player.getPotionEffect(PotionEffectType.WATER_BREATHING) ?: return
        val isJiaolongnuEffect = effect.amplifier == 0 &&
            !effect.hasParticles() &&
            !effect.hasIcon() &&
            effect.duration <= PASSIVE_WATER_BREATHING_TICKS
        if (isJiaolongnuEffect) {
            player.removePotionEffect(PotionEffectType.WATER_BREATHING)
        }
    }

    override fun deactivate(player: Player) {
        activeBuffs.remove(player.uniqueId)
        clearPassiveWaterBreathing(player)
        val pluginMain = plugin as com.hjh_database.Hjh_database
        val data = pluginMain.playerManager.getData(player.uniqueId) ?: return
        if (data.tempBonuses.remove(SPEED_BUFF_KEY) != null) {
            pluginMain.playerManager.updateStats(player)
        }
    }

    companion object {
        private const val SPEED_BUFF_KEY = "jiaolongnu::speed_percent"
        private const val PASSIVE_WATER_BREATHING_TICKS = 10 * 20
    }
}
