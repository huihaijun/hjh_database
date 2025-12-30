package com.hjh_database.dz.data;

import org.bukkit.inventory.ItemStack;
import java.util.List;

public class DzRecipe {
    private final String id;
    private final String category;
    private final ItemStack result;

    // 改动：不再是用 Map 存九宫格，而是用 List 存横向的5个材料 (或者更少)
    // 列表里的顺序对应 input_slots 的顺序
    private final List<ItemStack> ingredients;

    // 限制条件
    private final int reqJob;
    private final int reqForgeLevel;
    private final int reqLicense;
    private final int expReward;

    public DzRecipe(String id, String category, ItemStack result, List<ItemStack> ingredients,
                    int reqJob, int reqForgeLevel, int reqLicense, int expReward) {
        this.id = id;
        this.category = category;
        this.result = result;
        this.ingredients = ingredients;
        this.reqJob = reqJob;
        this.reqForgeLevel = reqForgeLevel;
        this.reqLicense = reqLicense;
        this.expReward = expReward;
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public ItemStack getResult() { return result; }
    public List<ItemStack> getIngredients() { return ingredients; } // Getter变化
    public int getReqJob() { return reqJob; }
    public int getReqForgeLevel() { return reqForgeLevel; }
    public int getReqLicense() { return reqLicense; }
    public int getExpReward() { return expReward; }
}