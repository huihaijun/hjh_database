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

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限使用此管理命令。")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW.toString() + "=== HJH 管理员指令 ===")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin reload - 重载配置")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin get <物品ID/名称> [数量] - 获取Resource物品")
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
            return true
        }

        val subCommand = args[0].lowercase()

        // === reload (重载) ===
        if (subCommand == "reload") {
            plugin.reloadConfig()
            plugin.menuManager.reload()
            plugin.playerManager.weaponManager.reload()
            plugin.playerManager.armorManager.reload()
            plugin.weaponSkillManager.reload()

            // 重载 Resource 物品
            if (plugin.resourceManager != null) {
                plugin.resourceManager.reload()
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
            sender.sendMessage(ChatColor.GREEN.toString() + "所有配置文件(含Resource/Medical/Alchemy/teleport)已重载！")
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

            // 2. 列出丹药
            if (alcSub == "list") {
                sender.sendMessage("§e=== 已注册的丹药效果 ===")
                plugin.alchemyManager.effects.keys.forEach { id ->
                    val recipe = plugin.alchemyManager.recipes[id]
                    val name = recipe?.displayName ?: "未配置"
                    sender.sendMessage("§7- §f$id §7($name)")
                }
                return true
            }

            // 3. 给予丹药
            // /hjhadmin alchemy give <player> <pill_id> [LOW/MID/HIGH]
            if (alcSub == "give") {
                if (args.size < 4) {
                    sender.sendMessage("§c用法: /hjhadmin alchemy give <玩家> <丹药ID> [品质(默认LOW)]")
                    return true
                }

                val target = Bukkit.getPlayer(args[2])
                if (target == null) {
                    sender.sendMessage("§c玩家不在线。")
                    return true
                }

                val pillId = args[3]
                // 默认品质为 LOW
                val tierStr = if (args.size >= 5) args[4].uppercase() else "LOW"
                val tier = try {
                    AlchemyTier.valueOf(tierStr)
                } catch (e: Exception) {
                    sender.sendMessage("§c无效的品质，请使用: LOW, MID, HIGH")
                    return true
                }

                // 调用 Manager 的方法生成物品
                val item = plugin.alchemyManager.createPillItem(pillId, tier)

                if (item != null) {
                    target.inventory.addItem(item)
                    sender.sendMessage("§a已给予 ${target.name} 丹药: $pillId ($tier)")
                    target.sendMessage("§a[系统] 你获得了丹药: ${item.itemMeta?.displayName}")
                } else {
                    sender.sendMessage("§c给予失败！可能是该丹药ID不存在，或者该丹药没有配置 '${tier.name}' 品质的物品。")
                }
                return true
            }
            return true
        }

        // === get (获取 Resource 物品) ===
        if (subCommand == "get") {
            if (sender !is Player) {
                sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此命令。")
                return true
            }
            val player = sender

            if (args.size < 2) return error(sender, "用法: /hjhadmin get <物品ID或名字> [数量]")

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
            return error(sender, "用法: /hjhadmin status <玩家> [set <数值>]")
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

                // 找到所有使用该 ID 的旧实例 UUID
                val oldInstances = manager.instances.filterValues { it.templateId == npcData.id }.keys

                // 遍历删除旧实体和数据
                if (oldInstances.isNotEmpty()) {
                    for (uuid in oldInstances) {
                        // 尝试从世界中移除实体
                        Bukkit.getEntity(uuid)?.remove()
                        // 从内存 Map 中移除
                        manager.instances.remove(uuid)
                    }
                    sender.sendMessage("§e[系统] 检测到旧的 ${npcData.displayName}，已清除。")
                }

                // 3. 【修改部分】直接生成新的 NPC (不再 else 跳过)
                try {
                    val loc = npcData.getLocation()
                    // 确保区块加载
                    if (!loc.chunk.isLoaded) loc.chunk.load()

                    manager.spawnNpc(loc, npcData.id)
                    sender.sendMessage("§a[系统] 已在 ${loc.blockX},${loc.blockY},${loc.blockZ} 生成 ${npcData.displayName}")
                    count++
                } catch (e: Exception) {
                    sender.sendMessage("§c[错误] 生成 $e{npcData.id} 失败: ${e.message}")
                }
            }

            sender.sendMessage("§a操作完成，共生成/刷新了 $count 个 NPC。")
            return true
        }

        // === spawner (刷怪笼 / 手动测试笼 工具) ===
        if (subCommand == "spawner") {
            // 指令: /hjhadmin spawner <get|button> <MobID> [x] [y] [z]
            if (args.size < 3 || (args[1].lowercase() != "get" && args[1].lowercase() != "button")) {
                sender.sendMessage("§c用法: /hjhadmin spawner <get|button> <MobID> [x] [y] [z]")
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
                    // 【核心修改】：无论是 get 还是 button，不填坐标都记录输入指令时的当前位置
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
                "job", "race", "givetoken", "level", "reload", "gettestgear", "get",
                "medical", "getstation", "quest", "gennpc", "alchemy", "spawner",
                "status", "gettp", "getarrayblock", "chonghua"
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
                if (args.size == 2) return listOf("get", "button").filter { it.startsWith(args[1].lowercase()) }
                if (args.size == 3 && (args[1].equals("get", ignoreCase = true) || args[1].equals("button", ignoreCase = true))) {
                    return MobRegistry.getAllIds().filter { it.startsWith(args[2]) }
                }
            }

            "status" -> {
                if (args.size == 2) return null // 玩家名
                if (args.size == 3) return listOf("set").filter { it.startsWith(args[2].lowercase()) }
                if (args.size == 4 && args[2].equals("set", true)) return listOf("0", "1", "2", "3", "4").filter { it.startsWith(args[3]) }
            }

            "gettp" -> {
                if (args.size == 2) return listOf("STONE_PRESSURE_PLATE", "OAK_BUTTON", "LEVER", "STONE").filter { it.startsWith(args[1].uppercase()) }
                if (args.size == 3) return plugin.teleportManager.points.keys.toList().filter { it.startsWith(args[2]) }
            }

            "get" -> {
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
        }

        // === 3. 兜底处理 ===
        // 如果 args.size == 2 且上面没有处理（比如 job, race 等只匹配 size=3 的指令）
        // 返回 null 表示默认采用 Bukkit 原生的在线玩家名称补全
        if (args.size == 2) return null

        return ArrayList()
    }
}