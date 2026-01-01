package com.hjh_database.skill.weapon.job_0;

import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.weapon.WeaponSkill;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class NoviceSwordSkill implements WeaponSkill {

    @Override
    public boolean castActive(Player player, PlayerData data, ConfigurationSection config, Entity projectile) {
        // 1. 读取参数
        double radius = config.getDouble("radius", 5.0);
        double duration = config.getDouble("duration", 2.0);
        int amp = config.getInt("slowness_level", 1) - 1;

        // 2. 视觉效果
        Location loc = player.getLocation();
        player.getWorld().playSound(loc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.5f);
        for (int i = 0; i < 360; i += 20) {
            double rad = Math.toRadians(i);
            double x = Math.cos(rad) * radius;
            double z = Math.sin(rad) * radius;
            player.getWorld().spawnParticle(Particle.CRIT, loc.clone().add(x, 0.5, z), 1, 0, 0, 0, 0);
        }

        // 3. 逻辑判定
        for (Entity entity : player.getNearbyEntities(radius, 2, radius)) {
            if (entity instanceof LivingEntity target && entity != player) {
                if (target.getScoreboardTags().contains("monster")) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, (int)(duration * 20), amp));
                }
            }
        }

        return true;
    }
}