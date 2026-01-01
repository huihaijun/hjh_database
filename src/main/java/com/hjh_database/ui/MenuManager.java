package com.hjh_database.ui;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MenuManager {
    private final Hjh_database plugin;
    private File file;
    private FileConfiguration config;
    private final NamespacedKey tokenKey;

    // 职业和种族名称映射
    private final String[] JOB_NAMES = {"战士", "弓箭手", "术士", "医师"};
    private final String[] RACE_NAMES = {"神", "仙", "人", "战神", "妖"};

    public MenuManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, "hjh_token_item");
        reload();
    }

    // === 枚举定义：方便管理 6 种元素 ===
    public enum ElementType {
        METAL("金元素", "panling:metal", Material.EMERALD),
        WOOD("木元素", "panling:wood", Material.BONE),
        WATER("水元素", "panling:water", Material.STRING),
        FIRE("火元素", "panling:fire", Material.BLAZE_ROD),
        EARTH("土元素", "panling:earth", Material.MAGMA_CREAM),
        RELIVE("重生石", "panling:relive_stone", Material.NETHER_STAR);

        public final String name;
        public final String nbtId;
        public final Material material;

        ElementType(String name, String nbtId, Material material) {
            this.name = name;
            this.nbtId = nbtId;
            this.material = material;
        }
    }

    public void reload() {
        file = new File(plugin.getDataFolder(), "menus.yml");
        if (!file.exists()) {
            plugin.saveResource("menus.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public ItemStack getTianjiToken() {
        String matStr = config.getString("token_item.material", "CLOCK");
        Material mat = Material.getMaterial(matStr);
        if (mat == null) mat = Material.CLOCK;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(format(config.getString("token_item.name", "&6&l天机令")));
            List<String> lore = config.getStringList("token_item.lore");
            List<String> coloredLore = new ArrayList<>();
            for (String s : lore) coloredLore.add(format(s));
            meta.setLore(coloredLore);
            meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }
        return item;
    }

    public void openMainMenu(Player player) {
        // 打开前先刷新一下属性，确保灵力上限等数据是最新的
        plugin.getPlayerManager().updateStats(player);

        String title = format(config.getString("gui.title", "天机阁"));
        int size = config.getInt("gui.size", 54);
        Inventory inv = Bukkit.createInventory(null, size, title);

        // 获取最新数据
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(ChatColor.RED + "数据加载中，请稍后再试...");
            return;
        }

        ConfigurationSection itemsSec = config.getConfigurationSection("gui.items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                ConfigurationSection itemSec = itemsSec.getConfigurationSection(key);
                if (itemSec == null) continue;
                int slot = itemSec.getInt("slot", 0);
                String matStr = itemSec.getString("material", "STONE");
                Material mat = Material.getMaterial(matStr);
                if (mat == null) mat = Material.STONE;
                String name = itemSec.getString("name", "Button");
                List<String> lore = itemSec.getStringList("lore");
                ItemStack icon = new ItemStack(mat);
                ItemMeta meta = icon.getItemMeta();
                if (matStr.equals("PLAYER_HEAD") && meta instanceof SkullMeta) {
                    ((SkullMeta) meta).setOwningPlayer(player);
                }
                if (meta != null) {
                    // 替换标题中的变量
                    meta.setDisplayName(format(replacePlaceholders(name, player, data)));
                    List<String> finalLore = new ArrayList<>();
                    for (String line : lore) {
                        // 替换每一行 Lore 中的变量
                        String replacedLine = replacePlaceholders(line, player, data);
                        finalLore.add(format(replacedLine));
                    }
                    meta.setLore(finalLore);
                    meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, "gui_item");
                    icon.setItemMeta(meta);
                }
                inv.setItem(slot, icon);
            }
        }

        // 放置“道天图录”入口按钮
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName("§b§l道天图录");
        List<String> lore = new ArrayList<>();
        lore.add("§7点击打开道天图录管理元素");
        meta.setLore(lore);
        book.setItemMeta(meta);
        inv.setItem(31, book);
        player.openInventory(inv);
    }

    // === 2. 打开道天图录 (二级菜单) ===
    public void openDaoTianMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§8§l道天图录 - 元素仓库");
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        inv.setItem(20, createGuiItem(ElementType.METAL, data.getMetal()));
        inv.setItem(21, createGuiItem(ElementType.WOOD, data.getWood()));
        inv.setItem(22, createGuiItem(ElementType.WATER, data.getWater()));
        inv.setItem(23, createGuiItem(ElementType.FIRE, data.getFire()));
        inv.setItem(24, createGuiItem(ElementType.EARTH, data.getEarth()));
        inv.setItem(31, createGuiItem(ElementType.RELIVE, data.getReliveStone()));

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c返回");
        back.setItemMeta(backMeta);
        inv.setItem(49, back);
        player.openInventory(inv);
    }

    private ItemStack createGuiItem(ElementType type, int amount) {
        ItemStack item = new ItemStack(type.material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + type.name);
        List<String> lore = new ArrayList<>();
        lore.add("§7----------------");
        lore.add("§f当前库存: §a" + amount);
        lore.add("§7----------------");
        lore.add("§e[左键] §f存入背包内所有此物品");
        lore.add("§e[右键] §f取出一个");
        lore.add("§e[Shift+右键] §f取出一组 (64个)");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "btn_element"), PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }

    // === 生成 NBT 物品 ===
    @SuppressWarnings("deprecation")
    public ItemStack getPanlingItem(ElementType type, int count) {
        ItemStack item = new ItemStack(type.material, count);
        String nbtTag = "";
        if (type == ElementType.RELIVE) {
            nbtTag = "{id:\"" + type.nbtId + "\",display:{Name:'{\"translate\":\"pl.item.name.relifestone\"}'}}";
        } else {
            // 安全起见，硬编码确保 ID 正确
            if (type == ElementType.METAL) nbtTag = "{id:\"panling:metal\",display:{Name:'{\"translate\":\"pl.item.name.metal\"}'}}";
            else if (type == ElementType.WOOD) nbtTag = "{id:\"panling:wood\",display:{Name:'{\"translate\":\"pl.item.name.wood\"}'}}";
            else if (type == ElementType.WATER) nbtTag = "{id:\"panling:water\",display:{Name:'{\"translate\":\"pl.item.name.water\"}'}}";
            else if (type == ElementType.FIRE) nbtTag = "{id:\"panling:fire\",display:{Name:'{\"translate\":\"pl.item.name.fire\"}'}}";
            else if (type == ElementType.EARTH) nbtTag = "{id:\"panling:earth\",display:{Name:'{\"translate\":\"pl.item.name.earth\"}'}}";
            else nbtTag = "{id:\"" + type.nbtId + "\",display:{Name:'{\"translate\":\"pl.item.name." + type.name().toLowerCase() + "\"}'}}";
        }
        try {
            return Bukkit.getUnsafe().modifyItemStack(item, nbtTag);
        } catch (Exception e) {
            plugin.getLogger().warning("无法生成 NBT 物品: " + type.name);
            e.printStackTrace();
            return item;
        }
    }

    public boolean isPanlingItem(ItemStack item, ElementType type) {
        if (item == null || item.getType() != type.material) return false;
        try {
            if (item.hasItemMeta()) {
                String metaStr = item.getItemMeta().getAsString();
                return metaStr.contains("id:\"" + type.nbtId + "\"");
            }
        } catch (Exception e) {
            String str = item.toString();
            return str.contains(type.nbtId);
        }
        return false;
    }

    public boolean isTianjiToken(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return false;
        return item.getItemMeta().getPersistentDataContainer().has(tokenKey, PersistentDataType.STRING);
    }

    private String format(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // === 核心修改逻辑 ===
    private String replacePlaceholders(String text, Player player, PlayerData data) {
        // 1. 基础替换
        text = text.replace("%player_name%", player.getName());
        text = text.replace("%lv%", String.valueOf(data.getLv()));

        // 【新增】经验相关占位符 (仅在此处插入逻辑，不动其他代码)
        if (text.contains("%exp%") || text.contains("%max_exp%")) {
            int currentExp = data.getExp(); // 需确保 PlayerData 中有 getExp()
            // 调用 PlayerManager 获取配置中的升级经验
            int maxExp = plugin.getPlayerManager().getMaxExpRequired(data.getLv());

            text = text.replace("%exp%", String.valueOf(currentExp));
            text = text.replace("%max_exp%", String.valueOf(maxExp));

            // 百分比显示
            if (text.contains("%exp_percent%")) {
                int percent = maxExp > 0 ? (int) (((double) currentExp / maxExp) * 100) : 0;
                text = text.replace("%exp_percent%", percent + "%");
            }
        }

        // 职业映射
        String jobName = "无";
        Integer job = data.getJob();
        if (job != null && job >= 0 && job < JOB_NAMES.length) {
            jobName = JOB_NAMES[job];
        }
        text = text.replace("%job%", jobName);

        // 种族映射
        String raceName = "未知";
        Integer race = data.getRace();
        if (race != null && race >= 0 && race < RACE_NAMES.length) {
            raceName = RACE_NAMES[race];
        }
        text = text.replace("%race%", raceName);

        // 数值替换
        text = text.replace("%max_health%", String.format("%.1f", data.getMaxHealth()));
        text = text.replace("%current_health%", String.format("%.1f", player.getHealth()));
        text = text.replace("%attack%", String.format("%.1f", data.getAttack()));
        text = text.replace("%archer_damage%", String.format("%.1f", data.getArcherDamage()));
        text = text.replace("%armor%", String.format("%.1f", data.getArmor()));
        text = text.replace("%money%", String.format("%.1f", data.getMoney()));
        text = text.replace("%crit_chance%", String.format("%.1f%%", data.getCritChance() * 100));

        // 冷却缩减
        text = text.replace("%CoolReduce%", String.format("%.1f%%", data.getCoolReduce() * 100));

        // 【修改的核心】灵力显示格式：当前/上限
        // 这里会自动读取 data.getLingli() (数据库存的) 和 data.getMaxLingli() (代码算的)
        // 并拼接成 "10/100" 这种格式返回给菜单
        String lingliDisplay = (int)data.getLingli().doubleValue() + "/" + (int)data.getMaxLingli();
        text = text.replace("%lingli%", lingliDisplay);

        // 抗击退显示为百分比 (0.1 -> 10%)
        text = text.replace("%knock_back_res%", String.format("%.0f%%", data.getKnockBackRes() * 100));

        // 2. 智能属性行替换
        if (text.contains("%main_stat_line%")) {
            String replacement = "&f主属性: &7暂无职业";

            if (job != null) {
                switch (job) {
                    case 0: // 战士
                        replacement = "&f近战强度: &b" + String.format("%.1f", data.getAttack());
                        break;
                    case 1: // 弓箭手
                        replacement = "&f箭矢强度: &b" + String.format("%.1f", data.getArcherDamage());
                        break;
                    case 2: // 术士
                        replacement = "&f阵法强度: &b" + String.format("%.1f", data.getZfStr());
                        break;
                    case 3: // 医师
                        replacement = "&f医术: &b" + String.format("%.1f", data.getZfStr());
                        break;
                    default:
                        replacement = "&f主属性: &7未知";
                        break;
                }
            }
            text = text.replace("%main_stat_line%", replacement);
        }

        return text;
    }
}