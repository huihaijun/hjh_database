package com.hjh_database.accessory.skill.medical

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable

abstract class BaseMedicalOverflowSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {
    private val storedKey = NamespacedKey(plugin, "medical_overflow_stored")
    private val cooldownKey = NamespacedKey(plugin, "medical_overflow_cooldown")

    override fun onMedicalHeal(event: MedicalHealEvent, item: ItemStack, slotKey: String, crystalData: CrystalData): Boolean {
        val overflow = event.overflowHeal
        if (overflow <= 0.0) return false

        val meta = item.itemMeta ?: return false
        val currentStored = meta.persistentDataContainer.get(storedKey, PersistentDataType.DOUBLE) ?: 0.0
        val maxStorage = getMaxStorage(crystalData)
        val storedToAdd = overflow.coerceAtMost((maxStorage - currentStored).coerceAtLeast(0.0))
        var newStored = (currentStored + storedToAdd).coerceAtMost(maxStorage)

        if (storedToAdd > 0.0) {
            meta.persistentDataContainer.set(storedKey, PersistentDataType.DOUBLE, newStored)
            item.itemMeta = meta
        }
        event.caster.sendMessage("§a[饰品-${getItemName(item)}§a]当前存入生命值：§b${formatNumber(newStored)}§a/§b${formatNumber(maxStorage)}")

        val now = System.currentTimeMillis()
        val cooldownEnd = meta.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) ?: 0L
        val shouldSummon = newStored >= getTriggerStorage(crystalData) && now >= cooldownEnd
        if (shouldSummon) {
            val consumed = newStored
            val target = findTarget(event.caster, getRange(crystalData))
            if (target != null) {
                newStored = 0.0
                meta.persistentDataContainer.set(storedKey, PersistentDataType.DOUBLE, newStored)
                meta.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, now + (getCooldownSeconds(crystalData) * 1000.0).toLong())
                item.itemMeta = meta
                event.caster.sendMessage("§a[饰品-${getItemName(item)}§a]发动！召唤了一只灵鸟！")
                launchSpiritBird(event.caster, target, consumed, crystalData)
            }
        }

        val data = plugin.playerManager.getPlayerData(event.caster)
        if (data != null) {
            plugin.playerManager.crystalManager.updateCrystalLore(item, crystalData, data, slotKey)
        }
        return storedToAdd > 0.0 || shouldSummon
    }

    protected open fun getMaxStorage(crystalData: CrystalData): Double = crystalData.medicalOverflowMaxStorage
    protected open fun getTriggerStorage(crystalData: CrystalData): Double = crystalData.medicalOverflowTriggerStorage
    protected open fun getCooldownSeconds(crystalData: CrystalData): Double = crystalData.medicalOverflowCooldownSeconds
    protected open fun getRange(crystalData: CrystalData): Double = crystalData.medicalOverflowRange
    protected open fun getZfMultiplier(crystalData: CrystalData): Double = crystalData.medicalOverflowZfMultiplier
    protected open fun getStorageMultiplier(crystalData: CrystalData): Double = crystalData.medicalOverflowStorageMultiplier

    private fun findTarget(player: Player, range: Double): LivingEntity? {
        val origin = player.location
        return player.world.getNearbyEntities(origin, range, range, range)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != player.uniqueId && !it.isDead }
            .filter { it.scoreboardTags.contains("panling") && it.scoreboardTags.contains("monster") }
            .filter { it.location.distanceSquared(origin) <= range * range }
            .minByOrNull { it.location.distanceSquared(origin) }
    }

    private fun launchSpiritBird(player: Player, target: LivingEntity, consumedStorage: Double, crystalData: CrystalData) {
        val spawnLoc = player.eyeLocation.clone().add(player.location.direction.normalize().multiply(0.8))
        val bird = player.world.spawn(spawnLoc, Parrot::class.java)
        bird.customName = "§b灵鸟"
        bird.isCustomNameVisible = true
        bird.isInvulnerable = true
        bird.setAI(false)
        bird.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)

        player.world.playSound(player.location, Sound.ENTITY_PARROT_FLY, 1.0f, 1.4f)
        val damage = plugin.playerManager.getPlayerData(player)?.let {
            it.zfStr * getZfMultiplier(crystalData) + consumedStorage * getStorageMultiplier(crystalData)
        } ?: (consumedStorage * getStorageMultiplier(crystalData))

        object : BukkitRunnable() {
            var ticks = 0
            val start = spawnLoc.clone()
            val distance = start.distance(target.location)
            val flightTicks = ((distance * 2.0).toInt()).coerceIn(14, 28)

            override fun run() {
                if (bird.isDead || target.isDead || !player.isOnline) {
                    bird.remove()
                    cancel()
                    return
                }

                val targetLoc = target.location.clone().add(0.0, 0.8, 0.0)
                if (ticks >= flightTicks || bird.location.distanceSquared(targetLoc) <= 0.8) {
                    impact(player, target, bird, damage)
                    cancel()
                    return
                }

                val progress = (ticks + 1).toDouble() / flightTicks.toDouble()
                val eased = progress * progress * (3.0 - 2.0 * progress)
                val arc = kotlin.math.sin(Math.PI * progress) * 1.1
                val nextLoc = start.clone().add(targetLoc.toVector().subtract(start.toVector()).multiply(eased))
                nextLoc.y += arc
                nextLoc.direction = targetLoc.toVector().subtract(nextLoc.toVector()).normalize()

                bird.teleport(nextLoc)
                bird.velocity = org.bukkit.util.Vector(0.0, 0.0, 0.0)
                bird.world.spawnParticle(
                    Particle.DUST,
                    bird.location,
                    4,
                    0.12,
                    0.12,
                    0.12,
                    Particle.DustOptions(Color.AQUA, 1.0f)
                )
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun impact(player: Player, target: LivingEntity, bird: Parrot, damage: Double) {
        target.noDamageTicks = 0
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.damage(damage, player)

        val knockback = target.location.toVector().subtract(player.location.toVector())
        if (knockback.lengthSquared() > 0.001) {
            target.velocity = knockback.normalize().multiply(0.85).setY(0.25)
        }

        target.world.spawnParticle(Particle.CLOUD, target.location.clone().add(0.0, 1.0, 0.0), 16, 0.3, 0.3, 0.3, 0.03)
        target.world.spawnParticle(Particle.HEART, target.location.clone().add(0.0, 1.5, 0.0), 2, 0.2, 0.2, 0.2, 0.0)
        target.world.playSound(target.location, Sound.ENTITY_PARROT_AMBIENT, 1.0f, 1.6f)
        bird.remove()
    }

    private fun getItemName(item: ItemStack): String {
        val name = item.itemMeta?.displayName ?: "桃李枝"
        return ChatColor.stripColor(name) ?: name
    }

    protected fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }
}
