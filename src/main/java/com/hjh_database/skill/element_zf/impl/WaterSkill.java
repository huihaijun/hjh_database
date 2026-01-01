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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class WaterSkill implements ElementSkill {
    private final Hjh_database plugin;

    public WaterSkill(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, int level, ConfigurationSection config) {
        // 1. 读取配置
        String path = "levels." + level;
        if (!config.contains(path)) path = "levels.1";

        double damagePercent = config.getDouble(path + ".damage_percent", 1.0);
        double range = config.getDouble(path + ".range", 4.0);
        double lingliAdd = config.getDouble(path + ".lingli_add", 1.0);

        double slowDurationSeconds = config.getDouble(path + ".slow_duration", 2.0);
        int slowAmplifier = config.getInt(path + ".slow_amplifier", 1);

        // 【新增】2. 消耗物品
        player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);

        // 3. 获取数据
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());

        // 【新增】4. 增加灵力
        if (lingliAdd > 0) {
            double currentLingli = data.getLingli();
            double maxLingli = data.getMaxLingli();
            if (currentLingli < maxLingli) {
                data.setLingli(Math.min(maxLingli, currentLingli + lingliAdd));
                plugin.getDatabaseManager().savePlayer(data);
            }
        }

        // 5. 寻找目标
        List<LivingEntity> targets = findTargets(player, range);

        // 【修改】如果有目标，才循环造成伤害；没有目标就跳过，但不打断流程
        if (!targets.isEmpty()) {
            double baseDamage = data.getZfStr();
            double finalDamage = baseDamage * damagePercent;

            for (LivingEntity target : targets) {
                // 法术伤害
                target.setMetadata("hjh_magic_damage", new FixedMetadataValue(plugin, true));
                target.damage(finalDamage, player);
                target.removeMetadata("hjh_magic_damage", plugin);

                // 减速
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, (int)(slowDurationSeconds * 20), slowAmplifier));
            }
        }

        // 6. 提示与特效 (空放也会播放扇形特效)
//        player.sendMessage("§b§l[霜冻] §f阵法释放成功！");
        playConeEffects(player, range);

        return true; // 总是进入冷却
    }

    private List<LivingEntity> findTargets(Player player, double range) {
        List<Entity> entities = player.getNearbyEntities(range, range, range);
        Location playerLoc = player.getLocation();
        Vector direction = playerLoc.getDirection();

        return entities.stream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(e -> !e.equals(player))
                .filter(e -> {
                    Set<String> tags = e.getScoreboardTags();
                    return tags.contains("panling") && tags.contains("monster");
                })
                .filter(e -> {
                    Vector toEntity = e.getLocation().toVector().subtract(playerLoc.toVector()).normalize();
                    return direction.dot(toEntity) > 0.5;
                })
                .filter(e -> e.getLocation().distance(playerLoc) <= range)
                .collect(Collectors.toList());
    }

    /**
     * 【核心优化】播放锥形/扇形 冰霜喷射特效
     */
    private void playConeEffects(Player player, double range) {
        // 起点：玩家眼睛稍微往下一点，模拟嘴巴/手部吹气
        Location start = player.getEyeLocation().add(0, -0.2, 0);
        Vector mainDir = start.getDirection().normalize();

        World world = player.getWorld();

        // 步长 0.5，意味着每半格生成一簇粒子
        for (double d = 0.5; d < range; d += 0.5) {
            // 中心点：沿着视线方向延伸 d 米
            Location centerPoint = start.clone().add(mainDir.clone().multiply(d));

            // 扩散半径：距离越远，粒子扩散范围越大 (d * 0.6 约等于 60度角的开口)
            double spread = d * 0.6;

            // 在这个距离切面上生成多个随机粒子，填满体积
            // 距离越远，需要的粒子越多才能填满视觉，所以 i < 5 + d
            int particleCount = (int) (5 + d * 2);

            for (int i = 0; i < particleCount; i++) {
                // 生成随机偏移量 (-spread/2 到 +spread/2)
                double offsetX = (Math.random() - 0.5) * spread;
                double offsetY = (Math.random() - 0.5) * spread;
                double offsetZ = (Math.random() - 0.5) * spread;

                Location particleLoc = centerPoint.clone().add(offsetX, offsetY, offsetZ);

                // 1. 雪花粒子 (主要视觉)
                world.spawnParticle(Particle.SNOWFLAKE, particleLoc, 1, 0, 0, 0, 0.01);

                // 2. 蓝色尘埃 (增加魔法感) - 30% 概率生成
                if (Math.random() < 0.3) {
                    world.spawnParticle(Particle.REDSTONE, particleLoc, 1,
                            new Particle.DustOptions(Color.fromRGB(150, 240, 255), 0.8f)); // 冰蓝色
                }

                // 3. 云雾 (增加厚重感) - 10% 概率生成，只在远端生成
                if (d > 2.0 && Math.random() < 0.1) {
                    world.spawnParticle(Particle.CLOUD, particleLoc, 0, 0, 0, 0, 0.05);
                }
            }
        }

        // 音效
        world.playSound(start, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f); // 碎裂声
        world.playSound(start, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.6f, 1.8f); // 呼啸声（高音调模拟寒风）
    }
}