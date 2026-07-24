package com.hjh_database.race

import com.hjh_database.Hjh_database
import com.hjh_database.race.impl.HumanRace
import com.hjh_database.race.impl.YaoRace
import com.hjh_database.race.impl.ShenRace
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.persistence.PersistentDataType
import java.sql.SQLException

class RaceManager(val plugin: Hjh_database) : Listener {
    private val races = HashMap<Int, RaceBase>()
    val keyResourceId = NamespacedKey(plugin, "resource_id")

    init {
        registerRace(0, ShenRace(this))
        registerRace(2, HumanRace(this))
        registerRace(4, YaoRace(this))
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun registerRace(id: Int, race: RaceBase) {
        races[id] = race
        plugin.server.pluginManager.registerEvents(race, plugin)
    }

    fun getRace(id: Int): RaceBase? = races[id]

    // 获取物品的 Resource ID
    fun getResourceId(item: org.bukkit.inventory.ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null
        return item.itemMeta.persistentDataContainer.get(keyResourceId, PersistentDataType.STRING)
    }

    // 检查任务状态 (优化版：查缓存)
    fun isQuestCompleted(player: Player, questId: String): Boolean {
        // 1. 获取在线玩家的缓存数据
        val data = plugin.playerManager.getPlayerData(player)

        // 2. 如果数据存在，直接查 Set
        if (data != null) {
            return data.completedQuests.contains(questId)
        }
        // 3.如果获取不到数据直接返回 false
        return false
    }
}
