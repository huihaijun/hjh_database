package com.hjh_database.dz.manager;

import com.hjh_database.Hjh_database;
import com.hjh_database.dz.data.DzRecipe;
import com.hjh_database.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeManager {
    private final Hjh_database plugin;
    // 存储结构: 分类 -> (配方ID -> 配方对象)
    private final Map<String, Map<String, DzRecipe>> recipes = new HashMap<>();

    public RecipeManager(Hjh_database plugin) {
        this.plugin = plugin;
        loadAllRecipes();
    }

    public void loadAllRecipes() {
        recipes.clear();
        File folder = new File(plugin.getDataFolder(), "recipes");
        if (!folder.exists()) folder.mkdirs();

        String[] categories = {"weapon", "armor", "artifact", "misc"};
        for (String cat : categories) {
            loadCategory(cat);
        }
        plugin.getLogger().info("锻造系统加载完成，共加载 " + getTotalRecipeCount() + " 个配方。");
    }

    private void loadCategory(String category) {
        File file = new File(plugin.getDataFolder(), "recipes/" + category + ".yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String id : config.getKeys(false)) {
            try {
                // 1. 解析结果物品 (核心修改)
                String resultStr = config.getString(id + ".result_id");
                ItemStack resultItem;

                // 尝试从 RPG 库加载
                if (plugin.getPlayerManager().getWeaponManager().getLoadedWeapons().containsKey(resultStr)) {
                    resultItem = plugin.getPlayerManager().getWeaponManager().getItemStack(resultStr);
                } else if (plugin.getPlayerManager().getArmorManager().getAllIds().contains(resultStr)) {
                    resultItem = plugin.getPlayerManager().getArmorManager().getItemStack(resultStr);
                } else {
                    // 尝试原版材质
                    Material mat = Material.getMaterial(resultStr != null ? resultStr : "STONE");
                    resultItem = new ItemStack(mat != null ? mat : Material.STONE);
                }

                // 2. 解析材料 List (核心修改)
                List<ItemStack> ingredients = new ArrayList<>();
                List<String> ingList = config.getStringList(id + ".ingredients");
                for (String ingStr : ingList) {
                    ItemStack ingItem;
                    if (ingStr.equalsIgnoreCase("AIR")) {
                        ingItem = new ItemStack(Material.AIR);
                    } else if (plugin.getPlayerManager().getWeaponManager().getLoadedWeapons().containsKey(ingStr)) {
                        ingItem = plugin.getPlayerManager().getWeaponManager().getItemStack(ingStr);
                    } else if (plugin.getPlayerManager().getArmorManager().getAllIds().contains(ingStr)) {
                        ingItem = plugin.getPlayerManager().getArmorManager().getItemStack(ingStr);
                    } else {
                        Material mat = Material.getMaterial(ingStr);
                        ingItem = new ItemStack(mat != null ? mat : Material.STONE);
                    }
                    ingredients.add(ingItem);
                }

                // 3. 基础数值
                int job = config.getInt(id + ".req_job", -1);
                int lv = config.getInt(id + ".req_level", 1);
                int lic = config.getInt(id + ".req_license", 0);
                int exp = config.getInt(id + ".exp_reward", 10);

                DzRecipe recipe = new DzRecipe(id, category, resultItem, ingredients, job, lv, lic, exp);
                recipes.computeIfAbsent(category, k -> new HashMap<>()).put(id, recipe);

            } catch (Exception e) {
                plugin.getLogger().warning("加载配方 " + id + " 失败: " + e.getMessage());
            }
        }
    }

    public void saveRecipe(DzRecipe recipe) {
        File file = new File(plugin.getDataFolder(), "recipes/" + recipe.getCategory() + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = recipe.getId();

        // 核心：保存 ID 而不是 ItemStack
        config.set(path + ".result_id", ItemUtil.getPublicId(recipe.getResult()));

        List<String> ingIds = new ArrayList<>();
        for (ItemStack item : recipe.getIngredients()) {
            ingIds.add(ItemUtil.getPublicId(item));
        }
        config.set(path + ".ingredients", ingIds);

        config.set(path + ".req_job", recipe.getReqJob());
        config.set(path + ".req_level", recipe.getReqForgeLevel());
        config.set(path + ".req_license", recipe.getReqLicense());
        config.set(path + ".exp_reward", recipe.getExpReward());

        try {
            config.save(file);
            recipes.computeIfAbsent(recipe.getCategory(), k -> new HashMap<>()).put(recipe.getId(), recipe);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void deleteRecipe(String category, String id) {
        File file = new File(plugin.getDataFolder(), "recipes/" + category + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set(id, null);
        try {
            config.save(file);
            if (recipes.containsKey(category)) recipes.get(category).remove(id);
        } catch (IOException e) { e.printStackTrace(); }
    }

    public List<DzRecipe> getRecipesByCategory(String category) {
        if (!recipes.containsKey(category)) return new ArrayList<>();
        return new ArrayList<>(recipes.get(category).values());
    }

    public DzRecipe getRecipe(String category, String id) {
        if (!recipes.containsKey(category)) return null;
        return recipes.get(category).get(id);
    }

    private int getTotalRecipeCount() {
        int count = 0;
        for (Map<String, DzRecipe> map : recipes.values()) count += map.size();
        return count;
    }
}