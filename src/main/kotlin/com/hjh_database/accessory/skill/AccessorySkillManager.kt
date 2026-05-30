package com.hjh_database.accessory

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.accessory.skill.medical.TaolizhiSkill
import com.hjh_database.accessory.skill.quiver.*
import com.hjh_database.accessory.skill.shield.*
import com.hjh_database.accessory.skill.warlock.BaseRefluxSkill
import com.hjh_database.accessory.skill.warlock.HuiliuyiSkill
import com.hjh_database.skill.medical.spell.MedicalHealEvent
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot // 【新增导入】用于判断主副手
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class AccessorySkillManager(private val plugin: Hjh_database) : Listener {
    private val crystalKey = NamespacedKey(plugin, "crystal_id")

    // 【统一注册表】
    private val skills = mapOf<String, BaseAccessorySkill>(
        "jiandai" to JiandaiSkill(plugin),
        "ranhuojiandai" to RanhuoJiandaiSkill(plugin),
        "qingshidunpai" to QingshidunpaiSkill(plugin),
        "huiliuyi" to HuiliuyiSkill(plugin),
        "taolizhi" to TaolizhiSkill(plugin)
    )

    /**
     * 【核心修改：通用激活状态拦截】
     * 监听玩家右键点击，如果盾牌未处于配置的激活槽位，或玩家职业/等级不符，直接取消举盾！
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onShieldInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        // 1. 检查是否是盾牌
        if (item.type != Material.SHIELD) return

        // 2. 检查是否是“饰品系统”的盾牌（通过 NBT 标签判断）
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(crystalKey, PersistentDataType.STRING)) return

        // 3. 检查右键行为（举盾）
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val pData = plugin.playerManager.getPlayerData(player) ?: return
            val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: return
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return

            // 获取玩家当前触发右键所在的槽位标识
            val slotKey = if (event.hand == EquipmentSlot.OFF_HAND) {
                "offhand"
            } else {
                // 如果是用主手举盾，槽位就是快捷栏当前选中的格子 (hotbar_0 到 hotbar_8)
                "hotbar_${player.inventory.heldItemSlot}"
            }

            // 4. 【核心逻辑】判断饰品是否在该槽位激活，且玩家符合激活要求
            if (!cData.activations.containsKey(slotKey) || !cData.isActivated(pData)) {
                player.sendMessage("§c⚠ 该盾牌未处于激活状态，你无法举起它！")

                // 取消事件，阻止玩家进入“举盾”状态
                event.isCancelled = true
                // 同时强制设置使用结果为 DENY，防止原版的动画和状态更新
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            }
        }
    }

    // 提供给 AccessoryManager 调用的统一路由（处理 Shift+右键 的 UI 技能）
    fun routeAccessoryClick(player: Player, item: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        val targetId = crystalData.skillId ?: crystalData.id
        val skillClass = skills[targetId] ?: return false
        return skillClass.handleShiftClick(player, item, isExtract, crystalData)
    }

    // 获取玩家身上所有生效槽位的物品 (饰品栏 + 副手 + 快捷栏)
    private fun getActiveAccessories(player: Player): List<Pair<ItemStack, String>> {
        val list = mutableListOf<Pair<ItemStack, String>>()
        val contents = plugin.accessoryManager.getAccessoryContents(player)
        if (contents != null) {
            for (i in contents.indices) {
                contents[i]?.let { list.add(it to "accessory_$i") }
            }
        }
        val offHand = player.inventory.itemInOffHand
        if (offHand.type != Material.AIR) list.add(offHand to "offhand")
        for (i in 0..8) {
            player.inventory.getItem(i)?.let { list.add(it to "hotbar_$i") }
        }
        return list
    }

    @EventHandler
    fun onShoot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val activeItems = getActiveAccessories(player)

        for ((item, slotKey) in activeItems) {
            val meta = item.itemMeta ?: continue
            val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue

            if (cData.activations.containsKey(slotKey) && cData.isActivated(data)) {
                val targetId = cData.skillId ?: cData.id
                val skillClass = skills[targetId] as? BaseQuiverSkill ?: continue

                skillClass.onShootEffect(event, player, data)

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    skillClass.processReplenish(player, item, cData, slotKey)
                }, 1L)
                break
            }
        }
    }

    @EventHandler
    fun onDamageBlock(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        if (!player.isBlocking) return
        if (player.hasCooldown(Material.SHIELD)) return
        val offHandItem = player.inventory.itemInOffHand
        if (offHandItem.type == Material.SHIELD && offHandItem.hasItemMeta()) {
            val meta = offHandItem.itemMeta
            val cid = meta?.persistentDataContainer?.get(crystalKey, PersistentDataType.STRING) ?: return

            val pData = plugin.playerManager.getPlayerData(player) ?: return
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return
            // 统一检查是否激活且在副手生效槽位
            if (!cData.isActivated(pData) || !cData.activations.containsKey("offhand")) return
            val targetId = cData.skillId ?: cData.id
            val skillClass = skills[targetId]
            // ============================================
            // 【核心重构逻辑】解耦硬编码判断
            // 只要它是盾牌基类的实例，就直接丢给基类去处理通用格挡动作
            // ============================================
            if (skillClass is BaseShieldSkill) {
                skillClass.handleShieldBlock(event, player, cData)
            }
        }
    }

    @EventHandler
    fun onMedicalHeal(event: MedicalHealEvent) {
        if (event.overflowHeal <= 0.0) return
        val player = event.caster
        val data = plugin.playerManager.getPlayerData(player) ?: return

        fun processItem(item: ItemStack?, slotKey: String): Boolean {
            if (item == null || item.type == Material.AIR) return false
            val meta = item.itemMeta ?: return false
            val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: return false
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: return false
            if (!cData.activations.containsKey(slotKey) || !cData.isActivated(data)) return false

            val targetId = cData.skillId ?: cData.id
            val skillClass = skills[targetId] ?: return false
            return skillClass.onMedicalHeal(event, item, slotKey, cData)
        }

        if (player.openInventory.title == plugin.accessoryManager.INVENTORY_TITLE) {
            val topInv = player.openInventory.topInventory
            for (i in 0 until topInv.size) {
                val item = topInv.getItem(i)
                if (processItem(item, "accessory_$i")) {
                    topInv.setItem(i, item)
                }
            }
        } else {
            val contents = plugin.accessoryManager.getAccessoryContents(player)
            if (contents != null) {
                var changed = false
                for (i in contents.indices) {
                    val item = contents[i]
                    if (processItem(item, "accessory_$i")) {
                        contents[i] = item
                        changed = true
                    }
                }
                if (changed) {
                    plugin.accessoryManager.saveAccessoryContents(player, contents)
                }
            }
        }

        val offHand = player.inventory.itemInOffHand
        if (processItem(offHand, "offhand")) {
            player.inventory.setItemInOffHand(offHand)
        }

        for (i in 0..8) {
            val item = player.inventory.getItem(i)
            if (processItem(item, "hotbar_$i")) {
                player.inventory.setItem(i, item)
            }
        }
    }

    /**
     * 供元素技能调用：检查玩家当前是否激活了回流类饰品，如果激活了，返回它的具体实例和数据
     */
    fun getActiveRefluxData(player: Player): Pair<BaseRefluxSkill, CrystalData>? {
        // 如果玩家根本没开启状态，直接返回 null，节省性能
        if (!BaseRefluxSkill.isRefluxActive(player)) return null

        val pData = plugin.playerManager.getPlayerData(player) ?: return null
        val activeItems = getActiveAccessories(player) // 使用你之前写好的获取生效饰品的方法

        for ((item, slotKey) in activeItems) {
            val meta = item.itemMeta ?: continue
            val cid = meta.persistentDataContainer.get(crystalKey, PersistentDataType.STRING) ?: continue
            val cData = plugin.playerManager.crystalManager.loadedCrystals[cid] ?: continue

            // 检查这个饰品是否在激活位置，并且满足玩家等级/职业要求
            if (cData.activations.containsKey(slotKey) && cData.isActivated(pData)) {
                val targetId = cData.skillId ?: cData.id
                val skillClass = skills[targetId]

                // 如果这个饰品是回流类饰品，就把它的实例和数据返回回去！
                if (skillClass is BaseRefluxSkill) {
                    return Pair(skillClass, cData)
                }
            }
        }
        return null
    }
}
