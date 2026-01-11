package com.hjh_database.command;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final Hjh_database plugin;

    // 映射表
    private final Map<Integer, String> jobMap = Map.of(0, "战士", 1, "弓箭手", 2, "术士", 3, "医师");
    private final Map<Integer, String> raceMap = Map.of(0, "神", 1, "仙", 2, "人", 3, "战神", 4, "妖");

    // 反向查找
    private final Map<String, Integer> jobReverseMap = jobMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    private final Map<String, Integer> raceReverseMap = raceMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    public AdminCommand(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此管理命令。");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "=== HJH 管理员指令 ===");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin reload - 重载配置");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin get <物品ID/名称> [数量] - 获取Resource物品");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin gettestgear <玩家> - 获取测试装备");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin givetoken <玩家> - 给予天机令");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin job <玩家> <职业> - 设置职业");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin race <玩家> <种族> - 设置种族");
            // 【新增提示】
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin medical <技能ID> - 获取医术秘籍");
            sender.sendMessage(ChatColor.YELLOW + "/hjhadmin getstation - 获取医术绘制台");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        // === reload (重载) ===
        if (subCommand.equals("reload")) {
            plugin.reloadConfig();
            plugin.getMenuManager().reload();
            plugin.getPlayerManager().getWeaponManager().reload();
            plugin.getPlayerManager().getArmorManager().reload();

            // 重载 Resource 物品
            if (plugin.getResourceManager() != null) {
                plugin.getResourceManager().reload();
            }
            // 【新增】重载医术配置
            if (plugin.getMedicalManager() != null) {
                plugin.getMedicalManager().loadSkillBooks(); // 假设你在 Manager 里有这个加载方法
            }

            sender.sendMessage(ChatColor.GREEN + "所有配置文件(含Resource/Medical)已重载！");
            return true;
        }

        // === get (获取 Resource 物品) ===
        if (subCommand.equals("get")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令。");
                return true;
            }
            Player player = (Player) sender;

            if (args.length < 2) return error(sender, "用法: /hjhadmin get <物品ID或名字> [数量]");

            String itemName = args[1];
            // 从 ResourceManager 获取物品
            ItemStack item = plugin.getResourceManager().getItem(itemName);

            if (item == null) {
                return error(sender, "未找到名为 [" + itemName + "] 的物品！请检查 resources 文件夹。");
            }

            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    return error(sender, "数量必须是数字。");
                }
            }

            item.setAmount(amount);
            player.getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "已获得物品: " + itemName + " x" + amount);
            return true;
        }

        // === medical (获取医术秘籍) 【新增部分】 ===
        if (subCommand.equals("medical")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令。");
                return true;
            }
            Player player = (Player) sender;

            if (args.length < 2) return error(sender, "用法: /hjhadmin medical <技能ID>");

            String skillId = args[1];
            // 调用 MedicalManager 获取秘籍
            if (plugin.getMedicalManager() != null) {
                ItemStack book = plugin.getMedicalManager().getSkillBook(skillId); // 之前写的方法叫 getSkillBook
                if (book != null) {
                    player.getInventory().addItem(book);
                    sender.sendMessage(ChatColor.GREEN + "已获得医术秘籍: " + skillId);
                } else {
                    sender.sendMessage(ChatColor.RED + "未找到技能ID为 [" + skillId + "] 的秘籍配置。");
                }
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("getstation")) {
            if (plugin.getMedicalManager() != null) {
                Player p = (Player) sender;
                p.getInventory().addItem(plugin.getMedicalManager().getMedicalStationItem());
                p.sendMessage("§a已获取医术绘制台！");
                return true;
            }
        }

        // === givetoken (给予天机令) ===
        if (subCommand.equals("givetoken")) {
            if (args.length < 2) return error(sender, "用法: /hjhadmin givetoken <玩家>");
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) return error(sender, "玩家不在线");
            target.getInventory().addItem(plugin.getMenuManager().getTianjiToken());
            sender.sendMessage(ChatColor.GREEN + "给予天机令成功。");
            return true;
        }

        // === gettestgear (获取测试装备) ===
        if (subCommand.equals("gettestgear")) {
            if (args.length < 2) return error(sender, "用法: /hjhadmin gettestgear <玩家>");
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) return error(sender, "玩家不在线");

            // 获取物品
            ItemStack sword = plugin.getPlayerManager().getWeaponManager().getItemStack("novice_sword");
            ItemStack helm = plugin.getPlayerManager().getArmorManager().getItemStack("novice_helmet");
            ItemStack chest = plugin.getPlayerManager().getArmorManager().getItemStack("novice_chestplate");
            ItemStack leg = plugin.getPlayerManager().getArmorManager().getItemStack("novice_leggings");
            ItemStack boot = plugin.getPlayerManager().getArmorManager().getItemStack("novice_boots");

            if (sword != null) target.getInventory().addItem(sword);
            if (helm != null) target.getInventory().setHelmet(helm);
            if (chest != null) target.getInventory().setChestplate(chest);
            if (leg != null) target.getInventory().setLeggings(leg);
            if (boot != null) target.getInventory().setBoots(boot);

            // 刷新属性
            plugin.getPlayerManager().updateStats(target);
            sender.sendMessage(ChatColor.GREEN + "已发放全套测试装备给 " + target.getName());
            return true;
        }

        // === job / race (设置职业/种族) ===
        if (args.length < 3) return error(sender, "用法: /hjhadmin <job|race> <玩家> <值>");

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return error(sender, "玩家不在线");
        PlayerData data = plugin.getPlayerManager().getData(target.getUniqueId());
        if (data == null) return error(sender, "数据加载中...");

        String valStr = args[2];
        if (subCommand.equals("job")) {
            Integer job = jobReverseMap.get(valStr);
            if (job == null) return error(sender, "无效职业 (战士/弓箭手/术士/医师)");
            data.setJob(job);
            sender.sendMessage(ChatColor.GREEN + "职业已设为: " + valStr);
        } else if (subCommand.equals("race")) {
            Integer race = raceReverseMap.get(valStr);
            if (race == null) return error(sender, "无效种族 (神/仙/人/战神/妖)");
            data.setRace(race);
            sender.sendMessage(ChatColor.GREEN + "种族已设为: " + valStr);
        } else {
            return error(sender, "未知指令");
        }

        plugin.getPlayerManager().updateStats(target);
        return true;
    }

    // 简化的错误提示
    private boolean error(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.RED + msg);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 【修改】添加 medical 到一级补全
        if (args.length == 1) return Arrays.asList("job", "race", "givetoken", "reload", "gettestgear", "get", "medical","getstation");

        // 如果是 get 指令，第二个参数提示所有物品的ID和名字
        if (args.length == 2 && args[0].equalsIgnoreCase("get")) {
            if (plugin.getResourceManager() != null) {
                List<String> allNames = plugin.getResourceManager().getAllItemNames();
                // 简单的过滤逻辑
                String currentInput = args[1].toLowerCase();
                return allNames.stream()
                        .filter(name -> name.toLowerCase().startsWith(currentInput))
                        .collect(Collectors.toList());
            }
            return new ArrayList<>();
        }

        // 【新增】如果是 medical 指令，提示技能ID
        if (args.length == 2 && args[0].equalsIgnoreCase("medical")) {
            if (plugin.getMedicalManager() != null) {
                // 之前让你在 Manager 里加的 getAllSkillIds()
                return new ArrayList<>(plugin.getMedicalManager().getAllSkillIds());
            }
        }

        if (args.length == 2) return null; // 其他指令默认回显玩家名

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("job")) return new ArrayList<>(jobReverseMap.keySet());
            if (args[0].equalsIgnoreCase("race")) return new ArrayList<>(raceReverseMap.keySet());
        }
        return new ArrayList<>();
    }
}