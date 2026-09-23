# platform/desktop-mac

CoreBluetooth and CoreWLAN through a small Swift helper process, Bonjour, menu bar (architecture §8, §10.2). WP10c.

Implements the `core` interfaces (`BeaconRadio`, `DataChannel`, `WifiLinkProvider`, `LanDiscovery`, `FileStore`,
`PowerPolicy`). Native helpers stay stateless and message-driven so the JVM engine stays authoritative.
