package com.hjh_database.client

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.io.DataOutputStream
import java.util.UUID

/** Server owns animation clocks; model/clip names resolve from client resource packs. Main thread only. */
class ClientEntityModels(private val transmit: (Collection<Player>, (DataOutputStream) -> Unit) -> Unit) {
    private data class Layer(val clip: String, val start: Int, val sequence: Long, val loop: Boolean,
                             val blend: Int, val duration: Int, val mask: Set<String>)
    private data class Binding(val entity: LivingEntity, val model: String, var body: Layer,
                               val overlays: LinkedHashMap<String,Layer> = linkedMapOf(),
                               val viewers: MutableSet<UUID> = hashSetOf(), var detached: Location? = null,
                               var end: Int = Int.MAX_VALUE)
    private val bindings=linkedMapOf<UUID,Binding>()
    private var sequence=0L
    private var nextSnapshot=0
    private val now get()=Bukkit.getCurrentTick()

    fun attach(entity: LivingEntity, model: String, clip: String, loop: Boolean = true) {
        check(Bukkit.isPrimaryThread())
        require(model.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) && !model.contains(".."))
        require(bindings.size<64 || entity.uniqueId in bindings)
        remove(entity.uniqueId)
        val binding=Binding(entity,model,layer(clip,loop,0,0,emptySet()))
        bindings[entity.uniqueId]=binding; publish(binding)
    }
    /** Same clip does not restart. replay=true is an explicit new cast of the same animation. */
    fun play(entity: LivingEntity, clip: String, loop: Boolean = false, blendTicks: Int = 2, replay: Boolean = false, elapsedTicks: Int = 0) {
        val binding=bindings[entity.uniqueId]?:return
        if(!replay && binding.body.clip==clip && binding.body.loop==loop) return
        binding.body=layer(clip,loop,blendTicks,0,emptySet()).copy(start=now-elapsedTicks.coerceIn(0,72_000_000)); publish(binding)
    }
    /** Optional overlay replaces only the named bones; does not advance or interrupt the base skill. */
    fun overlay(entity: LivingEntity, slot: String, clip: String, durationTicks: Int, bones: Set<String>, blendTicks: Int = 2) {
        val binding=bindings[entity.uniqueId]?:return
        require(slot.length in 1..64 && bones.size in 1..32 && durationTicks in 1..1200)
        require(binding.overlays.size<3 || slot in binding.overlays)
        binding.overlays[slot]=layer(clip,false,blendTicks,durationTicks,bones); publish(binding)
    }
    /** A short client-only finale survives the real entity's death, then expires without a server entity. */
    fun finish(entity: LivingEntity, clip: String, durationTicks: Int) {
        val binding=bindings[entity.uniqueId]?:return
        require(durationTicks in 1..200)
        binding.detached=entity.location.clone(); binding.end=now+durationTicks; binding.overlays.clear()
        binding.body=layer(clip,false,0,durationTicks,emptySet()); publish(binding)
    }
    fun remove(entityId: UUID) {
        val b=bindings.remove(entityId)?:return
        transmit(b.viewers.mapNotNull(Bukkit::getPlayer)) { it.writeByte(14); it.uuid(entityId) }
    }
    fun tick() {
        val snapshot=now>=nextSnapshot
        if(snapshot) nextSnapshot=now+20
        for ((id,b) in bindings.toMap()) {
            if(now>=b.end || (b.detached==null && (!b.entity.isValid || b.entity.isDead))) { remove(id); continue }
            val changed=b.overlays.entries.removeIf { now-it.value.start>=it.value.duration }
            // Full snapshots repair late joins, tracking-range changes and dropped/old client state.
            if(changed || snapshot) publish(b)
        }
    }
    fun clear() { bindings.keys.toList().forEach(::remove) }
    private fun layer(clip: String,loop: Boolean,blend: Int,duration: Int,mask: Set<String>): Layer {
        require(clip.length in 1..160 && mask.all { it.length in 1..64 })
        return Layer(clip,now,++sequence,loop,blend.coerceIn(0,20),duration,mask)
    }
    private fun publish(b: Binding) {
        val loc=b.detached?:b.entity.location
        val targets=loc.world!!.players.filter { it.location.distanceSquared(loc)<=192.0*192.0 }
        val current=targets.map { it.uniqueId }.toSet()
        transmit((b.viewers-current).mapNotNull(Bukkit::getPlayer)) { it.writeByte(14);it.uuid(b.entity.uniqueId) }
        b.viewers.clear();b.viewers+=current
        transmit(targets) { out ->
            out.writeByte(13);out.writeByte(1);out.uuid(b.entity.uniqueId);out.writeInt(b.entity.entityId)
            out.writeUTF(b.model);out.writeInt(60);out.writeBoolean(b.detached!=null)
            out.writeDouble(loc.x);out.writeDouble(loc.y);out.writeDouble(loc.z);out.writeFloat(loc.yaw)
            out.writeFloat((b.entity.getAttribute(Attribute.SCALE)?.value?:1.0).toFloat())
            val layers=listOf("base" to b.body)+b.overlays.toList()
            out.writeByte(layers.size)
            for((slot,l) in layers) {
                out.writeUTF(slot);out.writeUTF(l.clip);out.writeLong(l.sequence);out.writeInt((now-l.start).coerceAtLeast(0))
                out.writeBoolean(l.loop);out.writeByte(l.blend);out.writeInt(l.duration)
                out.writeByte(l.mask.size);l.mask.forEach(out::writeUTF)
            }
        }
    }
    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits);writeLong(id.leastSignificantBits) }
}
