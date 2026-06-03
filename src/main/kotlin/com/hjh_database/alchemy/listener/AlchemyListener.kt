package com.hjh_database.alchemy.listener

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.ActivePill
import com.hjh_database.alchemy.data.AlchemyRecipe
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.alchemy.gui.AlchemyAdminGui
import com.hjh_database.alchemy.gui.AlchemyAdminListGui // 导入新 GUI
import com.hjh_database.alchemy.gui.AlchemyPlayerGui
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import java.io.File

class AlchemyListener(private val plugin: Hjh_database) : Listener {

    private val cauldronKey = NamespacedKey(plugin, "hjh_alchemy_cauldron")
    private val alchemyIdKey = NamespacedKey(plugin, "hjh_alchemy_id")
    private val alchemyTierKey = NamespacedKey(plugin, "hjh_alchemy_tier")
    private val resourceIdKey = NamespacedKey(plugin, "resource_id") // 【新增】兼容资源管理器自带的 ID 标签
    private val pillCooldownKey = NamespacedKey(plugin, "alchemy_pill_sickness")
    private val presetColors = listOf("#FF5555", "#AA0000", "#5555FF", "#0000AA", "#00AA00", "#55FF55", "#FFAA00", "#FFFF55", "#FF55FF", "#000000")
    private val cauldronDataFile = File(plugin.dataFolder, "alchemy_cauldrons.yml")
    private val registeredCauldrons = mutableSetOf<String>()

    init {
        loadRegisteredCauldrons()
    }

    @EventHandler
    fun onCauldronPlace(event: BlockPlaceEvent) {
        if (event.blockPlaced.type != Material.CAULDRON) return
        val meta = event.itemInHand.itemMeta ?: return
        if (!meta.persistentDataContainer.has(cauldronKey, PersistentDataType.INTEGER)) return

        registeredCauldrons.add(locationKey(event.blockPlaced.location))
        saveRegisteredCauldrons()
    }

    @EventHandler
    fun onCauldronBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.CAULDRON) return
        if (registeredCauldrons.remove(locationKey(event.block.location))) {
            saveRegisteredCauldrons()
        }
    }


    @EventHandler
    fun onPlayerConsume(event: PlayerInteractEvent) {
        if (event.hand == EquipmentSlot.OFF_HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (!item.hasItemMeta()) return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer

        // 【修改】优先读取 hjh_alchemy_id，如果找不到（比如 admin get 拿到的），则读取 resource_id 回底
        var effectId = pdc.get(alchemyIdKey, PersistentDataType.STRING)
        if (effectId == null) {
            effectId = pdc.get(resourceIdKey, PersistentDataType.STRING)
        }
        if (effectId == null) return

        val effect = plugin.alchemyManager.getEffect(effectId) ?: return

        // 成功识别为丹药，立刻拦截原版动作，防止玩家进入“喝水动画”
        event.isCancelled = true
        val player = event.player

        // 获取品阶，没有被专门定义的统统按 LOW（初级）处理
        val tierName = pdc.get(alchemyTierKey, PersistentDataType.STRING) ?: "LOW"
        val tier = try { AlchemyTier.valueOf(tierName) } catch (e: Exception) { AlchemyTier.LOW }
        val playerData = plugin.playerManager.getPlayerData(player) ?: return

        if (playerData.isSick()) {
            val leftTime = (playerData.pillSicknessEnd - System.currentTimeMillis()) / 1000.0
            player.sendMessage("§c[药毒] 身体还在排斥药力，无法继续服用！(剩余 %.1f秒)".format(leftTime))
            return
        }

        // 【修改点】从玩家吃下的物品本身获取对应的药毒时间
        val consumeResourceId = pdc.get(resourceIdKey, PersistentDataType.STRING)
        val resourceData = if (consumeResourceId != null) plugin.resourceManager.getLocalResource(consumeResourceId) else null

        // 没写默认给 10 秒
        val sicknessTime = resourceData?.sicknessTime ?: 10
        val sicknessMillis = sicknessTime * 1000L

        val cooldownTicks = (sicknessMillis / 50L).toInt()
        applyPillCooldownComponent(item)

        // 【修改】1.21.3 中推荐使用 subtract()，更稳定地扣除物品数量
        item.subtract(1)
        player.inventory.setItemInMainHand(if (item.amount > 0) item else null)
        setPillSicknessCooldown(player, item.type, cooldownTicks)

        player.playSound(player.location, org.bukkit.Sound.ENTITY_GENERIC_DRINK, 1f, 1f)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 2f)

        val duration = effect.onConsume(player, playerData, tier)
        if (duration > 0) {
            val pill = ActivePill(effectId, tier, duration)
            playerData.activePills.add(pill)
        }
        playerData.pillSicknessEnd = System.currentTimeMillis() + sicknessMillis
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.CAULDRON) return
        if (!registeredCauldrons.contains(locationKey(block.location))) return

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
            // 如果玩家点击的是自己的背包 (bottomInventory)，直接允许操作！
            if (event.clickedInventory == event.view.bottomInventory) {
                event.isCancelled = false
                return
            }
            // 锁定上半部分的 GUI
            event.isCancelled = true
            // 允许放物品的格子 (0-4, 8, 9-13, 17, 18-22, 26)
            val allowedSlots = listOf(0,1,2,3,4,8, 9,10,11,12,13,17, 18,19,20,21,22,26)
            if (event.clickedInventory == event.view.topInventory && allowedSlots.contains(slot)) {
                event.isCancelled = false // 放开这几个格子
            }
            // 监听保存按钮
            if (slot == 53 && event.clickedInventory == event.view.topInventory) {
                event.isCancelled = true
                holder.saveFromGui()
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

                        // 【修改点】通过成品物品读取需要的冶药法等级
                        val resourceId = config.result.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
                        val resourceData = if (resourceId != null) plugin.resourceManager.getLocalResource(resourceId) else null
                        val reqLevel = resourceData?.reqLevel ?: 1

                        if (tier == AlchemyTier.HIGH && playerData.job != 3) {
                            player.sendMessage("§c高级丹药仅医师可以炼制。")
                            return
                        }

                        // 判断玩家等级是否足够
                        if (playerData.alchemyLevel >= reqLevel) {
                            player.closeInventory()
                            // 开始炼药
                            plugin.alchemyManager.startSession(player, holder.cauldronLoc, recipe, tier)
                        } else {
                            player.sendMessage("§c等级不足！该丹药需要冶药法等级: $reqLevel")
                        }
                    }
                }
            }
        }
    }

    private fun applyPillCooldownComponent(item: org.bukkit.inventory.ItemStack) {
        if (item.type == Material.AIR) return

        try {
            val cooldownComponent = UseCooldown.useCooldown(0.1f)
                .cooldownGroup(Key.key(pillCooldownKey.toString()))
                .build()

            item.setData(DataComponentTypes.USE_COOLDOWN, cooldownComponent)
        } catch (e: Exception) {
            // 只影响视觉冷却组件，服用逻辑不应被阻断。
        }
    }

    private fun setPillSicknessCooldown(player: org.bukkit.entity.Player, material: Material, ticks: Int) {
        if (ticks <= 0 || material == Material.AIR) return

        if (!sendPacketCooldown(player, pillCooldownKey, ticks)) {
            player.setCooldown(material, ticks)
        }
    }

    private fun sendPacketCooldown(player: org.bukkit.entity.Player, key: NamespacedKey, ticks: Int): Boolean {
        try {
            val craftPlayerMethod = player.javaClass.getMethod("getHandle")
            val nmsPlayer = craftPlayerMethod.invoke(player)
            val connectionField = nmsPlayer.javaClass.fields.firstOrNull {
                it.type.name.contains("ServerGamePacketListenerImpl") || it.name == "c" || it.name == "connection"
            } ?: throw NoSuchFieldException("No connection field")
            val connection = connectionField.get(nmsPlayer)

            val resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation")
            val parseMethod = resourceLocationClass.getMethod("parse", String::class.java)
            val nmsKey = parseMethod.invoke(null, key.toString())

            val packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundCooldownPacket")
            val packetConstructor = packetClass.getConstructor(resourceLocationClass, Int::class.javaPrimitiveType)
            val packet = packetConstructor.newInstance(nmsKey, ticks)

            val sendMethod = connection.javaClass.getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
            sendMethod.invoke(connection, packet)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun loadRegisteredCauldrons() {
        if (!cauldronDataFile.exists()) return

        val config = YamlConfiguration.loadConfiguration(cauldronDataFile)
        registeredCauldrons.clear()
        registeredCauldrons.addAll(config.getStringList("cauldrons"))
    }

    private fun saveRegisteredCauldrons() {
        cauldronDataFile.parentFile?.mkdirs()
        val config = YamlConfiguration()
        config.set("cauldrons", registeredCauldrons.sorted())
        config.save(cauldronDataFile)
    }

    private fun locationKey(location: Location): String {
        val worldId = location.world?.uid ?: "unknown"
        return "$worldId:${location.blockX}:${location.blockY}:${location.blockZ}"
    }
}
