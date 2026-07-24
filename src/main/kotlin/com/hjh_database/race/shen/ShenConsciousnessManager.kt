package com.hjh_database.race.shen

import com.hjh_database.Hjh_database
import com.hjh_database.npc.data.NpcTemplate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.scheduler.BukkitRunnable
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShenConsciousnessManager(private val plugin: Hjh_database) : Listener {

    data class ConsciousnessLink(val templateId: String, val displayName: String)

    private class ConsciousnessMenuHolder(
        val ownerId: UUID,
        val linksBySlot: Map<Int, ConsciousnessLink>
    ) : InventoryHolder {
        lateinit var backingInventory: Inventory

        override fun getInventory(): Inventory = backingInventory
    }

    private val linksByPlayer = ConcurrentHashMap<UUID, LinkedHashMap<String, ConsciousnessLink>>()
    private val loadedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingLoads = ConcurrentHashMap<UUID, Boolean>()
    private val pendingBindings = ConcurrentHashMap.newKeySet<String>()
    private val sessionTokens = ConcurrentHashMap<UUID, AtomicLong>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun bind(player: Player, villager: Villager, template: NpcTemplate) {
        if (!ensureLoaded(player)) return

        val links = linksByPlayer[player.uniqueId] ?: return
        if (links.containsKey(template.id)) {
            player.sendMessage("§e[神识] §f你已与 §e${template.name}§f 建立神识。")
            return
        }
        val bindingKey = bindingKey(player.uniqueId, template.id)
        if (bindingKey in pendingBindings) {
            player.sendMessage("§e[神识] §f正在与 §e${template.name}§f 建立神识，请稍候。")
            return
        }
        val pendingCount = pendingBindings.count { it.startsWith("${player.uniqueId}:") }
        if (links.size + pendingCount >= MAX_LINKS) {
            player.sendMessage("§c[神识] §f你目前最多只能维系三道神识，请先在神识菜单中删除一位商贩。")
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
            return
        }

        pendingBindings.add(bindingKey)
        player.sendMessage("§d[神识] §f你正在与 §e${template.name}§f 建立神识……")
        startBindingRitual(player, villager, template, bindingKey)
    }

    private fun startBindingRitual(player: Player, villager: Villager, template: NpcTemplate, bindingKey: String) {
        object : BukkitRunnable() {
            private var elapsedTicks = 0

            override fun run() {
                if (!player.isOnline || !villager.isValid || elapsedTicks >= BINDING_DURATION_TICKS) {
                    cancel()
                    finishBinding(player, villager, template, bindingKey)
                    return
                }

                spawnBindingRitualEffects(player, villager, elapsedTicks)
                if (elapsedTicks == 0) {
                    player.world.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.35f)
                    villager.world.playSound(villager.location, Sound.BLOCK_BEACON_AMBIENT, 0.55f, 1.6f)
                } else if (elapsedTicks % 10 == 0) {
                    // 随仪式推进逐渐升调，声音只在短暂绑定过程中播放，不创建常驻任务。
                    val pitch = 1.15f + elapsedTicks.toFloat() / BINDING_DURATION_TICKS * 0.55f
                    player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, pitch)
                    villager.world.playSound(villager.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, pitch + 0.08f)
                }
                elapsedTicks += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    private fun spawnBindingRitualEffects(player: Player, villager: Villager, elapsedTicks: Int) {
        val phase = elapsedTicks * PI / 20.0
        spawnConsciousnessAura(player.location, phase, 1.0)
        spawnConsciousnessAura(villager.location, phase, -1.0)

        // 在双方之间绘制短暂的神识连线，使两圈粒子看起来属于同一个仪式。
        if (player.world.uid != villager.world.uid) return
        val start = player.location.clone().add(0.0, 1.25, 0.0)
        val end = villager.location.clone().add(0.0, 1.25, 0.0)
        val step = end.toVector().subtract(start.toVector()).multiply(1.0 / 6.0)
        for (index in 1..5) {
            val point = start.clone().add(step.clone().multiply(index))
            player.world.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun spawnConsciousnessAura(base: org.bukkit.Location, phase: Double, direction: Double) {
        val world = base.world ?: return
        val center = base.clone().add(0.0, 1.0, 0.0)

        // 两道反向旋转的疏粒子环；绑定只持续两秒，视觉清晰且粒子量可控。
        repeat(6) { index ->
            val angle = phase * direction + index * (2.0 * PI / 6.0)
            val point = center.clone().add(
                cos(angle) * 0.65,
                sin(angle * 2.0) * 0.42,
                sin(angle) * 0.65
            )
            world.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0)
        }
        world.spawnParticle(Particle.ENCHANT, center, 10, 0.42, 0.65, 0.42, 0.08)
        world.spawnParticle(Particle.PORTAL, center, 4, 0.32, 0.55, 0.32, 0.02)
    }

    private fun finishBinding(player: Player, villager: Villager, template: NpcTemplate, bindingKey: String) {
        pendingBindings.remove(bindingKey)
        if (!player.isOnline || !villager.isValid) {
            if (player.isOnline) player.sendMessage("§c[神识] §f神识共鸣中断，请靠近商贩后重试。")
            return
        }

        val links = linksByPlayer[player.uniqueId] ?: return
        if (links.containsKey(template.id)) return
        if (links.size >= MAX_LINKS) {
            player.sendMessage("§c[神识] §f神识位已满，建立失败。")
            return
        }

        val link = ConsciousnessLink(template.id, template.name)
        links[template.id] = link
        player.sendMessage("§a[神识] §f已与 §e${template.name}§f 建立神识。")
        spawnBindingCompletionEffects(player.location)
        spawnBindingCompletionEffects(villager.location)
        player.world.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.4f)
        player.world.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.75f)
        villager.world.playSound(villager.location, Sound.BLOCK_BEACON_ACTIVATE, 0.65f, 1.65f)

        plugin.databaseManager.submitDatabaseOperation {
            insertLink(player.uniqueId, link)
        }.whenComplete { _, error ->
            if (error == null) return@whenComplete
            plugin.logger.warning("保存神识关系失败: ${error.message}")
            plugin.server.scheduler.runTask(plugin, Runnable {
                linksByPlayer[player.uniqueId]?.remove(link.templateId)
                if (player.isOnline) player.sendMessage("§c[神识] §f建立失败，请稍后重试。")
            })
        }
    }

    private fun spawnBindingCompletionEffects(base: org.bukkit.Location) {
        val world = base.world ?: return
        val effectLocation = base.clone().add(0.0, 1.05, 0.0)
        world.spawnParticle(Particle.END_ROD, effectLocation, 24, 0.4, 0.65, 0.4, 0.025)
        world.spawnParticle(Particle.ENCHANT, effectLocation, 40, 0.5, 0.7, 0.5, 0.16)
        world.spawnParticle(Particle.FIREWORK, effectLocation, 12, 0.35, 0.5, 0.35, 0.06)
    }

    fun openMenu(player: Player) {
        if (plugin.playerManager.getPlayerData(player)?.status != 3) {
            player.sendMessage("§c[神识] §f你暂时无法展开神识商贩菜单。")
            return
        }
        if (!ensureLoaded(player)) return

        val links = linksByPlayer[player.uniqueId]?.values?.toList().orEmpty()
        val slotMap = LinkedHashMap<Int, ConsciousnessLink>()
        val holder = ConsciousnessMenuHolder(player.uniqueId, slotMap)
        val inventory = Bukkit.createInventory(holder, MENU_SIZE, "§0神识商贩")
        holder.backingInventory = inventory

        links.forEachIndexed { index, link ->
            val slot = LINK_SLOTS[index]
            slotMap[slot] = link
            inventory.setItem(slot, createLinkItem(link))
        }
        if (links.isEmpty()) {
            inventory.setItem(13, ItemStack(Material.BOOK).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§7暂无神识商贩")
                    lore = listOf("§8主手持有神族证明右键可交易 NPC", "§8即可建立神识。")
                }
            })
        }
        inventory.setItem(26, ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta?.apply { setDisplayName("§c关闭") }
        })
        player.openInventory(inventory)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ConsciousnessMenuHolder ?: return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.ownerId || event.rawSlot !in 0 until event.view.topInventory.size) return
        if (event.rawSlot == 26) {
            player.closeInventory()
            return
        }

        val link = holder.linksBySlot[event.rawSlot] ?: return
        if (event.isRightClick) {
            removeLink(player, link)
            openMenu(player)
            return
        }

        player.closeInventory()
        plugin.server.scheduler.runTask(plugin, Runnable { openTrade(player, link) })
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is ConsciousnessMenuHolder) return
        if (event.rawSlots.any { it < event.view.topInventory.size }) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        unload(event.player.uniqueId)
    }

    private fun ensureLoaded(player: Player): Boolean {
        if (loadedPlayers.contains(player.uniqueId)) return true
        if (pendingLoads.putIfAbsent(player.uniqueId, true) == null) {
            val token = sessionTokens.computeIfAbsent(player.uniqueId) { AtomicLong() }.incrementAndGet()
            plugin.databaseManager.submitDatabaseOperation {
                loadLinks(player.uniqueId)
            }.whenComplete { links, error ->
                plugin.server.scheduler.runTask(plugin, Runnable {
                    pendingLoads.remove(player.uniqueId)
                    val currentToken = sessionTokens[player.uniqueId]?.get()
                    if (currentToken != token || !player.isOnline) return@Runnable
                    if (error != null) {
                        plugin.logger.warning("加载神识关系失败: ${error.message}")
                        player.sendMessage("§c[神识] §f同步失败，请稍后重试。")
                        return@Runnable
                    }
                    linksByPlayer[player.uniqueId] = LinkedHashMap<String, ConsciousnessLink>().apply {
                        links.forEach { put(it.templateId, it) }
                    }
                    loadedPlayers.add(player.uniqueId)
                    player.sendMessage("§7[神识] §f神识关系已同步，可再次使用神族证明。")
                })
            }
        }
        player.sendMessage("§7[神识] §f正在同步神识关系，请稍后再试。")
        return false
    }

    private fun removeLink(player: Player, link: ConsciousnessLink) {
        val links = linksByPlayer[player.uniqueId] ?: return
        if (links.remove(link.templateId) == null) return
        player.sendMessage("§e[神识] §f已断开与 §e${link.displayName}§f 的神识。")

        plugin.databaseManager.submitDatabaseOperation {
            deleteLink(player.uniqueId, link.templateId)
        }.whenComplete { _, error ->
            if (error == null) return@whenComplete
            plugin.logger.warning("删除神识关系失败: ${error.message}")
            plugin.server.scheduler.runTask(plugin, Runnable {
                linksByPlayer[player.uniqueId]?.put(link.templateId, link)
                if (player.isOnline) player.sendMessage("§c[神识] §f删除失败，已恢复该神识。")
            })
        }
    }

    private fun openTrade(player: Player, link: ConsciousnessLink) {
        if (plugin.playerManager.getPlayerData(player)?.status != 3) {
            player.sendMessage("§c[神识] §f你暂时无法通过神识进行交易。")
            return
        }
        val template = plugin.npcModule.manager.getTemplate(link.templateId)
        if (template == null) {
            player.sendMessage("§c[神识] §f该商贩已无法交易，请在神识菜单中右键将其删除。")
            return
        }

        // 钱庄掌柜使用专属存折 GUI，不应降级成原版村民交易界面。
        if (plugin.passbookListener.isBanker(template.name) || plugin.passbookListener.isBanker(link.displayName)) {
            plugin.passbookListener.open(player)
            return
        }

        if (template.trades.isEmpty()) {
            player.sendMessage("§c[神识] §f该商贩已无法交易，请在神识菜单中右键将其删除。")
            return
        }

        val merchant = Bukkit.createMerchant(template.name)
        merchant.recipes = ArrayList<MerchantRecipe>(template.trades.size).apply {
            template.trades.forEach { add(it.toMerchantRecipe()) }
        }
        player.openMerchant(merchant, true)
    }

    private fun createLinkItem(link: ConsciousnessLink): ItemStack = ItemStack(Material.VILLAGER_SPAWN_EGG).apply {
        itemMeta = itemMeta?.apply {
            setDisplayName("§e${link.displayName}")
            lore = listOf(
                "§7左键: §f以神识打开交易",
                "§7右键: §c断开神识",
                "§8商贩标识: ${link.templateId}"
            )
        }
    }

    private fun loadLinks(playerId: UUID): List<ConsciousnessLink> {
        val sql = "SELECT template_id, display_name FROM player_shen_consciousness WHERE player_uuid = ? ORDER BY created_at ASC"
        return try {
            plugin.databaseManager.dataSource?.connection?.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, playerId.toString())
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(ConsciousnessLink(result.getString("template_id"), result.getString("display_name")))
                            }
                        }
                    }
                }
            } ?: emptyList()
        } catch (exception: SQLException) {
            throw IllegalStateException("读取神识关系失败", exception)
        }
    }

    private fun insertLink(playerId: UUID, link: ConsciousnessLink) {
        val sql = """
            INSERT INTO player_shen_consciousness (player_uuid, template_id, display_name, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(player_uuid, template_id) DO UPDATE SET display_name = excluded.display_name
        """.trimIndent()
        plugin.databaseManager.dataSource?.connection?.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, link.templateId)
                statement.setString(3, link.displayName)
                statement.setLong(4, System.currentTimeMillis())
                statement.executeUpdate()
            }
        } ?: throw IllegalStateException("数据库未连接")
    }

    private fun deleteLink(playerId: UUID, templateId: String) {
        val sql = "DELETE FROM player_shen_consciousness WHERE player_uuid = ? AND template_id = ?"
        plugin.databaseManager.dataSource?.connection?.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, templateId)
                statement.executeUpdate()
            }
        } ?: throw IllegalStateException("数据库未连接")
    }

    private fun unload(playerId: UUID) {
        sessionTokens.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
        pendingLoads.remove(playerId)
        pendingBindings.removeIf { it.startsWith("$playerId:") }
        loadedPlayers.remove(playerId)
        linksByPlayer.remove(playerId)
    }

    companion object {
        private const val MAX_LINKS = 3
        private const val MENU_SIZE = 27
        private const val BINDING_DURATION_TICKS = 40
        private val LINK_SLOTS = intArrayOf(11, 13, 15)

        private fun bindingKey(playerId: UUID, templateId: String): String = "$playerId:$templateId"
    }
}
