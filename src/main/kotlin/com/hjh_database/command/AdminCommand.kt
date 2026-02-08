package com.hjh_database.command

import com.hjh_database.Hjh_database
import com.hjh_database.alchemy.data.AlchemyTier
import com.hjh_database.quest.core.QuestStatus
import com.hjh_database.quest.core.StoryNpcs
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
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin job <玩家> <职业> - 设置职业")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin race <玩家> <种族> - 设置种族")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin medical <技能ID> - 获取医术秘籍")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin getstation - 获取医术绘制台")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin quest <玩家> <ID> <状态> - 修改任务进度")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin gennpc <ID|ALL> - 生成剧情NPC")
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin spawn - 生成自定义刷怪笼等")
            // 【丹药提示】
            sender.sendMessage(ChatColor.YELLOW.toString() + "/hjhadmin alchemy <list|give|getcauldron> ... - 丹药系统指令")
            return true
        }

        val subCommand = args[0].lowercase()

        // === reload (重载) ===
        if (subCommand == "reload") {
            plugin.reloadConfig()
            plugin.menuManager.reload()
            plugin.playerManager.weaponManager.reload()
            plugin.playerManager.armorManager.reload()

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

            sender.sendMessage(ChatColor.GREEN.toString() + "所有配置文件(含Resource/Medical/Alchemy)已重载！")
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

        // === spawner (刷怪笼工具) ===
        if (subCommand == "spawner") {
            if (sender !is Player) {
                sender.sendMessage("§c只有玩家可以使用此指令。")
                return true
            }
            // /hjhadmin spawner [get/wand]
            if (args.size < 2) {
                sender.sendMessage("§c用法: /hjhadmin spawner <get|wand>")
                return true
            }

            val type = args[1].lowercase()
            val player = sender

            // 1. 获取自定义刷怪笼物品
            if (type == "get") {
                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.SPAWNER)
                val meta = item.itemMeta
                meta?.setDisplayName("§c§l自定义刷怪笼")
                meta?.lore = listOf("§7放置后使用木锄右键编辑", "§7HJH RPG System")

                // 打上标记，让 SpawnerListener 识别
                val key = org.bukkit.NamespacedKey(plugin, "hjh_spawner_item")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.BYTE, 1)

                item.itemMeta = meta
                player.inventory.addItem(item)
                player.sendMessage("§a已获取自定义刷怪笼。")
                return true
            }
            // 2. 获取链接权杖 (金锄头)
            else if (type == "wand") {
                val item = org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_HOE)
                val meta = item.itemMeta
                meta?.setDisplayName("§b§l[链接权杖]")
                meta?.lore = listOf("§7左键/右键普通方块: §f记录坐标", "§7右键刷怪笼: §a绑定坐标", "§7用于设定怪物的出生点")

                // 打上标记
                val key = org.bukkit.NamespacedKey(plugin, "hjh_link_wand")
                meta?.persistentDataContainer?.set(key, org.bukkit.persistence.PersistentDataType.BYTE, 1)

                item.itemMeta = meta
                player.inventory.addItem(item)
                player.sendMessage("§a已获取链接权杖。")
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
        // 【修改】添加 medical, alchemy 到一级补全
        if (args.size == 1) return listOf("job", "race", "givetoken", "reload", "gettestgear", "get", "medical", "getstation","quest","gennpc", "alchemy","spawner").filter { it.startsWith(args[0].lowercase()) }

        val subCmd = args[0].lowercase()

        // === 丹药 Tab 补全 ===
        if (subCmd == "alchemy") {
            if (args.size == 2) {
                return listOf("give", "list", "getcauldron").filter { it.startsWith(args[1].lowercase()) }
            }
            if (args.size == 3 && args[1].equals("give", ignoreCase = true)) {
                // 补全玩家名
                return null
            }
            if (args.size == 4 && args[1].equals("give", ignoreCase = true)) {
                // 补全丹药ID
                return plugin.alchemyManager.effects.keys.toList().filter { it.startsWith(args[3]) }
            }
            if (args.size == 5 && args[1].equals("give", ignoreCase = true)) {
                // 补全品质
                return listOf("LOW", "MID", "HIGH").filter { it.startsWith(args[4].uppercase()) }
            }
        }

        // 【新增】spawner 子命令补全
        if (subCmd == "spawner") {
            if (args.size == 2) {
                return listOf("get", "wand").filter { it.startsWith(args[1].lowercase()) }
            }
        }

        // 如果是 get 指令，第二个参数提示所有物品的ID和名字
        if (subCmd == "get") {
            if (args.size == 2) {
                if (plugin.resourceManager != null) {
                    val allNames = plugin.resourceManager.getAllItemNames()
                    val currentInput = args[1].lowercase()
                    return allNames.filter { it.lowercase().startsWith(currentInput) }
                }
                return ArrayList()
            }
        }

        // 任务系统的指令
        if (subCmd == "quest") {
            if (args.size == 2) return null // 玩家名
            if (args.size == 3) {
                // 返回所有注册的任务ID
                return plugin.questManager.getAllQuests().map { it.id }.filter { it.startsWith(args[2]) }
            }
            if (args.size == 4) return listOf("LOCKED", "IN_PROGRESS", "COMPLETED").filter { it.startsWith(args[3].uppercase()) }
            if (args.size == 5) return listOf("0", "1", "5", "10")
        }

        // 【新增】如果是 medical 指令，提示技能ID
        if (subCmd == "medical") {
            if (args.size == 2) {
                if (plugin.medicalManager != null) {
                    return ArrayList(plugin.medicalManager.getAllSkillIds()).filter { it.startsWith(args[1]) }
                }
            }
        }

        if (subCmd == "gennpc") {
            if (args.size == 2) {
                val list = ArrayList<String>()
                list.add("ALL")
                list.addAll(StoryNpcs.values().map { it.name })
                return list.filter { it.startsWith(args[1].uppercase()) }
            }
        }

        if (args.size == 2) return null // 其他指令默认回显玩家名

        if (args.size == 3) {
            if (args[0].equals("job", ignoreCase = true)) return ArrayList(jobReverseMap.keys).filter { it.startsWith(args[2]) }
            if (args[0].equals("race", ignoreCase = true)) return ArrayList(raceReverseMap.keys).filter { it.startsWith(args[2]) }
        }
        return ArrayList()
    }
}