package com.constrivo.drop.core.discovery

/**
 * Every place the (still undecided) app name reaches the wire or the file system (PRD §10, decision 1).
 * Renaming the app is this file plus the platform manifests and installers.
 */
object AppIdentity {
    const val CODE_NAME = "drop"
    const val DISPLAY_NAME = "Drop"
    const val PACKAGE = "com.constrivo.drop"

    /** DNS-SD service type for the LAN path (architecture §5.4). */
    const val MDNS_SERVICE_TYPE = "_drop._tcp"

    /** Host name the browser receive page is served under (architecture §10.3). */
    const val MDNS_HOST = "drop.local"

    /** Desktop default receive folder, relative to the user's home directory (architecture §10.2). */
    const val RECEIVED_FOLDER = "Received/Drop"

    /** Prefix of the temporary hotspot SSID shown in the "computer without the app" hint (design §4.4). */
    const val HOTSPOT_SSID_PREFIX = "DROP-"
}
