package com.hjh_database.skill.medical.spell

import com.hjh_database.Hjh_database
import com.hjh_database.skill.medical.spell.impl.YuHeHuaSpell
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

class MedicalSpellListener(private val plugin: Hjh_database) : Listener {
    // 用于记录正在“运气调息”的玩家
    private val chargingTasks: MutableMap<UUID, Int> = HashMap()

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        // 1. 基础过滤（保持不变）
        if (e.hand != EquipmentSlot.HAND) return
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        val p = e.player
        val mainHandItem = p.inventory.itemInMainHand
        // 2. 检查手中是否是医旗（只要是医旗就行，不管有没有激活）
        // 只有是医旗，才有资格去判断是否要释放技能
        if (!plugin.medicalManager.isMedicalBanner(mainHandItem)) return
        // 获取手中旗帜上的技能ID
        val skillId = plugin.medicalManager.getSkillIdFromBanner(mainHandItem)
        if (skillId == null) return

        // 【新增】如果玩家正在潜行，或正在运气调息的列表中，禁止释放医术
        if (p.isSneaking || chargingTasks.containsKey(p.uniqueId)) {
            p.sendMessage("§c[释放失败] §7运气调息时须全神贯注，无法分神施展医术！")
            return
        }

        // 3. 【核心逻辑优化】检查“激活位（第0格）”是否有合法的医旗
        // 无论你手里拿的是第几格的旗子，我们只看第0格有没有“医师资格”
        val activeSlotItem = p.inventory.getItem(0) // 获取快捷栏第一格物品

        // 使用 Manager 检查第0格的物品是否激活（符合职业、等级、必须在 slot 0）
        val activeWd = plugin.playerManager.weaponManager
            .checkActiveWeapon(p, activeSlotItem, 0)

        // 如果第0格不是激活的武器，或者第0格虽然激活了但不是医旗 -> 禁止施法
        if (activeWd == null || !plugin.medicalManager.isMedicalBanner(activeSlotItem)) {
            // 这里可以不发消息（静默失败），或者提示玩家“请先在第一格装备已激活的医旗”
            return
        }

        e.isCancelled = true
        // 4. 检查是否学会（保持不变）
        // 【关键】使用 !! 断言
        val data = plugin.playerManager.getPlayerData(p)!!

        if (!data.getMedicalLoadout().contains(skillId)) {
            p.sendMessage("§c[释放失败] §7你虽然持有此旗，但并未真正掌握其中蕴含的医术！")
            return
        }
        // 5. 释放技能（保持不变）
        plugin.medicalSpellManager.castSpell(p, skillId)
    }

    /**
     * 【新增功能】监听潜行（Shift）事件来实现“长按/运气”回蓝
     */
    @EventHandler
    fun onSneak(e: PlayerToggleSneakEvent) {
        val p = e.player

        // 如果玩家开始潜行 (isSneaking 返回 true 代表当前状态是"正在变为潜行")
        if (e.isSneaking) {
            startCharging(p)
        } else {
            // 停止潜行，取消任务
            stopCharging(p)
        }
    }

    private fun startCharging(p: Player) {
        // 如果已经在充能，不再重复开启
        if (chargingTasks.containsKey(p.uniqueId)) return

        // 1. 检查手持物品是否为激活的医旗
        val handItem = p.inventory.itemInMainHand
        val slot = p.inventory.heldItemSlot

        val wd = plugin.playerManager.weaponManager
            .checkActiveWeapon(p, handItem, slot)

        // 必须是有效的武器，且配置了回蓝属性 > 0
        if (wd == null || wd.manaRegen <= 0) return
        // 必须是医旗 (可选检查，防止其他职业武器也回蓝，如果想让所有武器都支持Shift回蓝则去掉这行)
        if (!plugin.medicalManager.isMedicalBanner(handItem)) return

        // 2. 发送开始提示
        p.sendMessage("§a[医术] §7你已开始凝聚灵力...")

        // 2. 开启循环任务
        val taskId = object : BukkitRunnable() {
            override fun run() {
                // 安全检查：玩家掉线、死亡、不再潜行
                if (!p.isOnline || p.isDead || !p.isSneaking) {
                    stopCharging(p)
                    return
                }
                // 持续检查：手中物品是否还在？是否还是那把医旗？
                val currentItem = p.inventory.itemInMainHand
                val currentSlot = p.inventory.heldItemSlot
                val currentWd = plugin.playerManager.weaponManager
                    .checkActiveWeapon(p, currentItem, currentSlot)

                // 如果切换了物品，或者物品失效
                if (currentWd == null || currentWd.manaRegen <= 0) {
                    stopCharging(p)
                    return
                }
                // === 执行效果 ===
                // 【关键】使用 !! 断言
                val data = plugin.playerManager.getData(p.uniqueId)!!

                // 1. 恢复灵力
                data.addLingli(currentWd.manaRegen)

                // 2. 获取数值用于显示 (假设 PlayerData 有 getMaxLingli 方法)
                val currentLingli = data.lingli
                val maxLingli = data.maxLingli // 使用你 PlayerData 里的方法

                // 3. 发送 ActionBar
                val barMsg = "§b☯ 当前灵力值：" + String.format("%.1f", currentLingli) + "/" + String.format("%.0f", maxLingli) + " ☯"
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(barMsg))
                p.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 25, 2, false, false, false))
                // 特效 (绿色粒子环绕)
                p.world.spawnParticle(Particle.HAPPY_VILLAGER, p.location.add(0.0, 1.0, 0.0), 3, 0.3, 0.5, 0.3, 0.0)
                p.world.spawnParticle(Particle.SPLASH, p.location.add(0.0, 0.5, 0.0), 0, 0.0, 1.0, 0.0, 1.0) // 绿色药水粒子
            }
        }.runTaskTimer(plugin, 40L, 40L).taskId // 2s延时，40tick(2秒)间隔

        chargingTasks[p.uniqueId] = taskId
    }

    private fun stopCharging(p: Player) {
        if (chargingTasks.containsKey(p.uniqueId)) {
            val taskId = chargingTasks.remove(p.uniqueId)!!
            plugin.server.scheduler.cancelTask(taskId)
            p.sendMessage("§a[医术] §c你已停止凝聚灵力...")
        }
    }

    // === 拾取愈合花逻辑保持不变 ===
    @EventHandler
    fun onPickup(e: EntityPickupItemEvent) {
        if (e.entity !is Player) return
        val p = e.entity as Player
        val item = e.item.itemStack
        if (!item.hasItemMeta()) return

        val keyHeal = NamespacedKey(plugin, YuHeHuaSpell.KEY_HEAL_AMOUNT)
        // 【关键】使用 !! 断言 meta 非空
        if (item.itemMeta!!.persistentDataContainer.has(keyHeal, PersistentDataType.DOUBLE)) {
            e.isCancelled = true
            e.item.remove()
            val heal = item.itemMeta!!.persistentDataContainer.get(keyHeal, PersistentDataType.DOUBLE)!!
            val maxHealth = p.getAttribute(Attribute.MAX_HEALTH)!!.value
            val newHealth = (p.health + heal).coerceAtMost(maxHealth)
            p.health = newHealth
            p.world.spawnParticle(Particle.HEART, p.location.add(0.0, 2.0, 0.0), 3, 0.3, 0.3, 0.3, 0.05)
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
            p.sendMessage("§d[医术] §7你拾取了愈合花，生命值恢复了 §a" + String.format("%.1f", heal) + " §7点！")
        }
    }
}