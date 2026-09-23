# Roadmap

**Version:** 0.1 draft · **Date:** 23 Sep 2026

## Phases

| Phase | Scope | Duration (approx.) | Exit criterion |
| --- | --- | --- | --- |
| 1 | Android 12+ app, desktop app (Windows → macOS → Linux), browser receive; offline nearby + same-network | ~10 weeks | `PRD.md` §8 metrics met on the device matrix; 50-user beta live |
| 1.1 | Beta fixes, OEM tuning, Play Store production release, desktop installers signed | ~3 weeks | 97% transfer completion in beta; store approval |
| 2 | iPhone app (CoreBluetooth discovery, Multipeer iPhone↔iPhone, hotspot join iPhone↔Android, share extension) | ~6 weeks | iPhone appears on Android radar; 1 GB ≤ 2 min iPhone↔Android |
| 3 | Internet "far" mode: directory/signalling, push wake, hole punching, relays in 2–3 regions, encrypted cloud drop, share links, mobile-data warnings, paid tier | ~8 weeks | Trusted devices reachable from anywhere; relay share < 15% of far transfers |
| Later | Courier / carry-and-forward, Wi‑Fi Aware links, Windows share target, clipboard sync, more languages | — | — |

## Phase 1 week by week

| Week | Android | Desktop | Shared / QA | Done when |
| --- | --- | --- | --- | --- |
| 1 | Repo, CI, BLE beacon prototype, handshake prototype, P2P 5 GHz spike | — | Screen designs; device lab bought; KMP vs Rust decision; name shortlist | Two phones find and pair in < 1 s; OEM 5 GHz table started |
| 2 | Radar UI with smoothed placement; identity keys; SAS; trusted devices | — | Protocol golden tests; CBOR schemas | Radar stable with 5 phones; trusted badge works |
| 3 | QR generate/scan; mDNS; capability detection; visibility modes | — | Fuzzer for control messages | QR connect ≤ 2 s; PC-less LAN discovery on two phones via a router |
| 4 | Wi‑Fi Direct 5 GHz + credentials in Accept; hotspot fallback; `WifiNetworkSpecifier` join; sockets bound to the link | Windows project skeleton; JmDNS; LAN path | Bench harness on the two-phone rig | 1 GB in < 60 s on Wi‑Fi 5 pair (raw engine) |
| 5 | Transfer engine: chunks, streams, AEAD, acks, manifest, resume; Wi‑Fi restore | Windows BLE + RFCOMM handshake; Wi‑Fi Direct client; drag-and-drop window | Resume test matrix | Resume after Wi‑Fi off/on and app kill; restore ≤ 5 s |
| 6 | Bluetooth head start + hop; bundling; share-sheet entry + direct share; incoming card; drop animation; tray | Windows Received folder, tray, notifications | 60 fps check on midrange phone | 5,000 photos < 90 s; gallery → device in two taps |
| 7 | Pre-warm; persistent groups; speed hints; thermal adaptation; foreground service polish | macOS app (BLE scan, GATT handshake, CoreWLAN join, Bonjour); Linux app start | Browser receive page (Ktor server on phone) | Phone → laptop 1 GB < 60 s on Windows and Mac; PC without Bluetooth receives via QR |
| 8 | Dashboard (5 tabs); onboarding + brand steps; permissions flows; Hindi strings | Linux app; installers (MSI, DMG, .deb/AppImage) | Security checks: capture shows no plaintext, no internet | All tabs live; Xiaomi survives 10 min background mid-transfer |
| 9 | Bug bash; OEM fixes; Play internal testing track | Code signing, notarisation | Full matrix run (`testing.md` §4); benchmark report | All P0 acceptance criteria pass |
| 10 | Beta release to 50 users; crash/feedback loop | Beta desktop builds | Beta metrics dashboard (local export + form) | Beta live; completion ≥ 97% in week one |

## Team (Phase 1)

| Role | Count | From |
| --- | --- | --- |
| Kotlin engineer — radios and platform (BLE, P2P, hotspot, OEM) | 1 | Week 1 |
| Kotlin engineer — transfer core, protocol, UI | 1 | Week 1 |
| Desktop engineer (Compose Multiplatform, Windows/macOS/Linux helpers) | 1 | Week 4 |
| Product designer | part-time | Weeks 1–2, 6–7 |
| QA / device-lab owner | 1 | Week 5 |
| Product owner | Dev | Throughout |

## Dependencies and lead times

- Device lab purchase (8–10 phones, 3 computers) — order in week 1; needed by week 4.
- Google Play developer account and app signing — week 1.
- Apple Developer account for macOS notarisation — week 1 (also needed for Phase 2).
- Windows code-signing certificate — week 2 (EV cert lead time can be weeks).
- Trademark and store-name check for the chosen app name — week 1.

## Risks to the schedule

| Risk | Signal | Response |
| --- | --- | --- |
| OEM ignores 5 GHz request | Week 1 spike shows 2.4 GHz groups | Hotspot host on the other device; document per-OEM fallback; keep target on Pixel/Samsung |
| Wi‑Fi Direct client unreliable on Windows | Week 5 | Windows uses hotspot join and LAN paths first |
| macOS helper complexity | Week 7 | Ship Windows + Linux in beta, macOS in 1.1 |
| Play review of foreground service | Week 9 | Prepare policy declaration and demo video early (week 7) |
| Store name unavailable | Week 1 | Have three name candidates cleared |
