# Developer Handoff

**Version:** 0.1 draft · **Date:** 23 Sep 2026 · **For:** the Phase 1 engineering team (Android, desktop, QA)

This file tells a new engineer what has been decided, what is still assumed, how to set up, what to build in
the first week, and what "done" means. Read `PRD.md`, `design.md` and `architecture.md` first.

## 1. Decided

| Topic | Decision |
| --- | --- |
| Minimum Android | Android 12 (API 31); target the latest stable SDK |
| First release | Android app **and** desktop app (Windows, macOS, Linux); browser receive page; no iPhone yet |
| Connectivity model | Offline-first. BLE discovery + handshake; Wi‑Fi Direct 5 GHz for payload; hotspot and LAN as alternatives; Bluetooth as slow fallback; no server |
| Speed policy | Request 5 GHz and verify the actual channel; leave infrastructure Wi‑Fi during transfers; restore after |
| Head start | First chunks over Bluetooth, hop to Wi‑Fi; progress bar must move within 1 s of Accept |
| Pairing | Identity keys + X25519 handshake; 6-digit SAS on first meeting; QR as out-of-band verification |
| Encryption | AEAD on every frame (AES‑256‑GCM, ChaCha20‑Poly1305 fallback) |
| Small files | Bundled into 4 MiB packages |
| Resume | Receiver manifest per chunk; resume across link changes; partials kept 24 h |
| Media handling | Never re-encode or compress photos/videos |
| Dashboard | Local only: Live, History, Devices, Stats, Settings |
| Privacy | No accounts, no analytics by default, no ads in nearby mode |
| UI stack | Jetpack Compose (Android); Compose Multiplatform (desktop) |
| Mental model / phrase | "Scan to send" |

## 2. Assumed (change in week 1 without rework)

| Topic | Assumption | Decide by |
| --- | --- | --- |
| Shared core language | Kotlin Multiplatform | End of week 1, after the throughput spike (§5) |
| Default visibility | Trusted only, with "Everyone for 10 minutes" switch | Design freeze, week 1 |
| Desktop order | Windows first, then macOS, then Linux — all inside Phase 1 | Week 1 |
| Launch languages | English + Hindi | Week 2 |
| APK sharing | Not at launch | Week 2 |
| App name | TBD; code name `drop` used in package names until then | Week 1 (trademark + store check) |

## 3. Repository layout and conventions

See `architecture.md` §3 for the module tree. Conventions:

- One Gradle multi-module repo; `core/*` has **no** Android/JVM-desktop imports (enforced by a Konsist/ArchUnit test).
- Kotlin coroutines + `StateFlow` for all engine state; the UI never talks to radios directly.
- Every protocol message has a serializer test with golden CBOR bytes checked in.
- Feature IDs from `features.md` (e.g. `F‑E2`) appear in PR titles and test names.
- Branches: `main` (protected), `feat/<id>-short-name`, `fix/…`. Squash merge; CI must pass unit tests, lint and the protocol fuzz smoke test.
- Commit messages in imperative mood; PR description lists the acceptance criteria touched.
- Secrets: none in the repo; signing keys in the CI secret store.

## 4. Environment setup

### Android

1. Android Studio (latest stable), JDK 17, Android SDK 31–latest, Kotlin Multiplatform plugin.
2. Enable developer mode on all lab phones; install `scrcpy` for screen capture during tests.
3. Two-phone rig: two lab phones on stands 3 m apart, USB to the CI machine, `adb` over USB only (never Wi‑Fi debugging, which would occupy the radio).

### Desktop

- Windows: Visual Studio Build Tools (for the WinRT helper), Windows SDK; a laptop with Bluetooth and Wi‑Fi 6.
- macOS: Xcode (for the Swift BLE/CoreWLAN helper); notarisation credentials.
- Linux: Ubuntu 22.04 VM or laptop with BlueZ ≥ 5.64, NetworkManager, Avahi.

### Tooling

- `tools/bench` runs a scripted transfer and appends a CSV row (see `testing.md` §3).
- Wireshark/tcpdump for the "no plaintext, no internet" checks.
- A cheap 5 GHz spectrum-aware Wi‑Fi analyser app on a spare phone to confirm channel/band during tests.

## 5. Week‑1 plan

| Day | Work | Output |
| --- | --- | --- |
| 1 | Repo, modules, CI skeleton; buy/collect the device lab (`testing.md` §1) | Building repo; lab table filled with real models |
| 1–2 | **Throughput spike:** raw Wi‑Fi Direct 5 GHz TCP between slot A and slot B at 3 m: single stream vs 4 vs 8; JVM `FileChannel` vs plain buffers | CSV + decision on KMP vs Rust core |
| 2 | BLE beacon encode/decode + advertise/scan prototype on two phones | Devices see each other ≤ 500 ms |
| 3 | Handshake over RFCOMM with SAS; identity key in Keystore | Two phones pair with matching codes |
| 3–4 | Design review of `design.md`; finalise visibility default; name shortlist | Frozen screen list and copy |
| 4–5 | `WifiP2pManager` group with 5 GHz request; read actual frequency on all dual-band lab phones; log which OEMs honour it | OEM behaviour table (first real data) |
| 5 | Plan weeks 2–10 in the tracker with feature IDs | Populated backlog |

## 6. Definition of done (Phase 1 beta)

A feature is done when: its acceptance criteria in `features.md` pass on the device matrix; unit tests cover its core logic; it has no P0/P1 bugs open; strings are externalised; the transport badge/hints it affects are correct; and it survives the OEM background test if it runs in the service.

The release is done when all P0 rows pass, the `PRD.md` §8 metrics are met on the matrix, the "no internet, no plaintext" capture is clean, Wi‑Fi restore works on every phone, and 50 beta users have the build.

## 7. Working agreements on speed

- Every PR that touches `core/transfer`, `core/ladder` or a radio must include a bench CSV row from the two-phone rig, and CI fails the nightly if median MB/s drops more than 10% from the previous build.
- Never ship a change that makes the first-byte time worse than 1 s.
- When in doubt between simplicity and speed inside the transfer path, choose speed; everywhere else choose simplicity.

## 8. Support and escalation

| Area | Owner (fill in) |
| --- | --- |
| Product decisions | Dev, Constrivo Group |
| Android radios / OEM issues | — |
| Transfer core / protocol | — |
| Desktop | — |
| Design | — |
| QA / device lab | — |

## 9. Reference links

- Master spec (living doc): "Nearby File Sharing App — Product & Technical Spec" (Claude Doc, shared with the team).
- Android docs to keep open: Wi‑Fi Direct (P2P), `WifiNetworkSpecifier`, Bluetooth LE advertising, `NsdManager`, Foreground services (Android 14+ types), MediaStore, Thermal API.
- Reference projects: LocalSend (LAN transfer and UI, open source), Google Nearby Connections (the discovery → Wi‑Fi upgrade pattern), PairDrop (browser receive UX).
