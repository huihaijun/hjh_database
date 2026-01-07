package com.hjh_database.weapon;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public class ArmorManager {
    private final Hjh_database plugin;
    public final Map<String, ArmorData> loadedArmors = new HashMap<>();
    private final NamespacedKey keyId;
    private final NamespacedKey armorKey;
    private File file;
    private FileConfiguration config;

    public ArmorManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "resource_id");
        this.armorKey = new NamespacedKey(plugin, "armor_id");
        reload();
    }

    public void reload() {
        loadedArmors.clear();
        file = new File(plugin.getDataFolder(), "armors.yml");
        if (!file.exists()) {
            plugin.saveResource("armors.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection sec = config.getConfigurationSection("armors");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                loadedArmors.put(key, new ArmorData(key, sec.getConfigurationSection(key)));
            }
        }
        plugin.getLogger().info("ArmorManager 加载了 " + loadedArmors.size() + " 件防具。");
    }

    /**
     * 【新增】刷新玩家身上防具的 Lore 状态
     * 显示是否已激活或条件不符
     */
    public void refreshPlayerArmors(Player player) {
        // 引用包路径可能需要根据你的实际情况调整，保持你原有的即可
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        // 获取玩家身上的装备内容
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        boolean changed = false;

        for (int i = 0; i < armorContents.length; i++) {
            ItemStack item = armorContents[i];
            if (item == null || !item.hasItemMeta()) continue;

            // 识别防具 ID
            String id = item.getItemMeta().getPersistentDataContainer().get(armorKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (id == null) {
                // 兼容旧 ID key
                id = item.getItemMeta().getPersistentDataContainer().get(keyId, org.bukkit.persistence.PersistentDataType.STRING);
            }
            if (id == null) continue; // 不是本系统的防具

            ArmorData aData = loadedArmors.get(id);
            if (aData == null) continue;

            // === 逻辑判断区域 (完全保持原样) ===
            boolean isActive = true;
            List<String> statusLore = new ArrayList<>();

            // 1. 检查职业
            if (aData.reqJob != -1) {
                if (data.getJob() == null || data.getJob() != aData.reqJob) {
                    isActive = false;
                    statusLore.add(ChatColor.RED + "⚠ 职业不符");
                }
            }
            // 2. 检查等级
            if (data.getLv() < aData.reqLv) {
                isActive = false;
                statusLore.add(ChatColor.RED + "⚠ 等级不足 (" + data.getLv() + "/" + aData.reqLv + ")");
            }

            // === Lore 构建区域 (仅在此处修改) ===
            ItemMeta meta = item.getItemMeta();
            // 顺便刷新一下名字，防止配置改了名字不生效
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', aData.display));

            List<String> newLore = new ArrayList<>();

            // 【新增】在第一行插入稀有度星星
            // 调用 WeaponManager 的静态方法 (前提是你已经改好了 WeaponManager)
//            newLore.add(WeaponManager.getRarityStars(aData.rarity));
            // 获取颜色代码（根据下面的 case 逻辑，必须跟下面保持一致）
            String colorCode = "§7"; // 默认为灰
            switch (aData.rarity) {
                case 1: colorCode = "§f"; break; // 白
                case 2: colorCode = "§a"; break; // 绿
                case 3: colorCode = "§9"; break; // 蓝
                case 4: colorCode = "§d"; break; // 粉
                case 5: colorCode = "§e"; break; // 黄
                case 6: colorCode = "§c"; break; // 红
            }

            // 拼接：颜色 + 文字 + 星星 (getRarityStars自带颜色，所以这里前面拼一次颜色即可)
            newLore.add(colorCode + "稀有度: " + WeaponManager.getRarityStars(aData.rarity));

            // 【保留】插入配置文件里的 Lore
            for (String line : aData.lore) {
                newLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }

            // 【保留】插入状态提示
            if (isActive) {
                newLore.add(" ");
                newLore.add(ChatColor.GREEN + "✔ 已激活 - 防御生效中");
            } else {
                newLore.add(" ");
                newLore.addAll(statusLore);
            }

            meta.setLore(newLore);
            meta.setCustomModelData(aData.customModelData); // 顺手刷新一下模型数据
            item.setItemMeta(meta);
            changed = true;
        }

        // 如果修改了物品 Meta，需要重新设置回去
        if (changed) {
            player.getInventory().setArmorContents(armorContents);
        }
    }

    /**
     * 计算所有防具属性
     */
    public Map<String, Double> calculateArmorStats(Player player, PlayerData data) {
        Map<String, Double> totalStats = new HashMap<>();
        // 1. 定义稀有度累加变量
        double totalRarity = 0.0;

        // 遍历身上 4 件装备
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item == null || !item.hasItemMeta()) continue;

            // 获取ID (优先 armor_id，其次 resource_id)
            String id = item.getItemMeta().getPersistentDataContainer().get(armorKey, PersistentDataType.STRING);
            if (id == null) id = item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            if (id == null) continue;

            ArmorData aData = loadedArmors.get(id);
            if (aData == null) continue;

            // === 校验激活条件 ===
            // 1. 等级不够，跳过
            if (data.getLv() < aData.reqLv) continue;
            // 2. 职业不符，跳过
            if (aData.reqJob != -1 && (data.getJob() == null || data.getJob() != aData.reqJob)) continue;

            // ★【新增】这里是激活成功的地方，把稀有度记入 List
            data.getRarityDetails().add(aData.rarity);
            // ★ 修改点1：累加稀有度
            totalRarity += aData.rarity;
            // ★ 修改点2：累加所有属性
            for (Map.Entry<String, Double> entry : aData.stats.entrySet()) {
                totalStats.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        // ★ 修改点3：把计算好的总稀有度放入 Map 返回
        totalStats.put("total_rarity", totalRarity);

        return totalStats;
    }

    /**
     * 【新增】获取纯净版护甲 (用于配方保存和指令获取)
     */
    public ItemStack getItemStack(String id) {
        ArmorData data = loadedArmors.get(id);
        if (data == null) return null;

        ItemStack item = new ItemStack(data.material);
        ItemMeta meta = item.getItemMeta();

        // 1. 基础信息
        if (data.display != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', data.display));
        }
        if (data.lore != null) {
            List<String> coloredLore = new ArrayList<>();
            for (String line : data.lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(coloredLore);
        }
        if (data.customModelData != 0) {
            meta.setCustomModelData(data.customModelData);
        }

        // 2. 写入 NBT (注意：护甲用的是 armorKey)
        meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(armorKey, PersistentDataType.STRING, id);

        // 3. 属性标记
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);

        item.setItemMeta(meta);
        return item;
    }

    private boolean checkRequirements(Player player, PlayerData data, ArmorData armor) {
        if (armor.reqJob != -1) {
            if (data.getJob() == null || data.getJob() != armor.reqJob) return false;
        }
        if (data.getLv() < armor.reqLv) return false;
        return true;
    }

    public Set<String> getAllIds() { return loadedArmors.keySet(); }

    public String getNameById(String id) {
        ArmorData data = loadedArmors.get(id);
        return data != null ? ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', data.display)) : null;
    }

    public static class ArmorData {
        String id;
        String display;
        Material material;
        int customModelData;
        List<String> lore;
        public int reqJob;
        public int reqLv;
        String activeLoreLine;
        public int rarity; // <--- 【1】新增字段
        public Map<String, Double> stats = new HashMap<>();

        public ArmorData(String id, ConfigurationSection sec) {
            this.id = id;
            this.display = sec.getString("display", "Armor");
            this.material = Material.valueOf(sec.getString("material", "LEATHER_CHESTPLATE"));
            this.customModelData = sec.getInt("custom_model_data", 0);
            this.lore = sec.getStringList("lore");
            this.reqJob = sec.getInt("req_job", -1);
            this.reqLv = sec.getInt("req_lv", 1);
            this.activeLoreLine = sec.getString("active_lore_line", "条件不符");
            this.rarity = sec.getInt("rarity", 1); // <--- 【2】读取配置，默认为1
            ConfigurationSection statSec = sec.getConfigurationSection("stats");
            if (statSec != null) {
                for (String key : statSec.getKeys(false)) {
                    stats.put(key, statSec.getDouble(key));
                }
            }
        }
    }
}