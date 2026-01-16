package com.hjh_database.skill.medical.spell;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell;
import com.hjh_database.weapon.WeaponManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MedicalSpellListener implements Listener {
    private final Hjh_database plugin;
    // 用于记录正在“运气调息”的玩家
    private final Map<UUID, Integer> chargingTasks = new HashMap<>();

    public MedicalSpellListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        // 1. 基础过滤（保持不变）
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack mainHandItem = p.getInventory().getItemInMainHand();
        // 2. 检查手中是否是医旗（只要是医旗就行，不管有没有激活）
        // 只有是医旗，才有资格去判断是否要释放技能
        if (mainHandItem == null || !plugin.getMedicalManager().isMedicalBanner(mainHandItem)) return;
        // 获取手中旗帜上的技能ID
        String skillId = plugin.getMedicalManager().getSkillIdFromBanner(mainHandItem);
        if (skillId == null) return;
        // ==================== 修改的核心区域 START ====================
        // 3. 【核心逻辑优化】检查“激活位（第0格）”是否有合法的医旗
        // 无论你手里拿的是第几格的旗子，我们只看第0格有没有“医师资格”
        ItemStack activeSlotItem = p.getInventory().getItem(0); // 获取快捷栏第一格物品
        // 使用 Manager 检查第0格的物品是否激活（符合职业、等级、必须在 slot 0）
        WeaponManager.WeaponData activeWd = plugin.getPlayerManager().getWeaponManager()
                .checkActiveWeapon(p, activeSlotItem, 0);
        // 如果第0格不是激活的武器，或者第0格虽然激活了但不是医旗 -> 禁止施法
        if (activeWd == null || !plugin.getMedicalManager().isMedicalBanner(activeSlotItem)) {
            // 这里可以不发消息（静默失败），或者提示玩家“请先在第一格装备已激活的医旗”
            return;
        }
        // ==================== 修改的核心区域 END ====================
        e.setCancelled(true);
        // 4. 检查是否学会（保持不变）
        PlayerData data = plugin.getPlayerManager().getPlayerData(p);
        if (!data.getMedicalLoadout().contains(skillId)) {
            p.sendMessage("§c[释放失败] §7你虽然持有此旗，但并未真正掌握其中蕴含的医术！");
            return;
        }
        // 5. 释放技能（保持不变）
        plugin.getMedicalSpellManager().castSpell(p, skillId);
    }

    /**
     * 【新增功能】监听潜行（Shift）事件来实现“长按/运气”回蓝
     */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();

        // 如果玩家开始潜行 (isSneaking 返回 true 代表当前状态是"正在变为潜行")
        if (e.isSneaking()) {
            startCharging(p);
        } else {
            // 停止潜行，取消任务
            stopCharging(p);
        }
    }

    private void startCharging(Player p) {
        // 如果已经在充能，不再重复开启
        if (chargingTasks.containsKey(p.getUniqueId())) return;

        // 1. 检查手持物品是否为激活的医旗
        ItemStack handItem = p.getInventory().getItemInMainHand();
        int slot = p.getInventory().getHeldItemSlot();

        WeaponManager.WeaponData wd = plugin.getPlayerManager().getWeaponManager()
                .checkActiveWeapon(p, handItem, slot);

        // 必须是有效的武器，且配置了回蓝属性 > 0
        if (wd == null || wd.manaRegen <= 0) return;
        // 必须是医旗 (可选检查，防止其他职业武器也回蓝，如果想让所有武器都支持Shift回蓝则去掉这行)
        if (!plugin.getMedicalManager().isMedicalBanner(handItem)) return;

        // 2. 发送开始提示
        p.sendMessage("§a[医术] §7你已开始凝聚灵力...");

        // 2. 开启循环任务 (每秒执行一次 = 20 ticks)
        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                // 安全检查：玩家掉线、死亡、不再潜行
                if (!p.isOnline() || p.isDead() || !p.isSneaking()) {
                    stopCharging(p);
                    return;
                }
                // 持续检查：手中物品是否还在？是否还是那把医旗？
                ItemStack currentItem = p.getInventory().getItemInMainHand();
                int currentSlot = p.getInventory().getHeldItemSlot();
                WeaponManager.WeaponData currentWd = plugin.getPlayerManager().getWeaponManager()
                        .checkActiveWeapon(p, currentItem, currentSlot);
                // 如果切换了物品，或者物品失效
                if (currentWd == null || currentWd.manaRegen <= 0) {
                    stopCharging(p);
                    return;
                }
                // === 执行效果 ===
                PlayerData data = plugin.getPlayerManager().getData(p.getUniqueId());
                if (data != null) {
                    // 1. 恢复灵力
                    double oldMana = data.getLingli();
                    data.addLingli(currentWd.manaRegen);
                    // 可选：提示 (防止刷屏，可以用 ActionBar)
                    // 1. 恢复灵力
                    data.addLingli(wd.manaRegen);
                    // 2. 获取数值用于显示 (假设 PlayerData 有 getMaxLingli 方法)
                    double currentLingli = data.getLingli();
                    double maxLingli = data.getMaxLingli(); // 使用你 PlayerData 里的方法
                    // 3. 发送 ActionBar
                    String barMsg = "§b☯ 当前灵力值：" + String.format("%.1f", currentLingli) + "/" + String.format("%.0f", maxLingli) + " ☯";
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(barMsg));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 25, 2, false, false, false));
                    // 特效 (绿色粒子环绕)
                    p.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, p.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0);
                    p.getWorld().spawnParticle(Particle.SPELL_MOB, p.getLocation().add(0, 0.5, 0), 0, 0, 1, 0, 1); // 绿色药水粒子
                }
            }
        }.runTaskTimer(plugin, 0L, 20L).getTaskId(); // 0延时，20tick(1秒)间隔

        chargingTasks.put(p.getUniqueId(), taskId);
    }

    private void stopCharging(Player p) {
        if (chargingTasks.containsKey(p.getUniqueId())) {
            int taskId = chargingTasks.remove(p.getUniqueId());
            plugin.getServer().getScheduler().cancelTask(taskId);
            p.sendMessage("§a[医术] §c你已停止凝聚灵力...");
        }
    }

    // === 拾取愈合花逻辑保持不变 ===
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        ItemStack item = e.getItem().getItemStack();
        if (item == null || !item.hasItemMeta()) return;

        NamespacedKey keyHeal = new NamespacedKey(plugin, YuHeHuaSpell.KEY_HEAL_AMOUNT);
        if (item.getItemMeta().getPersistentDataContainer().has(keyHeal, PersistentDataType.DOUBLE)) {
            e.setCancelled(true);
            e.getItem().remove();
            double heal = item.getItemMeta().getPersistentDataContainer().get(keyHeal, PersistentDataType.DOUBLE);
            double maxHealth = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
            double newHealth = Math.min(maxHealth, p.getHealth() + heal);
            p.setHealth(newHealth);
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 3, 0.3, 0.3, 0.3, 0.05);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);
            p.sendMessage("§d[医术] §7你拾取了愈合花，生命值恢复了 §a" + String.format("%.1f", heal) + " §7点！");
        }
    }
}