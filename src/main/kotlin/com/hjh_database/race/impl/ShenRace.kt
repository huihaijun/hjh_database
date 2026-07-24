package com.hjh_database.race.impl

import com.hjh_database.race.RaceBase
import com.hjh_database.race.RaceManager
import org.bukkit.Sound
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class ShenRace(manager: RaceManager) : RaceBase(manager) {

    override val raceId: Int = 0
    override val requiredQuestId: String = "main_shen_8"

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onProofUseNpc(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!isRaceActive(player) || manager.getResourceId(player.inventory.itemInMainHand) != "shen_zm_begin") return

        val villager = event.rightClicked as? Villager
        val npcManager = manager.plugin.npcModule.manager
        val templateId = villager?.persistentDataContainer?.get(npcManager.npcKey, PersistentDataType.STRING)
        if (villager == null || templateId == null) {
            openConsciousnessMenu(event, player)
            return
        }
        val template = npcManager.getTemplate(templateId)
        if (template == null) {
            openConsciousnessMenu(event, player)
            return
        }

        event.isCancelled = true
        val isBanker = manager.plugin.passbookListener.isBanker(template.name) ||
            manager.plugin.passbookListener.isBanker(villager.customName)
        if (template.trades.isEmpty() && !isBanker) {
            player.sendMessage("§e[神识] §f这名 NPC 没有可建立神识的交易。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        manager.plugin.shenConsciousnessManager.bind(player, villager, template)
    }

    // 染料右键空气没有原版用途，Bukkit 会将该交互预先标记为取消。
    // 这里必须接收已取消事件，否则神族证明只能在对准方块时打开菜单。
    @EventHandler(priority = EventPriority.LOWEST)
    fun onProofUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        val player = event.player
        if (!isRaceActive(player) || manager.getResourceId(player.inventory.itemInMainHand) != "shen_zm_begin") return

        event.isCancelled = true
        manager.plugin.shenConsciousnessManager.openMenu(player)
    }

    private fun openConsciousnessMenu(event: PlayerInteractEntityEvent, player: org.bukkit.entity.Player) {
        event.isCancelled = true
        manager.plugin.shenConsciousnessManager.openMenu(player)
    }

    override fun castActiveSkill(player: org.bukkit.entity.Player) = Unit
}
