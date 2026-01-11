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
    private static final String TEST_DUMMY_TAG = "hjh_test_dummy";

    public CombatListener(Hjh_database plugin) {
        this.plugin = plugin;
        this.armorKey = new NamespacedKey(plugin, "hjh_mob_armor");
        this.weaponKey = new NamespacedKey(plugin, "weapon_id");
    }

    private boolean isWeaponSlotValid(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return true;
        String weaponId = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        if (weaponId == null) return true;
        WeaponManager.WeaponData wd = plugin.getPlayerManager().getWeaponManager().getLoadedWeapons().get(weaponId);
        if (wd == null) return true;
        if (wd.activateSlot != -1 && player.getInventory().getHeldItemSlot() != wd.activateSlot) {
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;

        // =========================================================
        // 【核心修复 A】 优先检测法术伤害标记 (HJH_MAGIC_DAMAGE)
        // =========================================================
        // 如果受击者身上有这个标记，说明这是 TuiDiSpell 等技能传递过来的伤害。
        // 我们必须立刻提取伤害值，然后跳过"物理伤害计算"环节，防止被重置为 1.0。
        boolean isMagicDamage = false;
        double magicBaseDamage = 0.0;

        // 注意：这里用的是你 TuiDiSpell 里写的 Metadata Key "HJH_MAGIC_DAMAGE" (大写)
        // 请确保 TuiDiSpell 里 setMetadata 用的 Key 和这里完全一致！
        if (event.getEntity().hasMetadata("HJH_MAGIC_DAMAGE")) {
            isMagicDamage = true;
            magicBaseDamage = event.getEntity().getMetadata("HJH_MAGIC_DAMAGE").get(0).asDouble();
            // 立即清除标记
            event.getEntity().removeMetadata("HJH_MAGIC_DAMAGE", plugin);
        }
        // =========================================================

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

        // =========================================================
        // 【核心修复 B】 物理技能伤害 (WeaponSkill) 检测
        // =========================================================
        boolean isPhysicalSkill = event.getEntity().hasMetadata("hjh_physical_skill");
        if (isPhysicalSkill) {
            event.getEntity().removeMetadata("hjh_physical_skill", plugin);
        }

        double damage = event.getDamage();

        // 如果是法术伤害，直接使用传递过来的数值，覆盖原始伤害
        if (isMagicDamage) {
            damage = magicBaseDamage;
        }

        // 1. 攻击者逻辑 (物理伤害计算)
        // 只有当 [不是法术伤害] 且 [不是物理技能] 时，才执行这里的计算
        if (!isMagicDamage && !isPhysicalSkill && event instanceof EntityDamageByEntityEvent evt) {
            if (evt.getDamager() instanceof Player attacker) {

                // 槽位检查
                ItemStack hand = attacker.getInventory().getItemInMainHand();
                if (!isWeaponSlotValid(attacker, hand)) {
                    // 医师特化：如果是医师，不提示，静默取消
                    PlayerData data = plugin.getPlayerManager().getData(attacker.getUniqueId());
                    if (data != null && data.getJob() != null && data.getJob() == 3) {
                        event.setCancelled(true);
                        return;
                    }
                    // 其他人正常提示
                    event.setCancelled(true);
                    attacker.sendMessage(ChatColor.RED + "武器未激活！请将武器移动到正确的槽位使用！");
                    attacker.playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 0.5f);
                    return;
                }

                PlayerData data = plugin.getPlayerManager().getData(attacker.getUniqueId());
                if (data != null) {
                    boolean isRpgWeapon = hand.hasItemMeta() &&
                            hand.getItemMeta().getPersistentDataContainer().has(weaponKey, PersistentDataType.STRING);

                    if (data.getJob() != null && data.getJob() == 0) { // 战士
                        String type = hand.getType().name();
                        if (type.endsWith("_SWORD") || type.endsWith("_AXE")) {
                            double baseAttack = data.getVal(data.getAttack());
                            float cooldown = attacker.getAttackCooldown();
                            baseAttack *= cooldown;
                            damage = baseAttack;
                        }
                    }
                    // 【问题根源在这里】
                    // 如果是医师(非战士) + 拿医旗(RPG武器) -> 这里把 damage 变成了 1.0
                    // 但我们加了 !isMagicDamage 的判断，所以法术伤害会跳过这整段代码，保住了数值！
                    else if (isRpgWeapon) {
                        damage = 1.0;
                    }

                    // 暴击逻辑
                    double critChance = Math.min(0.8, data.getVal(data.getCritChance()));
                    if (attacker.getAttackCooldown() > 0.9F) {
                        if (Math.random() < critChance) {
                            damage *= 1.5;
                            attacker.getWorld().spawnParticle(Particle.CRIT, evt.getEntity().getLocation().add(0, 1, 0), 15);
                            attacker.playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
                        }
                    }
                }
            }
            // 远程逻辑
            else if (evt.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter) {
                PlayerData data = plugin.getPlayerManager().getData(shooter.getUniqueId());
                if (data != null) {
                    double archerDmg = data.getVal(data.getArcherDamage());
                    double velocity = arrow.getVelocity().length();
                    damage = archerDmg * (Math.min(3.0, velocity) / 3.0);
                    double critChance = Math.min(0.8, data.getVal(data.getCritChance()));
                    if (Math.random() < critChance) {
                        damage *= 1.5;
                        arrow.setCritical(true);
                        shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1);
                    }
                }
            }
        }

        // 2. 受击者逻辑 (减伤)
        if (event.getEntity() instanceof LivingEntity victim) {
            // ... (原版护甲清理逻辑) ...
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
                    if (victim.getPersistentDataContainer().has(armorKey, PersistentDataType.DOUBLE)) {
                        armor = victim.getPersistentDataContainer().get(armorKey, PersistentDataType.DOUBLE);
                    }
                }

                // === 【核心修复 C】如果是法术伤害，无视护甲 ===
                if (isMagicDamage || victim.hasMetadata("hjh_magic_damage")) {
                    // 这里兼容大小写写法，或者如果你上面已经处理了 isMagicDamage，这里就可以简写
                    // 只要确认是法术伤害，护甲视为 0 (真伤)
                    armor = 0.0;
                }
                // ==========================================

                if (armor < 0) armor = 0;
                double multiplier = 50.0 / (50.0 + armor);
                damage = damage * multiplier;
            }

            // 3. 测伤反馈
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

    // ... (onDeath 和 onShoot 保持不变) ...
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        // ... (保持你提供的代码，包括经验和测伤人偶复活) ...
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer != null && !entity.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
            if (entity.getScoreboardTags().contains("panling") && entity.getScoreboardTags().contains("monster")) {
                int expAmount = plugin.getPlayerManager().getMobExp();
                plugin.getPlayerManager().giveExp(killer, expAmount);
                killer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§e+ " + expAmount + " 经验"));
            }
        }
        if (entity.getScoreboardTags().contains(TEST_DUMMY_TAG)) {
            // ... (测伤人偶复活逻辑，省略以节省空间，直接用你原来的) ...
            event.getDrops().clear();
            event.setDroppedExp(0);
            Location loc = entity.getLocation();
            double maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            double armor = 0.0;
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
        ItemStack bow = event.getBow();
        if (!isWeaponSlotValid(player, bow)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "弓弩未激活！请将武器移动到正确的槽位使用！");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 0.5f);
            return;
        }
        PlayerData data = plugin.getPlayerManager().getData(player.getUniqueId());
        if (data != null && (data.getJob() == null || data.getJob() != 1)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "只有 [弓箭手] 才能使用弓弩！");
        }
    }
}