package com.hjh_database.medical

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*

// 【修改点】：从 object 改成了 class，并接收 plugin 实例，保持与你其他 Manager 完全一致
class MedicalTrialManager(private val plugin: Hjh_database) : Listener {

    val activeTrials = mutableMapOf<UUID, MedicalTrial>()

    // 【新增】试炼 ID 注册表，以后有新的试炼直接写在这个列表里即可
    val registeredTrialIds = listOf(
        "shanshenmiao",
        "wangyuanwai",
        "wenquankezhan",
        "zhuanyuanshangxian",
        "huzhenshangren",
        "chendafu",
        "yuzhu"
        // "xinmiao", "other_trial"  <-- 以后直接在这里往下加
    )

    // 【新增】供 onDisable 调用的清理方法
    fun cleanUpAllTrials() {
        for (instance in activeTrials.values) {
            instance.cleanUp() // 强制清除倒计时、销毁怪物和庙公
        }
        activeTrials.clear()
        plugin.logger.info("已清理所有正在进行的医术试炼实例。")
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        // 屏蔽副手交互，防止右键时主副手触发两次
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val item = player.inventory.itemInMainHand

        if (!item.hasItemMeta()) return
        val resId = getTrialItemId(item) ?: return

        if (resId == "shanshenmiao_test" || resId == "wangyuanwai_test" || resId == "wenquankezhan_test" || resId == "zhuanyuanshangxian_test" || resId == "huzhenshangren_test" || resId == "chendafu_test" || resId == "yuzhu_test") {
            event.isCancelled = true
            tryStartTrial(player, item, resId)
        }
    }

    private fun tryStartTrial(player: org.bukkit.entity.Player, item: ItemStack, resId: String) {
        if (activeTrials.containsKey(player.uniqueId)) return

        val trialId = when (resId) {
            "shanshenmiao_test" -> "shanshenmiao"
            "wangyuanwai_test" -> "wangyuanwai"
            "wenquankezhan_test" -> "wenquankezhan"
            "zhuanyuanshangxian_test" -> "zhuanyuanshangxian"
            "huzhenshangren_test" -> "huzhenshangren"
            "chendafu_test" -> "chendafu"
            "yuzhu_test" -> "yuzhu"
            else -> return
        }

        val data = plugin.playerManager.getPlayerData(player)
        if (data == null || data.job != 3) {
            if (resId == "wangyuanwai_test") {
                player.sendMessage("§c你不是医师，不能开启医术试炼")
            } else {
                player.sendMessage("§c你不是医师，无法进入医术试炼！")
            }
            return
        }

        // 这里的 completedMedicalTrials 是我们上一步在 PlayerData 里加的字段
        if (data.completedMedicalTrials.contains(trialId)) {
            player.sendMessage("§c你已经完成了这个医术试炼！")
            return
        }

        if (resId == "shanshenmiao_test") {
            // 【新增】坐标检查：必须在 835 40 104 和 834 40 105 的 2x2 黄色地毯区域内
            val loc = player.location
            val isOnCarpet = (loc.blockX == 834 || loc.blockX == 835) &&
                    (loc.blockZ == 104 || loc.blockZ == 105) &&
                    loc.blockY == 40

            if (!isOnCarpet) {
                player.sendMessage("§c你离山神庙太远了，靠近一些再传送吧！")
                return
            }
        } else if (resId == "wangyuanwai_test") {
            val center = org.bukkit.Location(player.world, -283.0, 55.0, 395.0)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离王员外太远了，离他近点试试吧！")
                return
            }
        } else if (resId == "wenquankezhan_test") {
            val center = org.bukkit.Location(player.world, -461.0, 95.0, 359.0)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离温泉客栈老板太远了，离他近点试试吧！")
                return
            }
        } else if (resId == "zhuanyuanshangxian_test") {
            val center = org.bukkit.Location(player.world, 121.0, 49.0, 805.0)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离篆元上仙太远了，离他近点试试吧！")
                return
            }
        } else if (resId == "huzhenshangren_test") {
            val center = org.bukkit.Location(player.world, -396.42, 112.00, 150.72)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离虎镇商人太远了，离他近点试试吧！")
                return
            }
        } else if (resId == "chendafu_test") {
            val center = org.bukkit.Location(player.world, -118.56, 46.00, 139.60)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离陈大夫太远了，离他近点试试吧！")
                return
            }
        } else if (resId == "yuzhu_test") {
            val center = org.bukkit.Location(player.world, -318.53, 115.00, -423.51)
            if (player.location.world != center.world || player.location.distanceSquared(center) > 3 * 3) {
                player.sendMessage("§c你离雨竹太远了，离她近点试试吧！")
                return
            }
        }

        // 【修改】全局单人试炼检查：只要 activeTrials 不为空，说明有人在里面
        if (activeTrials.isNotEmpty()) {
            player.sendMessage("§c已有医术试炼正在进行，还是等会再来吧……")
            return
        }

        // 原先的 containsKey 检查已经删掉，因为上面 isNotEmpty 已经拦截了并发

        item.amount -= 1
        // 启动试炼实例
        val instance = when (resId) {
            "shanshenmiao_test" -> com.hjh_database.medical.impl.ShanShenMiaoTrial(plugin, player)
            "wangyuanwai_test" -> com.hjh_database.medical.impl.WangYuanWaiTrial(plugin, player)
            "wenquankezhan_test" -> com.hjh_database.medical.impl.WenQuanKeZhanTrial(plugin, player)
            "zhuanyuanshangxian_test" -> com.hjh_database.medical.impl.ZhuanYuanShangXianTrial(plugin, player)
            "huzhenshangren_test" -> com.hjh_database.medical.impl.HuZhenShangRenTrial(plugin, player)
            "chendafu_test" -> com.hjh_database.medical.impl.ChenDaFuTrial(plugin, player)
            "yuzhu_test" -> com.hjh_database.medical.impl.YuZhuTrial(plugin, player)
            // 以后你有新的试炼，比如叫 xinmiao_test，只需在这里加一行：
            // "xinmiao_test" -> com.hjh_database.medical.impl.XinMiaoTrial(plugin, player)
            else -> return
        }
        activeTrials[player.uniqueId] = instance
        instance.start()
    }

    private fun getTrialItemId(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        val key = org.bukkit.NamespacedKey(plugin, "resource_id")
        val resId = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
        if (resId == "shanshenmiao_test" || resId == "wangyuanwai_test" || resId == "wenquankezhan_test" || resId == "zhuanyuanshangxian_test" || resId == "huzhenshangren_test" || resId == "chendafu_test" || resId == "yuzhu_test") return resId

        val plainName = if (meta.hasDisplayName()) ChatColor.stripColor(meta.displayName) else null
        return when (plainName) {
            "山神庙-传送卷轴[医术试炼]" -> "shanshenmiao_test"
            "王员外的[医术试炼]" -> "wangyuanwai_test"
            "温泉客栈老板的[医术试炼]" -> "wenquankezhan_test"
            "篆元上仙的[医术试炼]" -> "zhuanyuanshangxian_test"
            "虎镇商人的[医术试炼]" -> "huzhenshangren_test"
            "陈大夫的[医术试炼]" -> "chendafu_test"
            "雨竹的[医术试炼]" -> "yuzhu_test"
            else -> null
        }
    }

    @EventHandler
    fun onEntityTarget(event: EntityTargetEvent) {
        val entity = event.entity
        val target = event.target ?: return // 获取目标，如果目标为空直接放行

        // 【修改】简化仇恨逻辑：只要有人在试炼中，且怪物在试炼区域内，就不允许它盯着玩家看
        if (activeTrials.isNotEmpty()) {
            val center = org.bukkit.Location(entity.world, 846.0, 41.0, 102.0)
            if (entity.location.world == center.world && entity.location.distanceSquared(center) < 20 * 20) {
                // 如果怪物企图将仇恨锁定到任何玩家身上，立刻取消
                if (target is org.bukkit.entity.Player) {
                    event.isCancelled = true
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        activeTrials[event.entity.uniqueId]?.fail()
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        activeTrials[event.player.uniqueId]?.fail()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (instance in activeTrials.values) {
                // 如果需要补全隐藏逻辑可以写在这里
            }
        }, 10L)
    }
}
