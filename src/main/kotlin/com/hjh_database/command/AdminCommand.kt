package com.hjh_database.command

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.StoryNpcs
import com.hjh_database.spawner.MobRegistry
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.ArrayList

class AdminCommand(private val plugin: Hjh_database) : CommandExecutor, TabCompleter {

    // 映射表
    private val jobMap = mapOf(
        0 to "战士", 1 to "弓箭手", 2 to "术士", 3 to "医师"
    )
    private val raceMap = mapOf(
        0 to "神", 1 to "仙", 2 to "人", 3 to "战神", 4 to "妖"
    )

    // 反向查找 (使用 Kotlin 的 associate 替代 Stream)
    private val jobReverseMap = jobMap.entries.associate { (k, v) -> v to k }
    private val raceReverseMap = raceMap.entries.associate { (k, v) -> v to k }
    private val statusPresets = mapOf(
        0 to "新人进入服务器",
        1 to "新人-过前置描述-未进入盘古大陆",
        2 to "新人-已进入大陆-过剧情ing",
        3 to "大陆中",
        4 to "新人-已进入大陆-职业体验中",
        5 to "副本中",
        6 to "奈何桥中"
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限使用此管理命令。")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW.toString() + "=== HJH 管理员指令 ===")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin reload - 重载配置")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin <get|give> <物品ID/名称> [数量] - 获取Resource物品")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin gettestgear <玩家> - 获取测试装备")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin givetoken <玩家> - 给予天机令")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin level <玩家> [set|add] [数值] - 查看或修改玩家等级")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin job <玩家> <职业> - 设置职业")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin race <玩家> <种族> - 设置种族")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin medical <技能ID> - 获取医术秘籍")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin getstation - 获取医术绘制台")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin quest <玩家> <ID> <状态> - 修改任务进度")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin gennpc <ID|ALL> - 生成剧情NPC")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin spawn - 生成自定义刷怪笼等")
            // 【丹药提示】
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin alchemy <list|give|getcauldron> ... - 丹药系统指令")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin gettp <方块材质> <传送点ID> - 获取传送触发器")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin getarrayblock -获取术士阵法升级方块 ")
            // 【新增】副本系统提示
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin dungeon <trigger|set> - 副本系统指令")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin medicaltest <玩家名> <view|add|remove> <试炼ID>")
            return true
        }

        val subCommand = args[0].lowercase()

        // === reload (重载) ===
        if (subCommand == "reload") {
            plugin.reloadConfig()
            plugin.menuManager.reload()
            plugin.playerManager.weaponManager.reload()
            plugin.playerManager.armorManager.reload()
            plugin.playerManager.crystalManager.reload()
            plugin.weaponSkillManager.reload()
            plugin.chonghuaManager.reload()

            // 重载 Resource 物品
            if (plugin.resourceManager != null) {
                plugin.resourceManager.reload()
                plugin.jianghuXindeManager.reload()
                plugin.jianghuXindeManager.ensureStationBlock()
            }
            // 【新增】重载医术配置
            if (plugin.medicalManager != null) {
                plugin.medicalManager.loadSkillBooks()
            }

            // 【新增】重载 NPC 数据 (只读不存，防止覆盖)
            if (plugin.npcModule != null) {
                plugin.npcModule.manager.loadData()
                sender.sendMessage(ChatColor.AQUA.toString() + "NPC 数据已从磁盘重新加载！")
            }

            // 重载丹药配方
            plugin.alchemyManager.loadRecipes()
            // 重载传送点
            plugin.teleportManager.reload()
            sender.sendMessage(ChatColor.GREEN.toString() + "所有配置文件(含Resource/Medical/Alchemy/teleport/重华晶)已重载！")
            return true
        }

        // === alchemy (丹药系统) ===
        if (subCommand == "alchemy") {
            // 参数: /hjhadmin alchemy <list|give|getcauldron> ...
            if (args.size < 2) {
                sender.sendMessage("§c用法: /hjhadmin alchemy <list|give|getcauldron> ...")
                return true
            }

            val alcSub = args[1].lowercase()

            // 1. 获取炼药锅
            if (alcSub == "getcauldron") {
                if (sender !is Player) {
                    sender.sendMessage("§c只有玩家可以使用此指令。")
                    return true
                }
                val cauldron = org.bukkit.inventory.ItemStack(org.bukkit.Material.CAULDRON)
                val meta = cauldron.itemMeta
                meta?.setDisplayName("§5§l冶药锅")
                val key = org.bukkit.NamespacedKey(plugin, "hjh_alchemy_cauldron")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.INTEGER, 1)
                cauldron.itemMeta = meta
                sender.inventory.addItem(cauldron)
                sender.sendMessage("§a已获得冶药锅")
                return true
            }
        }

        // === get (获取 Resource 物品) ===
        if (subCommand == "get" || subCommand == "give") {
            if (sender !is Player) {
                sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此命令。")
                return true
            }
            val player = sender

            if (args.size < 2) return error(sender, "用法: /hjhadmin <get|give> <物品ID或名字> [数量]")

            val itemName = args[1]
            val item = plugin.resourceManager.getItem(itemName)

            if (item == null) {
                return error(sender, "未找到名为 [$itemName] 的物品！请检查 resources 文件夹。")
            }

            var amount = 1
            if (args.size >= 3) {
                try {
                    amount = args[2].toInt()
                } catch (e: NumberFormatException) {
                    return error(sender, "数量必须是数字。")
                }
            }

            item.amount = amount
            player.inventory.addItem(item)
            sender.sendMessage(ChatColor.GREEN.toString() + "已获得物品: " + itemName + " x" + amount)
            return true
        }

        // === medical (获取医术秘籍) ===
        if (subCommand == "medical") {
            if (sender !is Player) {
                sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此命令。")
                return true
            }
            val player = sender

            if (args.size < 2) return error(sender, "用法: /hjhadmin medical <技能ID>")

            val skillId = args[1]
            if (plugin.medicalManager != null) {
                val book = plugin.medicalManager.getSkillBook(skillId)
                if (book != null) {
                    player.inventory.addItem(book)
                    sender.sendMessage(ChatColor.GREEN.toString() + "已获得医术秘籍: " + skillId)
                } else {
                    sender.sendMessage(ChatColor.RED.toString() + "未找到技能ID为 [" + skillId + "] 的秘籍配置。")
                }
            }
            return true
        }

        if (args.size == 1 && args[0].equals("getstation", ignoreCase = true)) {
            if (plugin.medicalManager != null) {
                val p = sender as Player
                p.inventory.addItem(plugin.medicalManager.getMedicalStationItem())
                p.sendMessage("§a已获取医术绘制台！")
                return true
            }
        }

        // === givetoken (给予天机令) ===
        if (subCommand == "givetoken") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin givetoken <玩家>")
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")
            target.inventory.addItem(plugin.menuManager.getTianjiToken())
            sender.sendMessage(ChatColor.GREEN.toString() + "给予天机令成功。")
            return true
        }

        // === level (查看/修改玩家等级) ===
        if (subCommand == "level") {
            if (args.size < 2) {
                sender.sendMessage("§c用法: /hjhadmin level <玩家> [set|add] [数值]")
                return true
            }
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) {
                sender.sendMessage("§c玩家不在线。")
                return true
            }
            val data = plugin.playerManager.getData(target.uniqueId)
            if (data == null) {
                sender.sendMessage("§c玩家数据正在加载或不存在。")
                return true
            }
            // 仅查看等级: /hjhadmin level <玩家>
            if (args.size == 2) {
                sender.sendMessage("§8[§aLevel§8] §f玩家 ${target.name} 的当前等级为: §e${data.lv}")
                return true
            }
            // 修改等级: /hjhadmin level <玩家> <set|add> <数值>
            if (args.size >= 4) {
                val action = args[2].lowercase()
                val value = args[3].toIntOrNull()

                if (value == null) {
                    sender.sendMessage("§c数值必须为整数。")
                    return true
                }
                when (action) {
                    "set" -> data.lv = value
                    "add" -> data.lv += value
                    else -> {
                        sender.sendMessage("§c未知操作，请使用 set 或 add。")
                        return true
                    }
                }
                // 确保等级不小于1
                if (data.lv < 1) data.lv = 1
                // 刷新玩家属性，并将最新的等级状态同步回原版的客户端显示
                plugin.playerManager.updateStats(target)
                // 异步保存数据，防止回档
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    plugin.databaseManager.savePlayer(data)
                })
                sender.sendMessage("§8[§aLevel§8] §a已成功将 ${target.name} 的等级修改为: §e${data.lv}")
                return true
            }
            sender.sendMessage("§c用法: /hjhadmin level <玩家> [set|add] [数值]")
            return true
        }

        // === gettestgear (获取测试装备) ===
        if (subCommand == "gettestgear") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin gettestgear <玩家>")
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")

            // 获取物品
            val sword = plugin.playerManager.weaponManager.getItemStack("novice_sword")
            val helm = plugin.playerManager.armorManager.getItemStack("novice_helmet")
            val chest = plugin.playerManager.armorManager.getItemStack("novice_chestplate")
            val leg = plugin.playerManager.armorManager.getItemStack("novice_leggings")
            val boot = plugin.playerManager.armorManager.getItemStack("novice_boots")

            if (sword != null) target.inventory.addItem(sword)
            if (helm != null) target.inventory.helmet = helm
            if (chest != null) target.inventory.chestplate = chest
            if (leg != null) target.inventory.leggings = leg
            if (boot != null) target.inventory.boots = boot

            // 刷新属性
            plugin.playerManager.updateStats(target)
            sender.sendMessage(ChatColor.GREEN.toString() + "已发放全套测试装备给 " + target.name)
            return true
        }

        // === status (查看/修改玩家剧情状态) ===
        if (subCommand == "status") {
            // 参数检查: /hjhadmin status <玩家> [set <数值>]
            if (args.size < 2) return error(sender, "用法: /hjhadmin status <玩家> [set <数值>]")
            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")
            // 获取数据
            val data = plugin.playerManager.getPlayerData(target)
            if (data == null) return error(sender, "数据加载中...")
            // 1. 查询状态 (只有2个参数时)
            if (args.size == 2) {
                sender.sendMessage("§8[§aStatus§8] §f${target.name}: §e${data.status} §7(${data.statusDescription})")
                sender.sendMessage("§7可用预设: ${formatStatusPresets()}")
                return true
            }
            // 2. 修改状态 (参数 >= 4 且 第三个参数是 set)
            if (args.size >= 4 && args[2].equals("set", ignoreCase = true)) {
                val newStatus = args[3].toIntOrNull()
                if (newStatus == null) return error(sender, "状态值必须是整数")
                // 修改内存 (描述自动更新)
                data.updateStatus(newStatus)
                // 异步保存数据库
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        plugin.databaseManager.dataSource?.connection?.use { conn ->
                            // 调用之前在 DatabaseManager 写的子模块方法
                            plugin.databaseManager.savePlayerStatus(conn, data)
                        }
                        sender.sendMessage("§a[HJH] 已将 ${target.name} 状态设为 $newStatus (${data.statusDescription}) 并保存。")
                    } catch (e: Exception) {
                        sender.sendMessage("§c保存失败: ${e.message}")
                        e.printStackTrace()
                    }
                })
                return true
            }
            return error(sender, "用法: /hjhadmin status <玩家> [set <数值>]，可用预设: ${formatStatusPresets()}")
        }

        // === gettp (获取传送触发方块) ===
        if (subCommand == "gettp") {
            // 用法: /hjhadmin gettp <材质> <传送点ID>
            if (args.size < 3) return error(sender, "用法: /hjhadmin gettp <材质> <传送点ID>")
            if (sender !is Player) return error(sender, "只有玩家可用")
            val matName = args[1].uppercase()
            val pointId = args[2]
            // 1. 检查材质
            val mat = org.bukkit.Material.getMaterial(matName)
            if (mat == null || !mat.isBlock) {
                return error(sender, "无效的方块材质: $matName")
            }
            // 2. 检查配置中是否有这个ID (可选，建议检查)
            if (!plugin.teleportManager.points.containsKey(pointId)) {
                sender.sendMessage("§c[警告] 传送点ID [$pointId] 尚未在 teleports.yml 中配置，但你仍然可以放置它。")
            }
            // 3. 生成物品
            val item = org.bukkit.inventory.ItemStack(mat)
            val meta = item.itemMeta
            meta?.setDisplayName("§b§l[传送触发器] §e$pointId")
            meta?.lore = listOf("§7放置此方块后", "§7玩家交互将传送至: §f$pointId")
            // 写入 NBT (PDC)
            val key = org.bukkit.NamespacedKey(plugin, "hjh_tp_point_id")
            meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.STRING, pointId)
            item.itemMeta = meta
            sender.inventory.addItem(item)
            sender.sendMessage("§a已获取传送触发器: $pointId ($matName)")
            return true
        }

        // === job / race (设置职业/种族) ===
        // 【修改】将 Job 和 Race 的判断独立出来，不再阻断后续指令
        if (subCommand == "job" || subCommand == "race") {
            if (args.size < 3) return error(sender, "用法: /hjhadmin <job|race> <玩家> <值>")

            val target = Bukkit.getPlayerExact(args[1])
            if (target == null) return error(sender, "玩家不在线")
            val data = plugin.playerManager.getData(target.uniqueId)
            if (data == null) return error(sender, "数据加载中...")

            val valStr = args[2]
            if (subCommand == "job") {
                val job = jobReverseMap[valStr]
                if (job == null) return error(sender, "无效职业 (战士/弓箭手/术士/医师)")
                data.job = job
                sender.sendMessage(ChatColor.GREEN.toString() + "职业已设为: " + valStr)
            } else {
                val race = raceReverseMap[valStr]
                if (race == null) return error(sender, "无效种族 (神/仙/人/战神/妖)")
                data.race = race
                sender.sendMessage(ChatColor.GREEN.toString() + "种族已设为: " + valStr)
            }
            // 修改了属性后刷新
            plugin.playerManager.updateStats(target)
            return true
        }

        // === quest (任务管理指令) ===
        if (subCommand == "quest") {
            if (args.size < 4) return error(sender, "用法: /hjhadmin quest <玩家> <ID> <状态> [进度]")

            val target = Bukkit.getPlayer(args[1])
            if (target == null) {
                sender.sendMessage("§c玩家不在线")
                return true
            }

            val questId = args[2]
            val statusStr = args[3].uppercase()
            val status = try {
                QuestStatus.valueOf(statusStr)
            } catch (e: Exception) {
                sender.sendMessage("§c无效的状态 (LOCKED, IN_PROGRESS, COMPLETED)")
                return true
            }

            val progress = if (args.size >= 5) args[4].toIntOrNull() ?: 0 else 0

            // 1. 获取数据
            val data = plugin.playerManager.getPlayerData(target)
            if (data != null) {
                // 2. 修改内存
                data.questStatuses[questId] = status
                data.questProgress[questId] = progress

                // 3. 强制保存数据库
                plugin.databaseManager.saveQuestData(target, questId, status, progress)

                sender.sendMessage("§a已将玩家 ${target.name} 的任务 $questId 设置为 $status (进度: $progress)")
                target.sendMessage("§e[管理员] 你的任务状态已更新。")
            }
            return true
        }

        // === gennpc (生成NPC指令) ===
        if (subCommand == "gennpc") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin gennpc <ID|ALL>")

            val targetName = args[1]
            val listToSpawn = ArrayList<StoryNpcs>()

            if (targetName.equals("ALL", ignoreCase = true)) {
                listToSpawn.addAll(StoryNpcs.values())
            } else {
                try {
                    listToSpawn.add(StoryNpcs.valueOf(targetName))
                } catch (e: IllegalArgumentException) {
                    sender.sendMessage("§c找不到该剧情NPC配置: $targetName")
                    return true
                }
            }

            var count = 0
            val manager = plugin.npcModule.manager

            for (npcData in listToSpawn) {
                // 1. 检查模版 (Template) 是否存在，不存在则注册
                if (!manager.templates.containsKey(npcData.id)) {
                    val template = com.hjh_database.npc.data.NpcTemplate(
                        npcData.id,
                        npcData.displayName,
                        npcData.profession,
                        npcData.type
                    )
                    // 【修正】dialogue -> dialogues (复数)
                    template.dialogue.add("&7(好像没什么事发生...)")
                    manager.templates[npcData.id] = template
                    manager.saveData()
                    sender.sendMessage("§e[系统] 已新建模版: ${npcData.id}")
                }

                try {
                    val loc = npcData.getLocation()
                    val removedCount = manager.removeInstancesByTemplate(npcData.id, loc)
                    if (removedCount > 0) {
                        sender.sendMessage("§e[系统] 检测到旧的 ${npcData.displayName}，已清除 $removedCount 个实体。")
                    }

                    manager.spawnNpc(loc, npcData.id)
                    sender.sendMessage("§a[系统] 已在 ${loc.blockX},${loc.blockY},${loc.blockZ} 生成 ${npcData.displayName}")
                    count++
                } catch (e: Exception) {
                    sender.sendMessage("§c[错误] 生成 ${npcData.id} 失败: ${e.message}")
                }
            }

            sender.sendMessage("§a操作完成，共生成/刷新了 $count 个 NPC。")
            return true
        }

        // === spawner (刷怪笼 / 手动测试笼 工具) ===
        if (subCommand == "spawner") {
            // 指令: /hjhadmin spawner <get|button|fast> <MobID> [x] [y] [z]
            if (args.size < 3 || (args[1].lowercase() != "get" && args[1].lowercase() != "button" && args[1].lowercase() != "fast")) {
                sender.sendMessage("§c用法: /hjhadmin spawner <get|button|fast> <MobID> [x] [y] [z]")
                return true
            }

            val action = args[1].lowercase()
            val mobId = args[2]

            if (MobRegistry.get(mobId) == null) {
                sender.sendMessage("§c错误: 未找到 ID 为 $mobId 的怪物配置。请检查 MobRegistry。")
                return true
            }

            // 计算目标坐标 (如果有)
            var targetLocStr: String? = null
            var locDisplay = "§7生成位置: §f未知"

            if (sender is Player) {
                var loc = sender.location

                // 如果填了参数
                if (args.size >= 6) {
                    try {
                        val x = args[3].toDouble()
                        val y = args[4].toDouble()
                        val z = args[5].toDouble()
                        loc = org.bukkit.Location(sender.world, x, y, z)
                        targetLocStr = "${loc.world.name},${loc.x},${loc.y},${loc.z}"
                        locDisplay = "§7生成位置: §a${String.format("%.1f, %.1f, %.1f", x, y, z)}"
                    } catch (e: Exception) {
                        sender.sendMessage("§c坐标格式错误！")
                        return true
                    }
                } else {
                    // 对于 get 和 button，不填坐标都记录输入指令时的当前位置
                    targetLocStr = "${loc.world.name},${loc.x},${loc.y},${loc.z}"
                    locDisplay = "§7生成位置: §a${String.format("%.1f, %.1f, %.1f", loc.x, loc.y, loc.z)} §c(指令记录位置)"
                }

                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.SPAWNER)
                val meta = item.itemMeta
                val pdc = meta?.persistentDataContainer

                if (action == "get") {
                    meta?.setDisplayName("§e定点刷怪笼: §f$mobId")
                    val lore = ArrayList<String>()
                    lore.add(locDisplay)
                    lore.add("§7怪物ID: $mobId")
                    lore.add("§e放置后生效")
                    meta?.lore = lore

                    // 将数据存入 ItemStack PDC，以便放置时读取
                    val keyId = org.bukkit.NamespacedKey(plugin, "hjh_spawner_mobid")
                    pdc?.set(keyId, org.bukkit.persistence.PersistentDataType.STRING, mobId)
                    if (targetLocStr != null) {
                        val keyLoc = org.bukkit.NamespacedKey(plugin, "hjh_spawner_target")
                        pdc?.set(keyLoc, org.bukkit.persistence.PersistentDataType.STRING, targetLocStr)
                    }

                    item.itemMeta = meta
                    sender.inventory.addItem(item)
                    sender.sendMessage("§a已获取自动刷怪笼物品！")

                } else if (action == "button") {
                    meta?.setDisplayName("§b§l[手动测试方块] §e$mobId")
                    val lore = ArrayList<String>()
                    lore.add("§7怪物ID: $mobId")
                    lore.add(locDisplay)
                    lore.add("§e放置在地上后，右键点击即可生成怪物")
                    lore.add("§c自带3秒冷却，方便测试且防刷屏")
                    meta?.lore = lore

                    pdc?.set(org.bukkit.NamespacedKey(plugin, "hjh_spawner_manual_mobid_item"), org.bukkit.persistence.PersistentDataType.STRING, mobId)
                    if (targetLocStr != null) {
                        pdc?.set(org.bukkit.NamespacedKey(plugin, "hjh_spawner_manual_target_item"), org.bukkit.persistence.PersistentDataType.STRING, targetLocStr)
                    }

                    item.itemMeta = meta
                    sender.inventory.addItem(item)
                    sender.sendMessage("§a已获取测试用手动方块！")

                } else if (action == "fast") {
                    // ★★★ 新增：快速铺怪模式 (不绑定死坐标，放置时动态获取) ★★★
                    meta?.setDisplayName("§d快速铺怪笼: §f$mobId")
                    val lore = ArrayList<String>()
                    lore.add("§7怪物ID: $mobId")
                    lore.add("§7生成位置: §a你实际放置方块的位置")
                    lore.add("§7物理位置: §a放置位置下方2格")
                    lore.add("§e放置时自动向下埋设并绑定刷怪点")
                    meta?.lore = lore

                    val keyFast = org.bukkit.NamespacedKey(plugin, "hjh_spawner_fast")
                    pdc?.set(keyFast, org.bukkit.persistence.PersistentDataType.STRING, mobId)

                    item.itemMeta = meta
                    sender.inventory.addItem(item)
                    sender.sendMessage("§a已获取快速铺怪笼 (fast模式)！")
                }
            }
            return true
        }

        // === getarrayblock (获取阵法升级紫水晶块) ===
        if (subCommand == "getarrayblock") {
            if (sender !is Player) {
                sender.sendMessage("§c只有玩家可以使用此命令。")
                return true
            }
            // 假设你在主类 plugin 中或者 ElementZfManager 中实例化了 ElementZfGui
            // 这里我们直接调用我们写好的获取物品方法
            val amethystBlock = plugin.elementZfGui.getSpecialAmethystBlock()
            sender.inventory.addItem(amethystBlock)
            sender.sendMessage("§a已获取特殊的阵法升级紫水晶块！")
            return true
        }

        // === 重华晶指令 ===
        if (subCommand == "chonghua") {
            if (args.size < 3) {
                sender.sendMessage("§e=== 重华晶配置指令 ===")
                sender.sendMessage("§c/hjhadmin chonghua crystal <EAST|SOUTH|WEST|NORTH> §7- 获得区域传送门(黄绿粘土)")
                sender.sendMessage("§c/hjhadmin chonghua checkin <地点ID> §7- 获得特定地点的打卡方块(红色粘土)")
                return true
            }

            val type = args[1].lowercase()

            // 获得黄绿色粘土（主界面传送点）
            if (type == "crystal") {
                val regionStr = args[2].uppercase()
                val region = try { com.hjh_database.chonghua.Region.valueOf(regionStr) } catch(e: Exception) { null }

                if (region == null) {
                    sender.sendMessage("§c无效区域！请使用 EAST, SOUTH, WEST, NORTH")
                    return true
                }

                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.LIME_TERRACOTTA)
                val meta = item.itemMeta
                meta?.setDisplayName("§a§l重华晶传送门 - ${region.displayName}")
                val key = org.bukkit.NamespacedKey(plugin, "chonghua_region")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.STRING, region.name)
                item.itemMeta = meta

                (sender as Player).inventory.addItem(item)
                sender.sendMessage("§a已获得传送门方块: ${region.displayName}")
                return true
            }

            // 获得红色粘土（打卡点）
            if (type == "checkin") {
                val wpId = args[2] // 如 east_01
                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.LIME_GLAZED_TERRACOTTA)
                val meta = item.itemMeta
                meta?.setDisplayName("§c§l打卡点 - $wpId")
                val key = org.bukkit.NamespacedKey(plugin, "chonghua_waypoint")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.STRING, wpId)
                item.itemMeta = meta

                (sender as Player).inventory.addItem(item)
                sender.sendMessage("§a已获得打卡点方块: $wpId")
                return true
            }
        }

        // === dungeon (副本管理与金宝箱) ===
        if (subCommand == "dungeon") {
            if (args.size < 3) {
                sender.sendMessage("§c[系统] 用法:")
                sender.sendMessage("§c - /hjhadmin dungeon trigger <qinglong/baihu/zhuque/xuanwu>")
                sender.sendMessage("§c - /hjhadmin dungeon set <玩家> <qinglong/baihu/zhuque/xuanwu> <0|1>")
                sender.sendMessage("§c - /hjhadmin dungeon getchest <副本ID>  (获取金宝箱方块)")
                sender.sendMessage("§c - /hjhadmin dungeon info <玩家> <副本ID>  (查询进度)")
                sender.sendMessage("§c - /hjhadmin dungeon addclear/addopen <玩家> <副本ID> <数量>")
                return true
            }

            val action = args[1].lowercase()

            // 1. 获取触发器 (你原有的逻辑)
            if (action == "trigger") {
                if (sender !is Player) {
                    sender.sendMessage("§c只有玩家可以使用 trigger 指令。")
                    return true
                }
                val dungeonType = args[2].lowercase()
                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.SOUL_LANTERN)
                val meta = item.itemMeta

                val displayName = when (dungeonType) {
                    "qinglong" -> "§a§l青龙试炼触发器"
                    "baihu" -> "§f§l白虎试炼触发器"
                    "zhuque" -> "§c§l朱雀试炼触发器"
                    "xuanwu" -> "§e§l玄武试炼触发器"
                    else -> {
                        sender.sendMessage("§c[系统] 未知的副本类型！(可选: qinglong, baihu, zhuque, xuanwu)")
                        return true
                    }
                }

                meta?.setDisplayName(displayName)
                item.itemMeta = meta
                sender.inventory.addItem(item)
                sender.sendMessage("§a[系统] 已获得 $displayName！放置后玩家右键即可触发！")
                return true
            }

            // 2. 设置玩家通关状态 (你原有的逻辑)
            if (action == "set") {
                if (args.size < 5) {
                    sender.sendMessage("§c[系统] 用法: /hjhadmin dungeon set <玩家> <qinglong/baihu/zhuque/xuanwu> <0|1>")
                    return true
                }

                val target = Bukkit.getPlayerExact(args[2])
                if (target == null) {
                    sender.sendMessage("§c[系统] 玩家不在线！")
                    return true
                }

                val trialType = args[3].lowercase()
                if (trialType !in listOf("qinglong", "baihu", "zhuque", "xuanwu")) {
                    sender.sendMessage("§c[系统] 未知的副本类型！")
                    return true
                }

                val state = args[4].toIntOrNull()
                if (state == null || state !in 0..1) {
                    sender.sendMessage("§c[系统] 状态值只能是 0 (未完成) 或 1 (已完成)")
                    return true
                }

                // 异步写入数据库
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    try {
                        plugin.databaseManager.dataSource?.connection?.use { conn ->
                            val sql = """
                                INSERT INTO player_test (uuid, player_name, $trialType) 
                                VALUES (?, ?, ?) 
                                ON CONFLICT(uuid) DO UPDATE SET $trialType = ?
                            """.trimIndent()
                            conn.prepareStatement(sql).use { ps ->
                                ps.setString(1, target.uniqueId.toString())
                                ps.setString(2, target.name)
                                ps.setInt(3, state)
                                ps.setInt(4, state)
                                ps.executeUpdate()
                            }
                        }
                        sender.sendMessage("§a[系统] 已成功将玩家 ${target.name} 的 $trialType 试炼状态设置为 $state ！")
                    } catch (e: Exception) {
                        sender.sendMessage("§c[错误] 数据库更新失败: ${e.message}")
                        e.printStackTrace()
                    }
                })
                return true
            }

            // ================= 新增：金宝箱相关指令 =================

            // 3. 直接获取一个写好数据的金宝箱方块
            if (action == "getchest") {
                if (sender !is Player) {
                    sender.sendMessage("§c只有玩家可以使用此指令。")
                    return true
                }
                val dungeonId = args[2]

                // 检查这个副本ID是否在 GoldenChestManager 里注册了
                if (!plugin.goldenChestManager.chestRegistry.containsKey(dungeonId)) {
                    val registered = plugin.goldenChestManager.chestRegistry.keys.joinToString(", ")
                    sender.sendMessage("§c[系统] 未知的副本宝箱类型！已注册的: $registered")
                    return true
                }

                // 给玩家一个宝库 (Vault) 物品
                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.VAULT)
                val meta = item.itemMeta
                meta?.setDisplayName("§6§l[$dungeonId] 副本金宝箱")
                meta?.lore = listOf("§7管理员物品：", "§7直接放置在地上将自动成为", "§7该副本的奖励宝库。")

                // ★ 核心：把副本ID写进物品的 PDC 里
                val key = org.bukkit.NamespacedKey(plugin, "vault_dungeon_id")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.STRING, dungeonId)
                item.itemMeta = meta

                sender.inventory.addItem(item)
                sender.sendMessage("§a[系统] 已获得 §6§l[$dungeonId] 副本金宝箱§a！直接放置在地上即可生效。")
                return true
            }

            // 4. 查询与修改玩家的金宝箱数据 (用于测试保底和通关逻辑)
            if (action == "info") {
                val target = Bukkit.getPlayerExact(args[2]) ?: return true
                val dungeonId = args[3]

                // 【新增】尝试获取配置中的中文名，如果没配置则直接显示原来的英文ID
                val config = plugin.goldenChestManager.chestRegistry[dungeonId]
                val dungeonName = config?.displayName ?: dungeonId

                val pd = plugin.playerManager.getPlayerData(target) ?: return true
                val rec = pd.dungeonRecords[dungeonId] ?: com.hjh_database.dungeon.DungeonRecord()

                // 【修改】使用中文名替代英文ID展示
                sender.sendMessage("§6[${target.name}] §e副本 §b$dungeonName §e的数据:")
                sender.sendMessage("§7- 历史通关数: §a${rec.clears}")
                sender.sendMessage("§7- 历史开箱数: §c${rec.opens}")
                sender.sendMessage("§7- 剩余可开箱次数: §b${rec.availableOpens}")
                return true
            }

            // 5. 重置玩家某件物品的掉落记录（用于测试 OneTimeOnly 和 保底重置）
            if (action == "resetdrop") {
                if (args.size < 5) {
                    sender.sendMessage("§c[系统] 用法: /hjhadmin dungeon resetdrop <玩家> <副本ID> <物品ResourceId>")
                    return true
                }
                val target = Bukkit.getPlayerExact(args[2]) ?: return true
                val dungeonId = args[3]
                val resourceId = args[4]

                val pd = plugin.playerManager.getPlayerData(target) ?: return true
                val rec = pd.dungeonRecords[dungeonId]
                if (rec == null) {
                    sender.sendMessage("§c[系统] 该玩家尚未有该副本的任何数据！")
                    return true
                }

                // 移除已经掉落的次数
                rec.dropCounts.remove(resourceId)
                // 顺便把保底垫数也清零
                rec.opensSinceLastDrop.remove(resourceId)

                sender.sendMessage("§a[系统] 成功清除了玩家 ${target.name} 在副本 $dungeonId 中关于物品 [$resourceId] 的开出记录！现在TA可以再次抽到此生仅一次的物品了。")
                return true
            }

            if (action == "addclear" || action == "addopen" || action == "addavail") {
                val target = Bukkit.getPlayerExact(args[2]) ?: return true
                val dungeonId = args[3]
                val amount = args.getOrNull(4)?.toIntOrNull() ?: 1

                val pd = plugin.playerManager.getPlayerData(target) ?: return true
                val rec = pd.dungeonRecords.computeIfAbsent(dungeonId) { com.hjh_database.dungeon.DungeonRecord() }

                when (action) {
                    "addclear" -> {
                        rec.clears += amount
                        rec.availableOpens += amount // ★ 核心逻辑：通关一次，就发一次开箱机会
                        sender.sendMessage("§a已为玩家 ${target.name} 副本 $dungeonId 增加 $amount 次通关记录与可开箱次数！")
                    }
                    "addopen" -> {
                        rec.opens += amount
                        sender.sendMessage("§a已为玩家 ${target.name} 副本 $dungeonId 增加 $amount 次历史开箱数。")
                    }
                    "addavail" -> {
                        rec.availableOpens += amount // ★ 额外赠送开箱机会（不影响历史通关数）
                        sender.sendMessage("§a已为玩家 ${target.name} 副本 $dungeonId 额外赠送 $amount 次可开箱次数！")
                    }
                }
                return true
            }
            sender.sendMessage("§c[系统] 未知的 dungeon 子指令，请使用 trigger, set, getchest 等。")
            return true
        }

        // === 仓库系统 (Warehouse) ===
        if (subCommand == "getwarehouse") {
            if (sender !is Player) return true
            val chest = org.bukkit.inventory.ItemStack(org.bukkit.Material.CHEST)
            val meta = chest.itemMeta
            meta?.setDisplayName("§2§l个人仓库方块")
            meta?.lore = listOf("§7放置后生成一个个人仓库交互点")
            // 使用 PDC 标记特殊方块
            val key = org.bukkit.NamespacedKey(plugin, "is_warehouse_block")
            meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.BYTE, 1)
            chest.itemMeta = meta
            sender.inventory.addItem(chest)
            sender.sendMessage("§a已获取仓库方块！")
            return true
        }

        if (subCommand == "openwarehouse") {
            if (args.size < 2) return error(sender, "用法: /hjhadmin openwarehouse <玩家>")
            val targetName = args[1]
            val target = Bukkit.getPlayerExact(targetName)
            if (target == null) return error(sender, "玩家不在线")
            // 强制打开目标玩家的仓库 GUI（需要你在 Manager 里提供打开逻辑，传入 target 的数据即可）
            if (sender is Player) {
                plugin.warehouseManager.openMainMenu(sender, target)
                sender.sendMessage("§a正在查看 ${target.name} 的仓库")
            }
            return true
        }

        // === medicaltest (医术试炼测试与管理) ===
        if (subCommand == "medicaltest") {
            if (!sender.hasPermission("hjh.admin")) {
                sender.sendMessage("§c你没有权限执行此命令！")
                return true
            }

            if (args.size < 3) {
                sender.sendMessage("§c用法: /hjhadmin medicaltest <玩家> <view|add|remove> [试炼ID]")
                return true
            }

            val targetName = args[1]
            val action = args[2].lowercase()
            val trialId = if (args.size >= 4) args[3].lowercase() else ""

            val target = Bukkit.getPlayerExact(targetName)
            if (target == null) {
                // 保持你的风格，使用 return true 加 sendMessage
                sender.sendMessage("§c玩家不在线或不存在，只能修改在线玩家的试炼记录！")
                return true
            }

            val data = plugin.playerManager.getPlayerData(target)
            if (data == null) {
                sender.sendMessage("§c无法获取玩家 $targetName 的数据！")
                return true
            }

            when (action) {
                "view" -> {
                    sender.sendMessage("§8[§aMedicalTest§8] §f玩家 ${target.name} 已完成的医术试炼: §e${data.completedMedicalTrials}")
                }
                "add" -> {
                    if (trialId.isEmpty()) {
                        sender.sendMessage("§c请输入要添加的试炼ID！")
                        return true
                    }
                    data.completedMedicalTrials.add(trialId)

                    // 异步保存，风格与你的 status/dungeon 完全一致
                    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                        try {
                            plugin.databaseManager.dataSource?.connection?.use { conn ->
                                // 【修改点】现在只需要传 conn 和 data 两个参数了
                                plugin.databaseManager.saveCompletedMedicalTrials(conn, data)
                            }
                        }catch (e: Exception) {
                            sender.sendMessage("§c[错误] 数据库更新失败: ${e.message}")
                            e.printStackTrace()
                        }
                    })
                }
                "remove" -> {
                    if (trialId.isEmpty()) {
                        sender.sendMessage("§c请输入要移除的试炼ID！")
                        return true
                    }
                    data.completedMedicalTrials.remove(trialId)

                    // 异步保存
                    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                        try {
                            plugin.databaseManager.dataSource?.connection?.use { conn ->
                                // 【修改点】现在只需要传 conn 和 data 两个参数了
                                plugin.databaseManager.saveCompletedMedicalTrials(conn, data)
                            }
                        } catch (e: Exception) {
                            sender.sendMessage("§c[错误] 数据库更新失败: ${e.message}")
                            e.printStackTrace()
                        }
                    })
                }
                else -> {
                    sender.sendMessage("§c无效的动作: view, add, remove")
                }
            }
            return true
        }



        return error(sender, "未知指令: $subCommand")
    }

    // 简化的错误提示
    private fun error(sender: CommandSender, msg: String): Boolean {
        sender.sendMessage(ChatColor.RED.toString() + msg)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        // 防止空数组异常
        if (args.isEmpty()) return null

        // === 1. 一级补全 ===
        if (args.size == 1) {
            val rootCommands = listOf(
                "job", "race", "givetoken", "level", "reload", "gettestgear", "get", "give",
                "medical", "getstation", "quest", "gennpc", "alchemy", "spawner",
                "status", "gettp", "getarrayblock", "chonghua","dungeon","getwarehouse","openwarehouse",
                "medicaltest"
            )
            return rootCommands.filter { it.startsWith(args[0].lowercase()) }
        }

        // 只要走到这里，args.size 至少是 2
        val subCmd = args[0].lowercase()

        // === 2. 二级及以上补全 (根据主指令分支) ===
        when (subCmd) {
            "chonghua" -> {
                // 【修复】：严格分离 size == 2 和 size == 3
                if (args.size == 2) {
                    return listOf("crystal", "checkin").filter { it.startsWith(args[1].lowercase()) }
                }
                if (args.size == 3) {
                    val action = args[1].lowercase()
                    if (action == "crystal") {
                        val regions = listOf("EAST", "SOUTH", "WEST", "NORTH")
                        return regions.filter { it.startsWith(args[2].uppercase()) }
                    }
                    if (action == "checkin") {
                        return plugin.chonghuaManager.waypoints.keys.filter { it.startsWith(args[2].lowercase()) }
                    }
                }
            }

            "alchemy" -> {
                // 【修复】：移除了混入的 crystal 和 checkin
                if (args.size == 2) return listOf("give", "list", "getcauldron").filter { it.startsWith(args[1].lowercase()) }
                if (args.size == 3 && args[1].equals("give", ignoreCase = true)) return null // 玩家名
                if (args.size == 4 && args[1].equals("give", ignoreCase = true)) return plugin.alchemyManager.effects.keys.toList().filter { it.startsWith(args[3]) }
                if (args.size == 5 && args[1].equals("give", ignoreCase = true)) return listOf("LOW", "MID", "HIGH").filter { it.startsWith(args[4].uppercase()) }
            }

            "spawner" -> {
                // 【修复】：移除了重复的代码块
                if (args.size == 2) return listOf("get", "button","fast").filter { it.startsWith(args[1].lowercase()) }
                if (args.size == 3 && (args[1].equals("get", ignoreCase = true) || args[1].equals("button", ignoreCase = true)|| args[1].equals("fast", ignoreCase = true))) {
                    return MobRegistry.getAllIds().filter { it.startsWith(args[2]) }
                }
            }

            "status" -> {
                if (args.size == 2) return null // 玩家名
                if (args.size == 3) return listOf("set").filter { it.startsWith(args[2].lowercase()) }
                if (args.size == 4 && args[2].equals("set", true)) return statusPresets.keys.map { it.toString() }.filter { it.startsWith(args[3]) }
            }

            "gettp" -> {
                if (args.size == 2) return listOf("STONE_PRESSURE_PLATE", "OAK_BUTTON", "LEVER", "STONE").filter { it.startsWith(args[1].uppercase()) }
                if (args.size == 3) return plugin.teleportManager.points.keys.toList().filter { it.startsWith(args[2]) }
            }

            "get", "give" -> {
                if (args.size == 2 && plugin.resourceManager != null) {
                    val currentInput = args[1].lowercase()
                    return plugin.resourceManager.getAllItemNames().filter { it.lowercase().startsWith(currentInput) }
                }
            }

            "quest" -> {
                if (args.size == 2) return null // 玩家名
                if (args.size == 3) return plugin.questManager.getAllQuests().map { it.id }.filter { it.startsWith(args[2]) }
                if (args.size == 4) return listOf("LOCKED", "IN_PROGRESS", "COMPLETED").filter { it.startsWith(args[3].uppercase()) }
                if (args.size == 5) return listOf("0", "1", "5", "10").filter { it.startsWith(args[4]) }
            }

            "medical" -> {
                if (args.size == 2 && plugin.medicalManager != null) {
                    return ArrayList(plugin.medicalManager.getAllSkillIds()).filter { it.startsWith(args[1]) }
                }
            }

            "gennpc" -> {
                if (args.size == 2) {
                    val list = ArrayList<String>()
                    list.add("ALL")
                    list.addAll(StoryNpcs.values().map { it.name })
                    return list.filter { it.startsWith(args[1].uppercase()) }
                }
            }

            "job" -> {
                if (args.size == 3) return ArrayList(jobReverseMap.keys).filter { it.startsWith(args[2]) }
            }

            "race" -> {
                if (args.size == 3) return ArrayList(raceReverseMap.keys).filter { it.startsWith(args[2]) }
            }

            "dungeon" -> {
                if (args.size == 2) {
                    val subCmds = listOf("trigger", "set", "getchest", "info", "addclear", "addopen","addavail","resetdrop")
                    return subCmds.filter { it.startsWith(args[1].lowercase()) }
                }

                // 你原有的 trigger 补全
                if (args.size == 3 && args[1].equals("trigger", ignoreCase = true)) {
                    val dungeons = listOf("qinglong", "baihu", "zhuque", "xuanwu")
                    return dungeons.filter { it.startsWith(args[2].lowercase()) }
                }
                // 你原有的 set 补全
                if (args.size == 3 && args[1].equals("set", ignoreCase = true)) {
                    return null
                }
                if (args.size == 4 && args[1].equals("set", ignoreCase = true)) {
                    val dungeons = listOf("qinglong", "baihu", "zhuque", "xuanwu")
                    return dungeons.filter { it.startsWith(args[3].lowercase()) }
                }
                if (args.size == 5 && args[1].equals("set", ignoreCase = true)) {
                    return listOf("0", "1").filter { it.startsWith(args[4]) }
                }

                // ★ 新增的 getchest 补全：自动弹出所有已注册的金宝箱副本ID
                val chestDungeons = plugin.goldenChestManager.chestRegistry.keys.toList()
                if (args.size == 3 && args[1].equals("getchest", ignoreCase = true)) {
                    return chestDungeons.filter { it.startsWith(args[2].lowercase()) }
                }

                // 【修改点】info / addclear / addopen / addavail / resetdrop 的补全
                val isPlayerTargetCmd = args[1].equals("info", ignoreCase = true) ||
                        args[1].equals("addclear", ignoreCase = true) ||
                        args[1].equals("addopen", ignoreCase = true) ||
                        args[1].equals("addavail", ignoreCase = true) ||
                        args[1].equals("resetdrop", ignoreCase = true)

                if (isPlayerTargetCmd) {
                    if (args.size == 3) return null // 补全在线玩家
                    if (args.size == 4) return chestDungeons.filter { it.startsWith(args[3].lowercase()) } // 补全副本ID

                    // 【新增】为 resetdrop 提供第 5 参数(物品ID)的补全
                    if (args.size == 5 && args[1].equals("resetdrop", ignoreCase = true)) {
                        return plugin.resourceManager?.getAllItemNames()?.filter { it.startsWith(args[4]) } ?: emptyList()
                    }
                }
            }
            "getwarehouse" -> {
                // 获取仓库方块，不需要后续参数，返回空列表防止瞎补全
                if (args.size == 2) return emptyList()
            }
            "openwarehouse" -> {
                // 强制打开某人仓库，第二个参数为玩家名。返回 null 会自动调用 Bukkit 的在线玩家补全
                if (args.size == 2) return null
            }
            "medicaltest" -> {
                // /hjhadmin medicaltest <玩家> <view|add|remove> [试炼ID]
                if (args.size == 2) {
                    return null // 返回 null 会自动调用 Bukkit 默认的在线玩家名补全
                }
                if (args.size == 3) {
                    return listOf("view", "add", "remove").filter { it.startsWith(args[2].lowercase()) }
                }
                if (args.size == 4) {
                    // 【修改】直接从医术试炼管理器中读取注册的试炼列表，实现自动填充
                    val trials = plugin.medicalTrialManager.registeredTrialIds
                    return trials.filter { it.startsWith(args[3].lowercase()) }
                }
            }
        }

        // === 3. 兜底处理 ===
        // 如果 args.size == 2 且上面没有处理（比如 job, race 等只匹配 size=3 的指令）
        // 返回 null 表示默认采用 Bukkit 原生的在线玩家名称补全
        if (args.size == 2) return null

        return ArrayList()
    }

    private fun formatStatusPresets(): String {
        return statusPresets.entries.joinToString("§7, ") { "§e${it.key}§7=${it.value}" }
    }
}
