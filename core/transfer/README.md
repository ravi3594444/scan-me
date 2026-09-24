# core/transfer

The transfer engine (architecture §7.4–7.6, §9; WP4 of `docs/implementation-plan.md`): secure sessions over the WP2
handshake, the sender and receiver sides, head start over Bluetooth and the hop to Wi-Fi, acks, heartbeats, resume,
the throughput meter, and the platform-neutral file store contract.

Package `com.constrivo.drop.core.transfer`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this. The
module depends on `core/protocol` and `core/crypto` only: the ladder adapter (`LadderTransferBridge`) lives in
`core/ladder`, and the app adapts `core/data` to `ResumeStore`.

| Package | What |
| --- | --- |
| `session` | `SessionHandshake` (handshake plus the Finished exchange), `SecureSession` and `SecureConnection` (`StreamOpen` streams, S7), `CipherFrameProtector` (64 KiB AEAD blocks), `EndpointDialer` |
| `engine` | `TransferEngine` (`send`, `receive`, `IncomingTransfer`, `Transfer`), `EngineConfig`, `TransferProgress`, the per-transfer actor and both sides |
| `send` | `OfferBuilder` (N12 summary offer), the work queue, the unit reader (chunks and S4 bundles) |
| `receive` | `ResumeStore` and `UnitManifest` (N5), `FileNameSanitizer`, `FileTypes` |
| `flow` | `ThroughputMeter`, `StreamCountPolicy`, `AckBatcher`, the arrival meter for the ladder's LAN check |
| `hash` | XXH3-128 per frame and unit, streaming SHA-256 per file |
| `net` (JVM) | `TcpDataChannel`, `TcpListener`, `TcpSocketFactory` |
| `store` (JVM) | `DirectoryFileStore`: partials, publishing, per-drop subfolders |

Tests: `./gradlew :core:transfer:check` runs the unit and integration tests (`jvmTest`) and the memory budget test in
its own 320 MiB JVM (`jvmMemoryTest`). `-Pdrop.nightly=true` scales them up: 5,000 small files, 1 GiB for the
throughput floor, 2 GiB for the memory budget.
