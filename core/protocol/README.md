# core/protocol

Framing, CBOR control messages, chunk and bundle layouts, and the transfer state machine (architecture §7.1–7.3,
§7.7–7.8, with spec changes S1, S2, S4–S8, N2, N12, N13). Built in WP3 of `docs/implementation-plan.md`.

Package `com.constrivo.drop.core.protocol`. Kotlin Multiplatform with a JVM target; everything is in `src/commonMain`
(no JVM-only code). No Android or desktop-UI imports; `:tools:arch-test` enforces this.

| Area | Entry points |
| --- | --- |
| Frames (§7.1) | `FrameType`, `FrameCodec`, `FrameLimits`, `FrameReader`, `FrameWriter`, `FrameAad` |
| Protection seam | `FrameProtector` (implemented in `core/transfer` over `core/crypto`), `PlaintextFrameProtector` (tests), `writeControl` / `openControl`, `writeChunk` / `openChunk`, `StreamOpenFrame`, `StreamIdAllocator` |
| Control messages (§7.2) | `ControlMessage` and its 15 subtypes, `ControlCodec` (the single encode/decode entry point), `FileListPager`, `FileListAssembler`, `MimeHistogram` |
| Data (§7.3) | `ChunkHeader`, `ChunkFrame`, `BundlePlan`, `BundleIndex`, `ChunkPlan`, `TransferLayout` (units, tracking keys, `Resume` conversion) |
| State machine (§7.7–7.8) | `TransferStateMachine.reduce(state, event, nowMillis)`, `TransferEvent`, `TransferEffect`, `TransferPhase` |
| Test support | `InMemoryDataChannel.pair(...)` with `ReadChunking` for partial reads |

Every decoder turns bad input into `ProtocolException` (never another exception type) and allocates at most the
limits in `ProtocolConstants`; `tools/fuzz` checks this. Golden wire bytes for every message are in
`src/commonTest/kotlin/.../golden/GoldenVectors.kt`; changing one is a wire-format change.
