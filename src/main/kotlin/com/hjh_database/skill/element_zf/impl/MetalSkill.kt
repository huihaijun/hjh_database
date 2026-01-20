package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.skill.element_zf.ElementSkill
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

class MetalSkill(private val plugin: Hjh_database) : ElementSkill {

    override fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean {
        // config 在 Manager 传入时可能为空
        val safeConfig = config!!

        // 1. 获取配置数值
        var path = "levels.$level"
        if (!safeConfig.contains(path)) path = "levels.1"

        val damagePercent = safeConfig.getDouble("$path.damage_percent", 2.5)
        val range = safeConfig.getDouble("$path.range", 3.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        val maxTargets = safeConfig.getInt("$path.max_targets", 1)
        val effectRadius = safeConfig.getDouble("$path.effect_radius", 2.5 + (level * 0.5))

        // 2. 消耗物品
        val handItem = player.inventory.itemInMainHand
        handItem.amount = handItem.amount - 1

        // 3. 增加灵力 & 提示
        // 【修复点】：添加 !! 断言，确保 data 不为 null
        val data = plugin.playerManager.getData(player.uniqueId)!!

        data.lingli = data.lingli + lingliAdd

        // 修改完数据后，必须告诉数据库管理器保存数据
        // 【修复点】：由于上面加了 !!，这里的 data 已经是非空类型，不会再报错
        plugin.databaseManager.savePlayer(data)

        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.GOLD.toString() + "当前灵力值: " + String.format("%.1f", data.lingli))
        )

        // 4. 计算目标位置 (星云中心)
        val hitLoc = getHitLocation(player, range)
        val cloudCenter = hitLoc.clone().add(0.0, 0.1, 0.0) // 稍微抬高防止贴地

        // 【新增】播放指向性光束特效 (金色射线)
        playTargetingBeam(player, hitLoc)

        // 5. 寻找目标怪物并造成伤害
        // 搜索范围：以落点为中心，半径为 effectRadius 的立方体
        val nearby = cloudCenter.world!!.getNearbyEntities(cloudCenter, effectRadius, effectRadius, effectRadius)

        // Kotlin 风格的流式处理
        val victims = nearby.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it !== player } // 引用比较
            .filter { it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster") }
            // 高度限制：只打中心点下方的怪
            .filter { it.location.y <= cloudCenter.y }
            // 半球限制：距离必须在半径范围内
            .filter { it.location.distance(cloudCenter) <= effectRadius }
            .sortedBy { it.location.distance(player.location) }
            .take(maxTargets)
            .toList()

        // 造成伤害
        val damage = data.zfStr * damagePercent

        for (victim in victims) {
            // === 【核心修改】 法术伤害逻辑 ===
            // 1. 贴标签：告诉 CombatListener 这是法术伤害，请无视护甲
            victim.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))

            // 2. 造成伤害
            victim.damage(damage, player)

            // 3. 撕标签：防止影响后续的普通攻击
            victim.removeMetadata("hjh_magic_damage", plugin)
            // ==============================
        }

        // 6. 播放星云爆炸特效
        playEffects(cloudCenter, victims, effectRadius)

        return true
    }

    /**
     * 获取视线撞击点
     * 规则：只会在【实体方块】或者【带有 panling+monster 标签的怪物】身上停下
     */
    private fun getHitLocation(player: Player, maxRange: Double): Location {
        val eye = player.eyeLocation
        val direction = eye.direction

        // 射线检测：
        val result = player.world.rayTrace(
            eye, direction, maxRange,
            FluidCollisionMode.NEVER, true, 0.5
        ) { entity ->
            entity !== player &&
                    entity.scoreboardTags.contains("panling") &&
                    entity.scoreboardTags.contains("monster")
        }

        if (result != null) {
            if (result.hitBlock != null) {
                // 撞到方块
                return result.hitPosition.toLocation(player.world)
            } else if (result.hitEntity != null) {
                // 撞到符合条件的怪物
                return result.hitEntity!!.location.add(0.0, result.hitEntity!!.height / 2.0, 0.0)
            }
        }
        return eye.add(direction.multiply(maxRange))
    }

    /**
     * 【新增】播放指向性光束
     * 从玩家眼部下方射出一道粒子直到目标点
     */
    private fun playTargetingBeam(player: Player, endLoc: Location) {
        val startLoc = player.eyeLocation.add(0.0, -0.25, 0.0)

        // 稍微向右偏移一点，模拟右手施法
        val right = startLoc.direction.crossProduct(Vector(0, 1, 0)).normalize().multiply(-0.2)
        startLoc.add(right)

        val distance = startLoc.distance(endLoc)
        val dir = endLoc.toVector().subtract(startLoc.toVector()).normalize()

        var d = 0.0
        while (d < distance) {
            val point = startLoc.clone().add(dir.clone().multiply(d))
            player.world.spawnParticle(
                Particle.DUST, point, 1,
                Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.6f)
            )
            d += 0.25
        }
        player.world.spawnParticle(Particle.FLASH, endLoc, 1)
    }

    private fun playEffects(center: Location, targets: List<LivingEntity>, radius: Double) {
        val world = center.world ?: return

        // 1. 星云特效
        val particleCount = (20 * radius).toInt()
        for (i in 0 until particleCount) {
            val angle = Math.random() * 2 * Math.PI
            val r = Math.random() * radius
            val offsetX = r * cos(angle)
            val offsetZ = r * sin(angle)
            val offsetY = (Math.random() - 0.5) * 0.5
            world.spawnParticle(
                Particle.CLOUD,
                center.clone().add(offsetX, offsetY, offsetZ),
                0, 0.0, 0.0, 0.0
            )
        }

        // 2. 坠落流星
        if (targets.isNotEmpty()) {
            for (target in targets) {
                val angle = Math.random() * 2 * Math.PI
                val r = Math.random() * radius
                val startLoc = center.clone().add(r * cos(angle), 0.0, r * sin(angle))
                val endLoc = target.location.add(0.0, target.height / 2.0, 0.0)
                spawnFallingStar(startLoc, endLoc)
            }
        } else {
            // 空放效果
            if (center.clone().subtract(0.0, 1.0, 0.0).block.type.isAir) {
                val angle = Math.random() * 2 * Math.PI
                val r = Math.random() * radius * 0.5
                val startLoc = center.clone().add(r * cos(angle), 0.0, r * sin(angle))
                spawnFallingStar(
                    startLoc,
                    center.clone().add(r * cos(angle), -radius, r * sin(angle))
                )
            }
        }
    }

    private fun spawnFallingStar(startLoc: Location, endLoc: Location) {
        val fallDir = endLoc.toVector().subtract(startLoc.toVector()).normalize().multiply(0.8)
        val distance = startLoc.distance(endLoc)
        val steps = (distance / 0.8).toInt()

        object : BukkitRunnable() {
            var step = 0
            val current = startLoc.clone()

            override fun run() {
                if (step >= steps || (current.world != null && current.block.type.isSolid)) {
                    if (current.world != null) {
                        current.world!!.spawnParticle(
                            Particle.CRIT,
                            current,
                            10,
                            0.2,
                            0.2,
                            0.2,
                            0.1
                        )
                    }
                    this.cancel()
                    return;
                }
                current.add(fallDir)
                if (current.world != null) {
                    current.world!!.spawnParticle(Particle.WAX_OFF, current, 1)
                    current.world!!.spawnParticle(Particle.END_ROD, current, 0)
                }
                step++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }
}