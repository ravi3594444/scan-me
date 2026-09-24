# platform/android

The Android platform layer (architecture §8, §10.1, §11): BLE advertise/scan, the Bluetooth handshake channel,
capability detection, identity and crypto; later WP7 packages add Wi-Fi Direct, the hotspot, NSD, MediaStore, the
foreground service and the thermal listener. Package `com.constrivo.drop.platform.android`. Everything here is plain
classes that the transfer service (WP7e) owns; nothing starts by itself.

| Package | What (WP7a, WP7b) |
| --- | --- |
| `ble` | `AndroidBeaconRadio` (core `BeaconRadio`: advertising sets bounded to their epoch, shared filtered scan), `AdvertisingSets` (cancellation-safe set bookkeeping), `AdvertisingPlan`, `ScanPlan`, `ScanScheduler` (5 starts per 30 s, downgrade hysteresis), `ScanRecordAdapter`, `BluetoothPowerMonitor`, `BeaconRadioState` |
| `bluetooth` | `BluetoothChannelConnector` (L2CAP → GATT stream, RFCOMM toward desktops), `BluetoothChannelListener` (GATT server + L2CAP/RFCOMM servers), `SocketStreamChannel`, `DropBluetoothProfile` (UUIDs, `ChannelInfo`) |
| `bluetooth.gatt` | `GattStreamChannel` and its segment format (`GattSegments`: credits, sequence numbers), `GattClientLink`, `GattServerHost` and its routing (`GattServerRouting`) |
| `capability` | `AndroidCapabilityDetector` (`StateFlow<LocalRadioFacts>`), `CapabilityMapping` (pure F-A4 mapping), `NetworkLinkExtraction` (N6 hint inputs) |
| `crypto` | `AndroidCryptoProvider` (JCA + BouncyCastle fallback for X25519/Ed25519/ChaCha20-Poly1305), `AesHardwareProbe`, `AndroidSecretStorage` + `KeystoreSecretCipher`, `AndroidSecrets` |
| `discovery` | `AndroidDiscoveryController` (radar, service and transfer hooks; epoch restarts), `DiscoveryPolicy` |
| `permission` | `RadioPermissions` / `RadioPermissionState` ("radar open"), `RadioEnableIntents` (F-A6) |

Tests: `./gradlew :platform:android:testDebugUnitTest` runs the JVM tests of every pure piece plus the session handshake
and the transfer engine of `core/transfer` over the L2CAP/RFCOMM and GATT channel adapters (in-memory links).
`src/androidTest` holds device tests for the lab (Keystore, the crypto probe, capability detection, the radio); they
compile in CI and run on the lab phones. Radio behaviour, throughput and the per-OEM results are lab work (WP7 in
`docs/implementation-plan.md`).
