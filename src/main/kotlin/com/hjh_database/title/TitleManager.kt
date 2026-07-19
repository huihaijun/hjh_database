package com.hjh_database.title

import com.hjh_database.Hjh_database
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scoreboard.Team
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class TitleManager(private val plugin: Hjh_database) {
    companion object {
        const val CURRENCY_RESOURCE_ID = "yinpiao"
        private const val TITLE_DIRECTORY = "titles"
        private val OBTAINED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"))
    }

    private val repository = TitleRepository(plugin)
    private val profiles = ConcurrentHashMap<UUID, PlayerTitleProfile>()
    private val loadingProfiles = ConcurrentHashMap<UUID, CompletableFuture<PlayerTitleProfile>>()
    private val renderedTitles = ConcurrentHashMap<UUID, RenderedTitle>()
    private val pendingWrites = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingPurchases = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingRenames = ConcurrentHashMap<UUID, String>()
    private val originalTeams = ConcurrentHashMap<UUID, OriginalTeamState>()
    private val originalTabNames = mutableMapOf<UUID, OriginalTabName>()
    private val resourceIdKey = NamespacedKey(plugin, "resource_id")
    val menus = TitleMenus(plugin, this)

    @Volatile
    private var snapshot: TitleConfigSnapshot

    init {
        ensureDefaultResources()
        snapshot = readSnapshot()
    }

    fun settings(): TitleSettings = snapshot.settings

    fun definitions(category: TitleCategory): List<TitleDefinition> =
        snapshot.byCategory[category].orEmpty()

    fun definition(titleId: String): TitleDefinition? = snapshot.definitions[titleId]

    fun definitionIds(): List<String> = snapshot.definitions.keys.toList()

    fun customDefinitionIds(): List<String> = definitions(TitleCategory.CUSTOM)
        .take(settings().maxCustomTitles)
        .map { it.id }

    fun getProfile(uuid: UUID): PlayerTitleProfile? = profiles[uuid]

    fun loadPlayer(player: Player): CompletableFuture<PlayerTitleProfile> {
        profiles[player.uniqueId]?.let { return CompletableFuture.completedFuture(it) }
        return loadingProfiles.computeIfAbsent(player.uniqueId) { uuid ->
            plugin.databaseManager.submitDatabaseOperation {
                repository.loadProfile(uuid, player.name, settings())
            }.whenComplete { profile, throwable ->
                loadingProfiles.remove(uuid)
                if (throwable != null) {
                    plugin.logger.severe("加载玩家 ${player.name} 的称号数据失败: ${rootMessage(throwable)}")
                    return@whenComplete
                }
                if (!player.isOnline) return@whenComplete
                profiles[uuid] = profile
                runSync {
                    if (player.isOnline) applyVisualDisplay(player, profile)
                }
            }
        }
    }

    fun openMainMenu(player: Player) {
        val cached = profiles[player.uniqueId]
        if (cached != null) {
            menus.openRoot(player, cached)
            return
        }
        player.sendMessage("§7[称号] 正在读取称号数据……")
        loadPlayer(player).whenComplete { profile, throwable ->
            runSync {
                if (!player.isOnline) return@runSync
                if (throwable != null) {
                    player.sendMessage("§c[称号] 数据读取失败，请稍后重试。")
                } else {
                    menus.openRoot(player, profile)
                }
            }
        }
    }

    fun unloadPlayer(player: Player) {
        profiles.remove(player.uniqueId)
        renderedTitles.remove(player.uniqueId)
        loadingProfiles.remove(player.uniqueId)
        pendingWrites.remove(player.uniqueId)
        pendingPurchases.remove(player.uniqueId)
        pendingRenames.remove(player.uniqueId)
        clearVisualDisplay(player)
    }

    fun reloadPlayerAfterReset(player: Player) {
        profiles.remove(player.uniqueId)
        renderedTitles.remove(player.uniqueId)
        loadingProfiles.remove(player.uniqueId)
        pendingRenames.remove(player.uniqueId)
        clearVisualDisplay(player)
        if (player.isOnline) loadPlayer(player)
    }

    fun shutdown() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.openInventory.topInventory.holder is TitleMenuHolder) player.closeInventory()
            clearVisualDisplay(player)
        }
        profiles.clear()
        renderedTitles.clear()
        pendingRenames.clear()
        pendingWrites.clear()
        pendingPurchases.clear()
        originalTeams.clear()
        originalTabNames.clear()
    }

    fun sortedDefinitions(category: TitleCategory, profile: PlayerTitleProfile): List<TitleDefinition> {
        return definitions(category).sortedWith(
            compareBy<TitleDefinition> { if (profile.ownedTitles.containsKey(it.id)) 0 else 1 }
                .thenBy { it.order }
        )
    }

    fun displayText(definition: TitleDefinition, owned: OwnedTitle?): String {
        return if (definition.category == TitleCategory.CUSTOM && !owned?.customText.isNullOrBlank()) {
            owned!!.customText!!
        } else {
            definition.name
        }
    }

    fun equippedTitle(profile: PlayerTitleProfile): Pair<TitleDefinition, OwnedTitle>? {
        val titleId = profile.equippedTitleId ?: return null
        val definition = definition(titleId) ?: return null
        val owned = profile.ownedTitles[titleId] ?: return null
        return definition to owned
    }

    fun equippedComponent(profile: PlayerTitleProfile, hover: Boolean = true): Component? {
        val equipped = equippedTitle(profile)
        if (equipped == null) {
            renderedTitles.remove(profile.uuid)
            return null
        }
        val (definition, owned) = equipped
        val cached = renderedTitles.compute(profile.uuid) { _, old ->
            if (old != null && old.definition == definition && old.owned == owned) {
                old
            } else {
                // 称号必须是无父级样式的独立组件，不能让颜色/格式继承到玩家名或聊天正文。
                val plainComponent = Component.empty()
                    .append(Component.text("「", NamedTextColor.DARK_GRAY))
                    .append(TitleTextFormatter.component(displayText(definition, owned)))
                    .append(Component.text("」", NamedTextColor.DARK_GRAY))
                RenderedTitle(
                    definition,
                    owned,
                    plainComponent,
                    plainComponent.hoverEvent(HoverEvent.showText(hoverComponent(definition, owned)))
                )
            }
        } ?: return null
        return if (hover) cached.withHover else cached.withoutHover
    }

    fun equip(player: Player, titleId: String) {
        val current = profiles[player.uniqueId] ?: return
        if (!current.ownedTitles.containsKey(titleId)) {
            player.sendMessage("§c[称号] 该称号尚未解锁。")
            return
        }
        if (!pendingWrites.add(player.uniqueId)) {
            player.sendMessage("§e[称号] 上一次操作仍在保存，请稍候。")
            return
        }
        val targetTitle = if (current.equippedTitleId == titleId) null else titleId
        plugin.databaseManager.submitDatabaseOperation {
            repository.setEquipped(player.uniqueId, player.name, targetTitle, settings())
        }.whenComplete { updated, throwable ->
            pendingWrites.remove(player.uniqueId)
            runSync {
                if (throwable != null) {
                    player.sendMessage("§c[称号] 保存失败：${rootMessage(throwable)}")
                    return@runSync
                }
                if (player.isOnline) {
                    profiles[player.uniqueId] = updated
                    applyVisualDisplay(player, updated)
                    player.sendMessage(if (targetTitle == null) "§7[称号] 已卸下当前称号。" else "§a[称号] 已装扮该称号。")
                    menus.openLibrary(player, updated, definition(titleId)?.category ?: TitleCategory.CUSTOM, 0)
                }
            }
        }
    }

    fun purchaseCustom(player: Player, requestedIndex: Int) {
        val profile = profiles[player.uniqueId] ?: return
        val settings = settings()
        val ids = customDefinitionIds()
        if (requestedIndex !in ids.indices || requestedIndex >= settings.maxCustomTitles) return
        val nextIndex = ids.indexOfFirst { it !in profile.ownedTitles }
        if (nextIndex == -1) {
            player.sendMessage("§c[称号] 你已达到自定义称号购买上限。")
            return
        }
        if (requestedIndex != nextIndex) {
            player.sendMessage("§c[称号] 请从左到右依次购买。")
            return
        }
        if (!pendingPurchases.add(player.uniqueId)) {
            player.sendMessage("§e[称号] 购买正在处理中，请勿重复点击。")
            return
        }

        val cost = settings.customCosts.getOrElse(requestedIndex) { settings.customCosts.lastOrNull() ?: 20 }
        if (!removeCurrency(player, cost)) {
            pendingPurchases.remove(player.uniqueId)
            player.sendMessage("§c[称号] 银票不足，需要 $cost 张银票。")
            return
        }

        plugin.databaseManager.submitDatabaseOperation {
            repository.purchaseCustomTitle(player.uniqueId, player.name, requestedIndex, ids, settings)
        }.whenComplete { result, throwable ->
            pendingPurchases.remove(player.uniqueId)
            runSync {
                if (throwable != null) {
                    refundCurrency(player, cost)
                    player.sendMessage("§c[称号] 购买未完成，银票已退还：${rootMessage(throwable)}")
                    return@runSync
                }
                when (result) {
                    is CustomPurchaseResult.Success -> {
                        if (player.isOnline) profiles[player.uniqueId] = result.profile
                        player.sendMessage("§a[称号] 自定义称号购买成功，可在称号库中右键改名。")
                        if (player.isOnline) menus.openPurchase(player, result.profile)
                    }
                    is CustomPurchaseResult.Rejected -> {
                        refundCurrency(player, cost)
                        player.sendMessage("§c[称号] ${result.message}，银票已退还。")
                    }
                    null -> {
                        refundCurrency(player, cost)
                        player.sendMessage("§c[称号] 购买结果异常，银票已退还。")
                    }
                }
            }
        }
    }

    fun beginRename(player: Player, titleId: String) {
        val profile = profiles[player.uniqueId] ?: return
        val definition = definition(titleId) ?: return
        if (definition.category != TitleCategory.CUSTOM || titleId !in profile.ownedTitles) return
        pendingRenames[player.uniqueId] = titleId
        player.closeInventory()
        player.sendMessage("§6[称号] 请在聊天框输入新称号，输入 §c取消 §6可退出。")
        player.sendMessage("§7支持 & 颜色、&#RRGGBB 十六进制和 <gradient:#RRGGBB:#RRGGBB>文字</gradient> 渐变。")
        player.sendMessage("§7最多 ${settings().maxCustomNameLength} 个字，颜色代码不计入长度。")
    }

    fun isAwaitingRename(uuid: UUID): Boolean = pendingRenames.containsKey(uuid)

    fun handleRenameInput(player: Player, rawInput: String) {
        val titleId = pendingRenames.remove(player.uniqueId) ?: return
        val input = rawInput.trim()
        if (input.equals("取消", true) || input.equals("cancel", true)) {
            runSync { if (player.isOnline) player.sendMessage("§7[称号] 已取消修改。") }
            return
        }

        val validationError = TitleTextFormatter.validateCustomInput(input, settings().maxCustomNameLength)
        if (validationError != null) {
            pendingRenames[player.uniqueId] = titleId
            runSync {
                player.sendMessage("§c[称号] $validationError，请重新输入或输入“取消”。")
            }
            return
        }
        if (!pendingWrites.add(player.uniqueId)) {
            pendingRenames[player.uniqueId] = titleId
            runSync { player.sendMessage("§e[称号] 数据仍在保存，请稍后重新输入。") }
            return
        }

        plugin.databaseManager.submitDatabaseOperation {
            repository.renameCustomTitle(player.uniqueId, player.name, titleId, input, settings())
        }.whenComplete { updated, throwable ->
            pendingWrites.remove(player.uniqueId)
            runSync {
                if (throwable != null) {
                    player.sendMessage("§c[称号] 修改失败：${rootMessage(throwable)}")
                    return@runSync
                }
                if (player.isOnline) {
                    profiles[player.uniqueId] = updated
                    applyVisualDisplay(player, updated)
                }
                player.sendMessage(Component.text("[称号] 修改成功：", NamedTextColor.GREEN)
                    .append(TitleTextFormatter.component(input)))
            }
        }
    }

    fun reloadAsync(sender: CommandSender? = null) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val result = runCatching { readSnapshot() }
            runSync {
                result.onSuccess { loaded ->
                    snapshot = loaded
                    renderedTitles.clear()
                    for (player in Bukkit.getOnlinePlayers()) {
                        profiles[player.uniqueId]?.let { applyVisualDisplay(player, it) }
                    }
                    sender?.sendMessage("§a[称号] 已异步重载 ${loaded.definitions.size} 个称号配置。")
                }.onFailure { ex ->
                    plugin.logger.severe("重载称号配置失败: ${rootMessage(ex)}")
                    sender?.sendMessage("§c[称号] 重载失败：${rootMessage(ex)}")
                }
            }
        })
    }

    fun handleAdminCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sendAdminHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "reload", "重载" -> reloadAsync(sender)
            "list", "view", "查看" -> {
                if (args.size < 2) return adminError(sender, "用法: /hjhadmin title list <玩家>")
                withResolvedProfile(args[1]) { target, profile, error ->
                    if (error != null || target == null || profile == null) {
                        sender.sendMessage("§c[称号] ${error ?: "未找到玩家"}")
                        return@withResolvedProfile
                    }
                    sender.sendMessage("§6=== ${target.playerName} 的称号 (${profile.ownedTitles.size}) ===")
                    if (profile.ownedTitles.isEmpty()) sender.sendMessage("§7尚未获得任何称号。")
                    for (owned in profile.ownedTitles.values.sortedBy { it.obtainedAt }) {
                        val definition = definition(owned.titleId)
                        val name = definition?.let { displayText(it, owned) } ?: owned.titleId
                        val marker = if (profile.equippedTitleId == owned.titleId) "§a[佩戴] " else "§7- "
                        sender.sendMessage(TitleTextFormatter.component(marker)
                            .append(TitleTextFormatter.component(name))
                            .append(Component.text(" §8(${owned.titleId}, ${formatObtainedAt(owned.obtainedAt)})")))
                    }
                    sender.sendMessage("§7显示：聊天=${profile.showChat} 头顶=${profile.showOverhead} TAB=${profile.showTab}")
                }
            }
            "give", "grant", "给予" -> {
                if (args.size < 3) return adminError(sender, "用法: /hjhadmin title give <玩家> <称号ID>")
                val definition = definition(args[2])
                    ?: return adminError(sender, "称号 ID 不存在: ${args[2]}")
                mutateResolved(args[1]) { target ->
                    repository.giveTitle(target, definition.id, "admin:${sender.name}", settings())
                }.whenComplete { result, throwable ->
                    runSync {
                        if (throwable != null || result == null) {
                            sender.sendMessage("§c[称号] 给予失败：${rootMessage(throwable)}")
                            return@runSync
                        }
                        val (changed, profile) = result
                        updateCachedProfile(profile)
                        sender.sendMessage(if (changed) "§a[称号] 已给予 ${profile.playerName} 称号 ${definition.id}。" else "§e[称号] 该玩家已拥有此称号。")
                    }
                }
            }
            "take", "revoke", "remove", "撤销" -> {
                if (args.size < 3) return adminError(sender, "用法: /hjhadmin title take <玩家> <称号ID>")
                val titleId = args[2]
                mutateResolved(args[1]) { target ->
                    repository.revokeTitle(target, titleId, settings())
                }.whenComplete { result, throwable ->
                    runSync {
                        if (throwable != null || result == null) {
                            sender.sendMessage("§c[称号] 撤销失败：${rootMessage(throwable)}")
                            return@runSync
                        }
                        val (changed, profile) = result
                        updateCachedProfile(profile)
                        sender.sendMessage(if (changed) "§a[称号] 已撤销 ${profile.playerName} 的 $titleId。" else "§e[称号] 该玩家未拥有此称号。")
                    }
                }
            }
            "display", "显示" -> {
                if (args.size < 4) return adminError(sender, "用法: /hjhadmin title display <玩家> <chat|overhead|tab> <on|off>")
                val channel = TitleDisplayChannel.parse(args[2])
                    ?: return adminError(sender, "显示位置只能是 chat、overhead 或 tab。")
                val enabled = parseSwitch(args[3])
                    ?: return adminError(sender, "开关只能是 on/off、true/false 或 开/关。")
                mutateResolved(args[1]) { target ->
                    repository.setDisplay(target, channel, enabled, settings())
                }.whenComplete { profile, throwable ->
                    runSync {
                        if (throwable != null || profile == null) {
                            sender.sendMessage("§c[称号] 设置失败：${rootMessage(throwable)}")
                            return@runSync
                        }
                        updateCachedProfile(profile)
                        sender.sendMessage("§a[称号] 已将 ${profile.playerName} 的 ${channel.name.lowercase()} 显示设为 $enabled。")
                    }
                }
            }
            else -> sendAdminHelp(sender)
        }
        return true
    }

    private fun hoverComponent(definition: TitleDefinition, owned: OwnedTitle): Component {
        var hover = TitleTextFormatter.component(displayText(definition, owned))
        for (line in definition.lore) {
            hover = hover.append(Component.newline()).append(TitleTextFormatter.component(line))
        }
        return hover.append(Component.newline())
            .append(Component.text("获得时间：${formatObtainedAt(owned.obtainedAt)}", NamedTextColor.GRAY))
    }

    private fun applyVisualDisplay(player: Player, profile: PlayerTitleProfile) {
        val title = equippedComponent(profile, hover = false)
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val overheadActive = profile.showOverhead && title != null
        val tabActive = profile.showTab && title != null
        val needsTabOverride = overheadActive || tabActive

        // 在第一次改写 TAB 名称前保存精确原值；null 代表让客户端继续使用原版队伍格式。
        val originalTab = if (needsTabOverride) {
            originalTabNames.getOrPut(player.uniqueId) { OriginalTabName(player.playerListName()) }
        } else {
            originalTabNames[player.uniqueId]
        }

        val originalTeam = if (overheadActive) {
            ensureManagedOverheadTeam(player, title, scoreboard)
        } else {
            restoreOriginalTeam(player, scoreboard)
        }

        if (needsTabOverride) {
            val originalName = originalTab?.component
                ?: buildTeamFormattedName(player.name, originalTeam ?: snapshotTeam(scoreboard.getEntryTeam(player.name)))
            player.playerListName(if (tabActive) prependIsolated(title!!, originalName) else originalName)
        } else {
            originalTabNames.remove(player.uniqueId)?.let { player.playerListName(it.component) }
        }
    }

    private fun clearVisualDisplay(player: Player) {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        restoreOriginalTeam(player, scoreboard)
        originalTabNames.remove(player.uniqueId)?.let { player.playerListName(it.component) }
    }

    private fun managedTeamName(uuid: UUID): String = "ht_${uuid.toString().replace("-", "").take(12)}"

    private fun ensureManagedOverheadTeam(
        player: Player,
        title: Component,
        scoreboard: org.bukkit.scoreboard.Scoreboard
    ): OriginalTeamState {
        val managedName = managedTeamName(player.uniqueId)
        val currentTeam = scoreboard.getEntryTeam(player.name)
        val original = if (currentTeam?.name != managedName) {
            snapshotTeam(currentTeam).also { originalTeams[player.uniqueId] = it }
        } else {
            originalTeams[player.uniqueId] ?: snapshotTeam(null).also { originalTeams[player.uniqueId] = it }
        }

        val managed = scoreboard.getTeam(managedName) ?: scoreboard.registerNewTeam(managedName)
        managed.entries.toList().forEach { managed.removeEntry(it) }
        managed.prefix(prependIsolated(title, original.prefix))
        managed.suffix(original.suffix)
        managed.color(original.color)
        managed.setAllowFriendlyFire(original.allowFriendlyFire)
        managed.setCanSeeFriendlyInvisibles(original.canSeeFriendlyInvisibles)
        for (option in Team.Option.entries) {
            managed.setOption(option, original.options[option] ?: Team.OptionStatus.ALWAYS)
        }
        managed.addEntry(player.name)
        return original
    }

    private fun restoreOriginalTeam(
        player: Player,
        scoreboard: org.bukkit.scoreboard.Scoreboard
    ): OriginalTeamState? {
        val managedName = managedTeamName(player.uniqueId)
        val managed = scoreboard.getTeam(managedName)
        val current = scoreboard.getEntryTeam(player.name)
        val saved = originalTeams.remove(player.uniqueId)

        if (managed != null) {
            managed.entries.toList().forEach { managed.removeEntry(it) }
            managed.unregister()
        }

        // 若其他插件已把玩家移入新队伍，以新队伍为准，不强行恢复旧状态。
        if (current != null && current.name != managedName) return snapshotTeam(current)
        val originalTeam = saved?.teamName?.let(scoreboard::getTeam)
        originalTeam?.addEntry(player.name)
        return if (originalTeam != null) snapshotTeam(originalTeam) else saved
    }

    private fun snapshotTeam(team: Team?): OriginalTeamState {
        if (team == null) {
            return OriginalTeamState(
                teamName = null,
                prefix = Component.empty(),
                suffix = Component.empty(),
                color = NamedTextColor.WHITE,
                allowFriendlyFire = true,
                canSeeFriendlyInvisibles = false,
                options = emptyMap()
            )
        }
        return OriginalTeamState(
            teamName = team.name,
            prefix = team.prefix(),
            suffix = team.suffix(),
            color = NamedTextColor.nearestTo(team.color()),
            allowFriendlyFire = team.allowFriendlyFire(),
            canSeeFriendlyInvisibles = team.canSeeFriendlyInvisibles(),
            options = Team.Option.entries.associateWith(team::getOption)
        )
    }

    private fun buildTeamFormattedName(playerName: String, team: OriginalTeamState): Component {
        val name = if (team.teamName == null) Component.text(playerName) else Component.text(playerName, team.color)
        return Component.empty().append(team.prefix).append(name).append(team.suffix)
    }

    /**
     * 头顶称号需要把玩家临时移入独立队伍；聊天仍使用移动前的队伍姓名，
     * 避免 Paper 的计分板姓名着色读取到临时队伍后改变玩家名颜色。
     */
    fun originalChatDisplayName(player: Player, fallback: Component): Component {
        val originalTeam = originalTeams[player.uniqueId] ?: return fallback
        return buildTeamFormattedName(player.name, originalTeam)
    }

    private fun prependIsolated(title: Component, following: Component): Component {
        return Component.empty()
            .append(title)
            .append(Component.space())
            .append(following)
    }

    private fun updateCachedProfile(profile: PlayerTitleProfile) {
        if (!profiles.containsKey(profile.uuid) && Bukkit.getPlayer(profile.uuid) == null) return
        profiles[profile.uuid] = profile
        Bukkit.getPlayer(profile.uuid)?.takeIf { it.isOnline }?.let { applyVisualDisplay(it, profile) }
    }

    private fun withResolvedProfile(
        playerInput: String,
        callback: (ResolvedTitleTarget?, PlayerTitleProfile?, String?) -> Unit
    ) {
        val online = Bukkit.getPlayerExact(playerInput)
        plugin.databaseManager.submitDatabaseOperation {
            val target = online?.let { ResolvedTitleTarget(it.uniqueId, it.name) }
                ?: repository.resolveTarget(playerInput)
                ?: return@submitDatabaseOperation Triple(null, null, "找不到玩家 $playerInput")
            val profile = repository.loadProfile(target.uuid, target.playerName, settings())
            Triple(target, profile, null)
        }.whenComplete { result, throwable ->
            runSync {
                if (throwable != null || result == null) callback(null, null, rootMessage(throwable))
                else callback(result.first, result.second, result.third)
            }
        }
    }

    private fun <T> mutateResolved(
        playerInput: String,
        mutation: (ResolvedTitleTarget) -> T
    ): CompletableFuture<T> {
        val online = Bukkit.getPlayerExact(playerInput)
        return plugin.databaseManager.submitDatabaseOperation {
            val target = online?.let { ResolvedTitleTarget(it.uniqueId, it.name) }
                ?: repository.resolveTarget(playerInput)
                ?: throw IllegalArgumentException("找不到玩家 $playerInput")
            mutation(target)
        }
    }

    private fun countCurrency(player: Player): Int {
        var count = 0
        for (item in player.inventory.storageContents) {
            val id = item?.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
            if (id == CURRENCY_RESOURCE_ID) count += item.amount
        }
        return count
    }

    private fun removeCurrency(player: Player, amount: Int): Boolean {
        if (amount <= 0) return true
        if (countCurrency(player) < amount) return false
        var remaining = amount
        for (slot in player.inventory.storageContents.indices) {
            if (remaining <= 0) break
            val item = player.inventory.getItem(slot) ?: continue
            val id = item.itemMeta?.persistentDataContainer?.get(resourceIdKey, PersistentDataType.STRING)
            if (id != CURRENCY_RESOURCE_ID) continue
            val removed = minOf(remaining, item.amount)
            item.amount -= removed
            remaining -= removed
            if (item.amount <= 0) player.inventory.setItem(slot, null)
        }
        return remaining == 0
    }

    private fun refundCurrency(player: Player, amount: Int) {
        val template = plugin.resourceManager.getItem(CURRENCY_RESOURCE_ID)
        if (template == null) {
            plugin.logger.severe("无法退还 ${player.name} 的 $amount 张银票：资源 $CURRENCY_RESOURCE_ID 不存在")
            return
        }
        var remaining = amount
        while (remaining > 0) {
            val stack = template.clone()
            stack.amount = minOf(remaining, stack.maxStackSize)
            remaining -= stack.amount
            val leftovers = player.inventory.addItem(stack)
            for (leftover in leftovers.values) player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private fun readSnapshot(): TitleConfigSnapshot {
        val directory = File(plugin.dataFolder, TITLE_DIRECTORY)
        val settingsConfig = YamlConfiguration.loadConfiguration(File(directory, "settings.yml"))
        val rawCosts = settingsConfig.getIntegerList("custom.costs").ifEmpty { listOf(20, 40, 60) }
        val costs = (0 until 3).map { index -> rawCosts.getOrElse(index) { rawCosts.last() }.coerceAtLeast(0) }
        val settings = TitleSettings(
            maxCustomTitles = settingsConfig.getInt("custom.max_titles", 3).coerceIn(1, 3),
            maxCustomNameLength = settingsConfig.getInt("custom.max_name_length", 8).coerceIn(1, 64),
            customCosts = costs,
            defaultShowChat = settingsConfig.getBoolean("display_defaults.chat", true),
            defaultShowOverhead = settingsConfig.getBoolean("display_defaults.overhead", false),
            defaultShowTab = settingsConfig.getBoolean("display_defaults.tab", false)
        )

        val all = linkedMapOf<String, TitleDefinition>()
        val grouped = linkedMapOf<TitleCategory, List<TitleDefinition>>()
        var order = 0
        for (category in TitleCategory.entries) {
            val yaml = YamlConfiguration.loadConfiguration(File(directory, category.fileName))
            val section = yaml.getConfigurationSection("titles")
            val definitions = mutableListOf<TitleDefinition>()
            for (id in section?.getKeys(false).orEmpty()) {
                val titleSection = section?.getConfigurationSection(id) ?: continue
                if (!titleSection.getBoolean("enabled", true)) continue
                if (id in all) {
                    plugin.logger.warning("称号 ID $id 在多个分类中重复，已忽略 ${category.fileName} 中的重复项。")
                    continue
                }
                val material = Material.matchMaterial(titleSection.getString("material", "NAME_TAG") ?: "NAME_TAG")
                    ?: Material.NAME_TAG
                val definition = TitleDefinition(
                    id = id,
                    category = category,
                    name = titleSection.getString("name", id) ?: id,
                    lore = titleSection.getStringList("lore"),
                    material = material,
                    order = order++
                )
                all[id] = definition
                definitions += definition
            }
            grouped[category] = definitions.toList()
        }
        return TitleConfigSnapshot(settings, all.toMap(), grouped.toMap())
    }

    private fun ensureDefaultResources() {
        val resources = listOf("settings.yml") + TitleCategory.entries.map { it.fileName }
        for (name in resources) {
            val target = File(File(plugin.dataFolder, TITLE_DIRECTORY), name)
            if (!target.exists()) plugin.saveResource("$TITLE_DIRECTORY/$name", false)
        }
    }

    private fun formatObtainedAt(timestamp: Long): String =
        if (timestamp <= 0L) "未知" else OBTAINED_TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))

    private fun parseSwitch(input: String): Boolean? = when (input.lowercase()) {
        "on", "true", "1", "yes", "开", "开启" -> true
        "off", "false", "0", "no", "关", "关闭" -> false
        else -> null
    }

    private fun sendAdminHelp(sender: CommandSender) {
        sender.sendMessage("§6=== 称号管理 ===")
        sender.sendMessage("§e/hjhadmin title list <玩家> §7- 查看已获得称号")
        sender.sendMessage("§e/hjhadmin title give <玩家> <称号ID> §7- 给予称号")
        sender.sendMessage("§e/hjhadmin title take <玩家> <称号ID> §7- 撤销称号")
        sender.sendMessage("§e/hjhadmin title display <玩家> <chat|overhead|tab> <on|off>")
        sender.sendMessage("§e/hjhadmin title reload §7- 异步重载称号 YAML")
    }

    private fun adminError(sender: CommandSender, message: String): Boolean {
        sender.sendMessage("§c[称号] $message")
        return true
    }

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (Bukkit.isPrimaryThread()) block()
        else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun rootMessage(throwable: Throwable?): String {
        var root: Throwable = throwable ?: return "未知错误"
        while (root.cause != null) root = root.cause!!
        return root.message ?: root.javaClass.simpleName
    }

    private data class RenderedTitle(
        val definition: TitleDefinition,
        val owned: OwnedTitle,
        val withoutHover: Component,
        val withHover: Component
    )

    private data class OriginalTabName(val component: Component?)

    private data class OriginalTeamState(
        val teamName: String?,
        val prefix: Component,
        val suffix: Component,
        val color: NamedTextColor,
        val allowFriendlyFire: Boolean,
        val canSeeFriendlyInvisibles: Boolean,
        val options: Map<Team.Option, Team.OptionStatus>
    )
}
