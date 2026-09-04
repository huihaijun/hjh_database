package com.hjh_database.warehouse.utils

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

object ItemSerializer {
    fun itemsToBase64(items: Array<ItemStack?>): String {
        try {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)
            dataOutput.writeInt(items.size)
            for (item in items) {
                dataOutput.writeObject(item)
            }
            dataOutput.close()
            return Base64.getEncoder().encodeToString(outputStream.toByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("无法序列化物品", e)
        }
    }

    fun base64ToItems(data: String): Array<ItemStack?> {
        try {
            val inputStream = ByteArrayInputStream(Base64.getDecoder().decode(data))
            val dataInput = BukkitObjectInputStream(inputStream)
            val size = dataInput.readInt()
            val items = arrayOfNulls<ItemStack>(size)
            for (i in 0 until size) {
                items[i] = dataInput.readObject() as ItemStack?
            }
            dataInput.close()
            return items
        } catch (e: Exception) {
            throw IllegalStateException("无法反序列化物品", e)
        }
    }
}