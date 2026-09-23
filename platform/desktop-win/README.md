# platform/desktop-win

WinRT BLE (manufacturer-data beacon carrier), RFCOMM, Windows.Devices.WiFiDirect client, WLAN join, JmDNS, tray (architecture §8, §10.2). WP10b.

Implements the `core` interfaces (`BeaconRadio`, `DataChannel`, `WifiLinkProvider`, `LanDiscovery`, `FileStore`,
`PowerPolicy`). Native helpers stay stateless and message-driven so the JVM engine stays authoritative.
