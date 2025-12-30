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
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data == null) return;

        // 遍历整个背包（包括 0-35 存储/快捷栏，36-39 装备栏，40 副手）
        // getSize() 通常返回 41 (9+27+4+1)
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;

            // 检查是否是武器
            String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
            if (id == null) id = item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            if (id == null) continue; // 不是武器，跳过

            WeaponData wData = loadedWeapons.get(id);
            if (wData == null) continue;

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

            // 更新 Lore
            ItemMeta meta = item.getItemMeta();
            List<String> newLore = new ArrayList<>();
            for (String line : wData.lore) {
                newLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }

            if (isActive) {
                newLore.add(" ");
                newLore.add(ChatColor.GREEN + "✔ 已激活 - 属性生效中");
            } else {
                newLore.add(" ");
                newLore.addAll(statusLore);
            }
            meta.setLore(newLore);
            item.setItemMeta(meta);
        }
    }

    /**
     * 计算所有属性
     * 规则：只要位置对、等级够、职业对，所有属性（含攻击力）全部加上
     */
    public Map<String, Double> calculateWeaponStats(Player player, PlayerData data) {
        Map<String, Double> totalStats = new HashMap<>();

        // 遍历全背包
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;

            String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
            if (id == null) id = item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            if (id == null) continue;

            WeaponData wData = loadedWeapons.get(id);
            if (wData == null) continue;

            // 校验激活条件
            if (wData.activateSlot != -1 && wData.activateSlot != slot) continue;
            if (wData.reqJob != -1 && (data.getJob() == null || data.getJob() != wData.reqJob)) continue;
            if (data.getLv() < wData.reqLv) continue;

            // ★ 修改点：不再过滤 attack/crit 等属性
            // 只要激活，属性全给，方便后续技能调用 data.getAttack()
            for (Map.Entry<String, Double> entry : wData.stats.entrySet()) {
                totalStats.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
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
        String activeLoreLine;
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
            ConfigurationSection statSec = sec.getConfigurationSection("stats");
            if (statSec != null) {
                for (String key : statSec.getKeys(false)) {
                    stats.put(key, statSec.getDouble(key));
                }
            }
        }
    }
}