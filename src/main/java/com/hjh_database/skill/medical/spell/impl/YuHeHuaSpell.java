package com.hjh_database.skill.medical.spell.impl;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.spell.MedicalSpell;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public class YuHeHuaSpell implements MedicalSpell {
    private final Hjh_database plugin;
    public static final String KEY_HEAL_AMOUNT = "med_heal_amount";
    public static final String KEY_OWNER = "med_owner";

    public YuHeHuaSpell(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean cast(Player player, PlayerData data, ConfigurationSection config) {
        double zfStr = data.getZfStr();
        double healAmount = 4.0 + (zfStr * 0.2);

        Location center = player.getLocation();
        player.getWorld().playSound(center, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1f, 1.5f);

        double radius = 2.0;
        double[] angles = {0, 120, 240};

        for (double angle : angles) {
            double radians = Math.toRadians(angle + center.getYaw());
            double x = Math.cos(radians) * radius;
            double z = Math.sin(radians) * radius;

            // 【修复】计算目标位置
            // 降低高度到 0.5，避免卡进天花板
            Location targetLoc = center.clone().add(x, 0.5, z);

            // 【关键】检测目标方块是否是实心的
            if (isSafeLocation(targetLoc)) {
                spawnFlowerItem(player, targetLoc, healAmount);
            } else {
                // 如果目标位置卡墙了，就直接生成在玩家脚下，稍微给点随机偏移
                Location fallback = center.clone().add((Math.random()-0.5), 0.5, (Math.random()-0.5));
                spawnFlowerItem(player, fallback, healAmount);
            }
        }

        return true;
    }

    // 检查位置是否安全（不是实心方块）
    private boolean isSafeLocation(Location loc) {
        Block b = loc.getBlock();
        return b.getType().isAir() || b.isPassable();
    }

    private void spawnFlowerItem(Player owner, Location loc, double healAmount) {
        ItemStack flower = new ItemStack(Material.POPPY);
        ItemMeta meta = flower.getItemMeta();
        meta.setDisplayName("§d愈合之花");

        NamespacedKey keyHeal = new NamespacedKey(plugin, KEY_HEAL_AMOUNT);
        NamespacedKey keyOwner = new NamespacedKey(plugin, KEY_OWNER);

        meta.getPersistentDataContainer().set(keyHeal, PersistentDataType.DOUBLE, healAmount);
        meta.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, owner.getUniqueId().toString());

        flower.setItemMeta(meta);

        // 生成掉落物
        Item itemEntity = loc.getWorld().dropItem(loc, flower);

        // 【关键】确保可以拾取
        itemEntity.setPickupDelay(5); // 设置极短的捡起延迟(0.25秒)，防止刚生成就被吸走，但也防止捡不起来
        itemEntity.setInvulnerable(true);
        itemEntity.setGlowing(true);
        itemEntity.setVelocity(new Vector(0, 0.1, 0)); // 只有一点点向上的力，防止乱飞

        loc.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, loc, 5, 0.2, 0.2, 0.2, 0.05);
    }
}