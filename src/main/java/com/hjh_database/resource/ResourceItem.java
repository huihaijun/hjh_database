package com.hjh_database.resource;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class ResourceItem {
    private final String id;
    private final Material material;
    private final String name;
    private final List<String> lore;
    private final Integer customModelData;
    private final boolean unbreakable;

    // 原始全参构造
    public ResourceItem(String id, Material material, String name, List<String> lore, Integer customModelData, boolean unbreakable) {
        this.id = id;
        this.material = material;
        this.name = ChatColor.translateAlternateColorCodes('&', name);
        this.lore = new ArrayList<>();
        if (lore != null) {
            for (String line : lore) {
                this.lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
        }
        this.customModelData = customModelData;
        this.unbreakable = unbreakable;
    }

    // 新增：从 ConfigSection 构造
    public ResourceItem(String id, ConfigurationSection sec) {
        this.id = id;
        this.material = Material.matchMaterial(sec.getString("material", "STONE"));
        this.name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", "&f未知物品"));
        this.lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            this.lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        if (sec.contains("custom_model_data")) {
            this.customModelData = sec.getInt("custom_model_data");
        } else {
            this.customModelData = null;
        }

        this.unbreakable = sec.getBoolean("unbreakable", false);
    }

    public String getId() { return id; }
    public Material getMaterial() { return material == null ? Material.STONE : material; }
    public String getName() { return name; }
    public List<String> getLore() { return lore; }
    public boolean hasCustomModelData() { return customModelData != null; }
    public int getCustomModelData() { return customModelData != null ? customModelData : 0; }
    public boolean isUnbreakable() { return unbreakable; }
}