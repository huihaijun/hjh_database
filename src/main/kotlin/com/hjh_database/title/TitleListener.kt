package com.hjh_database.title

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class TitleListener(private val manager: TitleManager) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        manager.loadPlayer(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        manager.unloadPlayer(event.player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? TitleMenuHolder ?: return
        val player = event.whoClicked as? Player ?: return
        event.isCancelled = true
        if (holder.owner != player.uniqueId) return
        if (event.rawSlot !in 0 until event.view.topInventory.size) return

        when (holder) {
            is TitleRootHolder -> when (event.rawSlot) {
                TitleMenus.ROOT_LIBRARY_SLOT -> manager.menus.openLibrary(player)
                TitleMenus.ROOT_PURCHASE_SLOT -> manager.menus.openPurchase(player)
            }
            is TitlePurchaseHolder -> when {
                event.rawSlot == TitleMenus.PURCHASE_BACK_SLOT -> manager.openMainMenu(player)
                event.rawSlot in TitleMenus.PURCHASE_BUTTON_SLOTS -> {
                    manager.purchaseCustom(player, TitleMenus.PURCHASE_BUTTON_SLOTS.indexOf(event.rawSlot))
                }
            }
            is TitleLibraryHolder -> {
                val category = TitleMenus.CATEGORY_SLOTS[event.rawSlot]
                when {
                    category != null -> manager.menus.openLibrary(player, category = category, requestedPage = 0)
                    event.rawSlot == TitleMenus.LIBRARY_PREVIOUS_SLOT ->
                        manager.menus.openLibrary(player, category = holder.category, requestedPage = holder.page - 1)
                    event.rawSlot == TitleMenus.LIBRARY_BACK_SLOT -> manager.openMainMenu(player)
                    event.rawSlot == TitleMenus.LIBRARY_NEXT_SLOT ->
                        manager.menus.openLibrary(player, category = holder.category, requestedPage = holder.page + 1)
                    else -> {
                        val titleId = manager.menus.titleId(event.currentItem) ?: return
                        if (event.isRightClick && manager.definition(titleId)?.category == TitleCategory.CUSTOM) {
                            manager.beginRename(player, titleId)
                        } else if (event.isLeftClick) {
                            manager.equip(player, titleId)
                        }
                    }
                }
            }
        }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.1f)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is TitleMenuHolder) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (manager.isAwaitingRename(event.player.uniqueId)) {
            event.isCancelled = true
            manager.handleRenameInput(
                event.player,
                PlainTextComponentSerializer.plainText().serialize(event.message())
            )
            return
        }
        val profile = manager.getProfile(event.player.uniqueId) ?: return
        if (!profile.showChat) return
        val title = manager.equippedComponent(profile) ?: return
        val original = event.renderer()
        event.renderer(
            if (original is ChatRenderer.ViewerUnaware) {
                ChatRenderer.viewerUnaware(ChatRenderer.ViewerUnaware { source, sourceDisplayName, message ->
                    val originalName = manager.originalChatDisplayName(source, sourceDisplayName)
                    prependTitle(title, original.render(source, originalName, message))
                })
            } else {
                ChatRenderer { source, sourceDisplayName, message, viewer ->
                    val originalName = manager.originalChatDisplayName(source, sourceDisplayName)
                    prependTitle(title, original.render(source, originalName, message, viewer))
                }
            }
        )
    }

    private fun prependTitle(title: Component, renderedMessage: Component): Component =
        Component.empty()
            .append(title)
            .append(Component.space())
            .append(renderedMessage)
}
