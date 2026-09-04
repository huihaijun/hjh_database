package com.hjh_database.accessory.skill.medical

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class JinshengzhiSkill(plugin: Hjh_database) : BaseMedicalOverflowSkill(plugin) {
    private val flameDust = Particle.DustOptions(Color.fromRGB(255, 96, 24), 1.05f)

    override fun getBirdCount(crystalData: CrystalData): Int = 1

    override fun getMaxDamageRetargets(): Int = 2

    override fun getOverflowRetargetRange(): Double = 16.0

    override fun canKnockback(target: LivingEntity): Boolean {
        return !target.scoreboardTags.contains("instance_boss")
    }

    override fun getBirdDisplayName(): String = "火灵鸟"

    override fun getActionBarSkillName(): String = "生火不息"

    override fun getBirdCustomName(): String = "§c火灵鸟"

    override fun getHealStorageMultiplier(crystalData: CrystalData): Double = 0.5

    override fun getTrailDust(): Particle.DustOptions = flameDust

    override fun hasFlameTrail(): Boolean = true

    override fun getKnockbackStrength(): Double = 0.55

    override fun applyAllyBuffs(target: Player) {
        target.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 200, 0, false, false, true))
        target.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 200, 0, false, false, true))
    }
}
