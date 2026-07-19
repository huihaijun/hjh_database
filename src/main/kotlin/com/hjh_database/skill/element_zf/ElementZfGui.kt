package com.hjh_database.skill.element_zf.gui

import com.hjh_database.Hjh_database
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File

class ElementZfGui(private val plugin: Hjh_database) : Listener {

    private val guiTitle = "§5阵法元素升级"
    private val blockKey = NamespacedKey(plugin, "special_amethyst_block")

    // === 新增：坐标数据持久化管理 ===
    private val savedBlocksFile = File(plugin.dataFolder, "zf_blocks.yml")
    private val savedBlocksConfig = YamlConfiguration.loadConfiguration(savedBlocksFile)
    private val specialBlockLocations = mutableSetOf<Location>()

    init {
        loadLocations()
    }

    // 从文件加载已放置的阵法台坐标
    private fun loadLocations() {
        if (!savedBlocksFile.exists()) return
        val list = savedBlocksConfig.getStringList("locations")
        list.forEach { locStr ->
            val parts = locStr.split(",")
            if (parts.size == 4) {
                val world = Bukkit.getWorld(parts[0])
                if (world != null) {
                    specialBlockLocations.add(Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble()))
                }
            }
        }
    }

    // 保存坐标到文件
    private fun saveLocations() {
        val list = specialBlockLocations.map { "${it.world.name},${it.blockX},${it.blockY},${it.blockZ}" }
        savedBlocksConfig.set("locations", list)
        savedBlocksConfig.save(savedBlocksFile)
    }
    // ===================================

    private val elements = listOf(
        ElementInfo("METAL", "金元素 —— 星云术", 10004, "metal"),
        ElementInfo("WOOD", "木元素 —— 汲魂术", 10005, "wood"),
        ElementInfo("WATER", "水元素 —— 霜冻术", 10006, "water"),
        ElementInfo("FIRE", "火元素 —— 流火术", 10007, "fire"),
        ElementInfo("EARTH", "土元素 —— 裂地术", 10008, "earth")
    )

    data class ElementInfo(val type: String, val title: String, val modelData: Int, val resourceId: String)

    fun getSpecialAmethystBlock(): ItemStack {
        val item = ItemStack(Material.AMETHYST_BLOCK)
        val meta = item.itemMeta
        meta.setDisplayName("§5§l阵法升级控制台")
        meta.lore = listOf("§7右键打开阵法升级面板", "§8(放置在地上作为固定的升级台)")
        meta.persistentDataContainer.set(blockKey, PersistentDataType.BYTE, 1)
        item.itemMeta = meta
        return item
    }

    // 1. 监听管理员放置方块，记录坐标
    @EventHandler
    fun onBlockPlace(e: BlockPlaceEvent) {
        val item = e.itemInHand
        if (item.type == Material.AMETHYST_BLOCK && item.hasItemMeta()) {
            if (item.itemMeta.persistentDataContainer.has(blockKey, PersistentDataType.BYTE)) {
                // 将这个方块的坐标记录下来，并保存
                specialBlockLocations.add(e.block.location)
                saveLocations()
                e.player.sendMessage("§a[系统] 成功建立阵法升级台！")
            }
        }
    }

    // 2. 监听玩家右键地上的方块
    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action == Action.RIGHT_CLICK_BLOCK) {
            val block = e.clickedBlock ?: return

            // 检查是不是紫水晶，并且坐标是否在我们的记录本里
            if (block.type == Material.AMETHYST_BLOCK && specialBlockLocations.contains(block.location)) {
                e.isCancelled = true // 阻止原版交互

                val data = plugin.playerManager.getPlayerData(e.player)
                if (data == null || data.job != 2) {
                    e.player.sendMessage("§c你不是术士，无法使用此水晶")
                    return
                }

                openGui(e.player)
            }
        }
    }

    // 3. 监听破坏方块，防止变成普通的紫水晶掉落
    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        val block = e.block
        if (block.type == Material.AMETHYST_BLOCK && specialBlockLocations.contains(block.location)) {
            // 清除坐标记录
            specialBlockLocations.remove(block.location)
            saveLocations()

            // 取消原版掉落，掉落我们特制的物品
            e.isDropItems = false
            block.world.dropItemNaturally(block.location, getSpecialAmethystBlock())

            e.player.sendMessage("§c[系统] 阵法升级台已被拆除。")
        }
    }

    private fun openGui(player: Player) {
        val inv = Bukkit.createInventory(null, 27, guiTitle)
        val data = plugin.playerManager.getPlayerData(player) ?: return
        val config = plugin.elementZfManager.getConfig() ?: return
        val furnaceRarity = plugin.elementZfManager.getActiveFurnaceRarity(player)

        val slots = listOf(11, 12, 13, 14, 15)

        for (i in elements.indices) {
            val el = elements[i]
            val currentLevel = data.elementLevels.getOrDefault(el.type, 1)
            val effectiveLevel = if (furnaceRarity != null) {
                minOf(currentLevel, furnaceRarity)
            } else {
                currentLevel
            }

            val item = ItemStack(Material.GOLD_NUGGET)
            val meta = item.itemMeta
            meta.setDisplayName("§6§l${el.title}")
            meta.setCustomModelData(el.modelData)

            val lore = mutableListOf<String>()
            lore.add("§8阵法升级等级: §e$currentLevel")
            if (furnaceRarity == null) {
                lore.add("§c未激活可用法炉，以下仅预览升级等级效果")
            } else {
                lore.add("§8当前法炉稀有度: §b${furnaceRarity}阶")
                lore.add("§8当前生效等级: §a${effectiveLevel}级")
                if (effectiveLevel < currentLevel) {
                    lore.add("§c受法炉限制，当前仅生效${effectiveLevel}级阵法效果")
                }
            }
            lore.add("")

            // 描述与实际释放使用同一有效等级，避免界面显示高阶、释放却是低阶。
            val path = "skills.${el.type}.levels.$effectiveLevel"
            when (el.type) {
                "METAL" -> {
                    val range = config.getDouble("$path.range", 0.0)
                    val radius = config.getDouble("$path.effect_radius", 0.0)
                    val targets = config.getInt("$path.max_targets", 0)
                    val dmg = (config.getDouble("$path.damage_percent", 0.0) * 100).toInt()
                    val cd = config.getDouble("$path.cooldown", 0.0)
                    val ll = config.getDouble("$path.lingli_add", 0.0)
                    lore.add("§7在准心 §f${range}格 §7距离的位置释放一片半径为 §f${radius}格 §7的星云，")
                    lore.add("§7对星云下方 §c${targets}个 §7怪物造成 §c${dmg}% §7阵法强度的伤害，")
                    lore.add("§7冷却 §b${cd}秒§7。释放后增加 §a${ll}点 §7灵力。")
                }
                "WOOD" -> {
                    val range = config.getDouble("$path.range", 0.0)
                    val dmg = (config.getDouble("$path.damage_percent", 0.0) * 100).toInt()
                    val heal = (config.getDouble("$path.heal_percent", 0.0) * 100).toInt()
                    val cd = config.getDouble("$path.cooldown", 0.0)
                    val ll = config.getDouble("$path.lingli_add", 0.0)
                    lore.add("§7向准心方向 §f${range}格 §7离你最近的一名怪物链接魂链，")
                    lore.add("§7对其造成 §c${dmg}% §7阵法强度的伤害，")
                    lore.add("§7并将伤害的 §a${heal}% §7转化为自身生命，")
                    lore.add("§7冷却 §b${cd}秒§7。释放后增加 §a${ll}点 §7灵力。")
                }
                "WATER" -> {
                    val range = config.getDouble("$path.range", 0.0)
                    val dmg = (config.getDouble("$path.damage_percent", 0.0) * 100).toInt()
                    val dur = config.getDouble("$path.slow_duration", 0.0)
                    val amp = config.getInt("$path.slow_amplifier", 1)
                    val cd = config.getDouble("$path.cooldown", 0.0)
                    val ll = config.getDouble("$path.lingli_add", 0.0)
                    lore.add("§7向前方 §f${range}格 §7的锥形范围泼洒冰霜，")
                    lore.add("§7对怪物造成 §c${dmg}% §7阵法强度的伤害，")
                    lore.add("§7同时对其施加 §9${dur}秒 §7的减速 §9${amp}级 §7效果，")
                    lore.add("§7冷却 §b${cd}秒§7。释放后增加 §a${ll}点 §7灵力。")
                }
                "FIRE" -> {
                    val range = config.getDouble("$path.range", 0.0)
                    val dmg = (config.getDouble("$path.damage_percent", 0.0) * 100).toInt()
                    val cd = config.getDouble("$path.cooldown", 0.0)
                    val ll = config.getDouble("$path.lingli_add", 0.0)
                    lore.add("§7选择准心方向 §f${range}格 §7离你最近的一名怪物，")
                    lore.add("§7对其造成 §c${dmg}% §7阵法强度的伤害，")
                    lore.add("§7冷却 §b${cd}秒§7。释放后增加 §a${ll}点 §7灵力。")
                }
                "EARTH" -> {
                    val range = config.getDouble("$path.range", 0.0)
                    val dur = config.getDouble("$path.duration", 0.0)
                    val radius = config.getDouble("$path.radius", 0.0)
                    val cd = config.getDouble("$path.cooldown", 0.0)
                    val ll = config.getDouble("$path.lingli_add", 0.0)
                    lore.add("§7向准心方向 §f${range}格 §7距离建立阵眼，")
                    lore.add("§e${dur}秒 §7内卷起沙尘，将阵眼范围内 §f${radius}格 §7的怪物")
                    lore.add("§7每秒向阵眼中央牵引，冷却 §b${cd}秒§7。")
                    lore.add("§7释放后增加 §a${ll}点 §7灵力。")
                }
            }

            lore.add("")
            if (currentLevel >= 5) {
                lore.add("§a§l已满级")
            } else {
                lore.add("§e§l【左键升级到下一级】")
                val req = getUpgradeRequirement(currentLevel)
                lore.add("§7所需等级: §f${req.level}级")
                lore.add("§7所需元素: §f${req.elementAmount}个")

                val moneyName = when (req.moneyId) {
                    "hjh_tongqian" -> "铜钱"
                    "jinyuanbao" -> "金元宝"
                    "yinpiao" -> "银票"
                    else -> "货币"
                }
                lore.add("§7所需金钱: §f${req.moneyAmount}个 ($moneyName)")
            }

            meta.lore = lore
            meta.persistentDataContainer.set(NamespacedKey(plugin, "zf_type"), PersistentDataType.STRING, el.type)
            item.itemMeta = meta
            inv.setItem(slots[i], item)
        }

        player.openInventory(inv)
    }

    data class UpgradeRequirement(val level: Int, val elementAmount: Int, val moneyId: String, val moneyAmount: Int)

    private fun getUpgradeRequirement(currentLevel: Int): UpgradeRequirement {
        return when (currentLevel) {
            1 -> UpgradeRequirement(5, 32, "hjh_tongqian", 30)
            2 -> UpgradeRequirement(10, 64, "jinyuanbao", 5)
            3 -> UpgradeRequirement(20, 128, "yinpiao", 2)
            4 -> UpgradeRequirement(30, 256, "yinpiao", 5)
            else -> UpgradeRequirement(999, 999, "none", 999)
        }
    }

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        if (e.view.title != guiTitle) return
        e.isCancelled = true

        val player = e.whoClicked as Player
        val item = e.currentItem ?: return
        if (!item.hasItemMeta()) return

        val meta = item.itemMeta
        val zfType = meta.persistentDataContainer.get(NamespacedKey(plugin, "zf_type"), PersistentDataType.STRING) ?: return
        val elInfo = elements.find { it.type == zfType } ?: return

        val data = plugin.playerManager.getPlayerData(player) ?: return
        val currentLevel = data.elementLevels.getOrDefault(zfType, 1)

        if (currentLevel >= 5) {
            player.sendMessage("§c该阵法已满级！")
            return
        }

        val req = getUpgradeRequirement(currentLevel)

        if (data.lv < req.level) {
            player.sendMessage("§c升级失败！需要玩家等级达到 ${req.level} 级。")
            return
        }

        val elementItemOpt = plugin.resourceManager.getItem(elInfo.resourceId)
        if (elementItemOpt == null) {
            player.sendMessage("§c服务器未配置元素资源物品: ${elInfo.resourceId}")
            return
        }
        if (!hasEnoughItem(player.inventory, elementItemOpt, req.elementAmount)) {
            player.sendMessage("§c升级失败！你没有足够的 ${elementItemOpt.itemMeta?.displayName ?: elInfo.resourceId} §c(${req.elementAmount}个)。")
            return
        }

        val moneyItemOpt = plugin.resourceManager.getItem(req.moneyId)
        if (moneyItemOpt == null) {
            player.sendMessage("§c服务器未配置货币物品: ${req.moneyId}")
            return
        }
        if (!hasEnoughItem(player.inventory, moneyItemOpt, req.moneyAmount)) {
            player.sendMessage("§c升级失败！你没有足够的 ${moneyItemOpt.itemMeta?.displayName ?: req.moneyId} §c(${req.moneyAmount}个)。")
            return
        }

        consumeItem(player.inventory, elementItemOpt, req.elementAmount)
        consumeItem(player.inventory, moneyItemOpt, req.moneyAmount)

        data.elementLevels[zfType] = currentLevel + 1

        plugin.databaseManager.savePlayerAsync(data)

        player.sendMessage("§a恭喜！你的 ${elInfo.title} §a已成功升级至 §e${currentLevel + 1} §a级！")
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

        openGui(player)
    }

    private fun hasEnoughItem(inv: Inventory, target: ItemStack, amount: Int): Boolean {
        var count = 0
        for (item in inv.contents) {
            if (item != null && item.isSimilar(target)) {
                count += item.amount
            }
        }
        return count >= amount
    }

    private fun consumeItem(inv: Inventory, target: ItemStack, amount: Int) {
        var remaining = amount
        for (i in 0 until inv.size) {
            val item = inv.getItem(i)
            if (item != null && item.isSimilar(target)) {
                if (item.amount >= remaining) {
                    item.amount -= remaining
                    break
                } else {
                    remaining -= item.amount
                    inv.setItem(i, null)
                }
            }
        }
    }
}
