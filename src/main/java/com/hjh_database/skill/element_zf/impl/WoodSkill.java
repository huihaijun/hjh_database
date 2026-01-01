package com.hjh_database.skill.element_zf.impl;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.element_zf.ElementSkill;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class WoodSkill implements ElementSkill {
    private final Hjh_database plugin;

    public WoodSkill(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, int level, ConfigurationSection config) {
        // 1. 读取配置
        String path = "levels." + level;
        if (!config.contains(path)) path = "levels.1";

        double damagePercent = config.getDouble(path + ".damage_percent", 2.5);
        double healPercent = config.getDouble(path + ".heal_percent", 0.2);
        double range = config.getDouble(path + ".range", 5.0);
        double lingliAdd = config.getDouble(path + ".lingli_add", 1.0);

        // 【新增】2. 消耗物品 (不管有没有目标，先扣物品)
        player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);

        // 3. 获取玩家数据 (为后续加灵力和算伤害做准备)
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());

        // 【新增】4. 增加灵力 (空放也加)
        if (lingliAdd > 0) {
            double currentLingli = data.getLingli();
            double maxLingli = data.getMaxLingli();
            if (currentLingli < maxLingli) {
                data.setLingli(Math.min(maxLingli, currentLingli + lingliAdd));
                // 记得保存数据
                plugin.getDatabaseManager().savePlayer(data);
            }
        }

        // 5. 寻找目标
        LivingEntity target = findTarget(player, range);

        // 【修改】不再判空返回 false，而是判断如果有目标才造成伤害
        if (target != null) {
            // 计算伤害
            double baseDamage = data.getZfStr();
            double finalDamage = baseDamage * damagePercent;
            // 法术伤害逻辑
            target.setMetadata("hjh_magic_damage", new FixedMetadataValue(plugin, true));
            target.damage(finalDamage, player);
            target.removeMetadata("hjh_magic_damage", plugin);
            // 吸血逻辑
            double healAmount = Math.ceil(finalDamage * healPercent);
            double currentHp = player.getHealth();
            double maxHp = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
            if (currentHp < maxHp) {
                player.setHealth(Math.min(maxHp, currentHp + healAmount));
            }
            // 只有打中人才播放针对目标的特效
            playEffects(player, target);
        } else {
            // (可选) 如果空放，可以播放一个失败的音效或者只在脚下播点特效，这里暂不处理，保持安静
        }
        // 6. 提示与返回 (返回 true 代表释放成功，进入冷却)
//        player.sendMessage("§a§l[汲魂] §f阵法释放成功！");
        return true;
    }

    /**
     * 寻找前方最近的有效目标
     */
    private LivingEntity findTarget(Player player, double range) {
        List<Entity> entities = player.getNearbyEntities(range, range, range);
        Location playerLoc = player.getLocation();
        Vector direction = playerLoc.getDirection();

        // 过滤并排序
        List<LivingEntity> validTargets = entities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(e -> !e.equals(player)) // 不是自己
                .filter(e -> {
                    // 核心要求：必须同时拥有 panling 和 monster 标签
                    Set<String> tags = e.getScoreboardTags();
                    return tags.contains("panling") && tags.contains("monster");
                })
                .filter(e -> {
                    // 简单的视线判断 (点积 > 0 表示在前方 180度，> 0.5 表示前方 60度左右)
                    Vector toEntity = e.getLocation().toVector().subtract(playerLoc.toVector()).normalize();
                    return direction.dot(toEntity) > 0.5;
                })
                .sorted(Comparator.comparingDouble(e -> e.getLocation().distance(playerLoc))) // 按距离排序
                .collect(Collectors.toList());

        return validTargets.isEmpty() ? null : validTargets.get(0);
    }

    private void playEffects(Player player, LivingEntity target) {
        World world = player.getWorld();
        Location pLoc = player.getLocation().add(0, 1, 0);
        Location tLoc = target.getLocation().add(0, 1, 0);

        // 连线特效 (绿色粒子从怪物飞向玩家，表现"吸取")
        Vector dir = pLoc.toVector().subtract(tLoc.toVector());
        double dist = pLoc.distance(tLoc);
        Vector step = dir.normalize().multiply(0.5);

        for (double d = 0; d < dist; d += 0.5) {
            world.spawnParticle(Particle.VILLAGER_HAPPY, tLoc.clone().add(step.clone().multiply(d)), 1, 0, 0, 0, 0);
        }

        // 声音
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_DRINK, 1.0f, 1.2f);
        world.playSound(tLoc, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 0.8f);

        // 目标身上爆绿光
        world.spawnParticle(Particle.COMPOSTER, tLoc, 20, 0.5, 1, 0.5, 0.1);
    }
}