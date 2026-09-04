package com.hjh_database.npc.listener

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.hjh_database.Hjh_database
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
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NpcInteractListener(private val plugin: Hjh_database) : Listener {

    private data class NameEditSession(val templateId: String, val entityUuid: UUID?)

    private val interactCooldown = HashMap<UUID, Long>()
    private val editingNames = ConcurrentHashMap<UUID, NameEditSession>()
    private val dialogueProgress = HashMap<UUID, HashMap<String, Int>>()

    fun beginNameEdit(player: Player, templateId: String, entityUuid: UUID?) {
        editingNames[player.uniqueId] = NameEditSession(templateId, entityUuid)
    }

    private fun isCoolingDown(player: Player, intervalMillis: Long = 250L): Boolean {
        val now = System.currentTimeMillis()
        val last = interactCooldown[player.uniqueId] ?: 0L
        if (now - last < intervalMillis) return true
        interactCooldown[player.uniqueId] = now
        return false
    }

    // ================== 1. 右键村民（管理/模板/交易） ==================
    @EventHandler(ignoreCancelled = true)
    fun onNpcInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val villager = event.rightClicked as? Villager ?: return
        val player = event.player
        val heldItem = player.inventory.itemInMainHand.type

        // 管理员木锄具有最高业务优先级，也允许直接收编普通原版村民。
        if (player.isOp && heldItem == Material.WOODEN_HOE) {
            event.isCancelled = true
            if (isCoolingDown(player)) return

            val wasManaged = villager.persistentDataContainer.has(
                plugin.npcModule.manager.npcKey,
                PersistentDataType.STRING
            )
            val templateId = plugin.npcModule.manager.adoptVillager(villager)
            if (!wasManaged) player.sendMessage("§a[NPC] 已自动收编并锁定该村民！")
            NpcAdminGui(plugin, player, templateId, villager.uniqueId).open()
            return
        }

        val templateId = villager.persistentDataContainer.get(
            plugin.npcModule.manager.npcKey,
            PersistentDataType.STRING
        ) ?: return

        if (isCoolingDown(player)) {
            event.isCancelled = true
            return
        }

        // 只有管理员能用石锄把当前 NPC 明确保存进模板库。
        if (player.isOp && heldItem == Material.STONE_HOE) {
            event.isCancelled = true
            if (plugin.npcModule.manager.saveTemplateToLibrary(templateId)) {
                player.sendMessage("§a[NPC] 已保存到石锄模板库: $templateId")
                player.sendMessage("§7下蹲并用石锄右键地面可打开模板库。")
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f)
            } else {
                player.sendMessage("§c[NPC] 保存失败：找不到该 NPC 的配置。")
            }
            return
        }

        event.isCancelled = true
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return
        if (template.trades.isEmpty()) {
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            player.sendMessage("§e[提示] §7这个NPC暂时不能交易，试着左键和他说说话吧！")
        } else {
            openTradeWithDiscounts(player, template)
        }
    }

    private fun openTradeWithDiscounts(player: Player, template: NpcTemplate) {
        val merchant = Bukkit.createMerchant(template.name)
        val recipes = ArrayList<MerchantRecipe>(template.trades.size)
        template.trades.forEach { recipes.add(it.toMerchantRecipe()) }

        if (template.allowRaceDiscount) {
            try {
                val humanRace = plugin.raceModule.getRace(2) as? HumanRace
                if (humanRace?.isRaceActive(player) == true) humanRace.applyDiscounts(recipes)
            } catch (exception: Exception) {
                plugin.logger.warning("[NpcInteract] 调用种族打折失败: ${exception.message}")
            }
        }

        merchant.recipes = recipes
        player.openMerchant(merchant, true)
    }

    // ================== 2. 下蹲右键地面（新建/模板库） ==================
    @EventHandler
    fun onGroundInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (!player.isSneaking || !player.isOp) return

        val material = event.item?.type ?: return
        if (material != Material.WOODEN_HOE && material != Material.STONE_HOE) return
        event.isCancelled = true
        if (isCoolingDown(player)) return

        val block = event.clickedBlock ?: return
        val spawnLocation = block.location.add(0.5, 1.0, 0.5)
        spawnLocation.direction = player.location.toVector().subtract(spawnLocation.toVector()).normalize()
        spawnLocation.pitch = 0f

        if (material == Material.STONE_HOE) {
            NpcLibraryGui(plugin, player, spawnLocation).open()
            return
        }

        val templateId = "npc_${UUID.randomUUID().toString().take(8)}"
        plugin.npcModule.manager.templates[templateId] = NpcTemplate(
            id = templateId,
            name = "§e新建NPC"
        )
        plugin.npcModule.manager.spawnNpc(spawnLocation, templateId)
        player.sendMessage("§a[NPC] 已新建: $templateId")
    }

    // ================== 3. 左键 NPC（任务/对话） ==================
    @EventHandler
    fun onNpcDamage(event: PrePlayerAttackEntityEvent) {
        val villager = event.attacked as? Villager ?: return
        val templateId = villager.persistentDataContainer.get(
            plugin.npcModule.manager.npcKey,
            PersistentDataType.STRING
        ) ?: return

        event.isCancelled = true
        val player = event.player
        if (isCoolingDown(player)) return
        val template = plugin.npcModule.manager.getTemplate(templateId) ?: return

        if (plugin.questManager.handleNpcDialogue(player, templateId)) return
        if (plugin.raceModule.getZhanRace().handleIntelligenceDialogue(player, templateId)) return

        val dialogues = template.dialogue
        if (dialogues.isEmpty()) {
            player.sendMessage("§7(好像没什么事发生...)")
            return
        }

        val progress = dialogueProgress.computeIfAbsent(player.uniqueId) { HashMap() }
        val index = (progress[templateId] ?: 0).coerceIn(0, dialogues.lastIndex)
        player.sendMessage("§e[${template.name}§e]: §f${dialogues[index].replace("&", "§")}")
        player.playSound(player.location, Sound.ENTITY_VILLAGER_TRADE, 1f, 1f)

        if (index == dialogues.lastIndex) {
            player.sendMessage("§c[提示] -> 对话已结束！")
            progress[templateId] = 0
        } else {
            progress[templateId] = index + 1
        }
    }

    // ================== 4. 实体加载时恢复 NPC 属性 ==================
    @EventHandler
    fun onEntityLoad(event: EntityAddToWorldEvent) {
        val villager = event.entity as? Villager ?: return
        if (!villager.persistentDataContainer.has(plugin.npcModule.manager.npcKey, PersistentDataType.STRING)) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (villager.isValid) plugin.npcModule.manager.applyNpcAttributes(villager)
        })
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val session = editingNames.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val player = event.player
        val name = LegacyComponentSerializer.legacySection().serialize(event.message()).replace("&", "§")

        // AsyncChatEvent 不直接修改 Bukkit/NPC 数据或写文件，统一回到主线程。
        plugin.server.scheduler.runTask(plugin, Runnable {
            val template = plugin.npcModule.manager.getTemplate(session.templateId)
            if (template == null) {
                player.sendMessage("§c[NPC] 改名失败：配置已不存在。")
                return@Runnable
            }
            template.name = name
            plugin.npcModule.manager.saveData()
            NpcAdminGui(plugin, player, session.templateId, session.entityUuid).open()
            player.sendMessage("§a[NPC] 名字已更新。")
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        interactCooldown.remove(event.player.uniqueId)
        editingNames.remove(event.player.uniqueId)
        dialogueProgress.remove(event.player.uniqueId)
    }
}
