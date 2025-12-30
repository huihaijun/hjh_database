package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;

public class TestMobCommand implements CommandExecutor {

    private final Hjh_database plugin;
    private final NamespacedKey armorKey;
    // 专属标签
    private static final String TEST_DUMMY_TAG = "hjh_test_dummy";

    public TestMobCommand(Hjh_database plugin) {
        this.plugin = plugin;
        this.armorKey = new NamespacedKey(plugin, "hjh_mob_armor");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此指令。");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用此指令。");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "用法:");
            sender.sendMessage(ChatColor.YELLOW + "1. 生成: /testmob <血量> [护甲]");
            sender.sendMessage(ChatColor.YELLOW + "2. 清除: /testmob clear [半径(默认10)]");
            return true;
        }

        // === 清除逻辑 ===
        if (args[0].equalsIgnoreCase("clear")) {
            double radius = 10.0;
            if (args.length >= 2) {
                try {
                    radius = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "半径必须是数字");
                    return true;
                }
            }

            int count = 0;
            Collection<Entity> nearbyEntities = player.getWorld().getNearbyEntities(player.getLocation(), radius, radius, radius);
            for (Entity entity : nearbyEntities) {
                // 只清除拥有专属标签的测伤怪物，不误删普通怪物
                if (entity.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
                    // 移除标签，防止复活
                    entity.removeScoreboardTag(TEST_DUMMY_TAG);
                    entity.remove();
                    count++;
                }
            }
            sender.sendMessage(ChatColor.GREEN + "已清除周围 " + radius + " 格内的 " + count + " 个测伤人偶。");
            return true;
        }

        // === 生成逻辑 ===
        double maxHealth;
        double armor = 0.0;

        try {
            maxHealth = Double.parseDouble(args[0]);
            if (args.length >= 2) {
                armor = Double.parseDouble(args[1]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "请输入有效的数字！");
            return true;
        }

        Location loc = player.getLocation();
        double finalArmor = armor;
        double finalMaxHealth = maxHealth;

        loc.getWorld().spawn(loc, Creeper.class, creeper -> {
            // 按照要求添加基础TAG
            creeper.addScoreboardTag("panling");
            creeper.addScoreboardTag("monster");
            // 添加专属逻辑TAG
            creeper.addScoreboardTag(TEST_DUMMY_TAG);

            creeper.setAI(false);
            creeper.setPowered(false);
            creeper.setExplosionRadius(0);

            // NBT 存护甲
            creeper.getPersistentDataContainer().set(armorKey, PersistentDataType.DOUBLE, finalArmor);

            if (creeper.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                creeper.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(finalMaxHealth);
            }
            if (creeper.getAttribute(Attribute.GENERIC_ARMOR) != null) {
                creeper.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(0);
            }

            creeper.setHealth(finalMaxHealth);

            creeper.setCustomName(ChatColor.translateAlternateColorCodes('&',
                    "&c&l测伤人偶 &7(HP:" + (int)finalMaxHealth + " 护甲:" + (int)finalArmor + ")"));
            creeper.setCustomNameVisible(true);
        });

        player.sendMessage(ChatColor.GREEN + "已生成测试人偶！血量: " + finalMaxHealth + ", 护甲: " + finalArmor);

        return true;
    }
}