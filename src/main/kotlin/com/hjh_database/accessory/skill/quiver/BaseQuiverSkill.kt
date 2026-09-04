// 路径: com.hjh_database.accessory.skill.quiver.BaseQuiverSkill.kt
package com.hjh_database.accessory.skill.quiver

import com.hjh_database.Hjh_database
import com.hjh_database.accessory.skill.core.AccessoryHudValueKind
import com.hjh_database.accessory.skill.core.AccessorySkillHudState
import com.hjh_database.accessory.skill.core.BaseAccessorySkill
import com.hjh_database.data.PlayerData
import com.hjh_database.weapon.CrystalData
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

abstract class BaseQuiverSkill(plugin: Hjh_database) : BaseAccessorySkill(plugin) {
    protected val arrowKey = NamespacedKey(plugin, "quiver_arrows")

    abstract fun onShootEffect(event: EntityShootBowEvent, player: Player, data: PlayerData)

    override fun getHudState(player: Player, item: ItemStack, crystalData: CrystalData): AccessorySkillHudState {
        val arrows = item.itemMeta?.persistentDataContainer
            ?.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        return super.getHudState(player, item, crystalData).copy(
            valueKind = AccessoryHudValueKind.ARROWS,
            currentValue = arrows.coerceAtLeast(0),
            maxValue = crystalData.maxArrows.coerceAtLeast(0)
        )
    }

    override fun handleShiftClick(player: Player, quiverItem: ItemStack, isExtract: Boolean, crystalData: CrystalData): Boolean {
        val meta = quiverItem.itemMeta ?: return false
        var currentStored = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
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
            val retainAmount = crystalData.replenishAmount
            val totalArrowsInInv = player.inventory.contents.filter { it?.type == Material.ARROW }.sumOf { it?.amount ?: 0 }

            if (totalArrowsInInv <= retainAmount) {
                player.sendMessage("§c背包中的箭矢不足 ${retainAmount} 支，无法存入更多！")
            } else {
                var arrowsToStore = 0
                var allowedToTake = totalArrowsInInv - retainAmount

                for (i in 0 until player.inventory.size) {
                    val item = player.inventory.getItem(i)
                    if (item != null && item.type == Material.ARROW) {
                        val spaceLeft = maxArrows - currentStored
                        if (spaceLeft <= 0) break
                        if (allowedToTake <= 0) break

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

        meta.persistentDataContainer.set(arrowKey, PersistentDataType.INTEGER, currentStored)
        updateLore(meta, currentStored, crystalData)
        quiverItem.itemMeta = meta
        plugin.playerManager.crystalManager.refreshOpenAccessoryMenu(player, player.openInventory.topInventory)
        return true
    }

    fun processReplenish(player: Player, item: ItemStack, crystalData: CrystalData, slotKey: String) {
        val meta = item.itemMeta ?: return
        var currentStored = meta.persistentDataContainer.get(arrowKey, PersistentDataType.INTEGER) ?: 0
        if (currentStored <= 0) return

        val playerArrows = player.inventory.contents.filter { it?.type == Material.ARROW }.sumOf { it?.amount ?: 0 }
        val threshold = crystalData.replenishThreshold
        val amount = crystalData.replenishAmount

        if (playerArrows < threshold) {
            val taking = minOf(amount, currentStored)
            currentStored -= taking
            val leftover = player.inventory.addItem(ItemStack(Material.ARROW, taking))

            val actualTaken = taking - leftover.values.sumOf { it.amount }
            currentStored += leftover.values.sumOf { it.amount }

            if (actualTaken > 0) {
                player.sendMessage("§a已自动取出 ${actualTaken} 支箭矢，当前箭袋箭矢数量：$currentStored/${crystalData.maxArrows}")
            }

            meta.persistentDataContainer.set(arrowKey, PersistentDataType.INTEGER, currentStored)
            updateLore(meta, currentStored, crystalData)
            item.itemMeta = meta

            // 写回数据。因为增加了多槽位，所以需要判断物品在哪里
            if (slotKey.startsWith("accessory_")) {
                val index = slotKey.removePrefix("accessory_").toIntOrNull() ?: return
                val contents = plugin.accessoryManager.getAccessoryContents(player) ?: return
                contents[index] = item
                plugin.accessoryManager.saveAccessoryContents(player, contents)
            } else if (slotKey == "offhand") {
                player.inventory.setItemInOffHand(item)
            } else if (slotKey.startsWith("hotbar_")) {
                val index = slotKey.removePrefix("hotbar_").toIntOrNull() ?: return
                player.inventory.setItem(index, item)
            }
        }
    }

    protected fun updateLore(meta: org.bukkit.inventory.meta.ItemMeta, currentStored: Int, crystalData: CrystalData) {
        val newLore = crystalData.lore.map { line ->
            line.replace("{arrows}", currentStored.toString())
                .replace("{max_arrows}", crystalData.maxArrows.toString())
                .replace("{threshold}", crystalData.replenishThreshold.toString())
                .replace("{amount}", crystalData.replenishAmount.toString())
        }
        meta.lore = newLore
    }
}
