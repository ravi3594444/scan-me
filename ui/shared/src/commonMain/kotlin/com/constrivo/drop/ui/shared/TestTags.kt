package com.constrivo.drop.ui.shared

/** Test tags for UI tests and screenshot checks; never shown to users. */
object TestTags {
    const val RADAR = "radar"
    const val RADAR_BOTTOM_BAR = "radar.bottomBar"
    const val RADAR_NOTICE = "radar.notice"
    const val RADAR_TRAY = "radar.tray"
    const val RADAR_BANNER = "radar.banner"
    const val RADAR_CHIP = "radar.visibilityChip"
    const val RADAR_OVERFLOW = "radar.overflow"
    const val RADAR_SELF = "radar.self"
    const val PROGRESS_ANNOUNCER = "radar.progressAnnouncer"
    const val INCOMING_CARD = "incoming.card"
    const val SHEET = "sheet"
    const val PICKER = "picker"
    const val DASHBOARD = "dashboard"
    const val ONBOARDING = "onboarding"
    const val STATS_CHART = "stats.chart"

    fun bubble(key: String): String = "radar.bubble.$key"

    fun cancel(key: String): String = "radar.cancel.$key"
}
