package com.hjh_database.skill.weapon.job_1;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.weapon.WeaponSkill;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class NoviceBowSkill implements WeaponSkill {

    @Override
    public boolean castActive(Player player, PlayerData data, ConfigurationSection config, Entity projectile) {
        double damagePercent = config.getDouble("damage_percent", 2.0);
        double speed = config.getDouble("flight_speed", 1.5);
        double duration = config.getDouble("flight_duration", 3.0);
        double detectRadius = config.getDouble("detect_radius", 5.0);

        if (projectile == null || !(projectile instanceof Projectile)) {
            return false;
        }

        double baseDmg = data.getArcherDamage();
        double finalDmg = baseDmg * damagePercent;

        new StarArrowTask(
                JavaPlugin.getPlugin(Hjh_database.class),
                player,
                (Projectile) projectile,
                finalDmg,
                speed,
                duration,
                detectRadius
        ).runTaskTimer(JavaPlugin.getPlugin(Hjh_database.class), 0L, 1L);

        // 技能启动音效：清脆的充能声
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 2.0f);
        return true;
    }

    private class StarArrowTask extends BukkitRunnable {
        private final Hjh_database plugin;
        private final Player shooter;
        private final Projectile leaderArrow;

        private final double damage;
        private final double speed;
        private final double detectRadius;
        private final int maxTicks;

        private Location currentLoc;
        private Vector direction;
        private int ticks = 0;
        private LivingEntity lockedTarget;
        private Location lastKnownArrowLoc;
        private final int launchDelay = 10;

        public StarArrowTask(Hjh_database plugin, Player shooter, Projectile leaderArrow, double damage, double speed, double duration, double detectRadius) {
            this.plugin = plugin;
            this.shooter = shooter;
            this.leaderArrow = leaderArrow;
            this.damage = damage;
            this.speed = speed;
            this.detectRadius = detectRadius;
            this.maxTicks = (int) (duration * 20) + launchDelay;

            this.currentLoc = getRightSideLocation(shooter);
            this.direction = shooter.getLocation().getDirection();
            this.lastKnownArrowLoc = leaderArrow.getLocation();
        }

        @Override
        public void run() {
            if (ticks++ >= maxTicks || !shooter.isOnline()) {
                playFizzEffect();
                this.cancel();
                return;
            }

            if (leaderArrow.isValid() && !leaderArrow.isDead()) {
                lastKnownArrowLoc = leaderArrow.getLocation();
            }

            // 1. 蓄力阶段 (星辰汇聚)
            if (ticks <= launchDelay) {
                currentLoc = getRightSideLocation(shooter);
                playChargeEffect();

                // 伴随蓄力的细微铃声
                if (ticks % 3 == 0) {
                    currentLoc.getWorld().playSound(currentLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 2.0f);
                }

                if (ticks == launchDelay) launch();
                return;
            }

            // 2. 飞行阶段
            if (lockedTarget != null) {
                if (!lockedTarget.isDead() && lockedTarget.isValid()) {
                    driveTowards(lockedTarget.getLocation().add(0, lockedTarget.getHeight() / 2, 0));
                } else {
                    lockedTarget = null;
                }
            } else {
                if (leaderArrow.isValid() && !leaderArrow.isDead() && !leaderArrow.isOnGround()) {
                    driveTowards(leaderArrow.getLocation());
                } else {
                    LivingEntity found = findNearestValidTarget(lastKnownArrowLoc, detectRadius);
                    if (found != null) {
                        lockedTarget = found;
                        // 锁定目标时的提示音：高音叮一下
                        shooter.playSound(shooter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 2.0f);
                    } else {
                        if (currentLoc.distance(lastKnownArrowLoc) > 1.0) driveTowards(lastKnownArrowLoc);
                        LivingEntity passing = findNearestValidTarget(currentLoc, 3.0);
                        if (passing != null) lockedTarget = passing;
                    }
                }
            }

            currentLoc.add(direction.clone().multiply(speed));
            playFlyEffect();
            checkCollision();
        }

        private void launch() {
            // 发射音效：替换为更空灵的三叉戟投掷声 + 紫水晶击打声
            currentLoc.getWorld().playSound(currentLoc, Sound.ITEM_TRIDENT_THROW, 1.0f, 2.0f);
            currentLoc.getWorld().playSound(currentLoc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 2.0f);

            if (!leaderArrow.isValid() || leaderArrow.isDead() || leaderArrow.isOnGround()) {
                lockedTarget = findNearestValidTarget(lastKnownArrowLoc, detectRadius);
                if (lockedTarget != null) {
                    direction = lockedTarget.getLocation().subtract(currentLoc).toVector().normalize();
                } else {
                    direction = lastKnownArrowLoc.toVector().subtract(currentLoc.toVector()).normalize();
                }
            } else {
                direction = leaderArrow.getLocation().toVector().subtract(currentLoc.toVector()).normalize();
            }
        }

        private void driveTowards(Location targetLoc) {
            Vector toTarget = targetLoc.toVector().subtract(currentLoc.toVector()).normalize();
            direction = direction.add(toTarget.multiply(0.2)).normalize();
        }

        private void checkCollision() {
            for (Entity e : currentLoc.getWorld().getNearbyEntities(currentLoc, 1.0, 1.0, 1.0)) {
                if (isValidTarget(e)) {
                    hit((LivingEntity) e);
                    return;
                }
            }
            if (currentLoc.getBlock().getType().isSolid()) {
                playFizzEffect();
                this.cancel();
            }
        }

        private void hit(LivingEntity victim) {
            // 伤害逻辑 (保持不变)
            victim.setNoDamageTicks(0);
            victim.setMetadata("hjh_physical_skill", new FixedMetadataValue(plugin, true));
            victim.damage(damage, shooter);

            // === 命中特效 (完全重写) ===
            World w = victim.getWorld();

            // 1. 核心闪光 (代替爆炸)
            w.spawnParticle(Particle.FLASH, victim.getLocation().add(0, 1, 0), 1);

            // 2. 星屑爆散 (代替烟雾)
            // 使用 FIREWORKS_SPARK 但不加声音，或者用 WAX_ON (星星闪烁)
            w.spawnParticle(Particle.WAX_OFF, victim.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
            w.spawnParticle(Particle.END_ROD, victim.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.05);

            // 3. 命中音效：紫水晶破碎声 (清脆)
            w.playSound(victim.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 1.5f);
            w.playSound(victim.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.5f, 2.0f);

            this.cancel();
        }

        // 辅助方法
        private Location getRightSideLocation(Player p) {
            Location eye = p.getEyeLocation();
            Vector right = eye.getDirection().getCrossProduct(new Vector(0, 1, 0)).normalize();
            return eye.add(right.multiply(0.8)).add(0, -0.3, 0);
        }

        // 蓄力特效：金色的星光汇聚
        private void playChargeEffect() {
            World w = currentLoc.getWorld();
            for (int i = 0; i < 2; i++) {
                Vector offset = Vector.getRandom().subtract(new Vector(0.5, 0.5, 0.5)).normalize().multiply(0.5);
                Location start = currentLoc.clone().add(offset);
                // 金黄色 (255, 215, 0)
                w.spawnParticle(Particle.REDSTONE, start, 0,
                        -offset.getX(), -offset.getY(), -offset.getZ(), 0.1,
                        new Particle.DustOptions(Color.fromRGB(255, 215, 0), 0.5f));
            }
            // 偶尔闪烁的白色星光
            if (ticks % 2 == 0) {
                w.spawnParticle(Particle.END_ROD, currentLoc, 1, 0.1, 0.1, 0.1, 0);
            }
        }

        // 飞行特效：流星拖尾
        private void playFlyEffect() {
            World w = currentLoc.getWorld();
            // 主体：末地烛光 (白色光棒)
            w.spawnParticle(Particle.END_ROD, currentLoc, 1, 0, 0, 0, 0);
            // 拖尾：细微的图腾粒子 (绿色/金色的星尘感)
            w.spawnParticle(Particle.TOTEM, currentLoc, 1, 0, 0, 0, 0);
        }

        private void playFizzEffect() {
            // 消散时变成一点点星光消失
            currentLoc.getWorld().spawnParticle(Particle.WAX_OFF, currentLoc, 5, 0.1, 0.1, 0.1, 0.05);
        }

        private LivingEntity findNearestValidTarget(Location loc, double radius) {
            List<Entity> nearby = (List<Entity>) loc.getWorld().getNearbyEntities(loc, radius, radius, radius);
            return nearby.stream().filter(this::isValidTarget).map(e -> (LivingEntity) e).min(Comparator.comparingDouble(e -> e.getLocation().distance(loc))).orElse(null);
        }

        private boolean isValidTarget(Entity entity) {
            if (entity == shooter || entity == leaderArrow) return false;
            if (!(entity instanceof LivingEntity)) return false;
            Set<String> tags = entity.getScoreboardTags();
            return tags.contains("panling") && tags.contains("monster");
        }
    }
}