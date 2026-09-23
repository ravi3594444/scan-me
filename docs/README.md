# Nearby File Sharing App — Developer Handoff Pack

**Version:** 0.1 (draft) · **Date:** 23 September 2026 · **Owner:** Constrivo Group

An offline, AirDrop-style file sharing app for Android 12+ and desktop (Windows, macOS, Linux).
Devices are found over Bluetooth or a QR scan; files travel over a direct 5 GHz Wi‑Fi link at
20–100 MB/s with no internet, no SIM, no account and no mobile data.

The live, editable master spec is the Claude Doc "Nearby File Sharing App — Product & Technical Spec".
This pack breaks that spec into files a team can drop into a repository.

## Files in this pack

| File | What it holds | Read it if you are |
| --- | --- | --- |
| `README.md` | This index, glossary, how the files relate | Everyone |
| `PRD.md` | Product requirements: problem, users, scope, user stories, success metrics, non-goals, risks | Product, founders, design |
| `features.md` | Every feature with an ID, priority (P0/P1/P2), release and acceptance criteria | Product, engineering, QA |
| `design.md` | UX/UI spec: screens, radar geometry, animation timings, copy strings, states, accessibility, desktop and browser layouts | Design, front-end engineers |
| `architecture.md` | Technical requirements: modules, transport ladder, beacon layout, handshake, transfer protocol, chunking/crypto/resume, platform APIs, data model, security | Engineering |
| `handoff.md` | How to start: repo layout, environment, week‑1 plan, decisions made vs assumed, definition of done, conventions | Engineering leads |
| `roadmap.md` | Phases, week-by-week plan for Phase 1, team, dependencies, risks | Founders, engineering leads |
| `testing.md` | Device lab, benchmark procedure, test scenarios, pass marks, automation | QA, engineering |

Suggested reading order for a new engineer: `README.md` → `PRD.md` → `design.md` → `architecture.md` → `handoff.md`.

## The idea in five lines

1. Bluetooth Low Energy finds the other device and carries a tiny encrypted handshake.
2. The file itself moves over Wi‑Fi Direct on 5 GHz (or a temporary hotspot, or the local network).
3. The first bytes flow over Bluetooth immediately, then hop to Wi‑Fi when the link is up, so the progress bar never sits at zero.
4. A QR code ("scan to send") replaces the radar in crowded rooms and links laptops and browsers.
5. Everything runs on the two devices; there is no server in version 1.

## Glossary

| Term | Meaning |
| --- | --- |
| Beacon | The 31-byte BLE advertisement each device broadcasts so others can find it |
| Bubble | A nearby device as drawn on the radar screen |
| Bundle | A 4 MB package of many small files sent as one chunk |
| Chunk | A 4 MB piece of a file, hashed and acknowledged individually |
| Drop | One send from one device to another (the transfer as the user sees it) |
| Group owner (GO) | The device that hosts a Wi‑Fi Direct group; it chooses the channel |
| Head start | Sending the first chunks over Bluetooth while the Wi‑Fi link forms |
| Hop | Moving an in-progress transfer from the Bluetooth stream to the Wi‑Fi streams |
| LAN path | Sending through a router both devices are already connected to (works with no internet) |
| Manifest | The receiver's record of which chunks have arrived; what makes resume work |
| SAS | Short authentication string: the 6-digit code both screens show on first pairing |
| Trusted device | A device whose identity key the user has confirmed once; it skips the Accept card if auto-accept is on |
| Transport badge | The label under the progress ring: "Wi‑Fi Direct · 5 GHz", "Same network", "Bluetooth" |

## Status of decisions

Decisions already made are listed in `handoff.md` under "Decided". Items still open (app name, default
visibility, KMP vs Rust core, desktop order, business model, APK sharing, languages, beta group) are in
`PRD.md` under "Open decisions" and mirrored in `handoff.md`. Treat anything marked *assumed* as a
default that can be changed in week 1 without rework.
