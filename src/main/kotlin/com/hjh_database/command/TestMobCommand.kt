package com.hjh_database.command

import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Creeper
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

class TestMobCommand(private val plugin: Hjh_database) : CommandExecutor {

    companion object {
        const val TEST_DUMMY_TAG = "hjh_test_dummy"

        fun spawnDummy(plugin: Hjh_database, loc: Location, maxHealth: Double, armor: Double): Creeper? {
            val armorKey = NamespacedKey(plugin, "hjh_mob_armor")
            return loc.world?.spawn(loc, Creeper::class.java) { creeper ->
                creeper.addScoreboardTag("panling")
                creeper.addScoreboardTag("monster")
                creeper.addScoreboardTag(TEST_DUMMY_TAG)

                creeper.setAI(false)
                creeper.isPowered = false
                creeper.explosionRadius = 0

                creeper.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, armor)

                creeper.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
                creeper.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
                creeper.health = maxHealth

                creeper.customName = ChatColor.translateAlternateColorCodes(
                    '&',
                    "&c&l测伤人偶 &7(HP:${maxHealth.toInt()} 护甲:${armor.toInt()})"
                )
                creeper.isCustomNameVisible = true
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage("${ChatColor.RED}你没有权限使用此指令。")
            return true
        }

        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}只有玩家可以使用此指令。")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("${ChatColor.YELLOW}用法:")
            sender.sendMessage("${ChatColor.YELLOW}1. 生成: /testmob <血量> [护甲]")
            sender.sendMessage("${ChatColor.YELLOW}2. 清除: /testmob clear [半径(默认10)]")
            return true
        }

        if (args[0].equals("clear", ignoreCase = true)) {
            val radius = if (args.size >= 2) {
                args[1].toDoubleOrNull() ?: run {
                    sender.sendMessage("${ChatColor.RED}半径必须是数字")
                    return true
                }
            } else {
                10.0
            }

            var count = 0
            val nearbyEntities = sender.world.getNearbyEntities(sender.location, radius, radius, radius)
            for (entity in nearbyEntities) {
                if (entity.scoreboardTags.contains(TEST_DUMMY_TAG)) {
                    entity.removeScoreboardTag(TEST_DUMMY_TAG)
                    entity.remove()
                    count++
                }
            }
            sender.sendMessage("${ChatColor.GREEN}已清除周围 $radius 格内的 $count 个测伤人偶。")
            return true
        }

        val maxHealth = args[0].toDoubleOrNull()
        val armor = if (args.size >= 2) args[1].toDoubleOrNull() else 0.0
        if (maxHealth == null || armor == null) {
            sender.sendMessage("${ChatColor.RED}请输入有效的数字！")
            return true
        }

        spawnDummy(plugin, sender.location, maxHealth, armor)
        sender.sendMessage("${ChatColor.GREEN}已生成测试人偶！血量: $maxHealth, 护甲: $armor")
        return true
    }
}
