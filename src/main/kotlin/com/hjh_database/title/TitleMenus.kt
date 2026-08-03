package com.hjh_database.title

import com.hjh_database.Hjh_database
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed class TitleMenuHolder(val owner: UUID) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class TitleRootHolder(owner: UUID) : TitleMenuHolder(owner)
class TitlePurchaseHolder(owner: UUID) : TitleMenuHolder(owner)
class TitleLibraryHolder(
    owner: UUID,
    val category: TitleCategory,
    val page: Int
) : TitleMenuHolder(owner)

class TitleMenus(
    private val plugin: Hjh_database,
    private val manager: TitleManager
) {
    companion object {
        const val ROOT_HEAD_SLOT = 11
        const val ROOT_LIBRARY_SLOT = 13
        const val ROOT_PURCHASE_SLOT = 15
        const val PURCHASE_HEAD_SLOT = 4
        val PURCHASE_BUTTON_SLOTS = listOf(12, 13, 14)
        const val PURCHASE_BACK_SLOT = 22
        const val LIBRARY_PREVIOUS_SLOT = 45
        const val LIBRARY_BACK_SLOT = 49
        const val LIBRARY_NEXT_SLOT = 53
        const val TITLES_PER_PAGE = 36
        val CATEGORY_SLOTS = TitleCategory.entries.mapIndexed { index, category -> (2 + index) to category }.toMap()
        private val OBTAINED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"))
    }

    private val titleIdKey = NamespacedKey(plugin, "title_menu_id")

    fun openRoot(player: Player, profile: PlayerTitleProfile? = null) {
        val activeProfile = profile ?: manager.getProfile(player.uniqueId) ?: return
        val holder = TitleRootHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, 27, Component.text("称号系统", NamedTextColor.DARK_GRAY))
        holder.backingInventory = inventory
        fill(inventory, menuItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")))

        inventory.setItem(ROOT_HEAD_SLOT, playerHead(player, activeProfile, false))
        inventory.setItem(
            ROOT_LIBRARY_SLOT,
            menuItem(
                Material.CHEST,
                Component.text("称号库", NamedTextColor.GOLD),
                listOf(Component.text("查看、装扮和修改已解锁称号", NamedTextColor.GRAY))
            )
        )
        inventory.setItem(
            ROOT_PURCHASE_SLOT,
            menuItem(
                Material.IRON_INGOT,
                Component.text("购买自定义称号", NamedTextColor.AQUA),
                listOf(
                    Component.text("每位玩家最多购买 ${manager.settings().maxCustomTitles} 个", NamedTextColor.GRAY),
                    Component.text("点击查看购买价格", NamedTextColor.YELLOW)
                )
            )
        )
        player.openInventory(inventory)
    }

    fun openLibrary(
        player: Player,
        profile: PlayerTitleProfile? = null,
        category: TitleCategory = TitleCategory.CUSTOM,
        requestedPage: Int = 0
    ) {
        val activeProfile = profile ?: manager.getProfile(player.uniqueId) ?: return
        val definitions = manager.sortedDefinitions(category, activeProfile)
        val maxPage = ((definitions.size - 1).coerceAtLeast(0)) / TITLES_PER_PAGE
        val page = requestedPage.coerceIn(0, maxPage)
        val holder = TitleLibraryHolder(player.uniqueId, category, page)
        val inventory = Bukkit.createInventory(
            holder,
            54,
            Component.text("称号库 - ${category.displayName}", NamedTextColor.DARK_GRAY)
        )
        holder.backingInventory = inventory

        val border = menuItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
        for (slot in 0..8) inventory.setItem(slot, border)
        for (slot in 45..53) inventory.setItem(slot, border)

        for ((slot, categoryButton) in CATEGORY_SLOTS) {
            val selected = categoryButton == category
            inventory.setItem(
                slot,
                menuItem(
                    categoryButton.icon,
                    Component.text(
                        (if (selected) "▶ " else "") + categoryButton.displayName,
                        if (selected) NamedTextColor.GREEN else NamedTextColor.WHITE
                    ),
                    listOf(Component.text(if (selected) "当前分类" else "点击切换分类", NamedTextColor.GRAY))
                )
            )
        }

        val pageItems = definitions.drop(page * TITLES_PER_PAGE).take(TITLES_PER_PAGE)
        for ((index, definition) in pageItems.withIndex()) {
            inventory.setItem(9 + index, titleItem(definition, activeProfile))
        }

        if (page > 0) {
            inventory.setItem(
                LIBRARY_PREVIOUS_SLOT,
                menuItem(Material.ARROW, Component.text("上一页", NamedTextColor.YELLOW))
            )
        }
        inventory.setItem(
            LIBRARY_BACK_SLOT,
            menuItem(
                Material.BARRIER,
                Component.text("返回称号系统", NamedTextColor.RED),
                listOf(Component.text("第 ${page + 1}/${maxPage + 1} 页", NamedTextColor.GRAY))
            )
        )
        if (page < maxPage) {
            inventory.setItem(
                LIBRARY_NEXT_SLOT,
                menuItem(Material.ARROW, Component.text("下一页", NamedTextColor.YELLOW))
            )
        }
        player.openInventory(inventory)
    }

    fun openPurchase(player: Player, profile: PlayerTitleProfile? = null) {
        val activeProfile = profile ?: manager.getProfile(player.uniqueId) ?: return
        val holder = TitlePurchaseHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(
            holder,
            27,
            Component.text("购买自定义称号", NamedTextColor.DARK_GRAY)
        )
        holder.backingInventory = inventory
        fill(inventory, menuItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")))

        inventory.setItem(PURCHASE_HEAD_SLOT, playerHead(player, activeProfile, true))
        val ids = manager.customDefinitionIds()
        val nextIndex = ids.indexOfFirst { it !in activeProfile.ownedTitles }
        for ((index, slot) in PURCHASE_BUTTON_SLOTS.withIndex()) {
            val cost = manager.settings().customCosts.getOrElse(index) {
                manager.settings().customCosts.lastOrNull() ?: 20
            }
            val titleId = ids.getOrNull(index)
            val owned = titleId != null && titleId in activeProfile.ownedTitles
            val available = index == nextIndex
            val name = when {
                titleId == null -> "未配置的购买位"
                owned -> "第 ${index + 1} 个自定义称号（已购买）"
                available -> "购买第 ${index + 1} 个自定义称号"
                else -> "第 ${index + 1} 个自定义称号（未解锁）"
            }
            val lore = when {
                titleId == null -> listOf(Component.text("请在 titles/custom.yml 中补充此称号", NamedTextColor.RED))
                owned -> listOf(
                    Component.text("已拥有", NamedTextColor.GREEN),
                    Component.text("可在称号库中右键修改名称", NamedTextColor.GRAY)
                )
                available -> listOf(
                    Component.text("价格：$cost 张银票", NamedTextColor.YELLOW),
                    Component.text("点击购买", NamedTextColor.GREEN)
                )
                else -> listOf(Component.text("请先购买左侧称号", NamedTextColor.RED))
            }
            inventory.setItem(
                slot,
                menuItem(
                    Material.IRON_INGOT,
                    Component.text(name, if (available) NamedTextColor.AQUA else NamedTextColor.GRAY),
                    lore
                )
            )
        }
        inventory.setItem(
            PURCHASE_BACK_SLOT,
            menuItem(Material.ARROW, Component.text("返回称号系统", NamedTextColor.RED))
        )
        player.openInventory(inventory)
    }

    fun titleId(item: ItemStack?): String? = item?.itemMeta?.persistentDataContainer
        ?.get(titleIdKey, PersistentDataType.STRING)

    private fun playerHead(player: Player, profile: PlayerTitleProfile, purchaseView: Boolean): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta ?: return head
        meta.owningPlayer = player
        meta.displayName(withoutItalics(Component.text(player.name, NamedTextColor.GOLD)))
        val current = manager.equippedTitle(profile)?.let { (definition, owned) ->
            TitleTextFormatter.component(manager.displayText(definition, owned))
        } ?: Component.text("未装扮", NamedTextColor.GRAY)
        val lore = mutableListOf<Component>()
        lore += Component.text("当前称号：", NamedTextColor.WHITE).append(current)
        if (purchaseView) {
            val customIds = manager.customDefinitionIds().toSet()
            val count = profile.ownedTitles.keys.count { it in customIds }
            lore += Component.text("已购买自定义称号：$count/${manager.settings().maxCustomTitles}", NamedTextColor.AQUA)
        }
        meta.lore(lore.map(::withoutItalics))
        head.itemMeta = meta
        return head
    }

    private fun titleItem(definition: TitleDefinition, profile: PlayerTitleProfile): ItemStack {
        val owned = profile.ownedTitles[definition.id]
        val unlocked = owned != null
        val item = ItemStack(if (unlocked) Material.NAME_TAG else Material.RED_STAINED_GLASS_PANE)
        val meta = item.itemMeta ?: return item
        val status = if (unlocked) "§a[已解锁] " else "§8[未解锁] "
        meta.displayName(withoutItalics(TitleTextFormatter.component(status + manager.displayText(definition, owned))))
        val lore = definition.lore.map { TitleTextFormatter.component(it) }.toMutableList()
        if (lore.isNotEmpty()) lore += Component.empty()
        if (owned != null) {
            lore += Component.text("获得时间：${obtainedAt(owned.obtainedAt)}", NamedTextColor.GRAY)
            lore += Component.text(
                if (profile.equippedTitleId == definition.id) "当前正在装扮（左键卸下）" else "左键装扮",
                NamedTextColor.YELLOW
            )
            if (definition.category == TitleCategory.CUSTOM) {
                lore += Component.text("右键修改自定义名称", NamedTextColor.AQUA)
            }
        } else {
            lore += Component.text("尚未解锁", NamedTextColor.RED)
        }
        meta.lore(lore.map(::withoutItalics))
        meta.persistentDataContainer.set(titleIdKey, PersistentDataType.STRING, definition.id)
        item.itemMeta = meta
        return item
    }

    private fun menuItem(
        material: Material,
        name: Component,
        lore: List<Component> = emptyList()
    ): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(withoutItalics(name))
        if (lore.isNotEmpty()) meta.lore(lore.map(::withoutItalics))
        item.itemMeta = meta
        return item
    }

    private fun fill(inventory: Inventory, item: ItemStack) {
        for (slot in 0 until inventory.size) inventory.setItem(slot, item)
    }

    private fun obtainedAt(timestamp: Long): String =
        if (timestamp <= 0L) "未知" else OBTAINED_FORMAT.format(Instant.ofEpochMilli(timestamp))

    /**
     * 只关闭物品文本默认的斜体继承；子组件显式设置的 &o 仍应保留。
     */
    private fun withoutItalics(component: Component): Component =
        component.decoration(TextDecoration.ITALIC, false)
}
