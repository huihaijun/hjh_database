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
import org.bukkit.attribute.Attribute
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
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class yantiegongSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 数据结构：层数与过期时间
    private data class BurnInfo(var stacks: Int, var expireTime: Long)

    // 嵌套 Map：怪物 UUID -> (玩家 UUID -> 灼烧信息)
    // 这样完美实现了“每个玩家独立计算层数，互不干扰”
    private val burnMap = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, BurnInfo>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 视觉特效任务：让被灼烧的怪物身上冒火
        object : BukkitRunnable() {
            override fun run() {
                if (burnMap.isEmpty()) return
                val now = System.currentTimeMillis()

                val iter = burnMap.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    val victimId = entry.key
                    val shootersMap = entry.value

                    val victim = Bukkit.getEntity(victimId) as? LivingEntity
                    if (victim == null || !victim.isValid || victim.isDead) {
                        iter.remove()
                        continue
                    }

                    var totalStacks = 0
                    val shooterIter = shootersMap.iterator()
                    while (shooterIter.hasNext()) {
                        val shooterEntry = shooterIter.next()
                        if (now > shooterEntry.value.expireTime) {
                            shooterIter.remove() // 移除过期的玩家灼烧
                        } else {
                            totalStacks += shooterEntry.value.stacks
                        }
                    }

                    if (shootersMap.isEmpty()) {
                        iter.remove()
                    } else {
                        // 根据总层数播放火焰特效，层数越高火越大
                        val loc = victim.location.add(0.0, victim.height / 2, 0.0)
                        victim.world.spawnParticle(Particle.FLAME, loc, totalStacks, 0.4, 0.4, 0.4, 0.02)
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L)
    }

    // --- 1. 技能释放（主动引爆箭） ---
    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || projectile !is AbstractArrow) return false

        // 打上“主动引爆”标签
        projectile.setMetadata("hjh_yantiegong_active", FixedMetadataValue(plugin, true))

        val message = "&a&l武器技【灼焰】发动！"
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(ChatColor.translateAlternateColorCodes('&', message)))

        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1f, 1.5f)
        player.world.spawnParticle(Particle.LAVA, player.location.add(0.0, 1.0, 0.0), 10, 0.2, 0.2, 0.2, 0.0)

        return true
    }

    // --- 2. 射箭监听（被动叠层箭） ---
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        val bow = event.bow ?: return
        val meta = bow.itemMeta ?: return
        val weaponKey = NamespacedKey(plugin, "weapon_id")
        val weaponId = meta.persistentDataContainer.get(weaponKey, PersistentDataType.STRING)

        // 只要是用焰铁弓射出的箭，都打上武器专属标签
        if (weaponId == "yantiegong") {
            event.projectile.setMetadata("hjh_yantiegong_arrow", FixedMetadataValue(plugin, true))
        }
    }

    // --- 3. 命中监听（处理叠层与引爆） ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArrowHit(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return

        // 必须是焰铁弓的箭
        if (!arrow.hasMetadata("hjh_yantiegong_arrow")) return

        val victim = event.entity as? LivingEntity ?: return
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        val shooter = arrow.shooter as? Player ?: return

        val shootersMap = burnMap.computeIfAbsent(victim.uniqueId) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()

        // 判断是否是主动技能（引爆）
        if (arrow.hasMetadata("hjh_yantiegong_active")) {

            // 提取该玩家在目标身上的层数，并清空记录
            val burnInfo = shootersMap.remove(shooter.uniqueId)
            val stacks = if (burnInfo != null && now <= burnInfo.expireTime) burnInfo.stacks else 0

            // 获取玩家实时面板
            val pluginMain = plugin as com.hjh_database.Hjh_database
            val pData = pluginMain.playerManager.getData(shooter.uniqueId) ?: return

            // 伤害计算：300% 箭矢强度 + 每层1%最大生命（该部分附加伤害总和最多100）
            val baseDamage = pData.archerDamage * 3.0
            val maxHp = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            // 【修改点】：先将单层伤害乘以总层数，算出一个总的附加生命值伤害，然后再用 min 函数限制最高不超过100
            val totalHpBonus = min(maxHp * 0.01 * stacks, 100.0)
            // 最终伤害 = 无上限的300%箭矢强度 + 封顶100的百分比附加伤害
            val totalDetonateDamage = baseDamage + totalHpBonus
            // 造成引爆伤害
            applyDetonateDamage(victim, shooter, totalDetonateDamage)

            // 引爆音效与特效
            victim.world.playSound(victim.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f)
            victim.world.spawnParticle(Particle.LAVA, victim.location.add(0.0, victim.height / 2, 0.0), 15, 0.5, 0.5, 0.5, 0.1)

        } else {
            // 被动：叠加层数
            val burnInfo = shootersMap.computeIfAbsent(shooter.uniqueId) { BurnInfo(0, 0L) }

            // 如果过期了但是没被清理掉，重置层数
            if (now > burnInfo.expireTime) {
                burnInfo.stacks = 0
            }

            // 叠层 (最多5层)
            if (burnInfo.stacks < 5) {
                burnInfo.stacks++
            }
            // 刷新存在时间为 5 秒
            burnInfo.expireTime = now + 5000L

            // 命中叠加音效
            victim.world.playSound(victim.location, Sound.BLOCK_FIRE_AMBIENT, 1f, 1.5f)
        }
    }

    // 辅助方法：造成物理技能伤害（清除无敌帧）
    private fun applyDetonateDamage(victim: LivingEntity, attacker: Player, amount: Double) {
        victim.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
        victim.noDamageTicks = 0
        try {
            victim.damage(amount, attacker)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (victim.hasMetadata("hjh_physical_skill")) {
                victim.removeMetadata("hjh_physical_skill", plugin)
            }
            victim.noDamageTicks = 0
        }
    }
}