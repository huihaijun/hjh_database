package com.hjh_database.skill.element_zf.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.element_zf.ElementSkill
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.util.Vector
import kotlin.math.ceil
import kotlin.math.min

class WoodSkill(private val plugin: Hjh_database) : ElementSkill {

    override fun cast(player: Player, level: Int, config: ConfigurationSection?): Boolean {
        // 1. 读取配置
        // 显式断言 config 非空，保持原 Java 逻辑（如果为 null 原代码也会空指针）
        val safeConfig = config!!

        var path = "levels.$level"
        if (!safeConfig.contains(path)) path = "levels.1"

        val damagePercent = safeConfig.getDouble("$path.damage_percent", 2.5)
        val healPercent = safeConfig.getDouble("$path.heal_percent", 0.2)
        val range = safeConfig.getDouble("$path.range", 5.0)
        val lingliAdd = safeConfig.getDouble("$path.lingli_add", 1.0)

        // 【新增】2. 消耗物品 (不管有没有目标，先扣物品)
        val handItem = player.inventory.itemInMainHand
        handItem.amount = handItem.amount - 1

        // 3. 获取玩家数据 (为后续加灵力和算伤害做准备)
        // 【关键点】显式使用 !! 断言，将 PlayerData? 转为 PlayerData
        val data = plugin.playerManager.getData(player.uniqueId)!!

        // 【新增】4. 增加灵力 (空放也加)
        if (lingliAdd > 0) {
            val currentLingli = data.lingli
            val maxLingli = data.maxLingli
            if (currentLingli < maxLingli) {
                data.lingli = min(maxLingli, currentLingli + lingliAdd)
                // 记得保存数据
                plugin.databaseManager.savePlayer(data)
            }
        }

//        player.spigot().sendMessage(
//            ChatMessageType.ACTION_BAR,
//            TextComponent(ChatColor.GOLD.toString() + "当前灵力值: " + String.format("%.1f", data.lingli))
//        )

        // 5. 寻找目标
        val target = findTarget(player, range)

        // 【修改】不再判空返回 false，而是判断如果有目标才造成伤害
        if (target != null) {
            // 计算伤害
            val baseDamage = data.zfStr
            val finalDamage = baseDamage * damagePercent
            // 法术伤害逻辑
            target.setMetadata("hjh_magic_damage", FixedMetadataValue(plugin, true))
            target.damage(finalDamage, player)
            target.removeMetadata("hjh_magic_damage", plugin)

            // 吸血逻辑
            val healAmount = ceil(finalDamage * healPercent)
            val currentHp = player.health
            // getAttribute 可能返回 null，必须使用 !! 断言
            val maxHp = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value
            if (currentHp < maxHp) {
                player.health = min(maxHp, currentHp + healAmount)
            }
            // 只有打中人才播放针对目标的特效
            playEffects(player, target)
        } else {
            // (可选) 如果空放，可以播放一个失败的音效或者只在脚下播点特效，这里暂不处理，保持安静
        }
        // 6. 提示与返回 (返回 true 代表释放成功，进入冷却)
//        player.sendMessage("§a§l[汲魂] §f阵法释放成功！");
        return true
    }

    /**
     * 寻找前方最近的有效目标
     */
    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val entities = player.getNearbyEntities(range, range, range)
        val playerLoc = player.location
        val direction = playerLoc.direction

        // 过滤并排序
        val validTargets = entities.asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it != player } // 不是自己
            .filter { e ->
                // 核心要求：必须同时拥有 panling 和 monster 标签
                val tags = e.scoreboardTags
                tags.contains("panling") && tags.contains("monster")
            }
            .filter { e ->
                // 简单的视线判断 (点积 > 0 表示在前方 180度，> 0.5 表示前方 60度左右)
                val toEntity = e.location.toVector().subtract(playerLoc.toVector()).normalize()
                direction.dot(toEntity) > 0.5
            }
            .sortedBy { it.location.distance(playerLoc) } // 按距离排序
            .toList()

        return validTargets.firstOrNull()
    }

    private fun playEffects(player: Player, target: LivingEntity) {
        val world = player.world
        val pLoc = player.location.add(0.0, 1.0, 0.0)
        val tLoc = target.location.add(0.0, 1.0, 0.0)

        // 连线特效 (绿色粒子从怪物飞向玩家，表现"吸取")
        val dir = pLoc.toVector().subtract(tLoc.toVector())
        val dist = pLoc.distance(tLoc)
        val step = dir.normalize().multiply(0.5)

        var d = 0.0
        while (d < dist) {
            world.spawnParticle(
                Particle.HAPPY_VILLAGER,
                tLoc.clone().add(step.clone().multiply(d)),
                1, 0.0, 0.0, 0.0, 0.0
            )
            d += 0.5
        }

        // 声音
        player.playSound(player.location, Sound.ENTITY_WITCH_DRINK, 1.0f, 1.2f)
        world.playSound(tLoc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.8f)

        // 目标身上爆绿光
        world.spawnParticle(Particle.COMPOSTER, tLoc, 20, 0.5, 1.0, 0.5, 0.1)
    }
}