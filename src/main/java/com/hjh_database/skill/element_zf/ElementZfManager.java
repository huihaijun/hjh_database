package com.hjh_database.skill.element_zf;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.element_zf.impl.MetalSkill;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.md_5.bungee.api.ChatMessageType; // 需要导入
import net.md_5.bungee.api.chat.TextComponent; // 需要导入
import org.bukkit.ChatColor; // 需要导入
public class ElementZfManager {
    private final Hjh_database plugin;
    private FileConfiguration config;
    // 存储 元素名 -> 技能逻辑 的映射
    private final Map<String, ElementSkill> skills = new HashMap<>();
    public ElementZfManager(Hjh_database plugin) {
        this.plugin = plugin;
        reload();
        registerSkills();
    }
    public void reload() {
        File file = new File(plugin.getDataFolder(), "element_zf.yml");
        if (!file.exists()) {
            plugin.saveResource("element_zf.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    private void registerSkills() {
        // 注册具体的技能实现类
        // 后续添加 WoodSkill, WaterSkill 等只需在这里加一行
        skills.put("METAL", new MetalSkill(plugin));
    }

    /**
     * 尝试释放技能
     * @param player 玩家
     * @param type 元素类型 (METAL, WOOD...)
     * @param data 玩家数据
     */
    public void castSkill(Player player, String type, PlayerData data) {
        ElementSkill skill = skills.get(type);
        if (skill == null) return;

        // 1. 获取技能等级 (如果数据库里没有或出错，默认为1)
        Integer levelObj = data.getElementLevel(type);
        int level = (levelObj == null) ? 1 : levelObj;

        // 2. 检查物品冷却 (原版 Cooldown 机制)
        Material handItemType = player.getInventory().getItemInMainHand().getType();
        // 【新增】检查冷却并提示
        if (player.getCooldown(handItemType) > 0) {
            int remainingTicks = player.getCooldown(handItemType);
            double remainingSeconds = remainingTicks / 20.0;
            // 获取技能显示的名称 (从配置里读，或者直接硬编码中文)
            String skillName = config.getString("skills." + type + ".name", type);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.translateAlternateColorCodes('&',
                            "&c&l" + skillName + " 阵法冷却中，剩余 " + String.format("%.1f", remainingSeconds) + " 秒")));
            return;
        }

        // 3. 执行技能逻辑 (消耗物品、造成伤害、特效等)
        // 传入 config 是为了让 Skill 类能读到具体的数值
        boolean success = skill.cast(player, level, config.getConfigurationSection("skills." + type));

        if (success) {
            // 4. 计算冷却时间
            // 优先读取该等级的冷却，如果没有则读取通用冷却，默认 5.0 秒
            double baseCd = config.getDouble("skills." + type + ".levels." + level + ".cooldown",
                    config.getDouble("skills." + type + ".levels.1.cooldown", 5.0));

            // 应用冷却缩减 (硬上限 50%)
            double reduce = data.getCoolReduce();
            if (reduce > 0.5) reduce = 0.5;
            if (reduce < 0) reduce = 0;

            int finalCdTicks = (int) (baseCd * (1.0 - reduce) * 20); // 秒转 ticks

            // 设置物品冷却 (类似末影珍珠效果)
            player.setCooldown(handItemType, finalCdTicks);
        }
    }
}