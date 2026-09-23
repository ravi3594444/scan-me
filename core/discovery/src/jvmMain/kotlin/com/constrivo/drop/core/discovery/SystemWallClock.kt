package com.constrivo.drop.core.discovery

/** [WallClock] on `System.currentTimeMillis()`, for production use on the JVM (Android and desktop). */
object SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
