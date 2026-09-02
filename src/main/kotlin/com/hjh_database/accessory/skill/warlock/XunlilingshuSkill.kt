package com.hjh_database.accessory.skill.warlock

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.AccessoryHudValueKind
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.client.ClientParticleLayer
import com.hjh_database.client.ClientParticleShape
import com.hjh_database.weapon.CrystalData
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * 巽离灵枢：在元素阵法成功释放后维护一个独立的“卦印”。
 *
 * prepareCast 只制作本次施法计划，不修改卦印；只有阵法返回成功后 commitCast
 * 才会真正写入状态。这样湿气打断、目标无效或其他取消路径都不会误转移卦印。
 */
class XunlilingshuSkill(plugin: Hjh_database) : BaseRefluxSkill(plugin) {
    companion object {
        private const val BASE_COOLDOWN_MS = 1_500L
        private const val TRANSFER_EXTRA_COOLDOWN_MS = 6_000L
        private const val REFLUX_EFFECT_MULTIPLIER = 1.35
        private const val TRANSFER_FORMATION_COOLDOWN_MULTIPLIER = 2.0
    }

    enum class CastMode {
        CREATE_MARK,
        REFLUX,
        TRANSFER
    }

    data class CastPlan(
        val mode: CastMode,
        val currentElement: String,
        val previousElement: String?,
        val freeResourceCost: Boolean,
        val effectMultiplier: Double,
        val cooldownMultiplier: Double
    )

    private val marks = HashMap<UUID, String>()
    private var markDisplayTask: BukkitTask? = null

    override val accessoryId: String = "xunlilingshu"

    override fun getThresholdPercent(crystalData: CrystalData): Double = 0.5

    override fun getCostPerLevel(crystalData: CrystalData): Double = 1.5

    override fun getTriggerProbability(crystalData: CrystalData): Double = 0.65

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState =
        super.getHudState(player, item, crystalData).copy(
            valueKind = AccessoryHudValueKind.ELEMENT_MARK,
            elementMark = marks[player.uniqueId]
        )

    /**
     * 在元素技能真正执行前生成一次性计划。饰品冷却期间返回 null，卦印完全不变。
     */
    fun prepareCast(player: Player, rawElement: String): CastPlan? {
        val element = rawElement.uppercase()
        val now = System.currentTimeMillis()
        if (getTrackedCooldownEnd(player) > now) return null

        val markedElement = marks[player.uniqueId]
        return when {
            markedElement == null -> CastPlan(
                CastMode.CREATE_MARK,
                element,
                null,
                freeResourceCost = false,
                effectMultiplier = 1.0,
                cooldownMultiplier = 1.0
            )
            markedElement == element -> CastPlan(
                CastMode.REFLUX,
                element,
                markedElement,
                freeResourceCost = true,
                effectMultiplier = REFLUX_EFFECT_MULTIPLIER,
                cooldownMultiplier = 1.0
            )
            else -> CastPlan(
                CastMode.TRANSFER,
                element,
                markedElement,
                freeResourceCost = false,
                effectMultiplier = 1.0,
                cooldownMultiplier = TRANSFER_FORMATION_COOLDOWN_MULTIPLIER
            )
        }
    }

    /** 仅由成功的阵法释放调用。 */
    fun commitCast(player: Player, plan: CastPlan) {
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        when (plan.mode) {
            CastMode.CREATE_MARK -> {
                marks[uuid] = plan.currentElement
                startTrackedCooldown(player, BASE_COOLDOWN_MS, now)
                showMarkCreated(player, plan.currentElement)
                ensureMarkDisplayTask()
            }
            CastMode.REFLUX -> {
                marks.remove(uuid)
                startTrackedCooldown(player, BASE_COOLDOWN_MS, now)
                showReflux(player, plan.currentElement)
                stopMarkDisplayTaskIfIdle()
            }
            CastMode.TRANSFER -> {
                // 旧元素即使已经自然结束冷却，清除操作也只是安全的空操作。
                plan.previousElement?.let { plugin.elementZfManager.resetCooldown(player, it) }
                marks[uuid] = plan.currentElement
                val duration = BASE_COOLDOWN_MS + TRANSFER_EXTRA_COOLDOWN_MS
                startTrackedCooldown(player, duration, now)
                showTransfer(player, plan.previousElement, plan.currentElement)
                ensureMarkDisplayTask()
            }
        }
    }

    fun clearMark(player: Player) {
        marks.remove(player.uniqueId)
        clearTrackedCooldown(player)
        stopMarkDisplayTaskIfIdle()
    }

    fun shutdown() {
        marks.clear()
        shutdownHudState()
        markDisplayTask?.cancel()
        markDisplayTask = null
    }

    private fun ensureMarkDisplayTask() {
        if (markDisplayTask != null) return
        markDisplayTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val iterator = marks.entries.iterator()
            while (iterator.hasNext()) {
                val (uuid, element) = iterator.next()
                val player = Bukkit.getPlayer(uuid)
                val data = player?.let(plugin.playerManager::getPlayerData)
                if (player == null || !player.isOnline || player.isDead || data?.job != 2) {
                    iterator.remove()
                    if (player != null) clearTrackedCooldown(player)
                    continue
                }
                renderMark(player, element)
            }
            if (marks.isEmpty()) {
                markDisplayTask?.cancel()
                markDisplayTask = null
            }
        }, 0L, 10L)
    }

    private fun stopMarkDisplayTaskIfIdle() {
        if (marks.isNotEmpty()) return
        markDisplayTask?.cancel()
        markDisplayTask = null
    }

    private fun renderMark(player: Player, element: String) {
        val center = player.location.clone().add(0.0, 1.05, 0.0)
        val color = elementColor(element)
        plugin.clientBridge.emitParticles(
            center,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 8, color, 0.85f, .62, .08, .0),
                ClientParticleLayer("minecraft:wax_on", ClientParticleShape.RING, 3, radius = .40, height = .10, speed = .01)
            )
        )
        player.world.spawnParticle(
            Particle.DUST,
            center,
            1,
            0.28,
            0.05,
            0.28,
            0.0,
            Particle.DustOptions(org.bukkit.Color.fromRGB(color), 0.75f)
        )
    }

    private fun showMarkCreated(player: Player, element: String) {
        val center = effectCenter(player)
        val color = elementColor(element)
        plugin.clientBridge.emitParticles(
            center,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 22, color, 1.05f, 1.05, .18, .025),
                ClientParticleLayer("minecraft:end_rod", ClientParticleShape.BURST, 7, radius = .65, height = .8, speed = .035)
            )
        )
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .65f, elementPitch(element))
        player.sendActionBar("§e§l【${elementName(element)}卦印】§f已凝成")
    }

    private fun showReflux(player: Player, element: String) {
        val center = effectCenter(player)
        val color = elementColor(element)
        plugin.clientBridge.emitParticles(
            center,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.SPHERE, 30, color, 1.15f, 1.15, 1.65, .055),
                ClientParticleLayer("minecraft:flash", ClientParticleShape.BURST, 2, radius = .3, height = .45),
                ClientParticleLayer("minecraft:end_rod", ClientParticleShape.RING, 12, radius = .85, height = .22, speed = .03)
            )
        )
        player.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, .8f, 1.55f)
        player.playSound(player.location, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, .5f, 1.8f)
        // 与回流仪、炎灵共用极简战斗字幕事件；经典提示模式仍保留原 ActionBar。
        if (!plugin.passiveSubtitleManager.showAccessoryTrigger(player, accessoryId)) {
            player.sendActionBar("§d§l【回流】§f卦印归元，本次阵法效果提升§b35%")
        }
    }

    private fun showTransfer(player: Player, oldElement: String?, newElement: String) {
        val center = effectCenter(player)
        plugin.clientBridge.emitParticles(
            center,
            listOf(
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 16, elementColor(oldElement), .9f, .62, .12, .035),
                ClientParticleLayer("minecraft:dust", ClientParticleShape.RING, 24, elementColor(newElement), 1.15f, 1.20, .2, .045),
                ClientParticleLayer("minecraft:gust", ClientParticleShape.BURST, 5, radius = .72, height = .5, speed = .08)
            )
        )
        player.playSound(player.location, Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, .65f, elementPitch(newElement))
        player.sendActionBar("§6§l【卦印流转】§f${elementName(oldElement)}归寂，${elementName(newElement)}印成")
    }

    private fun effectCenter(player: Player): Location = player.location.clone().add(0.0, 1.0, 0.0)

    private fun elementColor(element: String?): Int = when (element?.uppercase()) {
        "METAL" -> 0xF5D66F
        "WOOD" -> 0x62D27A
        "WATER" -> 0x55B8F4
        "FIRE" -> 0xF06A38
        "EARTH" -> 0xB78A57
        else -> 0xE9E1D2
    }

    private fun elementPitch(element: String?): Float = when (element?.uppercase()) {
        "METAL" -> 1.65f
        "WOOD" -> 1.25f
        "WATER" -> 1.4f
        "FIRE" -> 1.1f
        "EARTH" -> .85f
        else -> 1.0f
    }

    private fun elementName(element: String?): String = when (element?.uppercase()) {
        "METAL" -> "金"
        "WOOD" -> "木"
        "WATER" -> "水"
        "FIRE" -> "火"
        "EARTH" -> "土"
        else -> "无"
    }
}
