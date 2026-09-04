package com.hjh_database.subtitle

import com.hjh_database.Hjh_database
import org.bukkit.SoundCategory
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一管理由极短静音 Sound Event 驱动的战斗字幕。
 *
 * 这里只节流表现层数据包；技能触发、伤害、治疗、消耗与各自的真实冷却均不经过本类。
 */
class PassiveSubtitleManager(private val plugin: Hjh_database) : Listener {
    private data class DisplayKey(val playerId: UUID, val throttleId: String)

    private val soundEventPattern = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private val accessorySounds = HashMap<String, Map<String, String>>()
    private val combatSounds = HashMap<String, String>()
    private val lastDisplayNanos = ConcurrentHashMap<DisplayKey, Long>()
    private val playerModeKey = NamespacedKey(plugin, "combat_notice_subtitles")

    @Volatile
    private var enabled = true

    @Volatile
    private var displayCooldownNanos = 10L * NANOS_PER_TICK

    init {
        reload()
    }

    /** 跟随现有 /hjhadmin reload 从 config.yml 重新载入并清空旧节流状态。 */
    fun reload() {
        val root = plugin.config.getConfigurationSection(CONFIG_ROOT)
        enabled = root?.getBoolean("enabled", true) ?: true
        val cooldownTicks = root?.getLong("display-cooldown-ticks", 10L)?.coerceAtLeast(0L) ?: 10L
        displayCooldownNanos = cooldownTicks * NANOS_PER_TICK

        accessorySounds.clear()
        combatSounds.clear()
        lastDisplayNanos.clear()

        root?.getConfigurationSection("accessories")?.let(::loadAccessories)
        root?.getConfigurationSection("combat-events")?.let { section ->
            // Bukkit 以点号作为配置路径分隔符；getKeys(true) 同时兼容 YAML 中的扁平点号键与嵌套写法。
            for (eventId in section.getKeys(true)) {
                if (!section.isString(eventId)) continue
                validSoundEvent(section.getString(eventId), "combat-events.$eventId")?.let {
                    combatSounds[eventId.lowercase()] = it
                }
            }
        }
    }

    /**
     * @return true 表示该饰品提示已由字幕体系接管（包括处于显示节流期）；false 时调用方应发送原提示。
     */
    fun showAccessoryTrigger(player: Player, accessoryId: String, variant: String = "triggered"): Boolean {
        if (!enabled || !isSubtitleMode(player)) return false
        val normalizedId = accessoryId.lowercase()
        val sounds = accessorySounds[normalizedId] ?: return false
        val soundEvent = sounds[variant.lowercase()] ?: return false
        return showSound(player, soundEvent, "accessory:$normalizedId")
    }

    /**
     * @return true 表示固定战斗事件已由字幕体系接管；false 时调用方应安全回退到原提示。
     */
    fun showCombatEvent(player: Player, eventId: String): Boolean {
        if (!enabled || !isSubtitleMode(player)) return false
        val normalizedId = eventId.lowercase()
        val soundEvent = combatSounds[normalizedId] ?: return false
        return showSound(player, soundEvent, "combat:$normalizedId")
    }

    fun clear() {
        lastDisplayNanos.clear()
    }

    /** 未设置过的玩家默认使用字幕模式；选择会由玩家 PDC 跨登录持久保存。 */
    fun isSubtitleMode(player: Player): Boolean {
        val storedMode = player.persistentDataContainer.get(playerModeKey, PersistentDataType.BYTE) ?: return true
        return storedMode.toInt() != 0
    }

    /** @return 切换后的状态：true 为字幕模式，false 为经典聊天模式。 */
    fun toggleSubtitleMode(player: Player): Boolean {
        val subtitleMode = !isSubtitleMode(player)
        player.persistentDataContainer.set(
            playerModeKey,
            PersistentDataType.BYTE,
            if (subtitleMode) 1.toByte() else 0.toByte()
        )
        lastDisplayNanos.keys.removeIf { it.playerId == player.uniqueId }
        return subtitleMode
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        lastDisplayNanos.keys.removeIf { it.playerId == playerId }
    }

    private fun loadAccessories(section: ConfigurationSection) {
        for (accessoryId in section.getKeys(false)) {
            val accessorySection = section.getConfigurationSection(accessoryId) ?: continue
            val variants = HashMap<String, String>()

            validSoundEvent(accessorySection.getString("sound-event"), "accessories.$accessoryId.sound-event")?.let {
                variants["triggered"] = it
            }

            accessorySection.getConfigurationSection("event-sounds")?.let { eventSection ->
                for (variant in eventSection.getKeys(false)) {
                    validSoundEvent(
                        eventSection.getString(variant),
                        "accessories.$accessoryId.event-sounds.$variant"
                    )?.let { variants[variant.lowercase()] = it }
                }
            }

            if (variants.isNotEmpty()) accessorySounds[accessoryId.lowercase()] = variants.toMap()
        }
    }

    private fun validSoundEvent(value: String?, configPath: String): String? {
        val soundEvent = value?.trim()?.lowercase().orEmpty()
        if (soundEvent.isEmpty()) return null
        if (!soundEventPattern.matches(soundEvent)) {
            plugin.logger.warning("忽略无效字幕 sound-event: passive-subtitles.$configPath = '$value'")
            return null
        }
        return soundEvent
    }

    private fun showSound(player: Player, soundEvent: String, throttleId: String): Boolean {
        val now = System.nanoTime()
        val key = DisplayKey(player.uniqueId, throttleId)
        val last = lastDisplayNanos[key]
        if (last != null && now - last < displayCooldownNanos) return true

        return try {
            player.playSound(player.location, soundEvent, SoundCategory.PLAYERS, 1.0f, 1.0f)
            lastDisplayNanos[key] = now
            true
        } catch (error: Exception) {
            plugin.logger.warning("播放战斗字幕失败 [$soundEvent]: ${error.message}")
            false
        }
    }

    private companion object {
        const val CONFIG_ROOT = "passive-subtitles"
        const val NANOS_PER_TICK = 50_000_000L
    }
}
