# Product Requirements Document (PRD)

**Product:** Nearby File Sharing App (name TBD) · **Version:** 0.1 draft · **Date:** 23 Sep 2026 · **Owner:** Constrivo Group

## 1. One-paragraph summary

A phone-and-computer app that sends files to anyone nearby at Wi‑Fi speed with no internet. Devices are
found over Bluetooth or a QR scan, the transfer plays out with an AirDrop-style drop animation, and a local
dashboard shows live speed, history and stats. Version 1 ships on Android 12+ and as a desktop app for
Windows, macOS and Linux; anyone without the app can receive in a browser by scanning a QR.

## 2. Problem

- Bluetooth file transfer is universal but moves 0.1–0.2 MB/s; a 1 GB video takes about two hours.
- WhatsApp and similar apps compress photos and video, need internet, and burn mobile data on both sides.
- Existing fast-share apps (ShareIt, Xender) are heavy, ad-filled, and often stuck on 2.4 GHz at ~10 MB/s.
- Google Quick Share is Android-to-Android/Windows only and gives the user no control or visibility of speed.
- In India most people are on mobile data, not shared Wi‑Fi, so "same Wi‑Fi network" tools (LocalSend and others) rarely apply.

## 3. Target users

| Segment | Situation | What they need |
| --- | --- | --- |
| Students and friends | Sharing camera rolls, videos, notes on campus, often with no Wi‑Fi | Fast, free, no data usage, works offline |
| Families | Moving photos between phones and to the family laptop | Zero setup, no accounts, obvious UI |
| Small offices and shops | Sending documents, invoices, catalog media between phones and PCs | Phone-to-computer, history, reliability |
| Event and field teams | Weddings, shoots, site visits: many large files, no connectivity | Very fast, resumable, works in a crowd (QR) |

Primary market: India, mobile-first, Android 12–17, mixed budget and midrange devices, users usually on mobile data.

## 4. Value proposition

- **Offline.** Needs no internet, SIM, account or server, and uses no mobile data.
- **Fast.** 1 GB in under a minute on most phones and in seconds on flagships (Bluetooth alone: ~2 hours).
- **Install anywhere.** Android 12+, Windows, macOS, Linux; browser receive for anyone without the app.
- **Honest speed.** The transport badge and one-line hints tell the user why a transfer is fast or slow.
- **Private.** End-to-end encrypted, files never re-encoded, nothing leaves the room.

## 5. Competitive landscape

| Product | Discovery | Transfer | Speed (typical) | Offline | Cross-platform | Weakness we exploit |
| --- | --- | --- | --- | --- | --- | --- |
| Apple AirDrop | BLE | AWDL (Wi‑Fi P2P) | 30–60 MB/s | Yes | Apple only | Closed ecosystem |
| Google Quick Share | BLE | Wi‑Fi Direct / LAN / BT | 10–40 MB/s | Yes | Android, Windows | Opaque speed, no 5 GHz control, no dashboard |
| ShareIt / Xender | Hotspot / Wi‑Fi Direct | Wi‑Fi Direct | 5–20 MB/s | Yes | Android, iOS, PC | Ads, bloat, often 2.4 GHz |
| LocalSend | LAN mDNS | HTTPS over LAN | 10–40 MB/s | Needs a router | All | Both devices must share a Wi‑Fi network |
| Send Anywhere | 6-digit code | Internet P2P / relay | Internet-bound | No | All | Not offline |
| WhatsApp / Telegram | Contacts | Servers | Internet-bound | No | All | Compression, data usage, size caps |

## 6. Scope

### Version 1 (Phase 1)

- Android 12–17 app; desktop app for Windows 10/11, macOS 12+, Ubuntu 22.04+.
- Nearby ring: Bluetooth discovery, Wi‑Fi Direct 5 GHz, temporary hotspot fallback, Bluetooth-only slow fallback.
- Same-network ring: mDNS discovery and LAN transfer when both devices happen to share a Wi‑Fi network.
- Scan-to-send QR pairing; browser receive page served by the phone.
- Android share-sheet entry (Share → app) with nearby devices as direct share targets.
- Drop animation, incoming card, tray, trusted devices, auto-accept.
- Dashboard: Live, History, Devices, Stats, Settings.
- Speed engineering rules and hints (see `architecture.md` §9).

### Later

- Phase 2: iPhone app (Multipeer for iPhone↔iPhone; hotspot join for iPhone↔Android).
- Phase 3: Internet "far" mode (signalling, hole punching, relays, encrypted cloud drop, share links).
- Candidates: courier / carry-and-forward, Wi‑Fi Aware links, Windows share target, clipboard sync.

### Non-goals for version 1

No accounts, no cloud storage, no internet transfers, no iPhone, no ads, no analytics by default, no
re-encoding or compression of media, no mesh networking, no hardware add-ons.

## 7. User stories

| ID | As a… | I want to… | So that… | Priority |
| --- | --- | --- | --- | --- |
| US‑01 | phone user | open the app and see nearby devices within a second | I can send without any setup | P0 |
| US‑02 | phone user | tap a device, pick files and watch them "drop" into it | sending feels instant and obvious | P0 |
| US‑03 | receiver | see who is sending what (thumbnails, size) before accepting | I stay in control of what lands on my phone | P0 |
| US‑04 | user on mobile data | send 1 GB in under a minute without using any data | it is faster and cheaper than any chat app | P0 |
| US‑05 | user in a crowded room | scan the receiver's QR and connect instantly | I do not hunt through 30 bubbles | P0 |
| US‑06 | gallery user | tap Share in Gallery and pick a nearby device | sending is two taps from where my photos are | P0 |
| US‑07 | user whose Wi‑Fi dropped | have the transfer resume where it stopped | I never restart a 2 GB video | P0 |
| US‑08 | frequent pair (family, colleagues) | mark a device as trusted and auto-accept | drops from them just arrive | P1 |
| US‑09 | laptop user | drag files onto the phone bubble on my computer | phone↔computer works without cables | P0 |
| US‑10 | person without the app | scan a QR and download in my browser | I can receive without installing anything | P0 |
| US‑11 | any user | see why a transfer is slow (2.4 GHz, SD card, warm phone) | I trust the speed numbers | P1 |
| US‑12 | any user | see history, totals and time saved | I know what I sent and to whom | P1 |
| US‑13 | privacy-minded user | stay invisible except to trusted devices | strangers cannot ping me | P1 |
| US‑14 | Xiaomi/Vivo user | have transfers survive the screen going dark | the app works on my phone like on a Pixel | P0 |

## 8. Success metrics (measured on the device lab in `testing.md`)

| Metric | Target |
| --- | --- |
| Time from app open to a nearby device on the radar | ≤ 1 s |
| Tap to first bytes flowing | ≤ 1 s |
| 5 GHz link established | ≤ 3 s (≤ 1 s for devices that have met before) |
| Median speed at 3 m, Wi‑Fi 5 pair | ≥ 25 MB/s |
| Median speed at 3 m, Wi‑Fi 6 pair | ≥ 60 MB/s |
| 5,000 photos (~1 GB), Wi‑Fi 5 pair | ≤ 90 s |
| Interrupted transfers that resume without restart | ≥ 99% |
| Both devices back on their previous Wi‑Fi after a transfer | ≤ 5 s |
| Mobile data used by a nearby transfer | 0 bytes |
| Beta: transfers completed / transfers started | ≥ 97% |

## 9. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| OEMs ignore the 5 GHz band request or kill background services | Speed and reliability on Xiaomi/Vivo/Oppo/Realme | Verify channel after group forms and re-form; foreground service; battery-exemption onboarding; device lab covers these brands |
| Budget phones are 2.4 GHz only | Low speed for a segment of users | Graceful 4–10 MB/s path with clear hints; still 50× Bluetooth |
| Wi‑Fi Direct group formation slow on some devices (3–5 s) | "Feels slow to start" | Bluetooth head start, pre-warming, persistent groups |
| Play Store review of foreground service / APK sharing | Launch delay | Declare dataSync FGS type; defer APK sharing decision |
| macOS lacks Wi‑Fi Direct | Slower desktop path on Mac | Hotspot join and LAN; both still 20–60 MB/s |
| Name collision / trademark | Rebrand cost | Decide name in week 1; check trademark and store availability |

## 10. Open decisions

1. App name and brand.
2. Default visibility: Trusted only + "Everyone for 10 minutes" (assumed) vs Everyone by default.
3. Shared core: Kotlin Multiplatform (assumed) vs Rust — decide after the week‑1 throughput spike.
4. Desktop order: Windows first, then macOS and Linux within Phase 1 (assumed) vs macOS/Linux in Phase 1.5.
5. Android 11 support later.
6. Business model: nearby free and ad-free (assumed); paid tier only with Phase 3 relay/cloud drop.
7. APK (installed app) sharing at launch vs files only.
8. Launch languages: English and Hindi (assumed); further Indian languages.
9. Beta group (50 users) and device-lab location.
