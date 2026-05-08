package com.hjh_database.accessory.skill

import com.hjh_database.Hjh_database
import com.hjh_database.data.PlayerData
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

// 抽象类：所有具体箭袋都要继承它
abstract class BaseQuiver(protected val plugin: Hjh_database) {
    protected val arrowKey = NamespacedKey(plugin, "quiver_arrows")

    // 【核心抽象方法】子类必须实现这个方法来写自己复杂的特效逻辑
    abstract fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData)

    // 1. 处理饰品栏的存取 (通用逻辑)
    open fun handleQuiverClick(player: Player, quiverItem: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        val meta = quiverItem.itemMeta ?: return false
        var currentStored = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        // 修复2：使用 yml 配置的最大值，不再写死 2048
        val maxArrows = crystalData.maxArrows

        if (isExtract) {
            if (currentStored <= 0) {
                player.sendMessage("§c箭袋空空如也！")
                return false
            }
            val leftover = player.inventory.addItem(ItemStack(Material.ARROW, currentStored))
            val returnedToQuiver = leftover.values.sumOf { it.amount }
            val extracted = currentStored - returnedToQuiver
            currentStored = returnedToQuiver
            player.sendMessage("§a取出 $extracted 支箭！剩余: $currentStored")
        } else {
            // ============================================
            // 修复1：存入逻辑（兜底保留配置的箭矢在背包里，默认32支）
            // ============================================
            val retainAmount = crystalData.replenishAmount // 读取你配置的单次补充量（比如32）
            val totalArrowsInInv = player.inventory.contents.filter { it?.type == Material.ARROW }.sumOf { it?.amount ?: 0 }

            if (totalArrowsInInv <= retainAmount) {
                player.sendMessage("§c背包中的箭矢不足 ${retainAmount} 支，无法存入更多！")
            } else {
                var arrowsToStore = 0
                var allowedToTake = totalArrowsInInv - retainAmount // 实际允许存入的数量

                for (i in 0 until player.inventory.size) {
                    val item = player.inventory.getItem(i)
                    if (item != null && item.type == Material.ARROW) {
                        val spaceLeft = maxArrows - currentStored
                        if (spaceLeft <= 0) break // 箭袋满了
                        if (allowedToTake <= 0) break // 已经保留了足够的箭矢

                        // 本次从这叠箭里拿走的数量
                        val taking = minOf(item.amount, allowedToTake, spaceLeft)

                        arrowsToStore += taking
                        currentStored += taking
                        allowedToTake -= taking

                        if (taking == item.amount) {
                            player.inventory.setItem(i, null)
                        } else {
                            item.amount -= taking
                        }
                    }
                }
                if (arrowsToStore > 0) player.sendMessage("§a存入 $arrowsToStore 支箭！目前容量: $currentStored/$maxArrows")
            }
        }

        // 保存数量，并【更新占位符】
        meta.persistentDataContainer.set(arrowKey, PersistentDataType.INTEGER, currentStored)
        updateLore(meta, currentStored, crystalData)

        quiverItem.itemMeta = meta
        plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, player.openInventory.topInventory)
        return true
    }

    // 2. 射击后的自动补充 (通用逻辑)
    fun processReplenish(player: Player, item: ItemStack, crystalData: CrystalData) {
        val meta = item.itemMeta ?: return
        var currentStored = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        if (currentStored <= 0) return

        val playerArrows = player.inventory.contents.filter { it?.type == Material.ARROW }.sumOf { it?.amount ?: 0 }

        val threshold = crystalData.replenishThreshold // 门槛（如16）
        val amount = crystalData.replenishAmount       // 补充量（如32）

        if (playerArrows < threshold) {
            val taking = minOf(amount, currentStored)
            currentStored -= taking
            val leftover = player.inventory.addItem(ItemStack(Material.ARROW, taking))

            val actualTaken = taking - leftover.values.sumOf { it.amount }
            currentStored += leftover.values.sumOf { it.amount } // 放不下的退回

            // ============================================
            // 修复1：发送自动取出的提示信息
            // ============================================
            if (actualTaken > 0) {
                player.sendMessage("§a已自动取出 ${actualTaken} 支箭矢，当前箭袋箭矢数量：$currentStored/${crystalData.maxArrows}")
            }

            // 保存数量，并【更新占位符】
            meta.persistentDataContainer.set(arrowKey, PersistentDataType.INTEGER, currentStored)
            updateLore(meta, currentStored, crystalData)
            item.itemMeta = meta

            // 保存回饰品栏
            val contents = plugin.accessoryManager.getAccessoryContents(player) ?: return
            contents[crystalData.activateSlot] = item
            plugin.accessoryManager.saveAccessoryContents(player, contents)
        }
    }

    // ==========================================
    // 修复2：【占位符修改核心点】
    // ==========================================
    protected fun updateLore(meta: org.bukkit.inventory.meta.ItemMeta, currentStored: Int, crystalData: CrystalData) {
        // 读取 yml 原本的 lore，把【所有】的占位符都动态替换掉！
        val newLore = crystalData.lore.map { line ->
            line.replace("{arrows}", currentStored.toString())
                .replace("{max_arrows}", crystalData.maxArrows.toString())
                .replace("{threshold}", crystalData.replenishThreshold.toString())
                .replace("{amount}", crystalData.replenishAmount.toString())
        }
        meta.lore = newLore
    }
}