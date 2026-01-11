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

public class WeaponManager {
    private final Hjh_database plugin;
    private final Map<String, WeaponData> loadedWeapons = new HashMap<>();
    private final NamespacedKey keyId;
    private final NamespacedKey weaponKey;
    private File file;
    private FileConfiguration config;

    public WeaponManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "resource_id");
        this.weaponKey = new NamespacedKey(plugin, "weapon_id");
        reload();
    }

    public WeaponData getWeaponData(String id) {
        return loadedWeapons.get(id);
    }

    public void reload() {
        loadedWeapons.clear();
        file = new File(plugin.getDataFolder(), "weapons.yml");
        if (!file.exists()) {
            plugin.saveResource("weapons.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection sec = config.getConfigurationSection("weapons");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                loadedWeapons.put(key, new WeaponData(key, sec.getConfigurationSection(key)));
            }
        }
        plugin.getLogger().info("WeaponManager 加载了 " + loadedWeapons.size() + " 把武器。");
    }


    /**
     * 获取副手当前处于“激活状态”的武器 ID (供 SpellListener 使用)
     */
    public String getActiveOffHandWeaponId(Player player) {
        ItemStack offItem = player.getInventory().getItemInOffHand();
        if (offItem == null || offItem.getType() == Material.AIR || !offItem.hasItemMeta()) {
            return null;
        }

        String id = offItem.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        if (id == null) {
            id = offItem.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
        }
        if (id == null) return null;

        WeaponData wData = loadedWeapons.get(id);
        if (wData == null) return null;

        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return null;

        // 判定：必须允许放在副手 (40) 或 任意位置 (-1)
        if (wData.activateSlot != -1 && wData.activateSlot != 40) {
            return null;
        }

        if (wData.reqJob != -1) {
            if (data.getJob() == null || data.getJob() != wData.reqJob) return null;
        }
        if (data.getLv() < wData.reqLv) return null;

        return id;
    }

    /**
     * 刷新玩家背包中【所有位置】武器的 Lore 状态
     */
    public void refreshPlayerWeapons(Player player) {
        // 获取玩家数据
        com.hjh_database.data.PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        // 遍历整个背包
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;

            // 检查是否是武器 (优先检查 weapon_id，兼容 resource_id)
            String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (id == null) id = item.getItemMeta().getPersistentDataContainer().get(keyId, org.bukkit.persistence.PersistentDataType.STRING);

            if (id == null) continue; // 不是武器，跳过

            WeaponData wData = loadedWeapons.get(id);
            if (wData == null) continue; // 配置文件里已经删除了这个武器

            // === 判定激活状态逻辑 (保留你原本的逻辑) ===
            boolean isActive = true;
            List<String> statusLore = new ArrayList<>();
            // 1. 检查槽位要求
            if (wData.activateSlot != -1 && wData.activateSlot != slot) {
                isActive = false;
                statusLore.add(ChatColor.RED + "⚠ " + ChatColor.translateAlternateColorCodes('&', wData.activeLoreLine));
            }
            // 2. 检查职业
            if (wData.reqJob != -1) {
                if (data.getJob() == null || data.getJob() != wData.reqJob) {
                    isActive = false;
                    statusLore.add(ChatColor.RED + "⚠ 职业不符");
                }
            }
            // 3. 检查等级
            if (data.getLv() < wData.reqLv) {
                isActive = false;
                statusLore.add(ChatColor.RED + "⚠ 等级不足 (" + data.getLv() + "/" + wData.reqLv + ")");
            }

            // === 重新构建 Meta ===
            ItemMeta meta = item.getItemMeta(); // 获取现有 Meta (保留旗帜图案)
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', wData.display)); // 确保名字也刷新
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "rarity"), PersistentDataType.INTEGER, wData.rarity);

            // 构建新的 Lore 列表
            List<String> newLore = new ArrayList<>();

            // 【修改点】 1. 第一行插入稀有度星星 (紧跟名字下方)
            // 获取颜色代码（根据下面的 case 逻辑，必须跟下面保持一致）
            String colorCode = "§7"; // 默认为灰
            switch (wData.rarity) {
                case 1: colorCode = "§f"; break; // 白
                case 2: colorCode = "§a"; break; // 绿
                case 3: colorCode = "§9"; break; // 蓝
                case 4: colorCode = "§d"; break; // 粉
                case 5: colorCode = "§e"; break; // 黄
                case 6: colorCode = "§c"; break; // 红
            }

            // 拼接：颜色 + 文字 + 星星 (假设 getRarityStars 方法存在于本类中)
            newLore.add(colorCode + "稀有度: " + getRarityStars(wData.rarity));

            // 2. 插入原有 Lore (配置文件的描述)
            for (String line : wData.lore) {
                newLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }

            // 3. 插入激活状态提示
            if (isActive) {
                newLore.add(" ");
                newLore.add(ChatColor.GREEN + "✔ 已激活 - 属性生效中");
            } else {
                newLore.add(" ");
                newLore.addAll(statusLore);
            }

            // =======================================================
            // 【关键修复】 4. 检查并保留医术信息 (防止刷新丢失)
            // =======================================================
            // 检查是否有医术ID的 NBT
            NamespacedKey medKey = new NamespacedKey(plugin, "med_skill_id");
            if (meta.getPersistentDataContainer().has(medKey, org.bukkit.persistence.PersistentDataType.STRING)) {
                String medSkillId = meta.getPersistentDataContainer().get(medKey, org.bukkit.persistence.PersistentDataType.STRING);

                // 检查是否有制作者 NBT
                NamespacedKey crafterKey = new NamespacedKey(plugin, "med_crafter");
                String crafterName = meta.getPersistentDataContainer().get(crafterKey, org.bukkit.persistence.PersistentDataType.STRING);

                newLore.add("§8§m------------------");

                // 尝试获取技能中文名
                if (plugin.getMedicalManager() != null) {
                    String skillName = plugin.getMedicalManager().getSkillName(medSkillId);
                    newLore.add("§6[医术] §e" + (skillName != null ? skillName : medSkillId));

                    // 【核心逻辑补充】从 MedicalManager 读取原始技能书的详细Lore
                    // 只有加上这一段，"冷却时间"、"灵力消耗" 这些信息才会被补回来
                    ItemStack originalBook = plugin.getMedicalManager().getSkillBook(medSkillId);
                    if (originalBook != null && originalBook.hasItemMeta() && originalBook.getItemMeta().hasLore()) {
                        for (String line : originalBook.getItemMeta().getLore()) {
                            // 过滤掉那句 "放入绘制台" 的提示，其他都加上
                            if (line.contains("放入绘制台")) continue;
                            newLore.add(line);
                        }
                    }

                } else {
                    newLore.add("§6[医术] §e" + medSkillId);
                }

                if (crafterName != null) {
                    newLore.add("§7绘旗者: " + crafterName);
                }
            }
            // =======================================================

            // 4. 应用更改
            meta.setLore(newLore);
            meta.setCustomModelData(wData.customModelData); // 顺便刷新材质
            item.setItemMeta(meta);
        }
    }
    // 获取稀有度显示的星星
    public static String getRarityStars(int rarity) {
        StringBuilder sb = new StringBuilder();
        String color;
        switch (rarity) {
            case 1: color = "§f"; break; // 白
            case 2: color = "§a"; break; // 绿
            case 3: color = "§9"; break; // 蓝
            case 4: color = "§d"; break; // 粉
            case 5: color = "§e"; break; // 黄
            case 6: color = "§c"; break; // 红
            default: color = "§7"; break;
        }
        sb.append(color);
        for (int i = 0; i < rarity; i++) {
            sb.append("★");
        }
        return sb.toString();
    }

    /**
     * 计算所有属性
     * 规则：只要位置对、等级够、职业对，所有属性（含攻击力）全部加上
     */
    public Map<String, Double> calculateWeaponStats(Player player, PlayerData data) {
        Map<String, Double> totalStats = new HashMap<>();
        // 1. 定义稀有度累加变量
        double totalRarity = 0.0;

        // 遍历全背包
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;

            // 获取ID (优先 weapon_id，其次 resource_id)
            String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
            if (id == null) id = item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            if (id == null) continue;

            WeaponData wData = loadedWeapons.get(id);
            if (wData == null) continue;

            // === 校验激活条件 ===
            // 1. 槽位不对，跳过
            if (wData.activateSlot != -1 && wData.activateSlot != slot) continue;
            // 2. 职业不符，跳过
            if (wData.reqJob != -1 && (data.getJob() == null || data.getJob() != wData.reqJob)) continue;
            // 3. 等级不够，跳过
            if (data.getLv() < wData.reqLv) continue;

            // === 激活成功 ===
            // ★【新增】这里是激活成功的地方，把稀有度记入 List
            data.getRarityDetails().add(wData.rarity);
            // ★ 修改点1：累加稀有度
            // (前提是你已经在 WeaponData 类里加了 rarity 字段，没加的话记得去加)
            totalRarity += wData.rarity;

            // ★ 修改点2：累加所有属性
            for (Map.Entry<String, Double> entry : wData.stats.entrySet()) {
                totalStats.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        // ★ 修改点3：把计算好的总稀有度放入 Map 返回
        // 这样 PlayerManager 就能通过 get("total_rarity") 拿到了
        totalStats.put("total_rarity", totalRarity);

        return totalStats;
    }

    /**
     * 【新增】获取纯净版武器 (用于配方保存和指令获取)
     */
    public ItemStack getItemStack(String id) {
        WeaponData data = loadedWeapons.get(id);
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

        // 2. 写入 NBT
        meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, id);

        // 3. 属性标记
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);

        item.setItemMeta(meta);
        return item;
    }

    public Set<String> getAllIds() { return loadedWeapons.keySet(); }
    public String getNameById(String id) {
        WeaponData data = loadedWeapons.get(id);
        return data != null ? ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', data.display)) : null;
    }
    public Map<String, WeaponData> getLoadedWeapons() { return loadedWeapons; } // 新增 getter

    public static class WeaponData {
        String id;
        String display;
        Material material;
        int customModelData;
        List<String> lore;
        public int reqJob;
        public int reqLv;
        public int activateSlot;
        public String activeLoreLine;
        //稀有度
        public int rarity; // <--- 新增
        public Map<String, Double> stats = new HashMap<>();

        public WeaponData(String id, ConfigurationSection sec) {
            this.id = id;
            this.display = sec.getString("display", "Weapon");
            this.material = Material.valueOf(sec.getString("material", "STONE"));
            this.customModelData = sec.getInt("custom_model_data", 0);
            this.lore = sec.getStringList("lore");
            this.reqJob = sec.getInt("req_job", -1);
            this.reqLv = sec.getInt("req_lv", 1);
            this.activateSlot = sec.getInt("activate_slot", 0);
            this.activeLoreLine = sec.getString("active_lore_line", "请放在快捷栏第一格激活");
            // 在构造函数里添加读取逻辑：
            this.rarity = sec.getInt("rarity", 1); // <--- 新增，默认值为 1
            ConfigurationSection statSec = sec.getConfigurationSection("stats");
            if (statSec != null) {
                for (String key : statSec.getKeys(false)) {
                    stats.put(key, statSec.getDouble(key));
                }
            }
        }
    }
}