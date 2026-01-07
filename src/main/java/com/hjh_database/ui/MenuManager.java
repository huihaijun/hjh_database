package com.hjh_database.ui;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.dz.data.DzPlayerData; // 【新增】导入锻造数据类
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
        // 打开前先刷新一下属性
        plugin.getPlayerManager().updateStats(player);

        String title = format(config.getString("gui.title", "天机阁"));
        int size = config.getInt("gui.size", 54);
        Inventory inv = Bukkit.createInventory(null, size, title);

        // 获取最新 RPG 数据
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
                    // 替换标题
                    meta.setDisplayName(format(replacePlaceholders(name, player, data)));
                    List<String> finalLore = new ArrayList<>();
                    for (String line : lore) {
                        // 替换 Lore
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

        // 道天图录按钮
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

    @SuppressWarnings("deprecation")
    public ItemStack getPanlingItem(ElementType type, int count) {
        ItemStack item = new ItemStack(type.material, count);
        String nbtTag = "";
        if (type == ElementType.RELIVE) {
            nbtTag = "{id:\"" + type.nbtId + "\",display:{Name:'{\"translate\":\"pl.item.name.relifestone\"}'}}";
        } else {
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

    // =========================================================
    //  ⚡️ 核心替换逻辑：包含 RPG 数据和 锻造数据
    // =========================================================
    private String replacePlaceholders(String text, Player player, PlayerData data) {
        // 1. 基础替换
        text = text.replace("%player_name%", player.getName());
        text = text.replace("%lv%", String.valueOf(data.getLv()));

        // 2. 锻造系统变量替换 (修正乱码问题的核心)
        if (text.contains("forge")) { // 简单优化：只有包含 forge 关键字时才去获取数据
            DzPlayerData dzData = plugin.getPlayerManager().getDzData(player.getUniqueId());
            if (dzData != null) {
                // 正确的做法：调用 get 方法获取 int，而不是直接用对象
                text = text.replace("%forge_level%", String.valueOf(dzData.getForgeLevel()));
                text = text.replace("%forge_exp%", String.valueOf(dzData.getForgeExp()));

                // 获取最大经验和资质名称
                int maxForgeExp = plugin.getDzLevelManager().getMaxExp(dzData.getForgeLevel());
                String maxExpStr = (maxForgeExp == -1) ? "MAX" : String.valueOf(maxForgeExp);
                text = text.replace("%forge_max_exp%", maxExpStr);

                String licName = plugin.getDzLevelManager().getLicenseName(dzData.getForgeLicense());
                text = text.replace("%forge_license_name%", licName);
            } else {
                // 如果获取不到数据，显示默认值
                text = text.replace("%forge_level%", "1");
                text = text.replace("%forge_exp%", "0");
                text = text.replace("%forge_max_exp%", "-");
                text = text.replace("%forge_license_name%", "无数据");
            }
        }

        // === 开物术变量 ===
        if (text.contains("%kaiwu_level%")) {
            text = text.replace("%kaiwu_level%", String.valueOf(data.getKaiWuLevel()));
        }
        if (text.contains("%kaiwu_exp%")) {
            text = text.replace("%kaiwu_exp%", String.valueOf(data.getKaiWuExp()));
        }
        // 【新增】
        if (text.contains("%kaiwu_next_exp%")) {
            text = text.replace("%kaiwu_next_exp%", String.valueOf(data.getKaiWuNextLevelExp()));
        }
        if (text.contains("%kaiwu_energy%")) {
            // 保留一位小数
            text = text.replace("%kaiwu_energy%", String.format("%.1f", data.getKaiWuEnergy()));
        }
        if (text.contains("%kaiwu_max_energy%")) {
            // 保留一位小数
            text = text.replace("%kaiwu_max_energy%", String.format("%.1f", data.getMaxKaiWuEnergy()));
        }

        // 3. RPG 经验相关
        if (text.contains("%exp%") || text.contains("%max_exp%") || text.contains("%exp_percent%")) {
            // 注意：这里要小心不要误伤到上面的 %forge_exp%，所以最好先处理完 forge 再处理这个
            // 或者你的变量名区分度足够高（forge_exp vs exp）

            int currentExp = data.getExp();
            int maxExp = plugin.getPlayerManager().getMaxExpRequired(data.getLv());

            // 这里只替换 %exp%，不会替换 %forge_exp%
            // 但为了安全，可以使用 replaceAll("\\b%exp%\\b", ...) 但这里简单处理即可
            // 因为 %forge_exp% 已经被上面替换成数字了，所以不会冲突
            text = text.replace("%exp%", String.valueOf(currentExp));

            if (maxExp <= 0) {
                text = text.replace("%max_exp%", "MAX");
                text = text.replace("%exp_percent%", "100%");
            } else {
                text = text.replace("%max_exp%", String.valueOf(maxExp));
                int percent = (int) (((double) currentExp / maxExp) * 100);
                text = text.replace("%exp_percent%", percent + "%");
            }
        }

        // 4. 职业与种族
        String jobName = "无";
        Integer job = data.getJob();
        if (job != null && job >= 0 && job < JOB_NAMES.length) {
            jobName = JOB_NAMES[job];
        }
        text = text.replace("%job%", jobName);

        String raceName = "未知";
        Integer race = data.getRace();
        if (race != null && race >= 0 && race < RACE_NAMES.length) {
            raceName = RACE_NAMES[race];
        }
        text = text.replace("%race%", raceName);

        // 稀有度

        // === 【新增】 稀有度详细显示 ===
        if (text.contains("%rarity_display%")) {
            text = text.replace("%rarity_display%", getRarityDisplayString(player, data));
        }

        // 5. 战斗属性
        text = text.replace("%max_health%", String.format("%.1f", data.getMaxHealth()));
        text = text.replace("%current_health%", String.format("%.1f", player.getHealth()));
        text = text.replace("%attack%", String.format("%.1f", data.getAttack()));
        text = text.replace("%archer_damage%", String.format("%.1f", data.getArcherDamage()));
        text = text.replace("%armor%", String.format("%.1f", data.getArmor()));
        text = text.replace("%money%", String.format("%.1f", data.getMoney()));
        text = text.replace("%crit_chance%", String.format("%.1f%%", data.getCritChance() * 100));
        text = text.replace("%CoolReduce%", String.format("%.1f%%", data.getCoolReduce() * 100));


        // 灵力
        String lingliDisplay = (int)data.getLingli().doubleValue() + "/" + (int)data.getMaxLingli();
        text = text.replace("%lingli%", lingliDisplay);

        // 抗击退
        text = text.replace("%knock_back_res%", String.format("%.0f%%", data.getKnockBackRes() * 100));

        // 6. 主属性行
        if (text.contains("%main_stat_line%")) {
            String replacement = "&f主属性: &7暂无职业";
            if (job != null) {
                switch (job) {
                    case 0: replacement = "&f近战强度: &b" + String.format("%.1f", data.getAttack()); break;
                    case 1: replacement = "&f箭矢强度: &b" + String.format("%.1f", data.getArcherDamage()); break;
                    case 2: replacement = "&f阵法强度: &b" + String.format("%.1f", data.getZfStr()); break;
                    case 3: replacement = "&f医术: &b" + String.format("%.1f", data.getZfStr()); break;
                    default: replacement = "&f主属性: &7未知"; break;
                }
            }
            text = text.replace("%main_stat_line%", replacement);
        }

        return text;
    }

    // 生成 "总和 / (分 + 分 + ...)" 格式的字符串
    private String getRarityDisplayString(Player player, PlayerData data) {
        // 1. 获取总分
        int total = data.getTotalRarity();
        // 2. 直接获取已经在 Manager 里计算好的明细列表
        List<Integer> details = data.getRarityDetails();
        // 3. 拼接字符串 (例如 "5+5+2")
        // 使用流操作简化代码，或者用循环拼
        List<String> strList = new ArrayList<>();
        for (Integer i : details) {
            strList.add(String.valueOf(i));
        }
        String detailStr = String.join("+", strList);
        if (detailStr.isEmpty()) {
            detailStr = "0";
        }
        // 返回格式: 15 / (5+5+2)
        return total + " / (" + detailStr + ")";
    }
}