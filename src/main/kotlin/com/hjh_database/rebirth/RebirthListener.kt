package com.hjh_database.rebirth

import com.hjh_database.Hjh_database
import com.hjh_database.admin.AdminInteractionBlocks.Kind
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RebirthListener(private val plugin: Hjh_database) : Listener {
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    private val rebirthClickWindowMs = 1000L
    private val lastAltarClicks = ConcurrentHashMap<UUID, Long>()
    private val rebirthingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAltarInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        if (plugin.adminInteractionBlocks.typeAt(block) != Kind.REBIRTH) return

        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (rebirthingPlayers.contains(player.uniqueId)) return

        if (!isHoldingWangchuanWater(player.inventory.itemInMainHand)) {
            player.sendMessage("§c请主手持有忘川水，再叩问转生祭坛。")
            return
        }

        val now = System.currentTimeMillis()
        val lastClick = lastAltarClicks[player.uniqueId] ?: 0L
        lastAltarClicks[player.uniqueId] = now
        if (now - lastClick > rebirthClickWindowMs) {
            player.sendMessage("§7祭坛微微震动，再次右键以饮尽忘川。")
            return
        }

        lastAltarClicks.remove(player.uniqueId)
        startRebirth(player)
    }

    private fun startRebirth(player: Player) {
        val uuid = player.uniqueId
        val playerName = player.name
        rebirthingPlayers.add(uuid)
        player.closeInventory()
        player.sendMessage("§8转生祭坛吞没了你的影子……")
        plugin.chonghuaManager.preparePlayerReset(uuid)
        plugin.qixiBridgeBuildManager.preparePlayerReset(uuid)

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.databaseManager.resetPlayerPersistentData(uuid, playerName)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (player.isOnline) {
                        finishRebirth(player)
                    } else {
                        plugin.chonghuaManager.cancelPlayerReset(uuid)
                        plugin.qixiBridgeBuildManager.cancelPlayerReset(uuid)
                    }
                    rebirthingPlayers.remove(uuid)
                })
            } catch (ex: Exception) {
                plugin.logger.severe("玩家 $playerName 转生失败: ${ex.message}")
                ex.printStackTrace()
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (player.isOnline) {
                        player.sendMessage("§c转生失败，请联系管理员查看后台日志。")
                    }
                    plugin.chonghuaManager.cancelPlayerReset(uuid)
                    plugin.qixiBridgeBuildManager.cancelPlayerReset(uuid)
                    rebirthingPlayers.remove(uuid)
                })
            }
        })
    }

    private fun finishRebirth(player: Player) {
        player.activePotionEffects.forEach { effect ->
            player.removePotionEffect(effect.type)
        }

        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
        player.enderChest.clear()
        player.setItemOnCursor(null)

        plugin.accessoryManager.clearAccessoryContents(player)
        plugin.elementCrystalManager.resetPlayerData(player)
        plugin.warehouseManager.resetCachedData(player)
        plugin.playerManager.resetCachedData(player)
        plugin.titleManager.reloadPlayerAfterReset(player)
        plugin.shenConsciousnessManager.resetPlayerData(player.uniqueId)
        plugin.shenTributeManager.resetPlayerData(player)
        plugin.xianTalentManager.resetPlayerData(player.uniqueId)
        plugin.chonghuaManager.resetPlayerData(player)
        plugin.qixiBridgeBuildManager.resetPlayerData(player)
        plugin.farmingManager.resetPlayerData(player)
        plugin.busuanManager.resetPlayerData(player.uniqueId)
        plugin.baihuMiasmaManager.resetPlayerData(player)

        removeFromTeams(player)

        val world = Bukkit.getWorld("world") ?: player.world
        player.teleport(Location(world, 1315.5, 76.5, 42.5, -90.0f, 0.0f))
        player.health = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        player.saturation = 5.0f
        player.fireTicks = 0
        player.remainingAir = player.maximumAir
        player.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 100, 0, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false))
        player.playSound(player.location, Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 0.6f)

        player.sendMessage("§c你感到自己坠入虚无，又在虚无中重生")
        player.sendMessage("§b前尘已断，往后便是新的一页……")
        player.sendMessage("§e※ 你的等级、物品、任务进度等均已清空。")
    }

    private fun removeFromTeams(player: Player) {
        val scoreboards = listOf(Bukkit.getScoreboardManager().mainScoreboard, player.scoreboard).distinct()
        for (scoreboard in scoreboards) {
            for (team in scoreboard.teams) {
                if (team.hasEntry(player.name)) {
                    team.removeEntry(player.name)
                }
            }
        }
    }

    private fun isHoldingWangchuanWater(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return false
        val resourceId = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
        return resourceId == "wangchuanshui"
    }
}
