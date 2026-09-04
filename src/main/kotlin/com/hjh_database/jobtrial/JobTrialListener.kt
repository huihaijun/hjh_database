package com.hjh_database.jobtrial

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType

class JobTrialListener(private val plugin: Hjh_database) : Listener {

    // 对应 CombatListener 里的护甲Key
    private val armorKey = NamespacedKey(plugin, "hjh_mob_armor")

    // === 1. 按钮刷怪 ===
    @EventHandler
    fun onPressButton(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.STONE_BUTTON) return

        if (block.location == WarriorTrialData.BUTTON_LOC) {
            val world = block.world

            // 清理旧的测试僵尸 (防止堆积)
            world.getNearbyEntities(WarriorTrialData.ZOMBIE_SPAWN_LOC, 3.0, 3.0, 3.0)
                .filterIsInstance<Zombie>()
                .filter { it.scoreboardTags.contains("trial_mob") }
                .forEach { it.remove() }

            // 生成僵尸
            val entity = world.spawnEntity(WarriorTrialData.ZOMBIE_SPAWN_LOC, EntityType.ZOMBIE) as Zombie

            // --- 基础设置 ---
            entity.customName = "§c僵尸" // 红色名字
            entity.isCustomNameVisible = true
            entity.setAI(true) // 确保有AI

            // --- 数值设置 (参考 MobFactory) ---
            // 1. 血量: 20
            val maxHealth = 20.0
            entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
            entity.health = maxHealth

            // 2. 攻击力: 5
            entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 5.0

            // 3. 护甲: 0 (属性 + PDC)
            entity.getAttribute(Attribute.ARMOR)?.baseValue = 0.0
            entity.persistentDataContainer.set(armorKey, PersistentDataType.DOUBLE, 0.0)

            // --- 标签设置 ---
            entity.addScoreboardTag("panling")
            entity.addScoreboardTag("monster")
            entity.addScoreboardTag("trial_mob") // 用于识别这是试炼怪，死后不给经验
        }
    }

    // === 2. 死亡处理 (经验归零) ===
    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        val entity = event.entity
        // 只有带有 trial_mob 标签的怪才处理
        if (entity.scoreboardTags.contains("trial_mob")) {
            // 1. 原版掉落经验设为 0
            event.droppedExp = 0
            // 2. 清空掉落物 (可选，保持场地整洁)
            event.drops.clear()

        }
    }
}