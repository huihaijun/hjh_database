package com.hjh_database.dungeon.zhenyao

/** This adapter follows the existing combat phases; it never schedules damage or movement. */
internal data class ChiyouAnimationFrame(val clip: String,val started: Long,val loop: Boolean = false,val blend: Int = 0)

internal class ChiyouAnimation(private val f: ZhenyaoFinale) {
    private var attached=false
    private var previous: ChiyouAnimationFrame?=null
    private var victory=false
    private var nextLook=0L
    private val models get()=f.plugin.clientBridge.entityModels
    fun tick() {
        val mob=f.boss?:return
        if(f.closed) return
        if(!attached) {
            if(!mob.isValid || mob.isDead) return
            models.attach(mob,"hjh_mod:chiyou","animation.chiyou.idle_ground")
            attached=true
        }
        if(f.mode==ZhenyaoFinale.Mode.VICTORY) {
            if(!victory) { models.finish(mob,"animation.chiyou.death_dissolve",60);victory=true }
            return
        }
        val start=f.now-f.elapsed
        fun frame(clip: String,offset: Long=0,loop: Boolean=false)=ChiyouAnimationFrame(clip,start+offset,loop)
        val next=when(f.mode) {
            ZhenyaoFinale.Mode.INTRO -> if(f.elapsed<410) frame("intro_materialize",310) else frame("idle_ground",410,true)
            ZhenyaoFinale.Mode.NORMAL,ZhenyaoFinale.Mode.EYES,ZhenyaoFinale.Mode.ORBS -> f.normalAnimation()
            ZhenyaoFinale.Mode.YIN_PREP -> if(f.elapsed<80) frame("flight_up",loop=true) else frame("yin_summon",80)
            ZhenyaoFinale.Mode.YIN -> if(f.now-f.yinAnimationSwap in 0L..11L)
                ChiyouAnimationFrame("yin_swap",f.yinAnimationSwap) else frame("yin_channel",loop=true)
            ZhenyaoFinale.Mode.YIN_RETURN -> frame("flight_down",loop=true)
            ZhenyaoFinale.Mode.YIN_REST -> frame("landing_rest")
            ZhenyaoFinale.Mode.EYE_PREP -> frame("eyes_summon")
            ZhenyaoFinale.Mode.WEAK -> when {
                f.elapsed < 10 -> frame("eyes_backlash")
                f.elapsed < 80 -> frame("weak_loop",10,true)
                else -> frame("weak_recover",80)
            }
            ZhenyaoFinale.Mode.COLLAPSE_MOVE -> frame("flight_up",loop=true)
            ZhenyaoFinale.Mode.COLLAPSE_SPEECH -> frame("collapse_speech")
            ZhenyaoFinale.Mode.COLLAPSE_BREAK -> frame("collapse_channel")
            ZhenyaoFinale.Mode.COLLAPSE_CENTER -> frame("flight_to_center",loop=true)
            ZhenyaoFinale.Mode.COLLAPSE_REST -> frame("phase_two_awaken")
            ZhenyaoFinale.Mode.RIFT_PREP -> frame("rift_horizontal")
            ZhenyaoFinale.Mode.RIFT -> when {
                f.elapsed<40 -> frame("rift_vertical")
                f.elapsed<80 -> frame("rift_diagonal",40)
                else -> f.normalAnimation()
            }
            ZhenyaoFinale.Mode.ORB_PREP -> frame("souls_summon")
            ZhenyaoFinale.Mode.SWORD_PREP -> if(f.swordsPrepared()) frame("sword_summon") else frame("flight_to_center",loop=true)
            ZhenyaoFinale.Mode.SWORD_THROW -> frame("sword_guide")
            ZhenyaoFinale.Mode.SWORD_DASH -> frame("sword_dash",loop=true)
            ZhenyaoFinale.Mode.SWORD_PICKUP -> frame(if(f.lastSword()) "sword_pickup_slash" else "sword_pickup_next")
            ZhenyaoFinale.Mode.VICTORY -> return
        }
        // Locomotion loops keep their phase when a combat mode changes without changing gait.
        val stable=next.loop && previous?.loop==true && previous?.clip==next.clip
        if(next!=previous && !stable) {
            models.play(mob,"animation.chiyou.${next.clip}",next.loop,next.blend,true,(f.now-next.started).coerceAtLeast(0).toInt())
            previous=next
        }
        if (f.mode==ZhenyaoFinale.Mode.YIN && f.now>=nextLook) {
            f.yinLookTarget()?.let { target ->
                val delta=target.eyeLocation.toVector().subtract(mob.eyeLocation.toVector())
                val pitch=Math.toDegrees(kotlin.math.atan2(-delta.y,kotlin.math.hypot(delta.x,delta.z)))
                val bucket=(kotlin.math.round(pitch/10)*10).toInt().coerceIn(0,60)
                models.overlay(mob,"yin_gaze","animation.chiyou.yin_look_$bucket",8,setOf("head"),3)
            }
            nextLook=f.now+4
        }
    }
    fun absorb() {
        val mob=f.boss?:return
        if(!attached) return
        // Only head/torso: arms retain their active spell or sword pose.
        models.overlay(mob,"absorb","animation.chiyou.absorb_flood",30,setOf("torso","head"),2)
    }
    fun close() { f.boss?.let { models.remove(it.uniqueId) };attached=false }
}
