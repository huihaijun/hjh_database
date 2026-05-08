package com.hjh_database.accessory.skill

import com.hjh_database.Hjh_database
import com.hjh_database.weapon.CrystalData
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.persistence.PersistentDataType

class QuiverManager(private val plugin: Hjh_database) : Listener {
    private val crystalKey = NamespacedKey(plugin, "crystal_id")

    // 【统一注册表】以后你加了任何新箭袋，只要在这里加一行就行了！
    private val quivers = mapOf<String, BaseQuiver>(
        "jiandai" to JiandaiSkill(plugin),
        "ranhuojiandai" to RanhuoJiandaiSkill(plugin),
        // "binghuangjiandai" to BinghuangJiandaiSkill(plugin)
    )

    // 提供给 AccessoryManager 调用的统一路由
    fun routeQuiverClick(player: Player, item: org.bukkit.inventory.ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        // 通过 YML 里的 id (比如 ranhuojiandai) 找到对应的箭袋类
        val quiverClass = quivers[crystalData.id] ?: return false
        // 委派给那个具体的类去处理
        return quiverClass.handleQuiverClick(player, item, isExtract, crystalData)
    }

    @EventHandler
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return

        val contents = plugin.accessoryManager.getAccessoryContents(player) ?: return

        // 遍历寻找激活的箭袋
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            val meta = item.itemMeta ?: continue
            val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue

            // 判断：配置了箭袋的特征，并且在正确的格子激活
            val quiverClass = quivers[cData.id]
            if (quiverClass != null && i == cData.activateSlot) {

                // 【完美接入你的系统】直接获取你写好的 PlayerData！
                val data = plugin.playerManager.getPlayerData(player) ?: return

                // ============================================
                // 【新增】判断职业和等级是否满足激活条件
                // ============================================
                val jobMatch = cData.reqJob == 0 || data.job == cData.reqJob
                if (data.lv < cData.reqLv || !jobMatch) {
                    continue // 如果未激活，跳过此饰品，不触发技能和补箭
                }
                // ============================================

                // 触发特效，把 data 传进去
                quiverClass.onShootEffect(event, player, data)

                // 2. 延迟 1 tick 让父类去处理补箭逻辑
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    quiverClass.processReplenish(player, item, cData)
                }, 1L)

                break // 找到了就跳出循环
            }
        }
    }
}