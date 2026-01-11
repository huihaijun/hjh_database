package com.hjh_database.skill.medical.spell.impl;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.spell.MedicalSpell;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue; // 导入 Metadata
import org.bukkit.util.Vector;

public class TuiDiSpell implements MedicalSpell {
    private final Hjh_database plugin;

    public TuiDiSpell(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, PlayerData data, ConfigurationSection config) {
        // 1. 读取配置参数
        double range = config != null ? config.getDouble("range", 4.0) : 4.0;
        double knockback = config != null ? config.getDouble("knockback_strength", 1.2) : 1.2;
        double damageRatio = config != null ? config.getDouble("damage_ratio", 0.9) : 0.9;

        // 2. 计算伤害 (基于阵法强度)
        double zfStr = data.getZfStr(); // 确保这里获取到了 80
        double damage = zfStr * damageRatio;
        if (damage < 1) damage = 1;

        // 3. 特效
        Location loc = player.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        player.getWorld().playSound(loc, Sound.ENTITY_BAT_TAKEOFF, 1f, 0.5f);
        spawnShockwaveParticles(player, range);

        // 4. 索敌与处理
        boolean hitAny = false;
        for (Entity entity : player.getNearbyEntities(range, range, range)) {

            // =========================================================
            // 【修复问题 3】 索敌逻辑：必须同时拥有 panling 和 monster 标签
            // =========================================================
            if (!(entity instanceof LivingEntity)) continue;
            if (entity == player) continue; // 排除自己

            boolean hasPanling = entity.getScoreboardTags().contains("panling");
            boolean hasMonster = entity.getScoreboardTags().contains("monster");

            if (hasPanling && hasMonster) {
                LivingEntity mob = (LivingEntity) entity;

                // 距离校验
                if (mob.getLocation().distance(loc) > range) continue;

                // =========================================================
                // 【修复问题 2】 伤害注入
                // =========================================================
                // 1. 给怪物打上伤害标记，告诉 CombatListener 这是 72点(80*0.9) 魔法伤害
                mob.setMetadata("HJH_MAGIC_DAMAGE", new FixedMetadataValue(plugin, damage));

                // 2. 触发伤害事件 (参数填0即可，反正 CombatListener 会读取 Meta 覆盖它)
                // 必须传 player 作为 attacker，否则不算玩家击杀
                mob.damage(0, player);
                // =========================================================

                // B. 施加击退
                Vector dir = mob.getLocation().toVector().subtract(loc.toVector());
                if (dir.lengthSquared() == 0) dir = player.getLocation().getDirection();

                mob.setVelocity(dir.normalize().multiply(knockback).setY(0.4));
                mob.getWorld().spawnParticle(Particle.CRIT, mob.getLocation().add(0, 1, 0), 5);

                hitAny = true;
            }
        }

        return true;
    }

    private void spawnShockwaveParticles(Player p, double range) {
        Location center = p.getLocation().add(0, 0.5, 0);
        for (double r = 0.5; r < range; r += 0.5) {
            for (int degree = 0; degree < 360; degree += 20) {
                double radians = Math.toRadians(degree);
                double x = Math.cos(radians) * r;
                double z = Math.sin(radians) * r;
                center.getWorld().spawnParticle(Particle.CLOUD, center.clone().add(x, 0, z), 1, 0, 0, 0, 0.05);
            }
        }
    }
}