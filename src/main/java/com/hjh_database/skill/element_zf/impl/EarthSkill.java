package com.hjh_database.skill.element_zf.impl;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.element_zf.ElementSkill;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Set;

public class EarthSkill implements ElementSkill {
    private final Hjh_database plugin;

    public EarthSkill(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, int level, ConfigurationSection config) {
        // 1. 读取配置
        String path = "levels." + level;
        if (!config.contains(path)) path = "levels.1";

        double range = config.getDouble(path + ".range", 10.0);
        double radius = config.getDouble(path + ".radius", 5.0);
        double duration = config.getDouble(path + ".duration", 5.0);
        double strength = config.getDouble(path + ".pull_strength", 0.08);
        double lingliAdd = config.getDouble(path + ".lingli_add", 1.0);

        // 2. 消耗物品
        player.getInventory().getItemInMainHand().setAmount(player.getInventory().getItemInMainHand().getAmount() - 1);

        // 3. 增加灵力
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (lingliAdd > 0) {
            double currentLingli = data.getLingli();
            double maxLingli = data.getMaxLingli();
            if (currentLingli < maxLingli) {
                data.setLingli(Math.min(maxLingli, currentLingli + lingliAdd));
                plugin.getDatabaseManager().savePlayer(data);
            }
        }

        // 4. 确定阵法中心
        Location center = getTargetLocation(player, range);

        // 5. 提示 & 启动音效
//        player.sendMessage("§6§l[裂地] §f阵法释放成功！");
        // 播放沉闷的碎地声
        center.getWorld().playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.5f);
        center.getWorld().playSound(center, Sound.BLOCK_GRAVEL_BREAK, 1.0f, 0.6f);

        // 6. 开启持续任务
        new BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = (int) (duration * 20);

            @Override
            public void run() {
                if (ticks >= maxTicks) {
                    this.cancel();
                    return;
                }

                // === 音效循环 (每秒播放一次低频轰鸣) ===
                if (ticks % 20 == 0) {
                    // 使用信标环境音，音调调低(0.5)，模拟重力场嗡嗡声
                    center.getWorld().playSound(center, Sound.BLOCK_BEACON_AMBIENT, 2.0f, 0.5f);
                }

                // === 增强版特效 ===
                playZoneEffect(center, radius);

                // === 物理牵引逻辑 ===
                List<Entity> entities = (List<Entity>) center.getWorld().getNearbyEntities(center, radius, radius, radius);

                for (Entity entity : entities) {
                    if (entity == player || !(entity instanceof LivingEntity)) continue;

                    Set<String> tags = entity.getScoreboardTags();
                    if (!tags.contains("panling") || !tags.contains("monster")) continue;

                    if (entity.getLocation().distance(center) > radius) continue;

                    Vector dir = center.toVector().subtract(entity.getLocation().toVector());
                    dir.setY(0);

                    if (dir.lengthSquared() < 0.25) continue;

                    dir.normalize().multiply(strength);
                    entity.setVelocity(entity.getVelocity().add(dir));
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }

    private Location getTargetLocation(Player player, double range) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        RayTraceResult result = player.getWorld().rayTrace(eye, direction, range,
                FluidCollisionMode.ALWAYS, true, 0.5,
                e -> e != player && e instanceof LivingEntity);

        if (result != null) {
            if (result.getHitEntity() != null) {
                return result.getHitEntity().getLocation();
            } else if (result.getHitBlock() != null) {
                return result.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
            } else if (result.getHitPosition() != null) {
                return result.getHitPosition().toLocation(player.getWorld());
            }
        }
        return eye.add(direction.multiply(range));
    }

    /**
     * 优化后的区域特效：更真实，不遮挡
     */
    private void playZoneEffect(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) return;

        // 1. 边缘碎裂圈 (贴地，清晰但不挡视野)
        // 增加密度，每tick画4个点
        for (int i = 0; i < 4; i++) {
            double angle = Math.random() * 2 * Math.PI;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);

            // 使用 BLOCK_DUST (DIRT) 模拟被震起来的泥土
            world.spawnParticle(Particle.BLOCK_DUST, x, center.getY() + 0.1, z, 1, 0, 0, 0, 0, Material.DIRT.createBlockData());
        }

        // 2. 区域内扬尘 (营造力场感)
        // 随机在范围内生成淡淡的烟雾，模拟尘土飞扬
        for (int i = 0; i < 2; i++) {
            double r = Math.random() * radius;
            double angle = Math.random() * 2 * Math.PI;
            double x = center.getX() + r * Math.cos(angle);
            double z = center.getZ() + r * Math.sin(angle);

            // SMOKE_NORMAL 比较淡，且只向上飘一点点
            world.spawnParticle(Particle.SMOKE_NORMAL, x, center.getY() + 0.2, z, 1, 0, 0.1, 0, 0.05);

            // 偶尔产生地面裂纹粒子
            if (Math.random() < 0.1) {
                world.spawnParticle(Particle.BLOCK_CRACK, x, center.getY() + 0.1, z, 1, 0, 0, 0, 0, Material.COARSE_DIRT.createBlockData());
            }
        }
    }
}