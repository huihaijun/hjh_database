package com.hjh_database.dungeon.zhenyao

/** One-shot milestones; catch-up cannot repeat damage or lose the grip milestone. */
internal class TowerSwordPickup(private val started: Long) {
    enum class Event { GRAB, HIT, FINISH }
    private val emitted=hashSetOf<Event>()
    fun update(now: Long): List<Event> = listOf(3L to Event.GRAB,7L to Event.HIT,12L to Event.FINISH)
        .filter { (tick,event) -> now-started>=tick && emitted.add(event) }.map { it.second }
}
