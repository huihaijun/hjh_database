package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.baihu_dz.BaihuWeaponData
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkill
import com.hjh_database.baihu_dz.skill.BaihuWeaponSkillResult
import com.hjh_database.data.PlayerData
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HuzhizhanqiSkill(
    private val plugin: Hjh_database
) : BaihuWeaponSkill {
    override fun castActive(
        player: Player,
        data: PlayerData,
        item: ItemStack,
        weaponData: BaihuWeaponData,
        config: ConfigurationSection,
        projectile: Entity?
    ): BaihuWeaponSkillResult {
        val manaCost = config.getDouble("mana_cost", 20.0).coerceAtLeast(0.0)
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，需要 ${format(manaCost)} 点灵力。")
            return BaihuWeaponSkillResult.FAIL
        }

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        castFormation(player, data, config)
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e${player.name}&f凭借&7虎志战旗&f，释放了阵法——&7虎志战阵"))
        sendLingliActionBar(player, data)
        return BaihuWeaponSkillResult.success(message = "")
    }

    private fun castFormation(player: Player, data: PlayerData, config: ConfigurationSection) {
        val center = player.location.clone()
        val radius = config.getDouble("radius", 15.0).coerceAtLeast(1.0)
        val durationTicks = config.getLong("duration_ticks", 160L).coerceAtLeast(20L)
        val damage = data.zfStr * config.getDouble("damage_multiplier", 1.25)
        val attackBonus = config.getDouble("offense_bonus_percent", 0.20)
        val manaRegen = config.getDouble("mana_regen_per_second", 5.0).coerceAtLeast(0.0)
        val miasmaMultiplier = config.getDouble("miasma_increase_multiplier", 0.5).coerceIn(0.0, 1.0)
        val flag = spawnFlag(center)
        val buffedPlayers = linkedMapOf<UUID, List<String>>()

        player.world.playSound(center, Sound.ITEM_TRIDENT_THUNDER, 0.75f, 1.35f)
        player.world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.65f, 1.55f)
        damageMonsters(player, center, radius, damage)
        spawnOpeningRing(center, radius)

        object : BukkitRunnable() {
            private var elapsed = 0L

            override fun run() {
                if (elapsed >= durationTicks || !player.isOnline) {
                    damageMonsters(player, center, radius, damage)
                    spawnOpeningRing(center, radius)
                    clearBuffs(buffedPlayers, attackBonus)
                    flag?.remove()
                    player.world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.4f)
                    cancel()
                    return
                }

                val inside = playersInside(center, radius)
                refreshMiasmaSlow(inside, miasmaMultiplier)
                refreshOffenseBuffs(inside, buffedPlayers, attackBonus)
                if (elapsed % 20L == 0L) {
                    restoreMana(inside, manaRegen)
                    spawnAmbientParticles(center, radius)
                }

                val insideIds = inside.mapTo(hashSetOf()) { it.uniqueId }
                val leaving = buffedPlayers.keys.filter { it !in insideIds }
                leaving.forEach { removeBuff(it, buffedPlayers, attackBonus) }

                elapsed += 10L
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    private fun spawnFlag(center: org.bukkit.Location): ItemDisplay? {
        return try {
            center.world.spawn(center.clone().add(0.0, 1.35, 0.0), ItemDisplay::class.java) { display ->
                display.setItemStack(ItemStack(Material.LIGHT_GRAY_BANNER))
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD)
                display.setTransformation(
                    Transformation(
                    Vector3f(0f, 0f, 0f),
                    AxisAngle4f(0f, 0f, 1f, 0f),
                    Vector3f(2.4f, 2.4f, 2.4f),
                    AxisAngle4f(0f, 0f, 1f, 0f)
                    )
                )
                display.setRotation(center.yaw, 0.0f)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun playersInside(center: org.bukkit.Location, radius: Double): List<Player> {
        val radiusSquared = radius * radius
        return center.world.getNearbyEntities(center, radius, 3.5, radius)
            .asSequence()
            .mapNotNull { it as? Player }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .toList()
    }

    private fun refreshMiasmaSlow(players: List<Player>, multiplier: Double) {
        players.forEach { player ->
            plugin.baihuMiasmaManager.setTemporaryIncreaseMultiplier(player, MIASMA_SOURCE, multiplier, 45L)
        }
    }

    private fun refreshOffenseBuffs(players: List<Player>, buffedPlayers: MutableMap<UUID, List<String>>, attackBonus: Double) {
        players.forEach { target ->
            val targetData = plugin.playerManager.getData(target.uniqueId) ?: return@forEach
            val keys = when (targetData.job) {
                0 -> listOf("attack_percent")
                1 -> listOf("archer_damage_percent")
                else -> emptyList()
            }
            if (keys.isEmpty() || buffedPlayers[target.uniqueId] == keys) return@forEach

            removeBuff(target.uniqueId, buffedPlayers, attackBonus)
            keys.forEach { key -> targetData.tempBonuses[key] = (targetData.tempBonuses[key] ?: 0.0) + attackBonus }
            buffedPlayers[target.uniqueId] = keys
            plugin.playerManager.updateStats(target)
        }
    }

    private fun removeBuff(uuid: UUID, buffedPlayers: MutableMap<UUID, List<String>>, attackBonus: Double) {
        val keys = buffedPlayers.remove(uuid) ?: return
        val target = plugin.server.getPlayer(uuid)
        val data = plugin.playerManager.getData(uuid) ?: return
        keys.forEach { key ->
            val next = (data.tempBonuses[key] ?: 0.0) - attackBonus
            if (next <= 0.0001) data.tempBonuses.remove(key) else data.tempBonuses[key] = next
        }
        if (target != null && target.isOnline) {
            plugin.playerManager.updateStats(target)
        }
    }

    private fun clearBuffs(buffedPlayers: MutableMap<UUID, List<String>>, attackBonus: Double) {
        buffedPlayers.keys.toList().forEach { removeBuff(it, buffedPlayers, attackBonus) }
    }

    private fun restoreMana(players: List<Player>, amount: Double) {
        if (amount <= 0.0) return
        players.forEach { target ->
            val data = plugin.playerManager.getData(target.uniqueId) ?: return@forEach
            if (data.job != 2 && data.job != 3) return@forEach
            val before = data.lingli
            data.addLingli(amount)
            if (data.lingli > before) {
                plugin.databaseManager.queuePlayerSave(data, 40L)
            }
        }
    }

    private fun damageMonsters(player: Player, center: org.bukkit.Location, radius: Double, damage: Double) {
        val radiusSquared = radius * radius
        center.world.getNearbyEntities(center, radius, 3.8, radius).forEach { entity ->
            val target = entity as? LivingEntity ?: return@forEach
            if (!isValidTarget(target) || target.location.distanceSquared(center) > radiusSquared) return@forEach
            magicDamage(player, target, damage)
            val hit = target.location.clone().add(0.0, target.height * 0.55, 0.0)
            target.world.spawnParticle(Particle.DUST, hit, 8, 0.28, 0.35, 0.28, 0.0, Particle.DustOptions(FLAG_GOLD, 1.0f))
        }
    }

    private fun magicDamage(player: Player, target: LivingEntity, amount: Double) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        target.setMetadata("HJH_MAGIC_DAMAGE", FixedMetadataValue(plugin, amount))
        val previousMaximum = target.maximumNoDamageTicks
        target.noDamageTicks = 0
        target.maximumNoDamageTicks = 0
        try {
            target.damage(amount, player)
        } finally {
            if (target.hasMetadata("HJH_MAGIC_DAMAGE")) {
                target.removeMetadata("HJH_MAGIC_DAMAGE", plugin)
            }
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
            target.noDamageTicks = 0
            target.maximumNoDamageTicks = previousMaximum
        }
    }

    private fun spawnOpeningRing(center: org.bukkit.Location, radius: Double) {
        val world = center.world
        val points = 64
        val dust = Particle.DustOptions(FLAG_GOLD, 1.15f)
        for (i in 0 until points) {
            val angle = 2.0 * PI * i / points
            val loc = center.clone().add(cos(angle) * radius, 0.16, sin(angle) * radius)
            world.spawnParticle(Particle.DUST, loc, 1, 0.02, 0.02, 0.02, 0.0, dust)
        }
        world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.2, 0.0), 32, radius * 0.35, 0.55, radius * 0.35, 0.0)
    }

    private fun spawnAmbientParticles(center: org.bukkit.Location, radius: Double) {
        val world = center.world
        val dust = Particle.DustOptions(FLAG_CYAN, 0.85f)
        repeat(18) {
            val angle = Random.nextDouble(0.0, 2.0 * PI)
            val distance = Random.nextDouble(1.5, radius * 0.95)
            val loc = center.clone().add(cos(angle) * distance, Random.nextDouble(0.9, 2.2), sin(angle) * distance)
            world.spawnParticle(Particle.DUST, loc, 1, 0.02, 0.05, 0.02, 0.0, dust)
            if (Random.nextDouble() < 0.35) {
                world.spawnParticle(Particle.END_ROD, loc, 1, 0.01, 0.04, 0.01, 0.0)
            }
        }
    }

    private fun isValidTarget(entity: LivingEntity): Boolean {
        val tags = entity.scoreboardTags
        return tags.contains("panling") && tags.contains("monster")
    }

    private fun sendLingliActionBar(player: Player, data: PlayerData) {
        val message = "&6☯当前灵力值：&b${String.format("%.1f", data.lingli)} &6/ &b${String.format("%.0f", data.maxLingli)} &6☯"
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(ChatColor.translateAlternateColorCodes('&', message))
        )
    }

    private fun format(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
    }

    companion object {
        private const val MIASMA_SOURCE = "huzhizhanqi"
        private val FLAG_GOLD: Color = Color.fromRGB(232, 196, 96)
        private val FLAG_CYAN: Color = Color.fromRGB(112, 210, 210)
    }
}
