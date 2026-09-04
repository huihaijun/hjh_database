package com.hjh_database.listener

import com.hjh_database.Hjh_database
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class DamageTestManager(private val plugin: Hjh_database) : Listener {

    companion object {
        private const val ITEM_MARKER_VALUE: Byte = 1
    }

    private data class DamageTrace(
        val lowestBaseDamage: Double,
        var calculatedOriginalDamage: Double? = null,
        var customArmorReduction: Double = 0.0
    )

    private val itemKey = NamespacedKey(plugin, "damage_test_device")
    private val enabledPlayers = HashSet<UUID>()

    /*
     * Bukkit伤害事件均在主线程分发。IdentityHashMap避免重写equals带来的碰撞，
     * 只保存当前正在分发的测试事件，MONITOR阶段立即删除。
     */
    private val traces = IdentityHashMap<EntityDamageEvent, DamageTrace>()

    fun createItem(): ItemStack {
        return ItemStack(Material.COMPASS).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§c§l受伤测试仪")
                lore = listOf(
                    "§7管理员专用伤害分析工具",
                    "§e主手右键 §f开启/关闭受伤反馈测试模式",
                    "§8记录护甲、抗性与技能等效果的减伤明细"
                )
                persistentDataContainer.set(itemKey, PersistentDataType.BYTE, ITEM_MARKER_VALUE)
                addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                setEnchantmentGlintOverride(true)
            }
        }
    }

    fun isTestItem(item: ItemStack?): Boolean {
        if (item?.type != Material.COMPASS) return false
        val meta = item?.itemMeta ?: return false
        return meta.persistentDataContainer.get(itemKey, PersistentDataType.BYTE) == ITEM_MARKER_VALUE
    }

    fun isTesting(playerId: UUID): Boolean = enabledPlayers.contains(playerId)

    /**
     * CombatListener在完成攻击方伤害、易伤等计算后调用，
     * beforeArmor即本次应进入护甲公式的“原伤害”。
     */
    fun recordArmorCalculation(event: EntityDamageEvent, beforeArmor: Double, afterArmor: Double) {
        if (enabledPlayers.isEmpty()) return
        val trace = traces[event] ?: return
        trace.calculatedOriginalDamage = beforeArmor
        trace.customArmorReduction = beforeArmor - afterArmor
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUseDevice(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        val player = event.player
        if (!isTestItem(player.inventory.itemInMainHand)) return

        event.isCancelled = true
        toggleTesting(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUseDeviceOnEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!isTestItem(player.inventory.itemInMainHand)) return

        event.isCancelled = true
        toggleTesting(player)
    }

    private fun toggleTesting(player: Player) {
        if (!player.isOp) {
            enabledPlayers.remove(player.uniqueId)
            player.sendMessage("§c只有管理员可以使用受伤测试仪。")
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.8f, 0.6f)
            return
        }

        if (enabledPlayers.remove(player.uniqueId)) {
            player.sendMessage("§c[受伤测试仪] §f受伤反馈测试模式已关闭。")
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 0.8f, 0.75f)
        } else {
            enabledPlayers.add(player.uniqueId)
            player.sendMessage("§a[受伤测试仪] §f受伤反馈测试模式已开启。")
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 0.8f, 1.35f)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun captureOriginalDamage(event: EntityDamageEvent) {
        if (enabledPlayers.isEmpty()) return
        val player = event.entity as? org.bukkit.entity.Player ?: return
        if (!enabledPlayers.contains(player.uniqueId)) return
        if (!player.isOp) {
            enabledPlayers.remove(player.uniqueId)
            return
        }
        traces[event] = DamageTrace(event.damage.coerceAtLeast(0.0))
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.MONITOR)
    fun reportFinalDamage(event: EntityDamageEvent) {
        if (enabledPlayers.isEmpty() && traces.isEmpty()) return
        val trace = traces.remove(event) ?: return
        val player = event.entity as? org.bukkit.entity.Player ?: return
        if (!player.isOp || !enabledPlayers.contains(player.uniqueId)) return

        val originalDamage = (trace.calculatedOriginalDamage ?: trace.lowestBaseDamage).coerceAtLeast(0.0)
        val vanillaArmorReduction = reductionFromModifier(event, EntityDamageEvent.DamageModifier.ARMOR)
        val armorReduction = normalize(trace.customArmorReduction + vanillaArmorReduction)
        val resistanceReduction = normalize(
            reductionFromModifier(event, EntityDamageEvent.DamageModifier.RESISTANCE)
        )
        val finalDamage = normalize(if (event.isCancelled) 0.0 else event.finalDamage.coerceAtLeast(0.0))

        // 剩余差额包含技能免伤、格挡、伤害吸收及其他插件/原版修正；负值代表发生了增伤。
        val otherReduction = normalize(originalDamage - armorReduction - resistanceReduction - finalDamage)

        player.sendMessage("§8§m---------------- §c§l受伤反馈 §8§m----------------")
        player.sendMessage("§f受到原伤害：§c${format(originalDamage)}")
        player.sendMessage("§f护甲减免：§b${format(armorReduction)}")
        player.sendMessage("§f抗性提升减免：§a${format(resistanceReduction)}")
        player.sendMessage("§f技能等其他效果减免：§d${format(otherReduction)}")
        player.sendMessage("§f最终受到的实际伤害：§e${format(finalDamage)}")
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        enabledPlayers.remove(event.player.uniqueId)
    }

    @Suppress("DEPRECATION")
    private fun reductionFromModifier(
        event: EntityDamageEvent,
        modifier: EntityDamageEvent.DamageModifier
    ): Double {
        return try {
            if (!event.isApplicable(modifier)) 0.0 else (-event.getDamage(modifier)).coerceAtLeast(0.0)
        } catch (_: IllegalArgumentException) {
            0.0
        }
    }

    private fun normalize(value: Double): Double {
        if (!value.isFinite() || abs(value) < 0.0005) return 0.0
        return value
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", normalize(value))
}
