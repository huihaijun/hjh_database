package com.hjh_database.listener

import com.hjh_database.Hjh_database
import com.hjh_database.command.TestMobCommand
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.WorldLoadEvent

class TestDummySignListener(private val plugin: Hjh_database) : Listener {

    private data class DummySign(
        val signX: Int,
        val signY: Int,
        val signZ: Int,
        val rotation: Int,
        val spawnX: Int,
        val spawnY: Int,
        val spawnZ: Int,
        val health: Double,
        val armor: Double
    )

    private val signs = listOf(
        DummySign(154, 44, 42, 14, 155, 43, 42, 10000.0, 0.0),
        DummySign(158, 44, 42, 2, 157, 43, 42, 10000.0, 50.0)
    )

    init {
        Bukkit.getScheduler().runTask(plugin, Runnable { setupSigns() })
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        setupSigns()
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!block.type.name.endsWith("_SIGN")) return
        val world = targetWorld() ?: return
        if (block.world.uid != world.uid) return

        val sign = signs.firstOrNull {
            block.x == it.signX && block.y == it.signY && block.z == it.signZ
        } ?: return

        event.isCancelled = true
        val loc = Location(block.world, sign.spawnX + 0.5, sign.spawnY + 1.0, sign.spawnZ + 0.5)
        clearExistingDummy(loc)
        TestMobCommand.spawnDummy(plugin, loc, sign.health, sign.armor)
        event.player.sendMessage("${ChatColor.GREEN}已生成测伤人偶：血量 ${sign.health.toInt()}，护甲 ${sign.armor.toInt()}")
    }

    private fun clearExistingDummy(loc: Location) {
        loc.world?.getNearbyEntities(loc, 1.0, 1.5, 1.0)
            ?.filter { it.scoreboardTags.contains(TestMobCommand.TEST_DUMMY_TAG) }
            ?.forEach {
                it.removeScoreboardTag(TestMobCommand.TEST_DUMMY_TAG)
                it.remove()
            }
    }

    private fun setupSigns() {
        val world = targetWorld() ?: return
        for (sign in signs) {
            val block = world.getBlockAt(sign.signX, sign.signY, sign.signZ)
            block.type = Material.OAK_SIGN
            block.blockData = Bukkit.createBlockData("minecraft:oak_sign[rotation=${sign.rotation}]")

            val state = block.state as? Sign ?: continue
            state.setLine(0, color("&0右键我，生成一个"))
            state.setLine(1, color("&c${sign.health.toInt()}&0血量,&c${sign.armor.toInt()}&0护甲"))
            state.setLine(2, color("&0测伤玩偶"))
            state.setLine(3, color("&0死亡后自动复活"))
            state.update(true, false)
        }
    }

    private fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    private fun targetWorld() = plugin.server.getWorld("world") ?: plugin.server.worlds.firstOrNull()
}
