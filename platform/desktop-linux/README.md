# platform/desktop-linux

BlueZ and NetworkManager over D-Bus, Avahi, tray (architecture §8, §10.2). WP10d.

Implements the `core` interfaces (`BeaconRadio`, `DataChannel`, `WifiLinkProvider`, `LanDiscovery`, `FileStore`,
`PowerPolicy`). Native helpers stay stateless and message-driven so the JVM engine stays authoritative.
