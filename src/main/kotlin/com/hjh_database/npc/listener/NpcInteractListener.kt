package com.hjh_database.npc.listener

import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.NpcInstance
import com.hjh_database.npc.data.NpcTemplate
import com.hjh_database.npc.gui.NpcAdminGui
import com.hjh_database.npc.gui.NpcLibraryGui
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
import org.bukkit.persistence.PersistentDataType
import java.util.*
import kotlin.collections.HashMap

class NpcInteractListener(private val plugin: Hjh_database) : Listener {

    // === 【核心修改1】将冷却表放入 companion object ===
    // 这样即使插件重载导致监听器重复注册，它们也会共享这份冷却数据，彻底杜绝双击
    companion object {
        private val interactCooldown = HashMap<UUID, Long>()
    }

    private val clipboard = HashMap<UUID, String>()
    val editingNameMap = HashMap<UUID, String>()
    private val dialogueProgress = HashMap<UUID, HashMap<String, Int>>()

    /**
     * 统一的冷却检查函数
     * 返回 true 表示正在冷却中（应该拦截）
     * 返回 false 表示可以通行
     */
    private fun isCoolingDown(player: Player): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastTime = interactCooldown.getOrDefault(player.uniqueId, 0L)

        // 500ms 冷却，防双击，防左右手并发
        if (currentTime - lastTime < 500) {
            return true
        }

        interactCooldown[player.uniqueId] = currentTime
        return false
    }

    // ================== 1. 右键 NPC (交易/管理) ==================
    @EventHandler
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        // 过滤副手交互
        if (event.hand != EquipmentSlot.HAND) return

        val entity = event.rightClicked
        if (entity !is Villager) return

        // === 【核心修改2】给右键也加上冷却检查 ===
        // 这能解决 "提示不能交易" 弹出两次的问题
        if (isCoolingDown(event.player)) return

        val container = entity.persistentDataContainer
        val key = plugin.npcModule.manager.npcKey
        val player = event.player
        val item = player.inventory.itemInMainHand

        // --- 管理员逻辑：手持木锄 (一键收编 + 编辑) ---
        if (player.isOp && item.type == Material.WOODEN_HOE) {
            event.isCancelled = true

            var templateId = container.get(key, PersistentDataType.STRING)

            // 如果还没有被收编
            if (templateId == null) {
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

            // 打开编辑器
            NpcAdminGui(plugin, player, templateId, entity.uniqueId).open()
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
        if (container.has(key, PersistentDataType.STRING)) {
            event.isCancelled = true // 阻止原版 GUI

            val tid = container.get(key, PersistentDataType.STRING) ?: return
            val template = plugin.npcModule.manager.getTemplate(tid) ?: return

            if (template.trades.isEmpty()) {
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                player.sendMessage("§e[提示] §7这个NPC暂时不能交易，试着左键和他说说话吧！")
            } else {
                plugin.npcModule.manager.openTrade(player, tid)
            }
        }
    }

    // ================== 2. 右键地面 (生成 NPC) ==================
    @EventHandler
    fun onGroundInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (!player.isSneaking || !player.isOp) return

        // 地面操作不需要那么严格的冷却，但如果也双击了，可以在这里加
        // if (isCoolingDown(player)) return

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

    // ================== 3. 左键 NPC (对话) ==================
    @EventHandler
    fun onNpcDamage(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        if (entity !is Villager) return

        // 只要是 NPC，左键一律取消伤害
        if (entity.persistentDataContainer.has(plugin.npcModule.manager.npcKey, PersistentDataType.STRING)) {
            event.isCancelled = true
        } else {
            return
        }

        val damager = event.damager
        if (damager !is Player) return

        // === 【核心修改3】调用静态冷却检查 ===
        if (isCoolingDown(damager)) return

        val templateId = entity.persistentDataContainer.get(plugin.npcModule.manager.npcKey, PersistentDataType.STRING) ?: return
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return

        // 任务系统拦截
        if (plugin.questManager.handleNpcDialogue(damager, templateId)) {
            return
        }

        val dialogues = template.dialogue
        if (dialogues.isNotEmpty()) {
            val playerProgress = dialogueProgress.computeIfAbsent(damager.uniqueId) { HashMap() }
            var currentIndex = playerProgress.getOrDefault(templateId, 0)
            if (currentIndex >= dialogues.size) currentIndex = 0

            val msg = dialogues[currentIndex]
            // 修复颜色溢出问题
            damager.sendMessage("§e[${template.name}§e]: §f${msg.replace("&", "§")}")
            damager.playSound(damager.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

            if (currentIndex < dialogues.size - 1) {
                // 如果不想每次都提示这一句，可以注释掉下面这行
                // damager.sendMessage("§a[提示] -> 请继续左键点击，继续对话...")
                playerProgress[templateId] = currentIndex + 1
            } else {
                damager.sendMessage("§c[提示] -> 对话已结束！")
                playerProgress[templateId] = 0
            }
        } else {
            // 如果NPC没有对话内容
            damager.sendMessage("§7(好像没什么事发生...)")
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