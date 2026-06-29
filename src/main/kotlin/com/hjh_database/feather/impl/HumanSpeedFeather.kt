package com.hjh_database.feather.impl

import com.hjh_database.data.PlayerData
import com.hjh_database.feather.FeatherBase
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlotGroup

class HumanSpeedFeather : FeatherBase {

    override val id: String = "hjh_xyzy" // 羽毛的资源ID
    override val cooldownSeconds: Int = 10

    // === 【1.21 修复】使用 NamespacedKey 替代 UUID ===
    // 注意：这里硬编码了 key，格式必须是 "namespace:key"
    private val key = NamespacedKey.fromString("hjh_database:feather_speed")!!

    // === 【1.21 修复】新的构造函数 ===
    // 参数变为：(NamespacedKey, amount, operation, slotGroup)
    private val speedModifier = AttributeModifier(
        key,
        0.5,
        AttributeModifier.Operation.ADD_SCALAR,
        EquipmentSlotGroup.ANY // 1.21 新增：指定该属性在任何槽位都有效
    )

    override fun canUse(player: Player, data: PlayerData): Boolean {
        val hasProofQuest = data.completedQuests.contains("main_ren_5") ||
                data.completedQuests.contains("main_yao_5")
        if (!hasProofQuest) {
            player.sendMessage("§c[提示] 请先完成前置任务后，再来使用新芽之羽。")
            return false
        }
        return true
    }

    override fun onStart(player: Player) {
        val attr = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return

        // === 【1.21 修复】直接通过 Key 移除旧的修饰符 ===
        // 1.21 API 允许直接 removeModifier(key)，非常方便
        if (attr.getModifier(key) != null) {
            attr.removeModifier(key)
        }

        // 添加加速
        attr.addModifier(speedModifier)

        player.sendMessage("§9你凭借新芽之羽，释放了技能——初飞！")
        player.playSound(player.location, Sound.ENTITY_BAT_TAKEOFF, 1f, 1.2f)
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 2.0f)
    }

    override fun onEnd(player: Player) {
        val attr = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return

        // === 【1.21 修复】通过 Key 检查和移除 ===
        if (attr.getModifier(key) != null) {
            attr.removeModifier(key)
            player.sendMessage("§c[羽毛] 加速效果已消失。")
            player.playSound(player.location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.5f)
        }
    }
}
