package com.hjh_database.listener;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.weapon.WeaponManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class CombatListener implements Listener {
    private final Hjh_database plugin;
    private final NamespacedKey armorKey;
    private final NamespacedKey weaponKey;

    // 专属标签，用于识别测伤怪物
    private static final String TEST_DUMMY_TAG = "hjh_test_dummy";

    public CombatListener(Hjh_database plugin) {
        this.plugin = plugin;
        this.armorKey = new NamespacedKey(plugin, "hjh_mob_armor");
        this.weaponKey = new NamespacedKey(plugin, "weapon_id");
    }

    /**
     * 【新增辅助方法】检查武器是否在正确槽位
     * 仅在不满足条件时返回 false
     */
    private boolean isWeaponSlotValid(Player player, ItemStack item) {
        // 1. 基础检查
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return true;

        // 2. 检查是否是 RPG 武器
        String weaponId = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        if (weaponId == null) return true;

        // 3. 获取配置
        WeaponManager.WeaponData wd = plugin.getPlayerManager().getWeaponManager().getLoadedWeapons().get(weaponId);
        if (wd == null) return true;

        // 4. 核心校验：如果规定了槽位且当前槽位不符
        if (wd.activateSlot != -1 && player.getInventory().getHeldItemSlot() != wd.activateSlot) {
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;

        // 0. 横扫攻击检测
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            if (event instanceof EntityDamageByEntityEvent evt && evt.getDamager() instanceof Player attacker) {
                PlayerData data = plugin.getPlayerManager().getData(attacker.getUniqueId());
                if (data == null || data.getJob() == null || data.getJob() != 0) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        double damage = event.getDamage();

        // 1. 攻击者逻辑
        if (event instanceof EntityDamageByEntityEvent evt) {
            if (evt.getDamager() instanceof Player attacker) {

                // === 【修复核心】 近战武器槽位限制检查 ===
                ItemStack hand = attacker.getInventory().getItemInMainHand();
                if (!isWeaponSlotValid(attacker, hand)) {
                    event.setCancelled(true);
                    attacker.sendMessage(ChatColor.RED + "武器未激活！请将武器移动到正确的槽位使用！");
                    attacker.playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 0.5f);
                    return;
                }
                // ======================================
                PlayerData data = plugin.getPlayerManager().getData(attacker.getUniqueId());
                if (data != null) {
                    // 判断是否为 RPG 武器
                    boolean isRpgWeapon = hand.hasItemMeta() &&
                            hand.getItemMeta().getPersistentDataContainer().has(weaponKey, PersistentDataType.STRING);
                    // A. 战士职业 (Job == 0)
                    // 基础伤害计算
                    if (data.getJob() != null && data.getJob() == 0) { // 战士
                        String type = hand.getType().name();
                        if (type.endsWith("_SWORD") || type.endsWith("_AXE")) {
                            double baseAttack = data.getVal(data.getAttack());
                            float cooldown = attacker.getAttackCooldown();
                            baseAttack *= cooldown;
                            damage = baseAttack;
                        }
                    }
                    // B. 非战士职业 + RPG 武器 -> 惩罚
                    else if (isRpgWeapon) {
                        damage = 1.0;
                    }
                    // 【核心修改】 暴击判定逻辑 (通用，战士/弓箭手/甚至其他职业普攻都能触发)
                    // 上限 80% (0.8)
                    double critChance = Math.min(0.8, data.getVal(data.getCritChance()));
                    // 只有冷却比较完善时才能暴击 (防止连点器)
                    if (attacker.getAttackCooldown() > 0.9F) {
                        if (Math.random() < critChance) {
                            damage *= 1.5; // 1.5倍伤害
                            // 播放原版暴击粒子 (跳劈效果)
                            attacker.getWorld().spawnParticle(Particle.CRIT, evt.getEntity().getLocation().add(0, 1, 0), 15);
                            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
                        }
                    }
                }
            }
            // 远程逻辑 (弓箭手)
            else if (evt.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter) {
                PlayerData data = plugin.getPlayerManager().getData(shooter.getUniqueId());
                if (data != null) {
                    double archerDmg = data.getVal(data.getArcherDamage());
                    double velocity = arrow.getVelocity().length();
                    damage = archerDmg * (Math.min(3.0, velocity) / 3.0);
                    // 【核心修改】 弓箭也能触发暴击属性
                    double critChance = Math.min(0.8, data.getVal(data.getCritChance()));
                    if (Math.random() < critChance) {
                        damage *= 1.5;
                        arrow.setCritical(true); // 视觉上的粒子
                        shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
                    }
                }
            }
        }

        // 2. 受击者逻辑 (减伤)
        if (event.getEntity() instanceof LivingEntity victim) {
            if (victim.getAttribute(Attribute.GENERIC_ARMOR) != null) {
                victim.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(0);
            }

            EntityDamageEvent.DamageCause cause = event.getCause();
            boolean isMagic = (cause == EntityDamageEvent.DamageCause.MAGIC ||
                    cause == EntityDamageEvent.DamageCause.DRAGON_BREATH ||
                    cause == EntityDamageEvent.DamageCause.WITHER ||
                    cause == EntityDamageEvent.DamageCause.POISON);
            boolean isTrueDamage = (cause == EntityDamageEvent.DamageCause.VOID ||
                    cause == EntityDamageEvent.DamageCause.SUICIDE ||
                    cause == EntityDamageEvent.DamageCause.STARVATION);

            if (!isMagic && !isTrueDamage) {
                double armor = 0.0;
                if (victim instanceof Player p) {
                    PlayerData data = plugin.getPlayerManager().getData(p.getUniqueId());
                    if (data != null) armor = data.getVal(data.getArmor());
                } else {
                    // 读取怪物身上的护甲 NBT
                    if (victim.getPersistentDataContainer().has(armorKey, PersistentDataType.DOUBLE)) {
                        armor = victim.getPersistentDataContainer().get(armorKey, PersistentDataType.DOUBLE);
                    }
                }

                // === 【核心修改】检测法术伤害标记 ===
                if (victim.hasMetadata("hjh_magic_damage")) {
                    armor = 0.0; // 如果是法术伤害，无视护甲
                    // TODO: 这里以后可以添加 victimMagicResist (法抗) 的逻辑
                }
                // ==============================

                if (armor < 0) armor = 0;
                double multiplier = 50.0 / (50.0 + armor);
                damage = damage * multiplier;
            }

            // 3. 测伤反馈 (保持原样，未修改)
            if (victim.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
                if (event instanceof EntityDamageByEntityEvent evt) {
                    CommandSender msgTarget = null;
                    if (evt.getDamager() instanceof Player p) msgTarget = p;
                    else if (evt.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) msgTarget = p;

                    if (msgTarget != null) {
                        msgTarget.sendMessage(String.format(
                                ChatColor.YELLOW + "[测试] " + ChatColor.WHITE + "造成伤害: " + ChatColor.RED + "%.2f",
                                damage
                        ));
                    }
                }
            }
        }

        if (damage != event.getDamage()) {
            event.setDamage(damage);
        }
    }

    // 复活逻辑 (已合并经验获取逻辑)
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // === 1. 【新增】经验获取逻辑 ===
        // 注意：测伤人偶(TEST_DUMMY_TAG)虽然也有panling标签，但不能给经验，否则会无限刷
        Player killer = entity.getKiller();
        if (killer != null && !entity.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
            // 检查标签：同时拥有 panling 和 monster
            if (entity.getScoreboardTags().contains("panling") &&
                    entity.getScoreboardTags().contains("monster")) {

                // 从 PlayerManager 获取配置的经验值
                int expAmount = plugin.getPlayerManager().getMobExp();

                // 给予经验 (会自动处理升级)
                plugin.getPlayerManager().giveExp(killer, expAmount);

                // 动作栏提示 (比聊天栏更清爽)
                killer.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent("§e+ " + expAmount + " 经验"));
            }
        }

        // === 2. 测伤人偶复活逻辑 (保持原样) ===
        if (entity.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
            event.getDrops().clear();
            event.setDroppedExp(0);

            Location loc = entity.getLocation();
            double maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            double armor = 0.0;
            // 读取旧尸体的护甲数据
            if (entity.getPersistentDataContainer().has(armorKey, PersistentDataType.DOUBLE)) {
                armor = entity.getPersistentDataContainer().get(armorKey, PersistentDataType.DOUBLE);
            }
            final double finalArmor = armor;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                loc.getWorld().spawn(loc, Creeper.class, creeper -> {
                    creeper.addScoreboardTag("panling");
                    creeper.addScoreboardTag("monster");
                    creeper.addScoreboardTag(TEST_DUMMY_TAG);
                    creeper.setAI(false);
                    creeper.setPowered(false);
                    creeper.setExplosionRadius(0);

                    // 写入新尸体的护甲数据
                    creeper.getPersistentDataContainer().set(armorKey, PersistentDataType.DOUBLE, finalArmor);

                    if (creeper.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                        creeper.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                    }
                    if (creeper.getAttribute(Attribute.GENERIC_ARMOR) != null) {
                        creeper.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(0);
                    }
                    creeper.setHealth(maxHealth);

                    creeper.setCustomName(ChatColor.translateAlternateColorCodes('&',
                            "&c&l测伤人偶 &7(HP:" + (int)maxHealth + " 护甲:" + (int)finalArmor + ")"));
                    creeper.setCustomNameVisible(true);
                });
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // === 【修复核心】 弓箭武器槽位限制检查 ===
        ItemStack bow = event.getBow();
        if (!isWeaponSlotValid(player, bow)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "弓弩未激活！请将武器移动到正确的槽位使用！");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 0.5f);
            return;
        }
        // ======================================

        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data != null && (data.getJob() == null || data.getJob() != 1)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "只有 [弓箭手] 才能使用弓弩！");
        }
    }
}