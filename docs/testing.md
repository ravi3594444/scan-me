# Testing and Benchmarks

**Version:** 0.1 draft · **Date:** 23 Sep 2026

The speed promise is proven on a real device lab, never in an emulator. Every pair in the matrix runs the
scenarios below before beta; the `PRD.md` §8 metrics are the pass marks.

## 1. Device lab

| Slot | Device class (fill in exact model) | Wi‑Fi | Purpose |
| --- | --- | --- | --- |
| A | Google Pixel (current or previous gen) | Wi‑Fi 6/6E | Baseline behaviour |
| B | Samsung Galaxy A/M midrange | Wi‑Fi 5 | Most common Indian midrange |
| C | Xiaomi / Redmi / POCO midrange (HyperOS) | Wi‑Fi 5 | Background killing, Autostart |
| D | Vivo / iQOO or Oppo / Realme midrange | Wi‑Fi 5 | Hotspot band quirks |
| E | Flagship (OnePlus / Samsung S) | Wi‑Fi 6E/7 | Top-speed row |
| F | Android Go or entry phone | 2.4 GHz only | Fallback path and hints |
| G | Any phone still on Android 12 | any | Location-permission path |
| H | Windows 11 laptop with Bluetooth | Wi‑Fi 6 | Main desktop path |
| I | MacBook (Apple silicon) | Wi‑Fi 6 | Hotspot-join path |
| J | Desktop PC without Bluetooth (wired to router) | — | QR and browser path |

Also: one dual-band router with internet disabled (for the LAN path), one cheap microSD card (Class 10) for slot B or C, a spare phone running a Wi‑Fi analyser.

## 2. Test environment

- Fixed positions: 1 m, 3 m (default), 5 m, 10 m marked on the floor; one interior wall position.
- Both devices at 50–80% battery, not charging, screen on, no other transfers.
- Before each run: forget stale P2P groups; set both to the same visibility; note the Wi‑Fi channel from the analyser.
- Record room conditions once per session (number of visible Wi‑Fi networks on 2.4 and 5 GHz).

## 3. Benchmark procedure (`tools/bench`)

1. Select pair (X, Y), distance, file mix.
2. Start `bench send --mix <mix> --peer <Y>` on X; it records Offer time, Accept time, first-byte time, link-ready time (with measured frequency), completion time, bytes, and throughput samples.
3. The tool appends a CSV row: `date, build, X, Y, distance_m, mix, transport, band_ghz, first_byte_s, link_ready_s, total_s, median_MBs, p10_MBs, hints`.
4. Repeat 3× per cell; report the median. Nightly CI runs the A–B cell at 3 m with the "big" mix and fails on a >10% regression.

File mixes:

| Mix | Contents |
| --- | --- |
| big | One 2 GB MP4 |
| roll | 5,000 JPEGs, 150–300 KB each (~1 GB) |
| docs | 50 PDFs of 20 MB |
| mixed | 200 photos + 3 videos of 300 MB + 20 PDFs |

## 4. Scenario matrix

| ID | Scenario | Setup | Pass when |
| --- | --- | --- | --- |
| T‑01 | Big file | mix `big`, 3 m, line of sight, every pair | Median MB/s ≥ the row for the slower device in `architecture.md`/PRD; badge shows 5 GHz on dual-band pairs |
| T‑02 | Camera roll | mix `roll`, A–B and B–C | ≤ 90 s on Wi‑Fi 5 pairs; "Bundling" hint shown |
| T‑03 | Documents | mix `docs` | Completes; each file opens from the tray |
| T‑04 | Distance | mix `big` at 1, 5, 10 m and through one wall | Speed falls smoothly; 2.4 GHz fallback engages at the edge with its hint; no stall |
| T‑05 | Wi‑Fi off mid-transfer | Toggle Wi‑Fi off 30 s on the receiver, then on | Resume from last chunk; total bytes moved = file size (no duplicates) |
| T‑06 | Walk away | Carry receiver out of range 60 s and back | Resume; no corrupt file |
| T‑07 | App kill | Force-stop receiver app mid-transfer, reopen | Resume prompt; completes |
| T‑08 | Background | Screen off, app backgrounded 10 min on slots C and D during `big` | Transfer continues; notification live |
| T‑09 | Thermal | Three back-to-back `big` transfers on slot E | No stall; streams reduce; hint shown |
| T‑10 | Crowded room | 30 beacons (lab phones + BLE beacon simulator) | Radar stable; correct device chosen; QR connects ≤ 2 s |
| T‑11 | Wrong code | Enter mismatched SAS | Pairing refused; no trusted badge |
| T‑12 | Expired QR | Scan a QR older than 5 min | Refused with clear message |
| T‑13 | Name spoof | Phone F renamed to slot A's nickname | No trusted badge; Accept card shown |
| T‑14 | Wi‑Fi restore | Both start on the router's Wi‑Fi | Back on it ≤ 5 s after completion |
| T‑15 | Mobile data only | Both on mobile data, Wi‑Fi on but not connected | Ladder skips LAN; P2P 5 GHz; 0 bytes of mobile data used (check data usage screen) |
| T‑16 | LAN path | Both on the router (internet disabled) | Same-network badge; ≥ 10 MB/s or switch to P2P |
| T‑17 | Desktop Windows | Phone ↔ slot H | 1 GB ≤ 60 s; drag-and-drop send works; Received folder opens |
| T‑18 | Desktop macOS | Phone ↔ slot I | 1 GB ≤ 60 s via hotspot join; Location permission flow acceptable |
| T‑19 | Desktop Linux | Phone ↔ Ubuntu laptop | 1 GB ≤ 60 s |
| T‑20 | No-Bluetooth PC | Phone → slot J | Browser page downloads 1 GB ≥ 20 MB/s over hotspot; LAN discovery works via router |
| T‑21 | Browser matrix | Chrome, Edge, Safari, Firefox on slot J/I | Page renders; zip download streams |
| T‑22 | Permissions | Deny each permission once, then retry | Plain explanation shown; recovery path works |
| T‑23 | SD card | Save location set to microSD on slot B | Hint shown; transfer completes |
| T‑24 | Android 12 path | Slot G | Location permission requested only at first send; P2P works |
| T‑25 | Share sheet | Gallery → Share → app; direct share target | Two taps to a device; files attached |
| T‑26 | Privacy capture | tcpdump on the router and on-device VPN capture during T‑01/T‑16 | No plaintext file bytes; zero connections to internet hosts |
| T‑27 | Storage full | Receiver with < 100 MB free | Clean cancel with reason; partials cleared |
| T‑28 | Hash mismatch injection | Fault-inject a corrupted chunk in a debug build | Chunk re-requested; file verifies |

## 5. Automated tests

| Layer | What | Where |
| --- | --- | --- |
| Unit | Beacon encode/decode, ephemeral ID rotation, SAS derivation, chunker/bundler boundaries, resume bitmap math, ladder decisions from flag combinations, throughput EWMA | `core/*` |
| Golden | CBOR bytes for every control message | `core/protocol` |
| Fuzz | Control-message parser and frame decoder (malformed lengths, types, fields) | `tools/fuzz`, nightly |
| Integration (JVM) | Two engine instances over loopback TCP with fault injection (drops, reorders, disconnects) | `core/transfer` |
| Instrumented | Two-phone rig nightly: T‑01 (A–B), T‑05, T‑14; CSV to CI artefacts | `tools/bench` |
| UI | Compose screenshot tests for radar states, cards, dashboard tabs, light/dark, 200% font | `ui/shared` |
| Architecture | `core` must not import Android/JVM-desktop packages | Konsist test |

## 6. Beta exit report

One page: matrix results (median MB/s per cell), first-byte and link-ready percentiles, resume success rate,
Wi‑Fi restore success, OEM background survival, privacy capture result, and the list of open P1 bugs. This
report is the go/no-go for the Play Store production release.
