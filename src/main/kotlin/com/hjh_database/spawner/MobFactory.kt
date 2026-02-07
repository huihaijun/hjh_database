package com.hjh_database.spawner

import com.google.gson.Gson
import com.hjh_database.Hjh_database
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

object MobFactory {

    private val KEY_MOB_ID = NamespacedKey.fromString("hjh_database:mob_id")!!
    // 对接 CombatListener 的 Key
    private val KEY_MOB_ARMOR = NamespacedKey.fromString("hjh_database:hjh_mob_armor")!!
    private val KEY_MOB_AFFIXES = NamespacedKey.fromString("hjh_database:mob_affixes")!!
    // ★★★ 新增：掉落物数据的 Key ★★★
    val KEY_MOB_DROPS = NamespacedKey.fromString("hjh_database:mob_drops")!!
    private val gson = Gson() // 用于序列化掉落物列表

    fun spawnMob(location: Location, data: SpawnerData): LivingEntity {
        val world = location.world ?: throw IllegalArgumentException("Location world is null")
        val entity = world.spawnEntity(location, data.mobType) as LivingEntity

        // --- 1. 处理属性数值 (先计算词缀加成) ---
        var finalSpeed = data.speed

        // 【神速的】：速度 x 1.4
        if (data.affixes.contains(MobAffix.SPEED)) {
            finalSpeed *= 1.4
        }

        // --- 2. 应用基础属性 ---
        applyAttribute(entity, Attribute.MAX_HEALTH, data.health)
        entity.health = data.health
        applyAttribute(entity, Attribute.ATTACK_DAMAGE, data.damage)
        applyAttribute(entity, Attribute.MOVEMENT_SPEED, finalSpeed)

        // 护甲写入 PDC，原版护甲清零
        entity.persistentDataContainer.set(KEY_MOB_ARMOR, PersistentDataType.DOUBLE, data.armor)
        applyAttribute(entity, Attribute.ARMOR, 0.0)

        // --- 3. 处理静态词缀效果 ---

        // 【千斤的】：防击退
        if (data.affixes.contains(MobAffix.HEAVY)) {
            applyAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, 1.0)
        }

        // 【燃烧的】：自身抗火 + 视觉效果
        if (data.affixes.contains(MobAffix.BURNING)) {
            entity.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 999999, 0, false, false))
            entity.isVisualFire = true
        }

        // --- 4. 名字拼接 (格式：&c词缀 &c名字，中间空格) ---
        val rawName = ChatColor.translateAlternateColorCodes('&', data.mobName)

        if (data.affixes.isNotEmpty()) {
            val prefixBuilder = StringBuilder()
            // 这里只取第一个词缀做前缀，或者你可以遍历所有词缀
            // 现在的需求是： "神速的 僵尸"
            val affixName = data.affixes.first().displayName

            // 格式：红色词缀 + 空格 + 红色名字
            entity.customName = "§c$affixName §c$rawName"
        } else {
            entity.customName = rawName
        }

        entity.isCustomNameVisible = true

        // --- 5. 设置装备 ---
        setupEquipment(entity, data)

        // --- 6. 存数据 ---
        entity.addScoreboardTag("panling")
        entity.addScoreboardTag("monster")
        entity.addScoreboardTag("hjh_mob_id:${data.internalId}")
        entity.persistentDataContainer.set(KEY_MOB_ID, PersistentDataType.STRING, data.internalId)

        if (data.affixes.isNotEmpty()) {
            val affixStr = data.affixes.joinToString(",") { it.id }
            entity.persistentDataContainer.set(KEY_MOB_AFFIXES, PersistentDataType.STRING, affixStr)
        }

        // ★★★ 核心修复：写入掉落物数据 ★★★
        if (data.drops.isNotEmpty()) {
            // 将 List<MobDrop> 转为 JSON 字符串存入实体
            val dropsJson = gson.toJson(data.drops)
            entity.persistentDataContainer.set(KEY_MOB_DROPS, PersistentDataType.STRING, dropsJson)
        }

        return entity
    }

    private fun setupEquipment(entity: LivingEntity, data: SpawnerData) {
        // 1. 获取实体装备栏，如果没有(如史莱姆)则跳过
        val equip = entity.equipment ?: return

        // 2. 清空原版默认装备
        equip.clear()

        // 3. ★★★ 核心修复：使用新的 Base64 数据加载装备 ★★★
        val equipmentMap = data.getEquipmentMap() // 调用 SpawnerData 中的新方法

        equipmentMap.forEach { (slot, item) ->
            if (item != null && item.type != Material.AIR) {
                equip.setItem(slot, item)
                // 设置掉落概率为 0 (装饰用)
                equip.setDropChance(slot, 0f)
            }
        }
    }

    private fun applyAttribute(entity: LivingEntity, attribute: Attribute, value: Double) {
        entity.getAttribute(attribute)?.baseValue = value
    }

    private fun createDisplayItem(matName: String?): ItemStack? {
        if (matName.isNullOrEmpty()) return null
        val mat = Material.matchMaterial(matName) ?: return null
        return ItemStack(mat)
    }
}