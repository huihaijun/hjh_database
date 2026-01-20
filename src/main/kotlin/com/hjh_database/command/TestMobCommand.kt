package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

class TestMobCommand(plugin: Hjh_database) : CommandExecutor {

    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")

    companion object {
        private const val TEST_DUMMY_TAG = "hjh_test_dummy"
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有权限使用此指令。")
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(ChatColor.RED.toString() + "只有玩家可以使用此指令。")
            return true
        }
        val player = sender

        if (args.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW.toString() + "用法:")
            sender.sendMessage(ChatColor.YELLOW.toString() + "1. 生成: /testmob <血量> [护甲]")
            sender.sendMessage(ChatColor.YELLOW.toString() + "2. 清除: /testmob clear [半径(默认10)]")
            return true
        }

        // === 清除逻辑 ===
        if (args[0].equals("clear", ignoreCase = true)) {
            var radius = 10.0
            if (args.size >= 2) {
                try {
                    radius = args[1].toDouble()
                } catch (e: NumberFormatException) {
                    sender.sendMessage(ChatColor.RED.toString() + "半径必须是数字")
                    return true
                }
            }

            var count = 0
            val nearbyEntities = player.world.getNearbyEntities(player.location, radius, radius, radius)
            for (entity in nearbyEntities) {
                // 只清除拥有专属标签的测伤怪物，不误删普通怪物
                if (entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
                    // 移除标签，防止复活
                    entity.removeScoreboardTag(TEST_DUMMY_TAG)
                    entity.remove()
                    count++
                }
            }
            sender.sendMessage(ChatColor.GREEN.toString() + "已清除周围 " + radius + " 格内的 " + count + " 个测伤人偶。")
            return true
        }

        // === 生成逻辑 ===
        val maxHealth: Double
        var armor = 0.0

        try {
            maxHealth = args[0].toDouble()
            if (args.size >= 2) {
                armor = args[1].toDouble()
            }
        } catch (e: NumberFormatException) {
            sender.sendMessage(ChatColor.RED.toString() + "请输入有效的数字！")
            return true
        }

        val loc = player.location
        val finalArmor = armor
        val finalMaxHealth = maxHealth

        loc.world?.spawn(loc, Creeper::class.java) { creeper ->
            // 按照要求添加基础TAG
            creeper.addScoreboardTag("panling")
            creeper.addScoreboardTag("monster")
            // 添加专属逻辑TAG
            creeper.addScoreboardTag(TEST_DUMMY_TAG)

            creeper.setAI(false)
            creeper.isPowered = false
            creeper.explosionRadius = 0

            // NBT 存护甲
            creeper.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, finalArmor)

            // 1.21.3 适配: GENERIC_MAX_HEALTH -> MAX_HEALTH
            if (creeper.getAttribute(Attribute.MAX_HEALTH) != null) {
                creeper.getAttribute(Attribute.MAX_HEALTH)!!.baseValue = finalMaxHealth
            }
            // 1.21.3 适配: GENERIC_ARMOR -> ARMOR
            if (creeper.getAttribute(Attribute.ARMOR) != null) {
                creeper.getAttribute(Attribute.ARMOR)!!.baseValue = 0.0
            }

            creeper.health = finalMaxHealth

            creeper.customName = ChatColor.translateAlternateColorCodes('&',
                "&c&l测伤人偶 &7(HP:${finalMaxHealth.toInt()} 护甲:${finalArmor.toInt()})"
            )
            creeper.isCustomNameVisible = true
        }

        player.sendMessage(ChatColor.GREEN.toString() + "已生成测试人偶！血量: " + finalMaxHealth + ", 护甲: " + finalArmor)

        return true
    }
}