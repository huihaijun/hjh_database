package com.hjh_database.baihu_dz.skill.impl

import com.hjh_database.Hjh_database
import com.hjh_database.baihu_dz.BaihuArtifactData
import com.hjh_database.baihu_dz.BaihuEquipmentDamageTag
import com.hjh_database.data.PlayerData
import com.hjh_database.listener.FormationMagicDamage
import com.hjh_database.skill.medical.spell.MedicalCastEvent
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 医师虎瘴饰品：第八格激活后，成功释放医术会触发战阵。 */
class HuzhizhanqiSkill(private val plugin: Hjh_database) : Listener {
    private val cooldownEnds = ConcurrentHashMap<UUID, Long>()

    init {
        val file = configFile()
        if (!file.exists()) plugin.saveResource("baihu_dz/artifact_skills/huzhizhanqi.yml", false)
    }

    @EventHandler
    fun onMedicalCast(event: MedicalCastEvent) {
        val player = event.caster
        val data = plugin.playerManager.getData(player.uniqueId) ?: return
        val config = loadConfig() ?: return
        if (!config.getBoolean("enable", true)) return

        val contents = plugin.accessoryManager.getAccessoryContents(player) ?: return
        val item = contents.getOrNull(ACTIVATION_SLOT) ?: return
        val artifact = plugin.baihuDzManager.getArtifactDataFromItem(item) ?: return
        if (artifact.id != ARTIFACT_ID) return
        if (!plugin.baihuDzManager.isArtifactActiveForSkill(player, item, artifact, "accessory_$ACTIVATION_SLOT")) return

        val now = System.currentTimeMillis()
        val cooldownEnd = cooldownEnds[player.uniqueId] ?: 0L
        if (cooldownEnd > now) return

        val manaCost = config.getDouble("mana_cost", 20.0).coerceAtLeast(0.0)
        if (data.lingli < manaCost) {
            player.sendMessage("§c灵力不足，虎志战旗需要 ${format(manaCost)} 点灵力。")
            return
        }
        if (plugin.baihuDzManager.getDurability(item, artifact) < artifact.durabilityCost) {
            player.sendMessage("§c虎志战旗的虎瘴耐久不足，无法展开战阵。")
            return
        }

        data.lingli -= manaCost
        plugin.databaseManager.queuePlayerSave(data)
        if (!plugin.baihuDzManager.consumeDurability(player, item, artifact)) return
        contents[ACTIVATION_SLOT] = item
        plugin.accessoryManager.saveAccessoryContents(player, contents)

        val cooldownMillis = (config.getDouble("cooldown", 10.0).coerceAtLeast(0.0) * 1000.0).toLong()
        cooldownEnds[player.uniqueId] = now + cooldownMillis
        castFormation(player, data, config)
        player.sendMessage("§e${player.name}§f凭借§7虎志战旗§f，释放了阵法——§7虎志战阵")
    }

    private fun castFormation(player: Player, data: PlayerData, config: ConfigurationSection) {
        val center = player.location.clone()
        val radius = config.getDouble("radius", 15.0).coerceAtLeast(1.0)
        val durationTicks = config.getLong("duration_ticks", 160L).coerceAtLeast(20L)
        val damage = data.zfStr * config.getDouble("damage_multiplier", 1.25)
        val attackBonus = config.getDouble("offense_bonus_percent", 0.15)
        val manaRegen = config.getDouble("mana_regen_per_second", 5.0).coerceAtLeast(0.0)
        val miasmaMultiplier = config.getDouble("miasma_increase_multiplier", 0.5).coerceIn(0.0, 1.0)
        val healthRegen = config.getDouble("stopped_miasma_health_regen_per_second", 2.0).coerceAtLeast(0.0)
        val saturationRegen = config.getDouble("stopped_miasma_saturation_regen_per_second", 1.0).coerceAtLeast(0.0)
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
                    clearBuffs(buffedPlayers)
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
                    restoreMiasmaStoppedPlayers(inside, healthRegen, saturationRegen)
                    spawnAmbientParticles(center, radius)
                }

                val insideIds = inside.mapTo(hashSetOf()) { it.uniqueId }
                buffedPlayers.keys.filter { it !in insideIds }.forEach { removeBuff(it, buffedPlayers) }
                elapsed += 10L
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    private fun spawnFlag(center: org.bukkit.Location): ItemDisplay? = try {
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

    private fun playersInside(center: org.bukkit.Location, radius: Double): List<Player> {
        val radiusSquared = radius * radius
        return center.world.getNearbyEntities(center, radius, 3.5, radius)
            .asSequence()
            .mapNotNull { it as? Player }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .toList()
    }

    private fun refreshMiasmaSlow(players: List<Player>, multiplier: Double) {
        players.forEach { plugin.baihuMiasmaManager.setTemporaryIncreaseMultiplier(it, MIASMA_SOURCE, multiplier, 15L) }
    }

    private fun refreshOffenseBuffs(players: List<Player>, buffedPlayers: MutableMap<UUID, List<String>>, attackBonus: Double) {
        players.forEach { target ->
            val targetData = plugin.playerManager.getData(target.uniqueId) ?: return@forEach
            val keys = when (targetData.job) {
                0 -> listOf("$MIASMA_SOURCE::attack_percent")
                1 -> listOf("$MIASMA_SOURCE::archer_damage_percent")
                else -> emptyList()
            }
            if (keys.isEmpty() || buffedPlayers[target.uniqueId] == keys) return@forEach

            removeBuff(target.uniqueId, buffedPlayers)
            keys.forEach { targetData.tempBonuses[it] = attackBonus }
            buffedPlayers[target.uniqueId] = keys
            plugin.playerManager.updateStats(target)
        }
    }

    private fun removeBuff(uuid: UUID, buffedPlayers: MutableMap<UUID, List<String>>) {
        val keys = buffedPlayers.remove(uuid) ?: return
        val target = plugin.server.getPlayer(uuid)
        val data = plugin.playerManager.getData(uuid) ?: return
        keys.forEach(data.tempBonuses::remove)
        if (target?.isOnline == true) plugin.playerManager.updateStats(target)
    }

    private fun clearBuffs(buffedPlayers: MutableMap<UUID, List<String>>) {
        buffedPlayers.keys.toList().forEach { removeBuff(it, buffedPlayers) }
    }

    private fun restoreMana(players: List<Player>, amount: Double) {
        if (amount <= 0.0) return
        players.forEach { target ->
            val data = plugin.playerManager.getData(target.uniqueId) ?: return@forEach
            if (data.job != 2 && data.job != 3) return@forEach
            val before = data.lingli
            data.addLingli(amount)
            if (data.lingli > before) plugin.databaseManager.queuePlayerSave(data, 40L)
        }
    }

    private fun restoreMiasmaStoppedPlayers(players: List<Player>, health: Double, saturation: Double) {
        players.forEach { target ->
            if (!plugin.baihuMiasmaManager.isMiasmaIncreaseStopped(target)) return@forEach
            if (health > 0.0) {
                val maxHealth = target.getAttribute(Attribute.MAX_HEALTH)?.value ?: target.maxHealth
                target.health = min(maxHealth, target.health + health)
            }
            if (saturation > 0.0) {
                target.saturation = min(target.foodLevel.toFloat(), target.saturation + saturation.toFloat())
            }
        }
    }

    private fun damageMonsters(player: Player, center: org.bukkit.Location, radius: Double, damage: Double) {
        val radiusSquared = radius * radius
        center.world.getNearbyEntities(center, radius, 3.8, radius).forEach { entity ->
            val target = entity as? LivingEntity ?: return@forEach
            if (!isValidTarget(target) || target.location.distanceSquared(center) > radiusSquared) return@forEach
            magicDamage(player, target, damage)
            target.world.spawnParticle(
                Particle.DUST,
                target.location.clone().add(0.0, target.height * 0.55, 0.0),
                8, 0.28, 0.35, 0.28, 0.0,
                Particle.DustOptions(FLAG_GOLD, 1.0f)
            )
        }
    }

    private fun magicDamage(player: Player, target: LivingEntity, amount: Double) {
        BaihuEquipmentDamageTag.markTarget(plugin, target)
        try {
            FormationMagicDamage.deal(plugin, player, target, amount)
        } finally {
            BaihuEquipmentDamageTag.clearTarget(plugin, target)
        }
    }

    private fun spawnOpeningRing(center: org.bukkit.Location, radius: Double) {
        val dust = Particle.DustOptions(FLAG_GOLD, 1.15f)
        repeat(64) { index ->
            val angle = 2.0 * PI * index / 64.0
            center.world.spawnParticle(Particle.DUST, center.clone().add(cos(angle) * radius, 0.16, sin(angle) * radius), 1, 0.02, 0.02, 0.02, 0.0, dust)
        }
        center.world.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.2, 0.0), 32, radius * 0.35, 0.55, radius * 0.35, 0.0)
    }

    private fun spawnAmbientParticles(center: org.bukkit.Location, radius: Double) {
        val dust = Particle.DustOptions(FLAG_CYAN, 0.85f)
        repeat(18) {
            val angle = Random.nextDouble(0.0, 2.0 * PI)
            val distance = Random.nextDouble(1.5, radius * 0.95)
            val loc = center.clone().add(cos(angle) * distance, Random.nextDouble(0.9, 2.2), sin(angle) * distance)
            center.world.spawnParticle(Particle.DUST, loc, 1, 0.02, 0.05, 0.02, 0.0, dust)
            if (Random.nextDouble() < 0.35) center.world.spawnParticle(Particle.END_ROD, loc, 1, 0.01, 0.04, 0.01, 0.0)
        }
    }

    private fun isValidTarget(entity: LivingEntity): Boolean =
        entity.scoreboardTags.contains("panling") && entity.scoreboardTags.contains("monster")

    private fun loadConfig(): ConfigurationSection? =
        YamlConfiguration.loadConfiguration(configFile()).getConfigurationSection("active")

    private fun configFile() = File(plugin.dataFolder, "baihu_dz/artifact_skills/huzhizhanqi.yml")

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

    companion object {
        private const val ARTIFACT_ID = "huzhizhanqi"
        private const val ACTIVATION_SLOT = 7
        private const val MIASMA_SOURCE = "huzhizhanqi"
        private val FLAG_GOLD: Color = Color.fromRGB(232, 196, 96)
        private val FLAG_CYAN: Color = Color.fromRGB(112, 210, 210)
    }
}
