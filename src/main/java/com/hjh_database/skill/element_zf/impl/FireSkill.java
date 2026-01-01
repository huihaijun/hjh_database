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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class FireSkill implements ElementSkill {
    private final Hjh_database plugin;

    public FireSkill(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, int level, ConfigurationSection config) {
        // 1. 读取配置
        String path = "levels." + level;
        if (!config.contains(path)) path = "levels.1";

        double damagePercent = config.getDouble(path + ".damage_percent", 3.0);
        double range = config.getDouble(path + ".range", 10.0);
        double searchRadius = config.getDouble(path + ".search_radius", 4.0);
        double lingliAdd = config.getDouble(path + ".lingli_add", 1.0);

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

        // 5. 索敌
        LivingEntity target = getTarget(player, range, searchRadius);

        // 【修改】只在有目标时造成伤害
        if (target != null) {
            double baseDamage = data.getZfStr();
            double finalDamage = baseDamage * damagePercent;

            // 法术伤害
            target.setMetadata("hjh_magic_damage", new FixedMetadataValue(plugin, true));
            target.damage(finalDamage, player);
            target.removeMetadata("hjh_magic_damage", plugin);

            // 只有打中人才播放特效
            playBurnEffect(target);
        }

        // 6. 提示
//        player.sendMessage("§c§l[流火] §f阵法释放成功！");

        return true; // 总是进入冷却
    }

    /**
     * 获取目标：
     * 1. 射线直接命中怪物 -> 返回该怪物
     * 2. 射线命中方块 -> 搜索方块周围 searchRadius 内最近的怪物
     * 3. 射线射空 -> 搜索终点周围 searchRadius 内最近的怪物
     */
    private LivingEntity getTarget(Player player, double range, double searchRadius) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        // 射线检测
        RayTraceResult result = player.getWorld().rayTrace(eye, direction, range,
                FluidCollisionMode.NEVER, true, 0.5,
                entity -> entity != player &&
                        entity.getScoreboardTags().contains("panling") &&
                        entity.getScoreboardTags().contains("monster"));

        Location hitLocation;

        // 情况A: 直接指到了怪物
        if (result != null && result.getHitEntity() instanceof LivingEntity) {
            return (LivingEntity) result.getHitEntity();
        }

        // 情况B: 指到了方块
        else if (result != null && result.getHitBlock() != null) {
            hitLocation = result.getHitPosition().toLocation(player.getWorld());
        }

        // 情况C: 射向天空/远处，取最大射程终点
        else {
            hitLocation = eye.clone().add(direction.multiply(range));
        }

        // 在撞击点周围寻找最近的有效目标
        List<Entity> nearby = (List<Entity>) hitLocation.getWorld().getNearbyEntities(hitLocation, searchRadius, searchRadius, searchRadius);

        return nearby.stream()
                .filter(e -> e instanceof LivingEntity && e != player)
                .filter(e -> e.getScoreboardTags().contains("panling") && e.getScoreboardTags().contains("monster"))
                .map(e -> (LivingEntity) e)
                // 按距离撞击点的远近排序，选最近的一个
                .min(Comparator.comparingDouble(e -> e.getLocation().distance(hitLocation)))
                .orElse(null);
    }

    /**
     * 播放灼烧特效：从脚底向上升起的火焰螺旋
     */
    private void playBurnEffect(LivingEntity target) {
        Location loc = target.getLocation();
        World world = loc.getWorld();
        double height = target.getHeight();

        // 1. 脚底爆发一圈火焰
        world.spawnParticle(Particle.LAVA, loc, 10, 0.5, 0.1, 0.5, 0.1);
        world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f);

        // 2. 螺旋上升的火焰粒子
        new org.bukkit.scheduler.BukkitRunnable() {
            double y = 0;
            double angle = 0;

            @Override
            public void run() {
                // 每次上升一点
                if (y > height + 0.5) {
                    this.cancel();
                    // 顶部冒烟
                    world.spawnParticle(Particle.SMOKE_LARGE, loc.clone().add(0, height, 0), 5, 0.2, 0.2, 0.2, 0.05);
                    return;
                }

                // 双螺旋
                for (int i = 0; i < 2; i++) {
                    double rad = angle + (i * Math.PI); // 对称
                    double x = 0.6 * Math.cos(rad);
                    double z = 0.6 * Math.sin(rad);

                    Location particleLoc = loc.clone().add(x, y, z);
                    world.spawnParticle(Particle.FLAME, particleLoc, 1, 0, 0, 0, 0.02);
                }

                y += 0.2;
                angle += 0.5;
            }
        }.runTaskTimer(plugin, 0L, 1L); // 快速上升
    }
}