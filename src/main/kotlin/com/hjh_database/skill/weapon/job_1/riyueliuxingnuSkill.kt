package com.hjh_database.skill.weapon.job_1

import com.hjh_database.data.PlayerData
import com.hjh_database.skill.weapon.WeaponSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.Location
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
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

class riyueliuxingnuSkill : WeaponSkill, Listener {

    private val plugin = JavaPlugin.getProvidingPlugin(this::class.java)

    // 记录玩家身旁的流星 (UUID -> 包含流星过期时间的列表，最多5个)
    private val activeMeteors = ConcurrentHashMap<UUID, MutableList<Long>>()
    private val appliedSpeedStacks = ConcurrentHashMap<UUID, Int>()

    // 记录玩家留下的星域
    private data class StarField(val loc: Location, val expireTime: Long, val sourceTargetId: UUID)
    private val activeStarFields = ConcurrentHashMap<UUID, MutableList<StarField>>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 全局视觉与状态监听任务 (每 2 tick 执行一次，即 0.1 秒)
        object : BukkitRunnable() {
            var tickCount = 0 // 新增计数器，用于控制粒子频率

            override fun run() {
                tickCount++
                val now = System.currentTimeMillis()
                val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return

                for (player in Bukkit.getOnlinePlayers()) {
                    val uuid = player.uniqueId
                    val pData = pluginMain.playerManager.getData(uuid) ?: continue

                    // ==========================================
                    // 1. 处理流星背后排列特效与移速加成
                    // ==========================================
                    val meteors = activeMeteors[uuid]
                    var validMeteorsCount = 0

                    if (meteors != null) {
                        meteors.removeIf { it < now }
                        validMeteorsCount = meteors.size

                        // 【优化】通过 tickCount 降频 (每 6 tick 生成一次)，杜绝走动时形成浓密拖尾挡视野
                        if (validMeteorsCount > 0 && tickCount % 3 == 0) {
                            val pLoc = player.location
                            // 【优化】往后推远至 1.2 格
                            val backVec = pLoc.direction.setY(0).normalize().multiply(-1.2)
                            val rightVec = backVec.clone().crossProduct(org.bukkit.util.Vector(0, 1, 0)).normalize()

                            val spreadWidth = 1.2
                            val startOffset = -spreadWidth / 2
                            val step = if (validMeteorsCount > 1) spreadWidth / (validMeteorsCount - 1) else 0.0

                            for (i in 0 until validMeteorsCount) {
                                val currentRightOffset = startOffset + (step * i)
                                val finalVec = backVec.clone().add(rightVec.clone().multiply(currentRightOffset))

                                // 【优化】高度提升到 1.8 格 (头顶后方)，彻底避开视线中心
                                val dy = 1.8 + Math.sin(now / 300.0 + i) * 0.1
                                val particleLoc = pLoc.clone().add(finalVec).add(0.0, dy, 0.0)

                                player.world.spawnParticle(Particle.END_ROD, particleLoc, 1, 0.0, 0.0, 0.0, 0.0)
                            }
                        }
                    }

                    // 每颗流星 +10% 移速，作为独立来源参与统一属性结算。
                    updateMeteorSpeedBuff(player, pData, validMeteorsCount)

                    // ==========================================
                    // 2. 处理地上的星域与拾取判定
                    // ==========================================
                    val fields = activeStarFields[uuid]
                    if (fields != null) {
                        val iter = fields.iterator()
                        while (iter.hasNext()) {
                            val field = iter.next()
                            if (now > field.expireTime) {
                                iter.remove()
                                continue
                            }

                            val fLoc = field.loc
                            // 星域粒子也降频，减少满地都是星域时的显卡压力
                            if (tickCount % 2 == 0) {
                                for (i in 0 until 2) {
                                    val rX = (Math.random() - 0.5) * 3.0
                                    val rZ = (Math.random() - 0.5) * 3.0
                                    if (rX * rX + rZ * rZ <= 2.25) {
                                        fLoc.world?.spawnParticle(Particle.WAX_ON, fLoc.clone().add(rX, 0.1, rZ), 1, 0.0, 0.0, 0.0, 0.0)
                                    }
                                }
                            }

                            // 拾取判定
                            val pLoc = player.location
                            if (fLoc.world == pLoc.world &&
                                Math.abs(pLoc.y - fLoc.y) < 2.0 &&
                                (pLoc.x - fLoc.x) * (pLoc.x - fLoc.x) + (pLoc.z - fLoc.z) * (pLoc.z - fLoc.z) <= 2.25) {

                                iter.remove()

                                // 1. 恢复4点生命，不再恢复饱食度或饱和度。
                                val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
                                player.health = (player.health + 4.0).coerceAtMost(maxHealth)

                                // 2. 增加流星
                                addMeteor(player)

                                // 3. ★★★ 减少 1 秒技能冷却 ★★★
                                val cdKey = "riyueliuxingnu"
                                val currentCd = pData.nodeCoolDowns[cdKey]
                                if (currentCd != null && currentCd > now) {
                                    pData.nodeCoolDowns[cdKey] = currentCd - 1000L // 减少 1000 毫秒
                                }

                                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f)
                                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§e§l拾取星域！恢复4点生命，+1 流星，技能冷却 -1秒！"))
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }

    override fun castActive(player: Player?, data: PlayerData?, config: ConfigurationSection?, projectile: Entity?): Boolean {
        if (player == null || data == null) return false

        val meteors = activeMeteors[player.uniqueId] ?: emptyList<Long>()
        val meteorCount = meteors.size

        if (meteorCount == 0) {
            player.sendMessage("§c身旁没有流星，无法释放繁星落！")
            return false
        }

        // 清空自身流星
        activeMeteors.remove(player.uniqueId)
        updateMeteorSpeedBuff(player, data, 0)

        // 寻找 15 格内目标
        val targets = player.getNearbyEntities(15.0, 15.0, 15.0).filterIsInstance<LivingEntity>().filter {
            it != player && it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster")
        }

        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.2f)
        // 同一次主动共享目标记录：每只怪物最多为本次繁星落生成一片星域。
        val starFieldTargets = HashSet<UUID>()
        // 同一次主动按目标分别记录命中次数，用于后续流星的伤害衰减。
        val meteorHitCounts = HashMap<UUID, Int>()

        for (i in 0 until meteorCount) {
            // 均匀分配目标，如果没有目标则设为 null (朝前瞎射)
            val target = if (targets.isNotEmpty()) targets[i % targets.size] else null

            // 发射流星 (带一点向上和左右的初始散布向量，更华丽)
            val startLoc = player.location.add(0.0, 1.5, 0.0)
            val randomOffset = Vector((Math.random() - 0.5) * 2, Math.random() * 1.5, (Math.random() - 0.5) * 2)
            startLoc.add(randomOffset)

            HomingMeteor(
                startLoc,
                target,
                player,
                data.archerDamage,
                starFieldTargets,
                meteorHitCounts
            ).runTaskTimer(plugin, 0L, 1L)
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§b§l[繁星落] 发射了 $meteorCount 枚流星！"))
        return true
    }

    override fun deactivate(player: Player) {
        val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return
        val pData = pluginMain.playerManager.getData(player.uniqueId) ?: return

        // 1. 清空该玩家身边的所有流星和地上的星域
        val hadMeteors = activeMeteors.remove(player.uniqueId) != null
        activeStarFields.remove(player.uniqueId)

        // 2. 只清除本技能自己的移速加成
        updateMeteorSpeedBuff(player, pData, 0)

        // 3. 提示音效与文本（仅在身上真的有流星消散时提示，避免切物品时频繁刷屏）
        if (hadMeteors) {
            player.playSound(player.location, org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.5f)
            player.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent("§c§l流星已消散！")
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        val shooter = arrow.shooter as? Player ?: return
        val victim = event.entity as? LivingEntity ?: return

        // 检查武器是否为本武器
        val bow = shooter.inventory.itemInMainHand
        val meta = bow.itemMeta
        if (meta == null) return
        val weaponId = meta.persistentDataContainer.get(NamespacedKey(plugin, "weapon_id"), PersistentDataType.STRING)
        if (weaponId != "riyueliuxingnu") return

        // 标签检测
        if (!victim.scoreboardTags.contains("panling") || !victim.scoreboardTags.contains("monster")) return

        // 触发被动：增加一枚流星
        addMeteor(shooter)
    }

    private fun addMeteor(player: Player) {
        val uuid = player.uniqueId
        val meteors = activeMeteors.computeIfAbsent(uuid) { mutableListOf() }

        // 最多 5 枚
        if (meteors.size < 5) {
            meteors.add(System.currentTimeMillis() + 20000L) // 持续 20 秒
            player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.5f)
        } else {
            // 如果满了，刷新最旧的一颗的时间
            meteors.removeAt(0)
            meteors.add(System.currentTimeMillis() + 20000L)
        }
    }

    private fun updateMeteorSpeedBuff(player: Player, data: PlayerData, stacks: Int) {
        val clampedStacks = stacks.coerceIn(0, 5)
        if ((appliedSpeedStacks[player.uniqueId] ?: 0) == clampedStacks) return

        if (clampedStacks > 0) {
            data.tempBonuses[METEOR_SPEED_KEY] = clampedStacks * 0.10
            appliedSpeedStacks[player.uniqueId] = clampedStacks
        } else {
            data.tempBonuses.remove(METEOR_SPEED_KEY)
            appliedSpeedStacks.remove(player.uniqueId)
        }

        val pluginMain = plugin as? com.hjh_database.Hjh_database ?: return
        pluginMain.playerManager.updateStats(player)
    }

    // 追踪流星的子任务
    private inner class HomingMeteor(
        startLoc: Location,
        val target: LivingEntity?,
        val shooter: Player,
        val archerDamage: Double,
        private val starFieldTargets: MutableSet<UUID>,
        private val meteorHitCounts: MutableMap<UUID, Int>
    ) : BukkitRunnable() {

        var loc = startLoc.clone()
        var ticks = 0
        // 如果没有目标，记录初始玩家视角方向，瞎打出去
        val fallbackDirection = shooter.eyeLocation.direction.normalize().multiply(0.8)

        override fun run() {
            if (ticks++ > 60 || !shooter.isOnline) { // 最多飞 3 秒
                cancel()
                return
            }

            // 移动计算
            if (target != null && target.isValid && !target.isDead) {
                val targetPos = target.location.add(0.0, target.height / 2, 0.0)
                val dir = targetPos.subtract(loc).toVector()
                if (dir.lengthSquared() > 0) {
                    dir.normalize().multiply(0.8) // 速度 0.8 格/tick (慢慢飘过去)
                }
                loc.add(dir)
            } else {
                loc.add(fallbackDirection)
            }

            // 流星拖尾特效
            loc.world?.spawnParticle(Particle.FIREWORK, loc, 2, 0.1, 0.1, 0.1, 0.05)
            loc.world?.spawnParticle(Particle.END_ROD, loc, 1, 0.0, 0.0, 0.0, 0.0)

            // 命中判定 (与目标距离小于 1.5，或者如果没目标，飞到底直接炸)
            val hit = target != null && loc.distanceSquared(target.location.add(0.0, target.height / 2, 0.0)) <= 2.25

            if (hit || (target == null && ticks > 30)) {
                // 爆炸特效
                loc.world?.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.2f)
                loc.world?.spawnParticle(Particle.FLASH, loc, 1)

                // 如果确实命中了怪物，造成穿甲伤害
                if (hit && target != null) {
                    val previousHits = meteorHitCounts.getOrDefault(target.uniqueId, 0)
                    // 200% → 150% → 100% → 50%，第四颗之后保持50%。
                    val damageMultiplier = (2.0 - previousHits.coerceAtMost(3) * 0.5).coerceAtLeast(0.5)
                    meteorHitCounts[target.uniqueId] = previousHits + 1
                    val damage = archerDamage * damageMultiplier

                    target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
                    target.setMetadata("hjh_physical_skill", FixedMetadataValue(plugin, true))
                    target.noDamageTicks = 0
                    try {
                        target.damage(damage, shooter)
                    } finally {
                        if (target.hasMetadata("hjh_magic_damage")) target.removeMetadata("hjh_magic_damage", plugin)
                        if (target.hasMetadata("hjh_physical_skill")) target.removeMetadata("hjh_physical_skill", plugin)
                        target.noDamageTicks = 0
                    }
                }

                // 只有真正命中怪物时才生成星域；同一次主动中，同一目标只生成一次。
                if (hit && target != null && starFieldTargets.add(target.uniqueId)) {
                    val now = System.currentTimeMillis()
                    val fields = activeStarFields.computeIfAbsent(shooter.uniqueId) { mutableListOf() }
                    // 冷却缩减可能令下一次主动在旧星域消失前发动，仍保证该怪物只有一片有效星域。
                    if (fields.none { it.sourceTargetId == target.uniqueId && it.expireTime > now }) {
                        val groundLoc = target.location.clone()
                        groundLoc.y = Math.floor(groundLoc.y) + 0.1 // 贴着方块上面一点
                        fields.add(StarField(groundLoc, now + 7000L, target.uniqueId)) // 持续 7 秒
                    }
                }

                cancel()
            }
        }
    }

    companion object {
        private const val METEOR_SPEED_KEY = "riyueliuxingnu::speed_percent"
    }
}
