package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HunLingYouSpell(private val plugin: Hjh_database) : MedicalSpell, Listener {

    companion object {
        // 记录正在“灵魂出窍”的玩家和他们对应的肉身(盔甲架)
        val activeSouls = ConcurrentHashMap<UUID, ArmorStand>()
    }

    init {
        // 注册事件监听器，用于肉体承伤和仇恨转移
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr

        // 参数读取
        val damageMultiplier = config?.getDouble("damage_multiplier", 1.5) ?: 1.5
        val speedBonus = config?.getDouble("speed_bonus", 0.5) ?: 0.5 // 50% 移速
        val durationTicks = config?.getInt("duration_ticks", 70) ?: 70 // 3.5秒 = 70 ticks
        val damage = zfStr * damageMultiplier

        // 1. 生成肉身 (盔甲架)
        val bodyLoc = player.location.clone()
        val body = player.world.spawn(bodyLoc, ArmorStand::class.java) {
            it.setBasePlate(false)
            it.setArms(true)
            it.isCustomNameVisible = true
            it.customName = "§7${player.name}的肉身"

            // 穿戴玩家当前的装备
            val eq = it.equipment
            if (eq != null) {
                eq.armorContents = player.inventory.armorContents
                eq.setItemInMainHand(player.inventory.itemInMainHand)
                eq.setItemInOffHand(player.inventory.itemInOffHand)

                // 给盔甲架戴上玩家的头颅
                val head = ItemStack(Material.PLAYER_HEAD)
                val headMeta = head.itemMeta as SkullMeta
                headMeta.owningPlayer = player
                head.itemMeta = headMeta
                eq.helmet = head
            }
        }

        // 记录状态
        activeSouls[player.uniqueId] = body

        // 2. 赋予灵魂状态 (隐身 + 移速)
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, durationTicks, 0, false, false, true))

        val speedAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: player.getAttribute(Attribute.MOVEMENT_SPEED)
        if (speedAttribute != null) {
            val modifierKey = org.bukkit.NamespacedKey(plugin, "hunlingyou_speed")
            speedAttribute.removeModifier(modifierKey)
            speedAttribute.addModifier(AttributeModifier(modifierKey, speedBonus, AttributeModifier.Operation.ADD_SCALAR))
        }

        player.world.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.2f)
//        player.sendMessage("§8[魂灵游] §f灵魂出窍！你的肉体留在了原地...")

        // 用于记录灵魂碰撞过的怪物
        val hitEntities = mutableSetOf<UUID>()

        // 3. 核心循环任务
        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                // 安全检查：如果玩家掉线或死亡，提前结束
                if (!player.isOnline || player.isDead || body.isDead) {
                    endSoulState(player, body, false)
                    cancel()
                    return
                }

                val soulLoc = player.location

                // 特效：灵魂脚下的幽灵粒子
                player.world.spawnParticle(Particle.SOUL, soulLoc.clone().add(0.0, 0.5, 0.0), 2, 0.3, 0.3, 0.3, 0.02)

                // --- 灵魂碰撞伤害检测 ---
                val nearby = player.world.getNearbyEntities(soulLoc, 1.5, 1.5, 1.5)
                for (entity in nearby) {
                    if (entity is LivingEntity && entity.uniqueId != player.uniqueId && !hitEntities.contains(entity.uniqueId)) {
                        val tags = entity.scoreboardTags
                        if (tags.contains("panling") && tags.contains("monster")) {
                            hitEntities.add(entity.uniqueId)

                            // 造成法术伤害
                            plugin.medicalSpellManager.applyMedicalDamage(player, entity, damage, "hunlingyou")

                            entity.world.playSound(entity.location, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 1.5f)
                            entity.world.spawnParticle(Particle.SCULK_SOUL, entity.location.clone().add(0.0, 1.0, 0.0), 5, 0.3, 0.3, 0.3, 0.05)
                        }
                    }
                }

                // --- 仇恨转移控制 ---
                // 每 5 ticks 将周围试图攻击灵魂的怪物，强制转移目标为肉身
                if (ticks % 5 == 0) {
                    val bodyNearby = body.world.getNearbyEntities(body.location, 15.0, 15.0, 15.0)
                    for (entity in bodyNearby) {
                        if (entity is Mob && entity.target == player) {
                            entity.target = body // 强制打盔甲架
                            try {
                                entity.pathfinder.moveTo(body.location)
                            } catch (e: Exception) {}
                        }
                    }
                }

                ticks++

                // 持续时间结束，灵魂归位
                if (ticks >= durationTicks) {
                    endSoulState(player, body, true)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)

        return true
    }

    // 结束灵魂状态并回收肉体
    private fun endSoulState(player: Player, body: ArmorStand, normalEnd: Boolean) {
        // 先从记录中移除，让后续的事件监听不再拦截仇恨
        activeSouls.remove(player.uniqueId)

        // 移除移速加成
        val speedAttribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: player.getAttribute(Attribute.MOVEMENT_SPEED)
        if (speedAttribute != null) {
            val modifierKey = org.bukkit.NamespacedKey(plugin, "hunlingyou_speed")
            speedAttribute.removeModifier(modifierKey)
        }

        // 移除隐身
        player.removePotionEffect(PotionEffectType.INVISIBILITY)

        if (normalEnd) {
            val soulLoc = player.location.clone().add(0.0, 1.0, 0.0)
            val bodyLoc = body.location.clone().add(0.0, 1.0, 0.0)

            // 播放肉体被拉回灵魂的粒子连线特效
            val distance = bodyLoc.distance(soulLoc)
            val direction = soulLoc.toVector().subtract(bodyLoc.toVector()).normalize()
            val step = 0.5
            var d = 0.0
            while (d < distance) {
                val pLoc = bodyLoc.clone().add(direction.clone().multiply(d))
                player.world.spawnParticle(Particle.WITCH, pLoc, 2, 0.1, 0.1, 0.1, 0.0)
                d += step
            }

            player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.5f)
            player.sendMessage("§8[魂灵游] §f灵魂归位！")
        }

        // ==================== 修复仇恨丢失 Bug ====================
        // 在销毁肉身前，将正在攻击肉身的怪物仇恨强制转回给玩家
        val nearbyMobs = body.world.getNearbyEntities(body.location, 20.0, 20.0, 20.0)
        for (mob in nearbyMobs) {
            if (mob is Mob && mob.target == body) {
                mob.target = player // 仇恨归还
            }
        }
        // ========================================================

        // 最后销毁肉身
        if (!body.isDead) {
            body.remove()
        }
    }

    // ==================== 肉体承伤逻辑 ====================
    // 肉身只作为怪物仇恨与承伤锚点，禁止玩家交换其手持物或盔甲。
    // 不能改成 Marker 或无敌盔甲架，否则会影响怪物命中及伤害转移逻辑。
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBodyManipulate(e: PlayerArmorStandManipulateEvent) {
        if (activeSouls.containsValue(e.rightClicked)) {
            e.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBodyDamage(e: EntityDamageByEntityEvent) {
        val entity = e.entity
        if (entity is ArmorStand && activeSouls.containsValue(entity)) {
            // 阻止盔甲架被破坏或弹出物品
            e.isCancelled = true

            // 找到是哪个玩家的肉身
            val ownerEntry = activeSouls.entries.find { it.value == entity } ?: return
            val owner = plugin.server.getPlayer(ownerEntry.key) ?: return

            // 将怪物对盔甲架造成的伤害，原原本本地转移给玩家本体
            if (e.damager is LivingEntity) {
                owner.damage(e.damage, e.damager)
                owner.world.spawnParticle(Particle.DAMAGE_INDICATOR, entity.location.clone().add(0.0, 1.5, 0.0), 3, 0.2, 0.2, 0.2, 0.0)
                owner.world.playSound(entity.location, Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f)
            }
        }
    }

    // ==================== 仇恨屏蔽逻辑 ====================
    @EventHandler
    fun onTarget(e: EntityTargetEvent) {
        val target = e.target
        if (target is Player && activeSouls.containsKey(target.uniqueId)) {
            val body = activeSouls[target.uniqueId]
            if (body != null && !body.isDead) {
                e.target = body // 怪物一旦试图索敌灵魂，立刻转移给肉体
            } else {
                e.isCancelled = true
            }
        }
    }
}
