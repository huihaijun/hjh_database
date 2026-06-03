package com.hjh_database.npc.listener

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.NpcInstance
import com.hjh_database.npc.data.NpcTemplate
import com.hjh_database.npc.gui.NpcAdminGui
import com.hjh_database.npc.gui.NpcLibraryGui
import com.hjh_database.race.impl.HumanRace
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.persistence.PersistentDataType
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class NpcInteractListener(private val plugin: Hjh_database) : Listener {

    // === 【核心修改1】静态冷却表 (防双击/并发) ===
    companion object {
        private val interactCooldown = HashMap<UUID, Long>()

        /**
         * 统一的冷却检查函数
         */
        fun isCoolingDown(player: Player): Boolean {
            val currentTime = System.currentTimeMillis()
            val lastTime = interactCooldown.getOrDefault(player.uniqueId, 0L)

            // 500ms 冷却，防止右键连点或左右手同时触发
            if (currentTime - lastTime < 500) {
                return true
            }

            interactCooldown[player.uniqueId] = currentTime
            return false
        }
    }

    private val clipboard = HashMap<UUID, String>()
    val editingNameMap = HashMap<UUID, String>()
    private val dialogueProgress = HashMap<UUID, HashMap<String, Int>>()

    // ================== 1. 右键 NPC (交易/管理) ==================
    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        // 过滤副手交互
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked
        if (entity !is Villager) return

        // 检查是否是本插件 NPC
        val container = entity.persistentDataContainer
        val key = plugin.npcModule.manager.npcKey
        if (!container.has(key, PersistentDataType.STRING)) return

        // === 【核心修改2】右键冷却检查 ===
        if (isCoolingDown(event.player)) {
            event.isCancelled = true // 必须取消，否则原版交易界面可能还会闪现
            return
        }

        val player = event.player
        val item = player.inventory.itemInMainHand

        // --- 管理员逻辑：手持木锄 (一键收编 + 编辑) ---
        if (player.isOp && item.type == Material.WOODEN_HOE) {
            event.isCancelled = true

            var templateId = container.get(key, PersistentDataType.STRING)

            // 如果还没有被收编 (或者数据丢失)
            // 增加空值检查，防止 getTemplate 报错
            val existingTemplate = if (templateId != null) plugin.npcModule.manager.getTemplate(templateId) else null

            if (existingTemplate == null) {
                val newId = "converted_${UUID.randomUUID().toString().substring(0, 8)}"
                val currentName = entity.customName ?: "NPC_${newId.take(4)}"

                // 保留原交易
                val convertedTrades = plugin.npcModule.manager.convertVanillaRecipes(entity.recipes)

                val newTemplate = NpcTemplate(
                    id = newId,
                    name = currentName,
                    profession = entity.profession,
                    type = entity.villagerType,
                    dialogue = mutableListOf("我被收编了！"),
                    trades = convertedTrades
                )

                plugin.npcModule.manager.templates[newId] = newTemplate
                container.set(key, PersistentDataType.STRING, newId)
                plugin.npcModule.manager.instances[entity.uniqueId] = NpcInstance(entity.uniqueId, newId, entity.location)
                plugin.npcModule.manager.applyNpcAttributes(entity)
                plugin.npcModule.manager.saveData()

                player.sendMessage("§a[NPC] 已自动收编并锁定该村民！")
                templateId = newId
            }

            // 打开编辑器 (使用 !! 强转是安全的，因为上面刚刚赋值过)
            NpcAdminGui(plugin, player, templateId!!, entity.uniqueId).open()
            return
        }

        // --- 管理员逻辑：手持石锄 (复制) ---
        if (player.isOp && item.type == Material.STONE_HOE) {
            val tid = container.get(key, PersistentDataType.STRING)
            if (tid != null) {
                event.isCancelled = true
                clipboard[player.uniqueId] = tid
                player.sendMessage("§a[NPC] 已复制模板: $tid")
                player.sendMessage("§7提示: 右键地面可打开[模板库]进行粘贴。")
            }
            return
        }

        // --- 普通玩家交互 (交易) ---
        event.isCancelled = true // 阻止原版 GUI

        val tid = container.get(key, PersistentDataType.STRING) ?: return
        val template = plugin.npcModule.manager.getTemplate(tid) ?: return

        if (template.trades.isEmpty()) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            player.sendMessage("§e[提示] §7这个NPC暂时不能交易，试着左键和他说说话吧！")
        } else {
            // === 【核心修改3】调用支持打折的交易方法 ===
            // 这里替换了原有的 manager.openTrade
            openTradeWithDiscounts(player, template)
        }
    }

    /**
     * 打开交易窗口并应用种族折扣
     * 包含安全检查，防止 RaceModule 未加载导致报错
     */
    private fun openTradeWithDiscounts(player: Player, template: NpcTemplate) {
        val merchant = Bukkit.createMerchant(template.name)
        val recipes = ArrayList<MerchantRecipe>()

        // 1. 转换配方 (将 CustomTrade 转为 MerchantRecipe)
        for (trade in template.trades) {
            val recipe = MerchantRecipe(trade.result, 0, 9999, false)
            recipe.addIngredient(trade.ingredient1)
            if (trade.ingredient2 != null) {
                recipe.addIngredient(trade.ingredient2!!)
            }
            recipes.add(recipe)
        }

        // 2. 种族打折判定
        if (template.allowRaceDiscount) {
            try {
                // 尝试获取人族逻辑
                // 这里的 2 是人族的 ID，需与 RaceManager 注册的一致
                val humanRace = plugin.raceModule.getRace(2) as? HumanRace

                if (humanRace != null) {
                    // 检查玩家是否激活了该种族特权 (种族匹配 + 任务完成)
                    if (humanRace.isRaceActive(player)) {
                        humanRace.applyDiscounts(recipes)
                    }
                }
            } catch (e: Exception) {
                // 防止因为 raceModule 未初始化或其他原因导致打不开商店
                plugin.logger.warning("[NpcInteract] 调用种族打折失败: ${e.message}")
            }
        }

        // 3. 设置配方并打开窗口
        merchant.recipes = recipes
        player.openMerchant(merchant, true)
    }

    // ================== 2. 右键地面 (生成 NPC) ==================
    @EventHandler
    fun onGroundInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (!player.isSneaking || !player.isOp) return

        // 地面生成不需要严格冷却，但也加上以防万一
        if (isCoolingDown(player)) return

        val item = event.item ?: return
        val block = event.clickedBlock ?: return
        val spawnLoc = block.location.add(0.5, 1.0, 0.5)
        val dir = player.location.toVector().subtract(spawnLoc.toVector()).normalize()
        spawnLoc.direction = dir
        spawnLoc.pitch = 0f

        // 石锄：打开模板库 GUI
        if (item.type == Material.STONE_HOE) {
            event.isCancelled = true
            NpcLibraryGui(plugin, player, spawnLoc).open()
        }

        // 木锄：新建
        if (item.type == Material.WOODEN_HOE) {
            event.isCancelled = true
            val newId = "npc_${UUID.randomUUID().toString().substring(0, 8)}"
            val defaultTemplate = NpcTemplate(
                id = newId,
                name = "§e新建NPC",
                trades = ArrayList()
            )
            plugin.npcModule.manager.templates[newId] = defaultTemplate
            plugin.npcModule.manager.spawnNpc(spawnLoc, newId)
            player.sendMessage("§a[NPC] 已新建: $newId")
        }
    }

    @EventHandler
    // ================== 3. 左键 NPC (对话) 修复：使用 PrePlayerAttackEntityEvent ==================
    fun onNpcDamage(event: PrePlayerAttackEntityEvent) {
        val entity = event.attacked
        if (entity !is Villager) return

        // 只要是 NPC，左键一律取消底层伤害判定
        if (entity.persistentDataContainer.has(plugin.npcModule.manager.npcKey, PersistentDataType.STRING)) {
            event.isCancelled = true
        } else {
            return
        }

        val damager = event.player

        // === 【核心修改4】左键冷却检查 ===
        if (isCoolingDown(damager)) return

        val templateId = entity.persistentDataContainer.get(plugin.npcModule.manager.npcKey, PersistentDataType.STRING) ?: return
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return

        // 任务系统拦截
        if (plugin.questManager.handleNpcDialogue(damager, templateId)) {
            return
        }

        // 对话逻辑
        val dialogues = template.dialogue
        if (dialogues.isNotEmpty()) {
            val playerProgress = dialogueProgress.computeIfAbsent(damager.uniqueId) { HashMap() }
            var currentIndex = playerProgress.getOrDefault(templateId, 0)
            if (currentIndex >= dialogues.size) currentIndex = 0

            val msg = dialogues[currentIndex]
            damager.sendMessage("§e[${template.name}§e]: §f${msg.replace("&", "§")}")
            damager.playSound(damager.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            if (currentIndex < dialogues.size - 1) {
                playerProgress[templateId] = currentIndex + 1
            } else {
                damager.sendMessage("§c[提示] -> 对话已结束！")
                playerProgress[templateId] = 0
            }
        } else {
            damager.sendMessage("§7(好像没什么事发生...)")
        }
    }

    // ================== 4. 【新增】监听实体进入世界 (修复碰撞箱丢失) ==================
    /**
     * 当实体被加载到世界（区块加载、生成、重启后加载）时触发
     * 确保 NPC 永远保持无碰撞状态
     */
    @EventHandler
    fun onEntityLoad(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (entity !is Villager) return
        // 检查是否是本插件 NPC
        // 注意：这里不要做太重的逻辑，只检查 PDC
        if (entity.persistentDataContainer.has(plugin.npcModule.manager.npcKey, PersistentDataType.STRING)) {
            // 延迟 1 tick 执行，确保实体完全初始化后再覆盖属性
            // 有时候刚生成的瞬间设置属性会被原版覆盖回去
            Bukkit.getScheduler().runTask(plugin, Runnable {
                plugin.npcModule.manager.applyNpcAttributes(entity)
            })
        }
    }

    // ... (onChat 保持不变) ...
    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val templateId = editingNameMap[player.uniqueId] ?: return

        event.isCancelled = true
        val plainText = LegacyComponentSerializer.legacySection().serialize(event.message())

        val template = plugin.npcModule.manager.getTemplate(templateId)
        if (template != null) {
            template.name = plainText.replace("&", "§")
            plugin.npcModule.manager.saveData()

            plugin.server.scheduler.runTask(plugin, Runnable {
                NpcAdminGui(plugin, player, templateId, null).open()
                player.sendMessage("§a[NPC] 名字已更新。")
            })
        }
        editingNameMap.remove(player.uniqueId)
    }
}
