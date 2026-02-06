package com.hjh_database.alchemy.listener

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.ActivePill
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.gui.AlchemyAdminGui
import com.hjh_database.alchemy.gui.AlchemyAdminListGui // 导入新 GUI
import com.hjh_database.alchemy.gui.AlchemyPlayerGui
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

class AlchemyListener(private val plugin: Hjh_database) : Listener {

    private val cauldronKey = NamespacedKey(plugin, "hjh_alchemy_cauldron")
    private val alchemyIdKey = NamespacedKey(plugin, "hjh_alchemy_id")
    private val alchemyTierKey = NamespacedKey(plugin, "hjh_alchemy_tier")
    private val presetColors = listOf("#FF5555", "#AA0000", "#5555FF", "#0000AA", "#00AA00", "#55FF55", "#FFAA00", "#FFFF55", "#FF55FF", "#000000")

    // ... onPlayerConsume 保持不变 ...
    @EventHandler
    fun onPlayerConsume(event: PlayerInteractEvent) {
        if (event.hand == EquipmentSlot.OFF_HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (!item.hasItemMeta()) return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        if (!pdc.has(alchemyIdKey, PersistentDataType.STRING)) return

        event.isCancelled = true
        val player = event.player
        val effectId = pdc.get(alchemyIdKey, PersistentDataType.STRING) ?: return
        val tierName = pdc.get(alchemyTierKey, PersistentDataType.STRING) ?: "LOW"
        val tier = try { AlchemyTier.valueOf(tierName) } catch (e: Exception) { AlchemyTier.LOW }
        val playerData = plugin.playerManager.getPlayerData(player) ?: return
        val effect = plugin.alchemyManager.getEffect(effectId)
        val recipe = plugin.alchemyManager.recipes[effectId]

        if (effect == null || recipe == null) return
        if (playerData.isSick()) {
            val leftTime = (playerData.pillSicknessEnd - System.currentTimeMillis()) / 1000.0
            player.sendMessage("§c[药毒] 身体还在排斥药力，无法继续服用！(剩余 %.1f秒)".format(leftTime))
            return
        }

        item.amount -= 1
        player.playSound(player.location, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1f, 1f)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 2f)
        val duration = effect.onConsume(player, playerData, tier)
        if (duration > 0) {
            val pill = ActivePill(effectId, tier, duration)
            playerData.activePills.add(pill)
        }
        val sicknessMillis = recipe.sicknessTime * 1000L
        playerData.pillSicknessEnd = System.currentTimeMillis() + sicknessMillis
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.CAULDRON) return

        val player = event.player
        val item = event.item

        // 1. 管理员入口：打开列表界面
        if (player.isOp && item?.type == Material.WOODEN_HOE) {
            event.isCancelled = true
            // 【修改点】打开列表 GUI
            AlchemyAdminListGui(plugin, player).open()
            return
        }

        // 2. 玩家入口
        if (plugin.alchemyManager.activeSessions.containsKey(player.uniqueId)) {
            player.sendMessage("§c你正在炼药中，请勿分心！")
            return
        }
        event.isCancelled = true
        AlchemyPlayerGui(plugin, player, block.location).open()
    }

    @EventHandler
    fun onInventoryClick(event: org.bukkit.event.inventory.InventoryClickEvent) {
        val holder = event.inventory.holder ?: return
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val slot = event.rawSlot
        val clickedItem = event.currentItem

        // ==========================
        // 1. 管理员配方列表 (AlchemyAdminListGui) 【新增】
        // ==========================
        if (holder is AlchemyAdminListGui) {
            if (event.clickedInventory != event.view.topInventory) return
            event.isCancelled = true // 列表禁止拿取

            if (slot == 49) {
                // 点击 [+] 新增配方
                // 生成一个随机 ID，比如 custom_12345
                val newId = "custom_${System.currentTimeMillis() % 10000}"
                val newRecipe = AlchemyRecipe(newId)
                newRecipe.displayName = "自定义丹药"

                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
                // 打开编辑器，传入新对象
                AlchemyAdminGui(plugin, player, newRecipe).open()
                return
            }

            // 点击配方图标
            val recipeId = holder.slotMap[slot]
            if (recipeId != null) {
                val recipe = plugin.alchemyManager.recipes[recipeId]
                if (recipe != null) {
                    if (event.isLeftClick) {
                        // 左键：编辑
                        player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
                        AlchemyAdminGui(plugin, player, recipe).open()
                    } else if (event.isRightClick) {
                        // 右键：删除
                        plugin.alchemyManager.recipes.remove(recipeId)
                        plugin.alchemyManager.saveRecipes() // 保存删除操作
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1f)
                        player.sendMessage("§c已删除配方: $recipeId")
                        // 重新打开列表刷新
                        AlchemyAdminListGui(plugin, player).open()
                    }
                }
            }
        }

        // ==========================
        // 2. 管理员编辑器 (AlchemyAdminGui)
        // ==========================
        else if (holder is AlchemyAdminGui) {
            if (event.clickedInventory != event.view.topInventory) return
            if (clickedItem != null && clickedItem.type.name.contains("STAINED_GLASS_PANE")) {
                event.isCancelled = true
            }

            if (slot >= 45) {
                event.isCancelled = true
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)

                when (slot) {
                    45 -> { // 医师
                        holder.editingRecipe.onlyDoctor = !holder.editingRecipe.onlyDoctor
                        holder.updateButtons()
                    }
                    46 -> { // 时间
                        val change = if (event.isLeftClick) 5 else -5
                        var newTime = holder.editingRecipe.sicknessTime + change
                        if (newTime < 0) newTime = 0
                        holder.editingRecipe.sicknessTime = newTime
                        holder.updateButtons()
                    }
                    47 -> { // 颜色
                        val currentHex = holder.editingRecipe.colorHex
                        val index = presetColors.indexOf(currentHex)
                        val nextIndex = if (index == -1) 0 else (index + 1) % presetColors.size
                        holder.editingRecipe.colorHex = presetColors[nextIndex]
                        holder.updateButtons()
                    }
                    48 -> { // 等级
                        val change = if (event.isLeftClick) 1 else -1
                        var newLv = holder.editingRecipe.requiredLevel + change
                        if (newLv < 0) newLv = 0
                        holder.editingRecipe.requiredLevel = newLv
                        holder.updateButtons()
                    }
                    49 -> { // 保存
                        holder.saveFromGui()
                    }
                    50 -> { // 设置经验
                        val recipe = holder.editingRecipe
                        if (event.isLeftClick) {
                            recipe.baseExp += 5
                        } else if (event.isRightClick) {
                            recipe.baseExp -= 5
                            if (recipe.baseExp < 0) recipe.baseExp = 0
                        }
                        // 刷新按钮显示
                        val item = event.currentItem
                        val meta = item?.itemMeta
                        if (meta != null) {
                            meta.lore = listOf(
                                "§7当前基础经验: §f${recipe.baseExp}",
                                "§7(初级炼制获得的经验)",
                                "",
                                "§7中级炼制: §f${recipe.baseExp + 10}",
                                "§7高级炼制: §f${recipe.baseExp + 20}",
                                "",
                                "§a左键: +5  §c右键: -5"
                            )
                            item.itemMeta = meta
                        }
                    }
                    53 -> { // 关闭
                        // 返回列表界面，而不是完全关闭
                        AlchemyAdminListGui(plugin, player).open()
                    }
                }
            }
        }

        // ... (PlayerGui 和 TierSelectHolder 的逻辑保持不变) ...
        else if (holder is AlchemyPlayerGui) {
            event.isCancelled = true
            if (event.clickedInventory == event.view.topInventory) {
                val recipe = holder.displayRecipes[slot]
                if (recipe != null) {
                    player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f)
                    holder.openTierSelect(recipe)
                }
            }
        }
        else if (holder is AlchemyPlayerGui.TierSelectHolder) {
            event.isCancelled = true
            if (event.clickedInventory == event.view.topInventory) {
                val tier = when (slot) {
                    11 -> AlchemyTier.LOW
                    13 -> AlchemyTier.MID
                    15 -> AlchemyTier.HIGH
                    else -> null
                }
                if (tier != null) {
                    val recipe = holder.recipe
                    val config = recipe.tierData[tier]
                    if (config != null) {
                        val playerData = plugin.playerManager.getPlayerData(player) ?: return
                        val reqLevel = recipe.requiredLevel + tier.levelOffset
                        if (playerData.alchemyLevel >= reqLevel) {
                            player.closeInventory()
                            // 开始炼药
                            plugin.alchemyManager.startSession(player, holder.cauldronLoc, recipe, tier)
                        } else {
                            player.sendMessage("§c等级不足！")
                        }
                    }
                }
            }
        }
    }
}