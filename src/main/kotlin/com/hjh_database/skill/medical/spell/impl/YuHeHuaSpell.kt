package com.hjh_database.skill.medical.spell.impl

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.skill.medical.spell.MedicalSpell
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector

class YuHeHuaSpell(private val plugin: Hjh_database) : MedicalSpell {

    companion object {
        const val KEY_HEAL_AMOUNT = "med_heal_amount"
        const val KEY_OWNER = "med_owner"
    }

    override fun cast(player: Player, data: PlayerData, config: ConfigurationSection?): Boolean {
        val zfStr = data.zfStr
        val healAmount = 4.0 + (zfStr * 0.2)

        val center = player.location
        player.world.playSound(center, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1f, 1.5f)

        val radius = 2.0
        val angles = doubleArrayOf(0.0, 120.0, 240.0)

        for (angle in angles) {
            val radians = Math.toRadians(angle + center.yaw)
            val x = Math.cos(radians) * radius
            val z = Math.sin(radians) * radius

            // 【修复】计算目标位置
            // 降低高度到 0.5，避免卡进天花板
            val targetLoc = center.clone().add(x, 0.5, z)

            // 【关键】检测目标方块是否是实心的
            if (isSafeLocation(targetLoc)) {
                spawnFlowerItem(player, targetLoc, healAmount)
            } else {
                // 如果目标位置卡墙了，就直接生成在玩家脚下，稍微给点随机偏移
                val fallback = center.clone().add((Math.random() - 0.5), 0.5, (Math.random() - 0.5))
                spawnFlowerItem(player, fallback, healAmount)
            }
        }

        return true
    }

    // 检查位置是否安全（不是实心方块）
    private fun isSafeLocation(loc: Location): Boolean {
        val b = loc.block
        return b.type.isAir || b.isPassable
    }

    private fun spawnFlowerItem(owner: Player, loc: Location, healAmount: Double) {
        val flower = ItemStack(Material.POPPY)
        // 【关键】使用 !! 断言
        val meta = flower.itemMeta!!
        meta.setDisplayName("§d愈合之花")

        val keyHeal = NamespacedKey(plugin, KEY_HEAL_AMOUNT)
        val keyOwner = NamespacedKey(plugin, KEY_OWNER)

        meta.persistentDataContainer.set(keyHeal, PersistentDataType.DOUBLE, healAmount)
        meta.persistentDataContainer.set(keyOwner, PersistentDataType.STRING, owner.uniqueId.toString())

        flower.itemMeta = meta

        // 生成掉落物
        val itemEntity = loc.world!!.dropItem(loc, flower)

        // 【关键】确保可以拾取
        itemEntity.pickupDelay = 5 // 设置极短的捡起延迟(0.25秒)，防止刚生成就被吸走，但也防止捡不起来
        itemEntity.isInvulnerable = true
        itemEntity.isGlowing = true
        itemEntity.velocity = Vector(0.0, 0.1, 0.0) // 只有一点点向上的力，防止乱飞

        loc.world!!.spawnParticle(Particle.HAPPY_VILLAGER, loc, 5, 0.2, 0.2, 0.2, 0.05)
    }
}