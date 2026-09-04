package com.hjh_database.accessory.skill.warlock

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal object FollowingWindGeometry {
    fun contains(dx: Double, dy: Double, dz: Double, yawDegrees: Double): Boolean {
        val yaw = Math.toRadians(yawDegrees)
        val forwardX = -sin(yaw)
        val forwardZ = cos(yaw)
        val length = dx * forwardX + dz * forwardZ
        val width = dx * forwardZ - dz * forwardX
        val epsilon = 1.0E-8
        return abs(dy) <= 5.0 + epsilon && length >= -epsilon &&
            length <= 10.0 + epsilon && abs(width) <= 4.0 + epsilon
    }
}
