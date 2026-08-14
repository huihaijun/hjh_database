package com.hjh_database.dungeon.qixi

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale

object QixiAccessPolicy {
    private data class Snapshot(
        val enabled: Boolean,
        val playerIds: Set<String>,
        val deniedMessage: String
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun reload(plugin: Hjh_database) {
        val file = File(plugin.dataFolder, "dungeon/qixi.yml")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            plugin.saveResource("dungeon/qixi.yml", false)
        }
        val config = YamlConfiguration.loadConfiguration(file)
        val ids = config.getStringList("internal-test.whitelist").mapNotNullTo(HashSet()) { raw ->
            raw.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
        }
        snapshot = Snapshot(
            enabled = config.getBoolean("internal-test.enabled", false),
            playerIds = ids,
            deniedMessage = ChatColor.translateAlternateColorCodes(
                '&',
                config.getString("internal-test.denied-message", DEFAULT_DENIED_MESSAGE) ?: DEFAULT_DENIED_MESSAGE
            )
        )
        plugin.logger.info("七夕副本内测白名单${if (snapshot!!.enabled) "已开启，共 ${ids.size} 个目标 ID" else "未开启"}。")
    }

    fun isAllowed(plugin: Hjh_database, player: Player): Boolean {
        val current = snapshot ?: synchronized(this) {
            snapshot ?: run {
                reload(plugin)
                snapshot!!
            }
        }
        if (!current.enabled) return true
        return player.uniqueId.toString().lowercase(Locale.ROOT) in current.playerIds ||
            player.name.lowercase(Locale.ROOT) in current.playerIds
    }

    fun deniedMessage(plugin: Hjh_database): String {
        return snapshot?.deniedMessage ?: run {
            reload(plugin)
            snapshot!!.deniedMessage
        }
    }

    private const val DEFAULT_DENIED_MESSAGE = "&c当前副本处于内测中，暂时无法进入"
}
