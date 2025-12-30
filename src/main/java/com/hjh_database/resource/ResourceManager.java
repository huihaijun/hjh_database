package com.hjh_database.resource;

import com.hjh_database.Hjh_database;
import com.hjh_database.weapon.ArmorManager;
import com.hjh_database.weapon.WeaponManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class ResourceManager {
    private final Hjh_database plugin;

    // 仅存储 resources 文件夹下的杂项物品
    private final Map<String, ResourceItem> localResources = new HashMap<>();

    // 全局名称索引 (中文名 -> ID)，包含 武器 + 护甲 + 杂项
    private final Map<String, String> nameIndex = new HashMap<>();

    private final NamespacedKey keyId;

    public ResourceManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.keyId = new NamespacedKey(plugin, "resource_id");
        loadAll();
    }

    public void reload() {
        // 先重载另外两个管理器，确保数据最新
        plugin.getPlayerManager().getWeaponManager().reload();
        plugin.getPlayerManager().getArmorManager().reload();

        loadAll();

        // 刷新在线玩家背包
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshInventory(p.getInventory());
        }
    }

    private void loadAll() {
        localResources.clear();
        nameIndex.clear();

        // 1. 加载 resources 文件夹下的杂项 (非 RPG 武器/护甲)
        loadLocalResources();

        // 2. 索引 WeaponManager 的物品
        WeaponManager wm = plugin.getPlayerManager().getWeaponManager();
        for (String id : wm.getAllIds()) {
            String name = wm.getNameById(id);
            if (name != null) nameIndex.put(name, id);
        }

        // 3. 索引 ArmorManager 的物品
        ArmorManager am = plugin.getPlayerManager().getArmorManager();
        for (String id : am.getAllIds()) {
            String name = am.getNameById(id);
            if (name != null) nameIndex.put(name, id);
        }

        plugin.getLogger().info("资源系统索引构建完成，共计索引 " + (localResources.size() + wm.getAllIds().size() + am.getAllIds().size()) + " 个物品。");
    }

    private void loadLocalResources() {
        File folder = new File(plugin.getDataFolder(), "resources");
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            for (String key : config.getKeys(false)) {
                try {
                    ConfigurationSection sec = config.getConfigurationSection(key);
                    if (sec == null) continue;

                    // 构建 ResourceItem 对象 (仅作为数据容器)
                    ResourceItem item = new ResourceItem(key, sec);
                    localResources.put(key, item);

                    // 添加到名称索引
                    String strippedName = ChatColor.stripColor(item.getName());
                    nameIndex.put(strippedName, key);

                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "加载资源物品 " + key + " 失败", e);
                }
            }
        }
    }

    /**
     * 【核心】获取物品
     * 优先级：WeaponManager -> ArmorManager -> LocalResources
     */
    public ItemStack getItem(String idOrName) {
        // 1. 如果是中文名，先转成 ID
        String id = idOrName;
        if (nameIndex.containsKey(idOrName)) {
            id = nameIndex.get(idOrName);
        } else {
            // 尝试检查是否是ID (如果不在nameIndex里，可能是因为ID和Name不匹配，或者是直接输入的ID)
            // 这里不做处理，直接用输入的字符串当ID去查
        }

        // 2. 尝试从 WeaponManager 获取 (自带 RPG 属性)
        WeaponManager wm = plugin.getPlayerManager().getWeaponManager();
        if (wm.getAllIds().contains(id)) {
            return wm.getItemStack(id);
        }

        // 3. 尝试从 ArmorManager 获取 (自带 RPG 属性)
        ArmorManager am = plugin.getPlayerManager().getArmorManager();
        if (am.getAllIds().contains(id)) {
            return am.getItemStack(id);
        }

        // 4. 尝试从 LocalResources 获取 (杂项)
        ResourceItem res = localResources.get(id);
        if (res != null) {
            return buildLocalItem(res);
        }

        return null;
    }

    /**
     * 刷新已有物品 (用于 ResourceListener)
     */
    public boolean refreshItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(keyId, PersistentDataType.STRING)) return false;

        String id = meta.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);

        // --- 逻辑分支 ---

        // A. 如果是武器
        WeaponManager wm = plugin.getPlayerManager().getWeaponManager();
        if (wm.getAllIds().contains(id)) {
            ItemStack newItem = wm.getItemStack(id);
            if (newItem != null) {
                // 直接替换 Meta，这会更新 Lore, Name, Flags, Unbreakable 等所有属性
                item.setType(newItem.getType());
                item.setItemMeta(newItem.getItemMeta());
                return true;
            }
        }

        // B. 如果是护甲
        ArmorManager am = plugin.getPlayerManager().getArmorManager();
        if (am.getAllIds().contains(id)) {
            ItemStack newItem = am.getItemStack(id);
            if (newItem != null) {
                item.setType(newItem.getType());
                item.setItemMeta(newItem.getItemMeta());
                return true;
            }
        }

        // C. 如果是杂项
        ResourceItem res = localResources.get(id);
        if (res != null) {
            if (item.getType() != res.getMaterial()) {
                item.setType(res.getMaterial());
            }
            applyResourceToMeta(meta, res);
            item.setItemMeta(meta);
            return true;
        }

        return false;
    }

    // 构建杂项物品
    private ItemStack buildLocalItem(ResourceItem res) {
        ItemStack item = new ItemStack(res.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyResourceToMeta(meta, res);
            meta.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, res.getId());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyResourceToMeta(ItemMeta meta, ResourceItem res) {
        meta.setDisplayName(res.getName());
        meta.setLore(res.getLore());
        if (res.hasCustomModelData()) {
            meta.setCustomModelData(res.getCustomModelData());
        }
        if (res.isUnbreakable()) {
            meta.setUnbreakable(true);
        }
    }

    public void refreshInventory(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            refreshItem(item);
        }
    }

    public List<String> getAllItemNames() {
        List<String> list = new ArrayList<>();
        list.addAll(nameIndex.keySet()); // 中文名
        list.addAll(localResources.keySet()); // 杂项ID
        list.addAll(plugin.getPlayerManager().getWeaponManager().getAllIds()); // 武器ID
        list.addAll(plugin.getPlayerManager().getArmorManager().getAllIds()); // 护甲ID
        return list;
    }
}