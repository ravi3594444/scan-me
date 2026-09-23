# Features

**Version:** 0.1 draft · **Date:** 23 Sep 2026

Priorities: **P0** = must ship in Phase 1 beta · **P1** = Phase 1 if time allows, else first update · **P2** = later phase.
Release: **P1‑A** = Android app · **P1‑D** = desktop app · **P1‑W** = browser receive page · **P2** = iPhone · **P3** = internet mode.

## A. Discovery

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑A1 | BLE beacon | Device broadcasts a rotating 31-byte beacon with capability flags every 100 ms in foreground, 1 s in background | P0 | P1‑A, P1‑D | Another device sees the beacon within 500 ms; ID changes every 15 min |
| F‑A2 | BLE scan and radar | Low-latency scan while the radar is open; bubbles placed by smoothed signal strength | P0 | P1‑A, P1‑D | Device on radar ≤ 1 s after open; bubble does not jump between rings within 2 s |
| F‑A3 | mDNS discovery | Devices announce and find each other over the local network | P0 | P1‑A, P1‑D | Two devices on one router (no internet) find each other in ≤ 2 s; PC without Bluetooth appears |
| F‑A4 | Capability detection | Read 5/6 GHz, Wi‑Fi 6/7, Aware, Direct, hotspot, BLE features, SD-card default on start; publish in beacon | P0 | P1‑A, P1‑D | Flags match the device's real hardware on all lab devices |
| F‑A5 | Visibility modes | Everyone / Everyone for 10 min / Trusted only / Hidden | P0 | P1‑A, P1‑D | Hidden device never appears; Trusted-only device visible only to devices holding its recognition secret |
| F‑A6 | Radio enable prompts | If Bluetooth or Wi‑Fi is off, one-tap system dialog/panel instead of Settings | P0 | P1‑A | User never leaves the app to enable radios |

## B. Pairing and trust

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑B1 | Identity keys | Ed25519 identity per device in Keystore/keychain, generated at first launch | P0 | all | Key survives app updates; lost on uninstall (documented) |
| F‑B2 | Handshake | X25519 + HKDF session key over BLE data channel; signed ephemeral keys | P0 | all | Handshake completes ≤ 400 ms on lab devices |
| F‑B3 | 6-digit confirmation | Short authentication string shown on both screens at first meeting | P0 | all | Mismatched codes cannot be confirmed into trust; wrong-code test refuses pairing |
| F‑B4 | Trusted devices | Remember confirmed devices; nickname; auto-accept toggle | P0 | all | Trusted device recognised by key with a changed name; badge shown only for verified key |
| F‑B5 | Scan-to-send QR | Receiver shows a signed QR with identity, ephemeral ID, link details, 5-min expiry; sender scans | P0 | P1‑A (scan), all (show) | Connect ≤ 2 s after scan; expired QR refused; works with Bluetooth off |
| F‑B6 | Static device QR | Long-lived QR (identity only) for desktop windows and printouts | P1 | P1‑D | Scanning connects once a beacon or LAN record is found |

## C. Sending

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑C1 | Tap bubble → pick files | In-app flow with photos, files and apps tabs; multi-select with running size | P0 | P1‑A, P1‑D | 200 selected photos show a correct total; Send button reflects count |
| F‑C2 | Share-sheet entry | Gallery/Files → Share → app → radar with files attached | P0 | P1‑A | Two taps from Gallery to a bubble |
| F‑C3 | Direct share targets | Nearby trusted devices appear as targets inside the Android share sheet | P1 | P1‑A | Target list updates within 2 s of radar changes |
| F‑C4 | Drop animation | Thumbnails fly into the bubble; ring fills; completion pop with haptic | P0 | P1‑A, P1‑D | 60 fps on the midrange lab phone during a 60 MB/s transfer |
| F‑C5 | Add files mid-transfer | Queue more files to an active drop | P1 | P1‑A, P1‑D | Added files start without a new Accept for trusted devices |
| F‑C6 | Desktop drag and drop | Drag files/folders onto a bubble in the desktop window | P0 | P1‑D | Folder of 1,000 files sends with bundling |
| F‑C7 | Text and link drop | Send clipboard text or a URL; appears as a card on the receiver | P1 | P1‑A, P1‑D | Link opens with one tap |

## D. Receiving

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑D1 | Incoming card | Sender avatar, summary ("12 photos · 48 MB"), thumbnails, Accept/Decline, 30 s countdown | P0 | P1‑A, P1‑D | Card within 300 ms of Offer; declines cleanly; timeout cancels |
| F‑D2 | Auto-accept for trusted | Skip the card; notification only | P0 | P1‑A, P1‑D | No UI interruption; notification shows progress |
| F‑D3 | Tray and open | Finished files land in a tray; tap to open; Open folder | P0 | P1‑A, P1‑D | Media visible in Gallery immediately after completion |
| F‑D4 | Save location | Gallery for media, Downloads for documents; user-selectable folder; SD-card detection | P0 | P1‑A | Hint shown when destination is microSD |
| F‑D5 | Safe handling | Never auto-open; APK/executable warning; sanitised names | P0 | all | Path-traversal test files land inside the receive folder with safe names |
| F‑D6 | Browser receive | Phone serves a page over its hotspot; QR or typed address; Download all; optional upload back | P0 | P1‑W | PC without Bluetooth downloads 1 GB at ≥ 20 MB/s; page works in Chrome, Edge, Safari, Firefox |

## E. Transfer engine

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑E1 | Transport ladder | LAN → Wi‑Fi Direct 5 GHz → hotspot → Bluetooth, chosen in ≤ 1 s | P0 | all | Badge shows the chosen path; two phones on mobile data go straight to Wi‑Fi Direct |
| F‑E2 | Wi‑Fi Direct 5 GHz | Group owner selection, 5 GHz band request, pre-shared credentials from the Accept | P0 | P1‑A, P1‑D (Windows) | Group forms on 5 GHz on all dual-band lab phones; actual frequency verified |
| F‑E3 | Hotspot fallback | Local-only hotspot with random SSID/password; joiner uses in-app network specifier | P0 | P1‑A, P1‑D | No system prompt on Android; macOS joins via CoreWLAN |
| F‑E4 | LAN path | Direct TCP over a shared network; speed check in first second | P0 | all | Switches to Wi‑Fi Direct if LAN < 10 MB/s |
| F‑E5 | Bluetooth head start and hop | First chunks over RFCOMM; hop to Wi‑Fi at chunk boundary | P0 | P1‑A, P1‑D | Progress > 0% within 1 s of Accept; no duplicate chunks after hop |
| F‑E6 | Chunking and hashing | 4 MB chunks, per-chunk BLAKE3/XXH3, whole-file SHA‑256 | P0 | all | Corrupted chunk detected and re-requested |
| F‑E7 | Small-file bundling | Files < 1 MB packed into 4 MB bundles with index | P0 | all | 5,000 photos ≤ 90 s on Wi‑Fi 5 pair |
| F‑E8 | Parallel streams | 4 streams default, 8 above 40 MB/s; shared queue; 2 in flight per stream | P0 | all | Throughput within 80% of iperf between the same devices |
| F‑E9 | Encryption | AES‑256‑GCM per chunk (ChaCha20‑Poly1305 fallback); encrypted control stream | P0 | all | No plaintext on the wire in a packet capture |
| F‑E10 | Resume | Receiver manifest after each chunk; Resume lists missing chunks; survives link changes | P0 | all | Wi‑Fi off 30 s / out of range / app kill → resumes from last chunk |
| F‑E11 | Wi‑Fi restore | Leave group / stop hotspot / release specifier; rejoin previous network | P0 | P1‑A, P1‑D | Back on previous Wi‑Fi ≤ 5 s |
| F‑E12 | Foreground service | Transfer runs in a dataSync foreground service with wake lock | P0 | P1‑A | 10 min background on Xiaomi/Vivo lab devices without interruption |

## F. Speed and feedback

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑F1 | Live speed | MB/s sampled every 250 ms, smoothed; ETA | P0 | all | Displayed value within 10% of bytes/time over any 5 s window |
| F‑F2 | Transport badge | "Wi‑Fi Direct · 5 GHz", "Same network", "Hotspot · 2.4 GHz", "Bluetooth" | P0 | all | Badge matches actual link and measured frequency |
| F‑F3 | Speed hints | One-line reasons: move closer, 2.4 GHz only, SD card, warm phone, bundling, slow mode | P1 | all | Each hint fires in its lab scenario; none fires falsely on the Pixel pair |
| F‑F4 | Pre-warm | Start forming the Wi‑Fi link when files are picked | P1 | P1‑A | Link ready ≤ 1 s after tapping a bubble in 80% of trials |
| F‑F5 | Persistent groups | Reconnect known devices without renegotiation | P1 | P1‑A | Second connection to the same device ≤ 1 s |
| F‑F6 | Thermal adaptation | Reduce streams on thermal throttling instead of stalling | P1 | P1‑A | Three back-to-back 2 GB transfers without stall |
| F‑F7 | Wi‑Fi Aware link | Use Aware data path where both support it | P2 | later | Link ≤ 1 s |

## G. Dashboard

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑G1 | Live tab | Active transfers, progress, speed, ETA, badge, hint; pause/cancel/add | P0 | all | Reflects service state within 250 ms |
| F‑G2 | History tab | All transfers newest first; status; tap to open; re-send; clear | P0 | all | Survives app restart; 10,000 rows scroll smoothly |
| F‑G3 | Devices tab | Trusted devices; rename; forget; auto-accept; Show my QR | P0 | all | Forget removes recognition secret; device shows as untrusted next time |
| F‑G4 | Stats tab | Total moved, average speed, transfers/week chart, hours saved vs Bluetooth; share card | P1 | all | Figures reconcile with History |
| F‑G5 | Settings | Visibility, prefer 5 GHz, keep awake, save location, bundling, clear partials, nickname, language | P0 | all | Every setting takes effect without restart |

## H. Desktop

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑H1 | Windows app | Radar window, tray icon, drag and drop, Received folder, notifications; BLE + Wi‑Fi Direct + LAN | P0 | P1‑D | Phone→laptop 1 GB ≤ 60 s on Wi‑Fi 5 laptop |
| F‑H2 | macOS app | BLE scan, Bonjour, hotspot join via CoreWLAN, LAN; notarised | P0 (assumed in Phase 1) | P1‑D | Phone→Mac 1 GB ≤ 60 s |
| F‑H3 | Linux app | BlueZ, Avahi, NetworkManager; .deb/AppImage/Flatpak | P1 | P1‑D | Phone→Ubuntu laptop 1 GB ≤ 60 s |
| F‑H4 | No-Bluetooth PC path | LAN discovery or QR/typed address; browser fallback | P0 | P1‑D, P1‑W | Desktop PC receives without Bluetooth |
| F‑H5 | Auto-start and tray | Start minimised on login (opt-in) | P1 | P1‑D | Phone finds the sleeping laptop |

## I. Onboarding, permissions and OEM

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑I1 | Just-in-time permissions | Each permission asked when first needed with one sentence of why | P0 | P1‑A | Denial paths show recovery; no permission asked before it is needed |
| F‑I2 | Battery exemption step | Xiaomi/Vivo/Oppo/Realme/Samsung guidance for Autostart, battery, sleeping apps | P0 | P1‑A | Brand-specific screens; skippable |
| F‑I3 | Nickname and avatar | Set once at first launch; initials fallback | P0 | all | Shown in beacon and cards |
| F‑I4 | Languages | English and Hindi at launch (assumed) | P1 | all | All strings externalised; RTL-safe layout |

## J. Security and privacy

| ID | Feature | Description | Priority | Release | Acceptance criteria |
| --- | --- | --- | --- | --- | --- |
| F‑J1 | Rotating IDs | Ephemeral beacon ID every 15 min, resolvable only by trusted devices | P0 | all | Two scans 20 min apart cannot be linked without the secret |
| F‑J2 | Encrypted DB | History DB encrypted with a Keystore/keychain key | P1 | all | DB file unreadable without the key |
| F‑J3 | No analytics by default | Opt-in crash reports only | P0 | all | Zero network calls in a nearby session (verified by capture) |
| F‑J4 | Privacy screen | One-paragraph plain-language statement | P0 | all | Present in onboarding and Settings |

## Later (P2/P3) headline features

| ID | Feature | Phase |
| --- | --- | --- |
| F‑K1 | iPhone app: CoreBluetooth discovery, Multipeer iPhone↔iPhone, hotspot join iPhone↔Android, share extension | Phase 2 |
| F‑K2 | Internet far mode: directory/signalling, push wake, hole punching, relays, encrypted cloud drop, share links, data warnings | Phase 3 |
| F‑K3 | Courier / carry-and-forward delivery queue | Later |
| F‑K4 | Clipboard sync phone↔computer; Windows share target | Later |
