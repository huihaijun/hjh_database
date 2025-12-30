package com.hjh_database.skill.element_zf.impl;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.element_zf.ElementSkill;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MetalSkill implements ElementSkill {
    private final Hjh_database plugin;

    public MetalSkill(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, int level, ConfigurationSection config) {
        // 1. 获取配置数值
        String path = "levels." + level;
        if (!config.contains(path)) path = "levels.1";

        double damagePercent = config.getDouble(path + ".damage_percent", 2.5);
        double range = config.getDouble(path + ".range", 3.0);
        double lingliAdd = config.getDouble(path + ".lingli_add", 1.0);

        int maxTargets = config.getInt(path + ".max_targets", 1);
        double effectRadius = config.getDouble(path + ".effect_radius", 2.5 + (level * 0.5));
        String msg = config.getString("message", "");

        // 2. 消耗物品
        player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);

        // 3. 增加灵力 & 提示
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        data.setLingli(data.getLingli() + lingliAdd);

        // 修改完数据后，必须告诉数据库管理器保存数据
        plugin.getDatabaseManager().savePlayer(data);

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GOLD + "当前灵力值: " + String.format("%.1f", data.getLingli())));
        if (!msg.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", player.getName())));
        }

        // 4. 计算目标位置 (星云中心)
        Location hitLoc = getHitLocation(player, range);
        Location cloudCenter = hitLoc.clone().add(0, 0.1, 0); // 稍微抬高防止贴地

        // 【新增】播放指向性光束特效 (金色射线)
        playTargetingBeam(player, hitLoc);

        // 5. 寻找目标怪物并造成伤害
        // 搜索范围：以落点为中心，半径为 effectRadius 的立方体
        List<Entity> nearby = (List<Entity>) cloudCenter.getWorld().getNearbyEntities(cloudCenter, effectRadius, effectRadius, effectRadius);

        List<LivingEntity> victims = nearby.stream()
                .filter(e -> e instanceof LivingEntity && e != player)
                .filter(e -> e.getScoreboardTags().contains("panling") && e.getScoreboardTags().contains("monster"))
                // 高度限制：只打中心点下方的怪
                .filter(e -> e.getLocation().getY() <= cloudCenter.getY())
                // 半球限制：距离必须在半径范围内
                .filter(e -> e.getLocation().distance(cloudCenter) <= effectRadius)
                .map(e -> (LivingEntity) e)
                .sorted(Comparator.comparingDouble(e -> e.getLocation().distance(player.getLocation())))
                .limit(maxTargets)
                .collect(Collectors.toList());

        // 造成伤害
        double damage = data.getZfStr() * damagePercent;
        for (LivingEntity victim : victims) {
            victim.damage(damage, player);
        }

        // 6. 播放星云爆炸特效
        playEffects(cloudCenter, victims, effectRadius);

        return true;
    }

    /**
     * 获取视线撞击点
     * 规则：只会在【实体方块】或者【带有 panling+monster 标签的怪物】身上停下
     */
    private Location getHitLocation(Player player, double maxRange) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        // 射线检测：
        // filter: entity -> ... 这里的逻辑决定了射线会撞到谁
        // 如果返回 true，射线就会停在这个实体上；如果返回 false，射线会穿过去
        RayTraceResult result = player.getWorld().rayTrace(eye, direction, maxRange,
                FluidCollisionMode.NEVER, true, 0.5,
                entity -> entity != player &&
                        entity.getScoreboardTags().contains("panling") &&
                        entity.getScoreboardTags().contains("monster"));

        if (result != null) {
            if (result.getHitBlock() != null) {
                // 撞到方块
                return result.getHitPosition().toLocation(player.getWorld());
            } else if (result.getHitEntity() != null) {
                // 撞到符合条件的怪物
                return result.getHitEntity().getLocation().add(0, result.getHitEntity().getHeight() / 2, 0);
            }
        }
        return eye.add(direction.multiply(maxRange));
    }

    /**
     * 【新增】播放指向性光束
     * 从玩家眼部下方射出一道粒子直到目标点
     */
    private void playTargetingBeam(Player player, Location endLoc) {
        // 起点：玩家眼睛位置稍微往下一点（大概胸口/手部位置），看起来更自然
        Location startLoc = player.getEyeLocation().add(0, -0.25, 0);

        // 稍微向右偏移一点，模拟右手施法
        Vector right = startLoc.getDirection().crossProduct(new Vector(0, 1, 0)).normalize().multiply(-0.2);
        startLoc.add(right);

        double distance = startLoc.distance(endLoc);
        Vector dir = endLoc.toVector().subtract(startLoc.toVector()).normalize();

        // 步长 0.25 (也就是每格生成 4 个粒子，形成连贯的线)
        for (double d = 0; d < distance; d += 0.25) {
            Location point = startLoc.clone().add(dir.clone().multiply(d));

            // 使用金色的红石粉尘粒子 (RGB: 255, 215, 0)
            player.getWorld().spawnParticle(Particle.REDSTONE, point, 1,
                    new Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.6f));
        }

        // 在终点处产生一个小光圈，标记落点
        player.getWorld().spawnParticle(Particle.FLASH, endLoc, 1);
    }

    private void playEffects(Location center, List<LivingEntity> targets, double radius) {
        World world = center.getWorld();
        if (world == null) return;

        // 1. 星云特效 (扁平云盘)
        int particleCount = (int) (20 * radius);
        for (int i = 0; i < particleCount; i++) {
            double angle = Math.random() * 2 * Math.PI;
            double r = Math.random() * radius;
            double offsetX = r * Math.cos(angle);
            double offsetZ = r * Math.sin(angle);
            double offsetY = (Math.random() - 0.5) * 0.5;
            world.spawnParticle(Particle.CLOUD, center.clone().add(offsetX, offsetY, offsetZ), 0, 0, 0, 0);
        }

        // 2. 坠落流星
        if (!targets.isEmpty()) {
            for (LivingEntity target : targets) {
                double angle = Math.random() * 2 * Math.PI;
                double r = Math.random() * radius;
                Location startLoc = center.clone().add(r * Math.cos(angle), 0, r * Math.sin(angle));
                Location endLoc = target.getLocation().add(0, target.getHeight() / 2.0, 0);
                spawnFallingStar(startLoc, endLoc);
            }
        } else {
            // 空放效果
            if (center.clone().subtract(0, 1, 0).getBlock().getType().isAir()) {
                double angle = Math.random() * 2 * Math.PI;
                double r = Math.random() * radius * 0.5;
                Location startLoc = center.clone().add(r * Math.cos(angle), 0, r * Math.sin(angle));
                spawnFallingStar(startLoc, center.clone().add(r * Math.cos(angle), -radius, r * Math.sin(angle)));
            }
        }
    }

    private void spawnFallingStar(Location startLoc, Location endLoc) {
        Vector fallDir = endLoc.toVector().subtract(startLoc.toVector()).normalize().multiply(0.8);
        double distance = startLoc.distance(endLoc);
        int steps = (int) (distance / 0.8);

        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0;
            Location current = startLoc.clone();

            @Override
            public void run() {
                if (step >= steps || (current.getWorld() != null && current.getBlock().getType().isSolid())) {
                    if (current.getWorld() != null) {
                        current.getWorld().spawnParticle(Particle.CRIT, current, 10, 0.2, 0.2, 0.2, 0.1);
                    }
                    this.cancel();
                    return;
                }
                current.add(fallDir);
                if (current.getWorld() != null) {
                    current.getWorld().spawnParticle(Particle.WAX_OFF, current, 1);
                    current.getWorld().spawnParticle(Particle.END_ROD, current, 0);
                }
                step++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}