package com.hjh_database.accessory.skill.medical

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Parrot
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

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

        val now = System.currentTimeMillis()
        val cooldownEnd = meta.persistentDataContainer.get(cooldownKey, PersistentDataType.LONG) ?: 0L
        val shouldSummon = newStored >= getTriggerStorage(crystalData) && now >= cooldownEnd
        if (shouldSummon) {
            val targets = chooseTargets(event.caster, getRange(crystalData), getBirdCount(crystalData))
            if (targets.isNotEmpty()) {
                val consumed = newStored
                newStored = 0.0
                val latestMeta = item.itemMeta ?: meta
                latestMeta.persistentDataContainer.set(storedKey, PersistentDataType.DOUBLE, newStored)
                latestMeta.persistentDataContainer.set(cooldownKey, PersistentDataType.LONG, now + (getCooldownSeconds(crystalData) * 1000.0).toLong())
                item.itemMeta = latestMeta

                sendSkillActionBar(event.caster)
                launchBirds(event.caster, targets, consumed, crystalData)
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
    protected open fun getHealStorageMultiplier(crystalData: CrystalData): Double = 0.35
    protected open fun getBirdCount(crystalData: CrystalData): Int = 1
    protected open fun getBirdDisplayName(): String = "灵鸟"
    protected open fun getActionBarSkillName(): String = "盼暖春来"
    protected open fun getBirdCustomName(): String = "§b灵鸟"
    protected open fun getTrailDust(): Particle.DustOptions = Particle.DustOptions(Color.AQUA, 1.0f)
    protected open fun hasFlameTrail(): Boolean = false
    protected open fun applyAllyBuffs(target: Player) {}
    protected open fun getKnockbackStrength(): Double = 0.65

    private fun sendSkillActionBar(player: Player) {
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent("§a§l饰品技【${getActionBarSkillName()}】已发动！")
        )
    }

    private data class SpiritTarget(val entity: LivingEntity, val kind: Kind) {
        enum class Kind { ALLY, SELF, MONSTER }
    }

    private fun chooseTargets(player: Player, range: Double, count: Int): List<SpiritTarget> {
        val origin = player.location
        val rangeSquared = range * range

        val injuredAllies = player.world.getNearbyEntities(origin, range, range, range)
            .asSequence()
            .filterIsInstance<Player>()
            .filter { it.uniqueId != player.uniqueId && !it.isDead && it.location.distanceSquared(origin) <= rangeSquared }
            .filter { isInjured(it) }
            .sortedBy { it.location.distanceSquared(origin) }
            .map { SpiritTarget(it, SpiritTarget.Kind.ALLY) }
            .toList()

        val selfTarget = if (isInjured(player)) listOf(SpiritTarget(player, SpiritTarget.Kind.SELF)) else emptyList()
        val priorityTargets = injuredAllies + selfTarget
        if (priorityTargets.isNotEmpty()) {
            return fillTargets(priorityTargets, count)
        }

        val monsters = player.world.getNearbyEntities(origin, range, range, range)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.location.distanceSquared(origin) <= rangeSquared }
            .filter { isValidMonster(it) }
            .map { SpiritTarget(it, SpiritTarget.Kind.MONSTER) }
            .toMutableList()

        if (monsters.isEmpty()) return emptyList()
        monsters.shuffle(Random.Default)
        return fillTargets(monsters, count)
    }

    private fun fillTargets(candidates: List<SpiritTarget>, count: Int): List<SpiritTarget> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val result = ArrayList<SpiritTarget>(count)
        for (i in 0 until count) {
            result.add(candidates[i % candidates.size])
        }
        return result
    }

    private fun isInjured(player: Player): Boolean {
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: return false
        return player.health < maxHealth - 0.01
    }

    private fun isValidMonster(entity: LivingEntity): Boolean {
        return entity !is Player &&
            entity !is ArmorStand &&
            entity.isValid &&
            !entity.isDead &&
            entity.scoreboardTags.contains("panling") &&
            entity.scoreboardTags.contains("monster")
    }

    private fun launchBirds(player: Player, targets: List<SpiritTarget>, consumedStorage: Double, crystalData: CrystalData) {
        targets.forEachIndexed { index, target ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                launchSpiritBird(player, target, consumedStorage, crystalData, index)
            }, (index * 4L).coerceAtMost(12L))
        }
    }

    private fun launchSpiritBird(
        player: Player,
        target: SpiritTarget,
        consumedStorage: Double,
        crystalData: CrystalData,
        index: Int
    ) {
        val side = if (index % 2 == 0) 1.0 else -1.0
        val spawnLoc = player.eyeLocation.clone()
            .add(player.location.direction.clone().normalize().multiply(0.55))
            .add(Vector(-player.location.direction.z, 0.0, player.location.direction.x).normalize().multiply(0.25 * side))
        val bird = player.world.spawn(spawnLoc, Parrot::class.java)
        bird.customName = getBirdCustomName()
        bird.isCustomNameVisible = true
        bird.isInvulnerable = true
        bird.setAI(false)
        bird.velocity = Vector(0.0, 0.0, 0.0)

        player.world.playSound(player.location, Sound.ENTITY_PARROT_FLY, 0.9f, if (hasFlameTrail()) 1.15f else 1.4f)

        val damage = plugin.playerManager.getPlayerData(player)?.let {
            it.zfStr * getZfMultiplier(crystalData) + consumedStorage * getStorageMultiplier(crystalData)
        } ?: (consumedStorage * getStorageMultiplier(crystalData))
        val heal = consumedStorage * getHealStorageMultiplier(crystalData)

        object : BukkitRunnable() {
            var ticks = 0
            val start = spawnLoc.clone()
            val flightTicks = computeFlightTicks(start, target.entity.location)

            override fun run() {
                if (bird.isDead || target.entity.isDead || !player.isOnline || !target.entity.isValid) {
                    bird.remove()
                    cancel()
                    return
                }

                val targetLoc = targetLocation(target.entity)
                if (ticks >= flightTicks || bird.location.distanceSquared(targetLoc) <= 0.8) {
                    impact(player, target, bird, damage, heal)
                    cancel()
                    return
                }

                moveBirdAlongArc(bird, start, targetLoc, ticks, flightTicks)
                renderTrail(bird.location)
                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun computeFlightTicks(start: Location, target: Location): Int {
        val distance = start.distance(target)
        return ((distance * 2.0).toInt()).coerceIn(14, 30)
    }

    private fun targetLocation(entity: LivingEntity): Location {
        return entity.location.clone().add(0.0, (entity.height * 0.55).coerceIn(0.6, 1.4), 0.0)
    }

    private fun moveBirdAlongArc(bird: Parrot, start: Location, targetLoc: Location, ticks: Int, flightTicks: Int) {
        val progress = (ticks + 1).toDouble() / flightTicks.toDouble()
        val eased = progress * progress * (3.0 - 2.0 * progress)
        val arc = sin(PI * progress) * 1.1
        val nextLoc = start.clone().add(targetLoc.toVector().subtract(start.toVector()).multiply(eased))
        nextLoc.y += arc
        val direction = targetLoc.toVector().subtract(nextLoc.toVector())
        if (direction.lengthSquared() > 0.0001) {
            nextLoc.direction = direction.normalize()
        }

        bird.teleport(nextLoc)
        bird.velocity = Vector(0.0, 0.0, 0.0)
    }

    private fun renderTrail(loc: Location) {
        loc.world?.spawnParticle(Particle.DUST, loc, 4, 0.12, 0.12, 0.12, 0.0, getTrailDust())
        if (hasFlameTrail()) {
            loc.world?.spawnParticle(Particle.SMALL_FLAME, loc, 2, 0.09, 0.09, 0.09, 0.015)
        }
    }

    private fun impact(player: Player, target: SpiritTarget, bird: Parrot, damage: Double, heal: Double) {
        when (target.kind) {
            SpiritTarget.Kind.ALLY, SpiritTarget.Kind.SELF -> healTarget(target.entity as Player, heal)
            SpiritTarget.Kind.MONSTER -> damageTarget(player, target.entity, damage)
        }
        bird.remove()
    }

    private fun healTarget(target: Player, amount: Double) {
        val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: return
        val actualHeal = amount.coerceAtMost(maxHealth - target.health).coerceAtLeast(0.0)
        if (actualHeal > 0.0) {
            target.health = (target.health + actualHeal).coerceAtMost(maxHealth)
        }
        applyAllyBuffs(target)

        val center = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(Particle.HEART, center.clone().add(0.0, 0.45, 0.0), 3, 0.25, 0.25, 0.25, 0.0)
        target.world.spawnParticle(Particle.DUST, center, 14, 0.35, 0.45, 0.35, 0.0, getTrailDust())
        if (hasFlameTrail()) {
            target.world.spawnParticle(Particle.SMALL_FLAME, center, 10, 0.35, 0.45, 0.35, 0.02)
            target.world.playSound(target.location, Sound.BLOCK_FIRE_AMBIENT, 0.45f, 1.55f)
        }
        target.world.playSound(target.location, Sound.ENTITY_PARROT_AMBIENT, 0.9f, 1.65f)
    }

    private fun damageTarget(player: Player, target: LivingEntity, damage: Double) {
        target.noDamageTicks = 0
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, damage))
        target.damage(damage, player)

        val knockback = target.location.toVector().subtract(player.location.toVector())
        knockback.y = 0.0
        if (knockback.lengthSquared() > 0.001) {
            target.velocity = knockback.normalize().multiply(getKnockbackStrength()).setY(0.0)
        }

        val center = target.location.clone().add(0.0, 1.0, 0.0)
        target.world.spawnParticle(if (hasFlameTrail()) Particle.FLAME else Particle.CLOUD, center, 16, 0.3, 0.3, 0.3, 0.03)
        target.world.spawnParticle(Particle.DUST, center, 10, 0.28, 0.35, 0.28, 0.0, getTrailDust())
        target.world.playSound(target.location, if (hasFlameTrail()) Sound.ITEM_FIRECHARGE_USE else Sound.ENTITY_PARROT_AMBIENT, 0.9f, 1.35f)
    }

}
