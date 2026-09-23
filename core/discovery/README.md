# core/discovery

Beacon encode/decode, capability flags, ephemeral IDs, mDNS record model, radar placement maths and the nearby-device
model (architecture §5, design §3.2–3.3). Built in WP1 of `docs/implementation-plan.md` with spec changes S3, S10, S11,
N4 and N6; the wire formats are summarised in the "Changed (WP1)" notes of architecture §5.1–5.4.

Package `com.constrivo.drop.core.discovery`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code in
`src/jvmMain` (only `SystemWallClock`). No Android or desktop-UI imports; `:tools:arch-test` enforces this. The only
crypto used is `CryptoProvider.sha256` / `hmacSha256` from `core/crypto`.

## What is where

| Need | API |
| --- | --- |
| What to advertise this epoch | `BeaconAdvertisement.create(crypto, kAdv, LocalBeaconState, carrier, now)`; restart at `validUntilMillis` (N4) |
| Platform payload pieces | `carrierPayload()`, `scanResponsePayload()`, `serviceUuid16`, `companyId`; raw bytes via `advertisingData()` / `scanResponseData()` |
| Parse a raw scan record | `BeaconSighting.fromAdvertisingData(bytes, rssi, address, at)` or `BeaconAdvertisements.parse(bytes)` |
| Parse per-UUID / per-company data (BlueZ, CoreBluetooth, WinRT) | `DropRecord.fromServiceData(uuid16, payload)`, `DropRecord.fromManufacturerData(companyId, data)` |
| Beacon body codec | `BeaconBody.encode()` / `BeaconBody.decode(bytes)` |
| Identifiers (placeholders, decision 5) | `AdvertisingFormat.SERVICE_UUID_16`, `SERVICE_UUID_128`, `COMPANY_ID`, `MANUFACTURER_MARKER` |
| Rotating IDs | `EphemeralIds.derive(crypto, kAdv, epoch)`, `epochAt`, `millisUntilNextEpoch` |
| Trusted resolution | `EphemeralIdResolver(crypto, peers, ownKAdv).resolve(id, now)` with `TrustedPeer(deviceId, kAdv, nickname)` |
| Network hint (N6) | `NetworkHint.derive(crypto, NetworkLinkInfo(gateways, dhcpServer, ipv6Addresses))` |
| mDNS TXT | `MdnsRecord.create(...)`, `toTxt()`, `toLanService(host)`, `MdnsRecord.fromTxt(map)` |
| Radar model | `NearbyDevices(crypto, clock, ownKAdv).run(sightings, lanEvents, trustedPeers)` → `devices: StateFlow<List<NearbyDevice>>` |
| Radar maths | `RssiSmoothing`, `RingThresholds.classify`, `RadarPlacement.layout(items, RadarGeometry.forViewport(w, h))` |

Every decoder throws only `DiscoveryFormatException` (or its subclass `UnsupportedBeaconVersionException`) for bad
input. Encoding something that must never go on air (a Hidden beacon, a nickname in Trusted-only mode) is an
`IllegalArgumentException`.

## Tests

`./gradlew :core:discovery:jvmTest`. Golden vectors were computed independently in Python (`hmac`, `hashlib`).
Pure logic is tested in `src/commonTest`; tests that need the JCA `CryptoProvider` are in `src/jvmTest`.
