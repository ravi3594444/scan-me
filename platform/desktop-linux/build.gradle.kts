plugins {
    alias(libs.plugins.drop.jvm.library)
}

// Linux: the shared desktop layer (JmDNS, the LAN rung, directories, secrets, database, DesktopNode) plus what only
// Linux has. WP10a adds the XDG autostart entry; BlueZ, NetworkManager and Avahi over D-Bus follow in WP10d.
dependencies {
    api(project(":platform:desktop-common"))
}
