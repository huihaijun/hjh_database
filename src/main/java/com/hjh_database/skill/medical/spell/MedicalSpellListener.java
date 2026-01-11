package com.hjh_database.skill.medical.spell;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell;
import com.hjh_database.weapon.WeaponManager;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class MedicalSpellListener implements Listener {
    private final Hjh_database plugin;

    public MedicalSpellListener(Hjh_database plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        // 1. 基础过滤：必须是主手，必须是右键
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack mainHandItem = p.getInventory().getItemInMainHand();

        // 2. 判断主手拿的是不是医旗 (是否有 med_skill_id)
        if (mainHandItem == null || mainHandItem.getType() == Material.AIR) return;
        if (!plugin.getMedicalManager().isMedicalBanner(mainHandItem)) return;

        // 获取技能ID
        String skillId = plugin.getMedicalManager().getSkillIdFromBanner(mainHandItem);
        if (skillId == null) return;

        // 3. 【核心判定】检查快捷栏第一格 (Slot 0) 是否是激活的医旗
        ItemStack slot0Item = p.getInventory().getItem(0);
        if (!isValidActivatedMedicalBanner(p, slot0Item)) {
            // 如果第一格不是激活的医旗，不阻止交互(可能是当方块放)，但提示一下或者直接return
            // 这里为了体验，如果不满足激活条件，不做任何反应
            return;
        }

        // 4. 阻止原版交互 (防止把旗帜插地上)
        e.setCancelled(true);

        // 5. 检查玩家是否学会了该技能
        PlayerData data = plugin.getPlayerManager().getPlayerData(p);
        if (!data.getMedicalLoadout().contains(skillId)) {
            p.sendMessage("§c[释放失败] §7你虽然持有此旗，但并未真正掌握其中蕴含的医术！");
            return;
        }

        // 6. 交给 Manager 进行释放 (处理冷却、灵力、逻辑)
        plugin.getMedicalSpellManager().castSpell(p, skillId);
    }

    /**
     * 判断物品是否为有效的、已激活的医旗
     * 逻辑：必须是旗帜 + 必须是 WeaponManager 里的物品 + 必须满足职业/等级要求
     */
    private boolean isValidActivatedMedicalBanner(Player p, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;

        // 1. 必须是旗帜材质 (根据你的设定，医旗都是BANNER)
        if (!item.getType().name().endsWith("_BANNER")) return false;

        // 2. 获取 WeaponID
        NamespacedKey keyWeaponId = new NamespacedKey(plugin, "weapon_id"); // 假设这是你的 Weapon key
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String weaponId = meta.getPersistentDataContainer().get(keyWeaponId, PersistentDataType.STRING);

        if (weaponId == null) return false; // 不是 RPG 武器

        // 3. 获取武器数据并判断使用权
        WeaponManager.WeaponData wd = plugin.getPlayerManager().getWeaponManager().getWeaponData(weaponId);
        if (wd == null) return false;

        // 4. 判断职业和等级限制
        PlayerData data = plugin.getPlayerManager().getPlayerData(p);

        // 职业判定 (假设医师的 job ID 是 3)
        if (wd.reqJob != -1 && (data.getJob() == null || data.getJob() != wd.reqJob)) {
            return false;
        }

        // 等级判定
        if (data.getLv() < wd.reqLv) {
            return false;
        }

        return true;
    }

    // === 【新增】监听拾取愈合花 ===
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        ItemStack item = e.getItem().getItemStack();
        if (item == null || !item.hasItemMeta()) return;
        // 检查是否有愈合花的 NBT 标记
        NamespacedKey keyHeal = new NamespacedKey(plugin, YuHeHuaSpell.KEY_HEAL_AMOUNT);
        if (item.getItemMeta().getPersistentDataContainer().has(keyHeal, PersistentDataType.DOUBLE)) {
            e.setCancelled(true); // 【关键】阻止进入背包
            e.getItem().remove(); // 删除掉落物实体
            // 获取治疗量
            double heal = item.getItemMeta().getPersistentDataContainer().get(keyHeal, PersistentDataType.DOUBLE);
            // 执行治疗
            double maxHealth = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
            double newHealth = Math.min(maxHealth, p.getHealth() + heal);
            p.setHealth(newHealth);
            // 特效
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 3, 0.3, 0.3, 0.3, 0.05);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f);
            p.sendMessage("§d[医术] §7你拾取了愈合花，生命值恢复了 §a" + String.format("%.1f", heal) + " §7点！");
        }
    }
}