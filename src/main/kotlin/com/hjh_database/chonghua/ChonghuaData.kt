package com.hjh_database.chonghua

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class ChonghuaData(val uuid: UUID, var playerName: String) {
    // 已解锁的打卡点ID
    var unlockedWaypoints: MutableSet<String> = HashSet()
    // 打卡点传送冷却 (ID -> 上次传送时间戳)
    var waypointCooldowns: MutableMap<String, Long> = HashMap()

    fun getUnlockedAsJson(): String {
        return if (unlockedWaypoints.isEmpty()) "[]" else Gson().toJson(unlockedWaypoints)
    }

    fun setUnlockedFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "[]" || json == "null") {
            unlockedWaypoints = HashSet()
            return
        }
        try {
            val type = object : TypeToken<Set<String>>() {}.type
            unlockedWaypoints = Gson().fromJson(json, type)
        } catch (e: Exception) {
            unlockedWaypoints = HashSet()
        }
    }

    fun getCooldownsAsJson(): String {
        return if (waypointCooldowns.isEmpty()) "{}" else Gson().toJson(waypointCooldowns)
    }

    fun setCooldownsFromJson(json: String?) {
        if (json.isNullOrEmpty() || json == "{}" || json == "null") {
            waypointCooldowns = HashMap()
            return
        }
        try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            waypointCooldowns = Gson().fromJson(json, type)
        } catch (e: Exception) {
            waypointCooldowns = HashMap()
        }
    }
}