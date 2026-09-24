# Implementation Plan — Nearby File Sharing App (code name `drop`)

**Version:** 0.1 · **Date:** 23 Sep 2026 · **Source spec:** the handoff pack in this folder

## Context

Constrivo Group's developer handoff pack (uploaded twice; both zips are byte‑identical, 8 Markdown files: README, PRD, features, design, architecture, handoff, roadmap, testing) specifies an offline, AirDrop‑style file sharing app: BLE discovery and handshake, Wi‑Fi Direct 5 GHz payload, Bluetooth head start, QR "scan to send", browser receive page, local dashboard; Android 12+ and desktop (Windows, macOS, Linux) in Phase 1. The `scan-me` repository is empty (a 9‑byte README).

This file is the build plan: the whole of Phase 1 broken into work packages (WPs) that Claude Code sessions or engineers pick up one at a time, with time estimates, the decisions still open, and the spec changes found while reading the pack. Progress is tracked in §9 at the bottom.

---

## 1. Constraints that shape the plan

Facts checked in this container: JDK 21 and Gradle 8.14 installed (wrapper can pull Gradle 9.x); Maven Central, the Gradle plugin portal and Google's Maven repository are reachable; **no Android SDK** (installable: Google's repository is reachable); **no Bluetooth or Wi‑Fi hardware**; Chromium + Playwright preinstalled.

Consequences:
- Everything in `core/*`, `web-receive`, `tools/*` and the shared Compose UI can be built **and verified** inside a Claude Code session (unit, golden‑bytes, fuzz, loopback integration, JVM screenshot tests, Playwright for the browser page).
- Radio behaviour (BLE, RFCOMM/GATT, Wi‑Fi Direct band, hotspot, OEM background killing, Wi‑Fi restore, real MB/s) can only be verified in the device lab (`docs/testing.md` §1). Sessions can write and compile that code; a human with the lab closes those work packages.
- So the order below front‑loads the device‑free packages (WP0–WP6, WP9, WP10a) so a tested engine exists before the lab arrives (the pack wants the lab by week 4).

Versions observed today, 23 Sep 2026 (pin the latest **stable** at WP0 time): Kotlin 2.4.20 · Gradle 9.7.1 · AGP 9.4.x line (9.5 is alpha) · Compose Multiplatform 1.12.x line (1.13 is alpha) · kotlinx‑coroutines 1.11.0 · kotlinx‑serialization 1.11.x line (1.12.0 is RC) · Konsist 0.17.3 · kotlinx‑io 0.9.1 · ktlint‑gradle 14.2.0 · detekt 1.23.8.

Naming until the app name is decided (PRD §10): Gradle root project `drop`, package `com.constrivo.drop`, mDNS type `_drop._tcp`, hostname `drop.local`, received folder `~/Received/Drop/`. All four live as constants in one file (`core/discovery/.../AppIdentity.kt`) plus the platform manifests, so the rename is one commit.

Assumptions taken (state them in `docs/implementation-plan.md`; change any in week 1 without rework):
- Shared core = Kotlin Multiplatform (the pack's assumption; see decision 3).
- One Claude Code session = one work package, ending in a pushed, CI‑green branch and a PR a human reviews before the next session starts.
- Estimates are ranges; the calendar critical path is the device lab, not code volume.

---

## 2. Decisions the product owner must make (they gate work packages)

| # | Decision | Pack status | Recommendation | Gates |
| --- | --- | --- | --- | --- |
| 1 | App name and brand | Open | Clear 3 candidates (trademark, Play, App Store); keep code name `drop` in packages until then | WP8 strings, WP9 hostname, WP10 folder name |
| 2 | Default visibility | Assumed: Trusted only + "Everyone for 10 min" | Keep; it is also the privacy‑safe default | WP8 |
| 3 | KMP vs Rust core | Assumed KMP; decide after the week‑1 throughput spike | Proceed with KMP now. The spike needs two phones, so no session can run it; the hot path is `java.nio` sockets + JCA AEAD either way, and a Rust core would still cross JNI for every radio. Revisit only if the lab shows the JVM below 80% of iperf (F‑E8) | WP0 |
| 4 | Desktop order | Assumed Windows → macOS → Linux, all in Phase 1 | Build the shared desktop shell on Linux first **in sessions** (the only desktop OS a session can run); ship Windows first to users as assumed | WP10 |
| 5 | BLE service UUID | Pack flags: a 16‑bit UUID needs Bluetooth SIG assignment | Size math: with a 128‑bit UUID the 31‑byte legacy packet leaves 10 bytes for the 14‑byte body, so the §5.1 layout **requires** a 16‑bit UUID. Apply for a SIG 16‑bit UUID (fee + lead time). Until assigned, debug builds use a placeholder 16‑bit value and the 128‑bit UUID in the scan response as specified | WP1, WP7a |
| 6 | Per‑chunk hash | "BLAKE3 or XXH3‑128" | XXH3‑128 (`zero-allocation-hashing`, pure JVM, works on Android) for corruption detection; integrity already comes from AEAD + whole‑file SHA‑256 | WP4 |
| 7 | Android crypto library | Pack suggests libsodium via lazysodium | Behind the `CryptoProvider` interface: JCA on desktop JVM (JDK 21 has X25519, Ed25519, AES‑GCM, ChaCha20‑Poly1305). On Android, verify Ed25519/X25519 availability on API 31 first thing in WP7b; if missing, lazysodium‑android or Tink | WP2, WP7b |
| 8 | APK sharing at launch | Open | No (Play review risk, PRD §9); build the Apps tab behind a feature flag | WP8 |
| 9 | Launch languages | Assumed English + Hindi | Keep; externalise every string from the first UI commit | WP8 |
| 10 | Beta group and lab location | Open | Needed before any WP7 sub‑package can be closed | WP7, WP13 |

---

## 3. Spec changes to make before the affected work package starts

Found while reading the pack. Each is small on paper but would otherwise be discovered mid‑implementation. Make the edit in `docs/architecture.md` in the same PR as the code, with a "Changed:" note at the top of the section.

| # | Issue | Where | Recommended change | Fix in |
| --- | --- | --- | --- | --- |
| S1 | Head start vs "progress > 0% within 1 s" and "no duplicate chunks after hop": a 4 MiB chunk over Bluetooth at 0.1–0.2 MB/s takes 20–40 s, acks are per chunk, and the hop waits for the in‑flight chunk | architecture §7.3, §7.5; F‑E5 | Add a 64 KiB **block** inside a chunk for the Bluetooth path: BT frames carry `(chunk_index, block_offset)`, the receiver manifest records partial chunk 0 at block granularity, progress counts received blocks, and on `LinkReady` the Wi‑Fi streams take chunk 0 from the first missing block (`Resume` ranges gain an optional byte offset) | WP3, WP4 |
| S2 | Whole‑file SHA‑256 sits in the `Offer`, so a 2 GB file is fully hashed before the receiver sees the card; that alone breaks "tap to first bytes ≤ 1 s" on midrange phones | architecture §7.2; PRD §8 | Make `Offer.files[].sha256` optional; hash while streaming and send a per‑file `FileDone{file_index, sha256}` control message before `Complete`; receiver verifies on `FileDone` | WP3, WP4 |
| S3 | Trusted‑only visibility: the beacon carries one 6‑byte `eph_id`, but each trusted peer holds a different `recognition_secret`, so at most one trusted peer can resolve any given advertisement | architecture §5.3; F‑A5, F‑J1 | Share the per‑device advertising secret `k_adv` (already defined) with trusted peers inside the encrypted handshake; all trusted peers resolve the same rotating ID; rotate `k_adv` on "Forget" or "Reset identity" and re‑share on next contact. Keep the per‑pair HKDF `"recog"` value only for the `Hello` proof | WP1, WP2 |
| S4 | Bundles must be reconstructible for resume: `Resume.missing[]` addresses `(file_index = 0xFFFFFFFF, chunk_index)`, so both sides must agree which small files went into bundle *n* | architecture §7.3, §7.6 | Define the bundle plan deterministically from the `Offer` file order (greedy fill in index order, ≤ 4 MiB each) and add `bundle_count` to the `Offer`; the receiver derives the same plan | WP3, WP4 |
| S5 | Who generates Wi‑Fi Direct credentials: the pack puts them in `Accept.chosen_link` (receiver), but group‑owner election can pick the sender | architecture §4, §7.2 | Rule: the elected group owner generates SSID/passphrase. Sender is GO → credentials in `Offer.link_options[]`; receiver is GO → in `Accept.chosen_link`. `LinkReady` always carries the measured `freq_mhz` | WP3, WP5 |
| S6 | Units drift between MB and MiB (chunk "4 MB" vs "4 MiB", bundle threshold "1 MB" vs "1 MiB") | features §C/E vs architecture §7 | Standardise on MiB internally; decimal MB only in UI speed readouts | WP3 constants |
| S7 | AEAD nonce = `stream_id ‖ counter` needs distinct stream ids for the control stream, the Bluetooth stream and each Wi‑Fi stream | architecture §7.1 | Reserve `stream_id 0` = control, `1` = Bluetooth, `2..` = Wi‑Fi data; directional keys already separate the two directions; add a loopback test asserting no (key, nonce) pair repeats | WP2, WP3 |
| S8 | `Interrupted → Cancelled after 24 h` (state machine) vs "reconnect for 2 min, then wait for the peer's beacon" (§7.8) | architecture §7.7, §7.8 | Both are right; make them explicit states `Reconnecting` (2 min active) and `Parked` (24 h passive) so the UI can show "Waiting for {name}" | WP3 |
| S9 | Android 15 caps `dataSync` foreground services at about 6 h per day | architecture §8 notes | No behaviour change; log the quota and surface it in the ring‑buffer log so support can see it | WP7e |
| S10 | **Android↔Android RFCOMM handshake is probably not reachable.** Apps cannot read their own Bluetooth Classic address (`BluetoothAdapter.getAddress()` returns `02:00:00:00:00:00` since Android 6), and app BLE advertising uses a resolvable private address, so a scanner cannot derive the peer's BR/EDR address to open `createInsecureRfcommSocketToServiceRecord` | architecture §6.1, §7.5; F‑B2, F‑E5 | Make **GATT** the default handshake and head‑start channel for Android↔Android (the pack already lists it as the alternative; expect 20–60 KB/s, enough for "first bytes ≤ 1 s"). Use RFCOMM only toward peers that can publish their Classic address: desktops put it in the 6 reserved beacon bytes (§5.1 offset 25), phones leave zeros. Verify on day 1 of WP7b whether any supported way exists on API 31+ to learn the local Classic MAC; none is expected | WP1 (beacon field), WP7b |
| S11 | Desktops cannot emit the §5.1 beacon as written. Windows `BluetoothLEAdvertisementPublisher` lets apps set **manufacturer‑specific data only** (Microsoft: `ServiceUuids` is not settable; Flags and Local Name are system‑controlled; about 20 bytes of payload). macOS `CBPeripheralManager` allows service UUIDs + local name only | architecture §5.1, §8 | Define one 14‑byte beacon **body** with two carriers: service data (Android, Linux BlueZ) and manufacturer‑specific data (Windows; needs a Bluetooth SIG company identifier — `0xFFFE` is for testing only). Scanners accept both. macOS never advertises: it scans and appears to phones via mDNS or its static QR (F‑B6, F‑H4), which the pack already allows | WP1, WP7a, WP10b/c |



### 3b. Found by an independent review of the pack (23 Sep 2026)

A second reader went through `architecture.md` against the acceptance criteria and the platform APIs. Items
marked *verify* rest on platform behaviour that the lab or the API docs must confirm before the code depends on it.

| # | Severity | Issue | Where | Recommended change | Fix in |
| --- | --- | --- | --- | --- | --- |
| N1 | Blocker | The SAS has no commitment round. A man in the middle answering last sees `eph_pk_A` first and can grind about 10⁶ X25519 keys until its 6-digit code matches the victim's, so the wrong-code test passes while the attacker is confirmed | §6.2; F‑B3; T‑11 | Commit then reveal, as in Bluetooth Numeric Comparison: `Hello` carries `SHA-256(eph_pk_A ‖ nonce_A)`, `HelloAck` reveals `eph_pk_B`, a third message `HelloReveal` reveals `eph_pk_A`, checked against the commitment | WP2 |
| N2 | High | The Hello signature covers only `eph_pk ‖ nonce`. Version, capabilities, nickname, platform and the peer's identity are unsigned (downgrade, stripped capability bits); there is no key confirmation; §7.1 does not say what the AEAD authenticates | §6.2, §7.1 | Each side signs `SHA-256(transcript)` over both complete messages; encrypted `Finished` MACs both ways; AEAD associated data = `length ‖ type ‖ stream_id`; trust `caps`/`version` only after verification | WP2, WP3 |
| N3 | High | After a reconnect the streams restart; if `(stream_id, counter)` restarts under the same key, AES-GCM nonces repeat. Session keys live in memory only, so an app kill or the 24 h parked window leaves nothing to resume under | §7.1, §7.6–7.8 | Run the handshake again on every reconnect (≤ 400 ms), require the same `identity_pk`, send `Resume` under the fresh keys | WP2, WP4 |
| N4 | Medium | F‑J1 ("two scans 20 min apart cannot be linked") does not hold: the nickname is in clear in the scan response, the BSSID hash is stable, capability bits fingerprint the device, mDNS publishes the permanent `device_id`, and OS address rotation is not aligned with the 15‑min epoch | §5.1, §5.3, §5.4; F‑J1 | In Trusted-only mode leave nickname and network hint out of the beacon; restart the advertising set at each epoch boundary; publish only `eph` in mDNS TXT; reword F‑J1 to what the beacon guarantees | WP1, WP7a |
| N5 | High | The resume bitmap may be persisted before the writer has flushed the chunk, so after an app kill the manifest claims bytes that are not on disk; per-chunk hashes are not kept, so a whole-file mismatch cannot name the bad chunk | §7.4, §7.6, §7.7 | Set a bit only after the chunk's write and `fsync`; keep the 16-byte chunk hashes in `chunk_manifest` so the receiver can re-hash `.part` and re-request exact ranges | WP4, WP6 |
| N6 | High | The network hint hashes the BSSID, but apps get `02:00:00:00:00:00` without location permission (Android 13+ too), and recent macOS and Windows also gate SSID/BSSID behind Location (*verify* builds) | §5.1, §5.2 bit 11, §11 | Derive the hint from link properties (gateway IP ‖ DHCP server ‖ IPv6 prefix); treat mDNS reachability as the real same-network test | WP1, WP7d |
| N7 | High | `WifiNetworkSpecifier` shows a system dialog for every new SSID; random per-transfer credentials mean a dialog on every hotspot join, and the request fails in the background, breaking auto-accept (*verify*) | §8; F‑E3 | Stable per-pair SSID/passphrase for trusted peers; prefer Wi‑Fi Direct `connect(config)` for phone↔phone; restate F‑E3 as "one dialog on first join per device" | WP5, WP7d |
| N8 | High | Apps cannot choose the local-only hotspot band and AOSP defaults it to 2.4 GHz (*verify* per OEM), yet the macOS and browser paths depend on it and need ≥ 17–20 MB/s | §4, §8; F‑H2, F‑D6; T‑18, T‑20 | Let desktops and browsers join the phone's Wi‑Fi Direct group as a legacy WPA2 client (band is app-selectable); keep the local-only hotspot as the last resort | WP5, WP7c, WP10 |
| N9 | High | Apps cannot leave infrastructure Wi‑Fi (`disconnect()` is a no-op at targetSdk ≥ 29). Chipsets without dual-band concurrency force the group onto the station channel, so a phone on a 2.4 GHz router gets a 2.4 GHz group. The serial LAN probe (1 s + 1 s) also breaks "ladder ≤ 1 s" when LAN is slow | §4, §9; F‑E1, F‑F3; T‑16 | Publish the station band in capabilities; elect the other device as group owner when one is on 2.4 GHz; add the hint "Your Wi‑Fi network is on 2.4 GHz"; start Wi‑Fi Direct formation in parallel with the LAN probe and cancel the loser | WP5 |
| N10 | Medium | `Windows.Devices.WiFiDirect` pairs with a discovered device; it cannot join a group by SSID and passphrase | §8; F‑E2, F‑H1 | One Windows "legacy join" provider (`WiFiAdapter.ConnectAsync`) for both the phone's group and its hotspot; the laptop leaves its network during the transfer and F‑E11 restore applies | WP10b |
| N11 | Medium | Android Keystore has no Ed25519/X25519 on API 31–32 (*verify*); Windows CNG and the Secure Enclave lack Ed25519 too, so the identity key is really a software key | §13; F‑B1 | State it plainly: a software Ed25519 seed wrapped at rest by a Keystore / keychain AES key (same for recognition secrets and the DB key) | WP2, WP7b |
| N12 | High | `Offer` carries the whole file list with hashes (about 0.5 MB of CBOR for 5,000 photos): seconds over RFCOMM and minutes over GATT before the card can show, and it has no thumbnails although the card shows six | §7.2; F‑D1; T‑02; design §5.1 | `Offer` becomes a summary (count, total bytes, type histogram, first six names, up to six previews ≤ 4 KiB); the full list follows after Accept as paged `FileList` messages; hashes move to `FileDone` (S2) | WP3, WP4 |
| N13 | Medium | It is undefined whether the control stream stays on Bluetooth after the hop; if it does, a Bluetooth drop marks a healthy Wi‑Fi transfer interrupted. Stopping advertising during a transfer makes both devices vanish from every radar | §7.2, §7.5, §9 | Move control to the first Wi‑Fi stream with a `ControlMoved` marker, keep Bluetooth as a secondary heartbeat; keep advertising at the background interval and pause only scanning | WP4, WP5, WP7 |
| N14 | Medium | Partials in `getFilesDir()` cannot be renamed into MediaStore (different volume), so every file is copied twice and needs twice the space | §7.6, §10.1 | On Android, back each partial with an `IS_PENDING` MediaStore item created at Accept and publish it on verify; keep only the manifest app-private | WP7e |
| N15 | Medium | The browser page: the local-only hotspot SSID and password are system-generated (no `DROP-7F3A`); `drop.local` needs a custom mDNS host that `NsdManager` cannot set before Android 15 (*verify*); the QR shows both the password and the URL token, so anyone who photographs it can fetch the files | §10.3; design §4.4, §10; F‑D6 | Show the real SSID and password; ship a small mDNS responder bound to the link interface; make the token single-use and ask "Allow this computer?" on the phone before serving `/files` | WP9 |

Sharpened items from §3:

- **S1 with S10.** GATT runs at roughly 20–100 KB/s (*verify*), so progress within 1 s needs blocks of 16 KiB or less, and the Bluetooth stream keeps one block in flight rather than two chunks.
- **S5.** `LinkReady` carries the credentials and the measured frequency; `Accept` carries a link intent, because the address is unknown before the link exists.
- **S7.** Every new data connection starts with an authenticated `StreamOpen` frame naming the transfer, stream id, direction and link generation, so the receiver can select the key and nonce space.
- **S9.** Background visibility needs a foreground service before any Offer arrives. Use the `connectedDevice` type for the radio session and `dataSync` only while a transfer runs (*verify* Play policy).
- **Scan response size.** A 14-byte name structure plus an 18-byte 128-bit UUID structure is 32 bytes, over the 31-byte limit. With a registered 16-bit UUID (decision 5) the scan response carries service data with the nickname and no 128-bit UUID.

---

## 4. Work packages

Conventions for every WP: branch `feat/<F-ID>-short-name` (or the session's assigned `claude/*` branch); PR title carries the feature IDs; PR body lists the acceptance criteria touched; CI green before merge (`./gradlew check` = unit + golden + Konsist + lint; plus the fuzz smoke from WP3 on, the loopback suite from WP4 on). "Session" = one Claude Code session that starts from `docs/implementation-plan.md` and ends with a pushed, CI‑green branch. "Lab" = needs the device lab; a human closes it.

### WP0 — Repository bootstrap (pack: week 1, day 1)
- **Features:** none; enables all. **Read:** `docs/architecture.md` §3, `docs/handoff.md` §3–4.
- **Build:** Gradle Kotlin DSL multi‑module KMP repo exactly as `architecture.md` §3: `core/{protocol,crypto,discovery,transfer,ladder,data}`, `platform/{android,desktop-win,desktop-mac,desktop-linux}`, `ui/{shared,android,desktop}`, `web-receive`, `tools/{bench,fuzz}`. Version catalog `gradle/libs.versions.toml`, wrapper. `core` interfaces: `BeaconRadio`, `DataChannel`, `WifiLinkProvider`, `LanDiscovery`, `FileStore`, `PowerPolicy`, plus `CryptoProvider`, `IdentityKeyStore`, `Clock`. Konsist test: `core` imports nothing from `android.*`, `androidx.*`, `java.awt.*`, `javax.swing.*`; dependency direction `ui → platform → core`. GitHub Actions: `check` on push and PR (JDK 21; Android SDK from the hosted runner), nightly workflow stub. ktlint + detekt. `.editorconfig`, `.gitignore`, `CONTRIBUTING.md` (handoff §3 rules), `.github/pull_request_template.md` (feature IDs + acceptance‑criteria checklist).
- **Android modules:** install Android cmdline‑tools into the session to compile them; if that fails, gate them on `ANDROID_HOME` in `settings.gradle.kts` and say so in the PR.
- **Done when:** `./gradlew build` passes locally and in CI with every module present (empty but compiling).
- **Estimate:** 1 session · 0.5–1 engineer‑day.

### WP1 — Discovery core: beacon, capability flags, rotating IDs, mDNS record (F‑A1 codec, F‑A4 flags, F‑J1, F‑A3 record; S3, S10, S11; decision 5)
- **Read:** architecture §5; features A and J1; testing §5 "Unit" row; design §3.2.
- **Build in `core/discovery`:** `Beacon` encode/decode to the exact 31‑byte layout (§5.1) with the two carriers of S11 and the optional Classic address of S10; scan response (nickname truncated to 12 UTF‑8 bytes at a code‑point boundary); `Capabilities` 16‑bit flags (§5.2); `EphemeralId` = HMAC‑SHA256(k_adv, epoch)[0..6], epoch = floor(t/900), resolver over epochs e‑1..e+1; visibility/platform byte; network hint = SHA‑256(BSSID)[0..4]; `MdnsRecord` TXT encode/decode (§5.4); RSSI→ring smoothing (EWMA α 0.2, 5 dBm hysteresis) and hash‑derived angle as pure functions for the radar (design §3.2).
- **Tests:** round trips, golden bytes, size ≤ 31 always, rotation exactly at epoch boundaries, adjacent‑epoch resolution, unlinkability (IDs 20 min apart share nothing beyond chance), fuzzed decode never throws.
- **Done when:** green in CI. Fully session‑verifiable.
- **Estimate:** 1 session · 1–2 days.

### WP2 — Crypto core (F‑B1 core, F‑B2, F‑B3, F‑B5 payload, F‑E9 primitives; S3, S7; decision 7)
- **Read:** architecture §6, §7.1, §13.
- **Build in `core/crypto`:** `CryptoProvider` interface (X25519, Ed25519 sign/verify, HKDF‑SHA256, AES‑256‑GCM, ChaCha20‑Poly1305, SHA‑256, HMAC, secure random) with a `jvmMain` implementation on JCA (HKDF hand‑written, RFC 5869 vectors); handshake key schedule (`ss`, `k_session`, directional keys, `recog`, the shared `k_adv` of S3); `Hello`/`HelloAck` construction and signature check (Ed25519 over `eph_pk ‖ nonce ‖ peer nonce`); SAS exactly as §6.2 (6 digits, zero‑padded); `FrameCipher` with nonce = `u32 stream_id ‖ u64 counter` and a rekey guard; QR payload (§6.3): CBOR, Ed25519 over preceding fields, base64url, 5‑min expiry, static form without `link`/`exp`.
- **Tests:** RFC 7748 / RFC 8032 / RFC 5869 vectors; SAS golden; wrong SAS cannot be confirmed (F‑B3, T‑11); tampered `Hello` rejected; expired QR refused (T‑12); nonce never repeats across 10⁶ frames.
- **Done when:** green in CI. Fully session‑verifiable.
- **Estimate:** 1–2 sessions · 2–3 days.

### WP3 — Protocol core: framing, control messages, golden CBOR, state machine, fuzzer (F‑E9 control stream, F‑E6 frame format; S1, S2, S4, S5, S6, S7, S8)
- **Read:** architecture §7.1–7.3, §7.7, §7.8; testing §5 "Golden" and "Fuzz" rows.
- **Build in `core/protocol`:** `Frame` codec (`u32 len ‖ u8 type ‖ payload`, hard cap 4 MiB + header); every control message as `@Serializable` CBOR with integer `@CborLabel` keys (compact, stable): `Offer`, `Accept`, `Decline`, `Ack`, `Resume`, `Hint`, `LinkReady`, `Heartbeat`, `Complete`, `Cancel`, plus `FileDone` (S2) and block‑granular fields (S1); `ChunkFrame` header (transfer_id 16 B, file_index, chunk_index, payload_len, hash 16 B) and the bundle index layout; deterministic `BundlePlan` (S4); `TransferStateMachine` as a pure reducer with the §7.8 timeout table and the `Reconnecting`/`Parked` states (S8); golden byte files under `core/protocol/src/commonTest/resources/golden/*.cbor` for every message; `tools/fuzz` harness (random bytes + mutation of the golden files) with a 10‑second CI smoke job and a long nightly run.
- **Done when:** golden tests and fuzz smoke green in CI. Fully session‑verifiable.
- **Estimate:** 1–2 sessions · 2–3 days.

### WP4 — Transfer engine core (F‑E5 logic, F‑E6, F‑E7, F‑E8, F‑E10, F‑F1, F‑F6 logic; S1, S2, S4; decision 6)
- **Read:** architecture §7.4–7.6, §9, §15; features E and F1; testing §5 "Integration" row.
- **Build in `core/transfer`:** `Chunker` (4 MiB chunks, direct `ByteBuffer`s, 64 KiB AEAD blocks to keep memory flat); `Bundler` (< 1 MiB files, deterministic plan); `StreamScheduler` (shared work‑stealing queue, per‑stream window 2, 4 → 8 streams above 40 MB/s, → 2 under thermal); receiver `WriteQueue` (16 MiB bounded, one writer per destination file, backpressure); `AckBatcher` (50 ms or 8 chunks); `ResumeManifest` bitmap math + write‑behind ≤ 100 ms through `FileStore`; `HeadStart` (Bluetooth stream at block granularity, hop at a block boundary, no duplicates); `ThroughputMeter` (250 ms samples, EWMA α 0.3, ETA); per‑chunk XXH3‑128 + whole‑file SHA‑256 with `FileDone`; `Hint` emission (`bundling`, `thermal`, `sdcard` from `FileStore`).
- **Tests:** unit for each part; JVM integration = two engines over loopback TCP `DataChannel`s with fault injection (drop, reorder, disconnect mid‑chunk, simulated app kill, corrupted chunk → re‑request, disk full → cancel), asserting byte‑exact output and "total bytes moved = file size" (the T‑05, T‑07, T‑27, T‑28 equivalents); memory budget (≤ 120 MB extra for a 2 GB loopback transfer) as a JVM test under a fixed `-Xmx`.
- **Done when:** integration suite green in CI. Session‑verifiable except real MB/s (lab T‑01, T‑02).
- **Estimate:** 2–3 sessions · 5–8 days. Largest core package.

### WP5 — Transport ladder core (F‑E1, F‑E4 decision, F‑E11 policy, F‑F2, F‑F3 codes; S5)
- **Read:** architecture §4, §9; design §8.2–8.3.
- **Build in `core/ladder`:** `Ladder(capsA, capsB, hintA, hintB, radioState) → List<LinkCandidate>`; group‑owner election (5 GHz host > Wi‑Fi 6 > battery > receiver); `LinkLifecycle` state machine with timeouts (LAN 1 s connect + 1 s measure, P2P 6 s, hotspot 6 s), 5 GHz verification with one re‑form, LAN < 10 MB/s fall‑through, teardown and restore within 5 s or after 60 s idle; `TransportBadge` from (kind, freq_mhz) using the exact strings of design §8.3; the hint‑code table.
- **Tests:** table‑driven over the flag combinations that matter (mobile‑data pair skips LAN; 2.4‑only peer; no Wi‑Fi Direct → hotspot; nothing → Bluetooth); timeout fall‑through with a fake clock; restore timing.
- **Done when:** green in CI. Logic session‑verifiable; real behaviour is lab (T‑04, T‑14, T‑15, T‑16).
- **Estimate:** 1 session · 2 days.

### WP6 — Data layer (architecture §12; backs F‑G2, F‑G3, F‑G5, F‑B4, F‑E10 persistence, F‑J2 hook)
- **Build in `core/data`:** SQLDelight schema exactly as §12; repositories (device, transfer, transfer_file, chunk_manifest, settings); the 24‑hour partial/manifest cleanup job; JVM sqlite driver for tests. Encryption is a driver‑factory hook filled by WP7 (SQLCipher) and WP10 (OS keychain).
- **Tests:** repository round trips; migration test; 10,000‑row history query performance (F‑G2).
- **Estimate:** 1 session · 1–2 days.

### WP7 — Android platform (device‑bound: sessions write and compile, the lab verifies)
Sub‑packages, each its own PR, in order:
- **7a BLE advertise/scan + capability detection** (F‑A1, F‑A2 scan, F‑A4, F‑A6): `BluetoothLeAdvertiser`/`Scanner` with §5.1 timing (100 ms / 1 s; low‑latency / low‑power; service‑UUID filter, both carriers of S11); `WifiManager.is5GHzBandSupported()` / `is6GHzBandSupported()` / `isP2pSupported()`, `PackageManager` features, SD‑card default detection; radio‑enable dialog and Wi‑Fi panel. Lab: ≤ 500 ms discovery, T‑10 crowded room.
- **7b Handshake channel + identity** (F‑B1, F‑B2; S10; decision 7): GATT server + client channel (MTU‑negotiated, ≤ 512 B writes) as default; RFCOMM insecure socket toward desktops that advertise a Classic address; Android Keystore‑backed identity (Ed25519 key wrapped by a Keystore AES key if the Keystore cannot hold Ed25519 natively); `CryptoProvider` Android implementation. Lab: handshake ≤ 400 ms.
- **7c Wi‑Fi Direct** (F‑E2, F‑F5): `createGroup(WifiP2pConfig)` with `GROUP_OWNER_BAND_5GHZ`, `DIRECT-xx-…` name, random passphrase, persistent mode for trusted pairs; joiner `connect(config)`; read `WifiP2pGroup.getFrequency()`; sockets from the P2P `Network`. Lab: the per‑OEM 5 GHz table (the pack's week‑1 output), T‑01.
- **7d Hotspot + join + LAN sockets** (F‑E3, F‑E4, F‑A3): `startLocalOnlyHotspot`; `WifiNetworkSpecifier` + `requestNetwork`; `network.socketFactory` / `bindProcessToNetwork`; `NsdManager` announce/discover. Lab: T‑15 zero mobile data, T‑16, T‑20.
- **7e TransferService** (F‑E12, F‑E11, F‑F6, F‑F4; S9): foreground `dataSync`, partial wake lock, thermal listener → 2 streams + hint, Wi‑Fi restore (release request, remove group), pre‑warm on file pick, stop 60 s after the last transfer; MediaStore `IS_PENDING` writes, Downloads, partials in `filesDir/partials`. Lab: T‑05–T‑09, T‑14.
- **7f Permissions + OEM** (F‑I1, F‑I2): the §11 matrix, just‑in‑time sheets, the Android 12 location path (T‑24), brand screens for Xiaomi/Vivo/Oppo/Realme/Samsung, battery‑optimisation intent. Lab: T‑08, T‑22.
- **Estimate:** 3–4 sessions of coding · lab‑bound calendar ≈ 4 weeks (pack weeks 1, 4, 5, 7). The OEM table is the schedule risk (PRD §9).

### WP8 — Shared UI + Android UI (F‑A2 radar, F‑C1–C5, F‑C7, F‑D1–D5, F‑G1–G5, F‑I1, F‑I3, F‑I4, F‑J4, badge and hint display; decisions 1, 2, 8, 9)
- **Read:** `design.md` entirely; features C, D, G, I; architecture §10.1.
- **Build in `ui/shared` (Compose Multiplatform):** tokens (design §1) with light/dark; radar (rings, bubble placement from WP1's smoothing, hash‑derived angle, 72 dp repulsion, "+N more" above 12 devices); the §3.3 motion table + reduced motion; file‑picker sheet; sending state + drop animation (≤ 8 flyers, "+N" tile); incoming card (30 s bar, SAS row, "Always accept"); tray with spring; dashboard 5 tabs (single‑series Stats chart); onboarding (welcome, brand step); every empty/error state and copy key of §8 externalised (`en`, `hi`); accessibility §11 (labels, 200% font, 48 dp targets).
- **Build in `ui/android`:** Activity; share‑sheet `ACTION_SEND` / `ACTION_SEND_MULTIPLE`; direct‑share shortcuts (F‑C3); CameraX + ZXing scan; permission flows; progress notifications.
- **Tests:** Compose screenshot tests on the JVM for radar states, cards, tabs, light/dark, 200% font (testing §5 "UI" row); state‑to‑UI mapping tests (F‑G1 ≤ 250 ms is a `StateFlow` test). 60 fps (F‑C4) is lab on the midrange phone.
- **Estimate:** 4–6 sessions · 3–4 weeks including designer time (pack weeks 2, 6, 8).

### WP9 — Browser receive (F‑D6, F‑H4 browser path; architecture §10.3; design §10)
- **Build:** `web-receive/index.html`, one file < 30 KB, no frameworks, light/dark, file list, "Download all (zip)" as a streamed stored zip, per‑file links, progress, optional upload dropzone (P1), privacy sentence. Ktor CIO server module used by `platform/android` (and by desktops for the no‑Bluetooth PC path), bound to the link interface only, one‑time token in the path, `/files`, `/file/{i}`, `/all.zip`, `/upload`, shutdown 60 s after the last download; mDNS `drop.local` announcement.
- **Tests:** Ktor test host for endpoints and token refusal; Playwright (Chromium is preinstalled here) drives the page: renders, lists files, downloads the zip, uploads (P1); size gate test (< 30 KB). Safari/Edge/Firefox (T‑21) and 1 GB at ≥ 20 MB/s (T‑20) are lab.
- **Estimate:** 1–2 sessions · 3–4 days.

### WP10 — Desktop apps (F‑H1–H5, F‑C6, F‑B6; architecture §8, §10.2; design §9)
- **10a Shared desktop shell** (`ui/desktop`, `platform/desktop-linux` LAN parts first, because a session can run them): 420 × 640 window, drag‑and‑drop onto bubbles, tray, Received folder `~/Received/<App>/`, keyboard shortcuts, static device QR (F‑B6), no‑Bluetooth banner, JmDNS discovery + LAN path on the shared engine. Session test: two desktop instances on loopback send a folder of 1,000 files with bundling (F‑C6) — verifiable here.
- **10b Windows helpers** (F‑H1; S11): WinRT BLE publisher (manufacturer‑data carrier) and watcher, `RfcommServiceProvider` / `StreamSocket`, `Windows.Devices.WiFiDirect` client, `WiFiAdapter.ConnectAsync`, Mark‑of‑the‑Web, MSI. Lab: T‑17.
- **10c macOS helper** (F‑H2; S11): Swift helper process (CoreBluetooth central + GATT handshake, CoreWLAN join), Bonjour, quarantine attribute, DMG + notarisation. Lab: T‑18.
- **10d Linux** (F‑H3): BlueZ D‑Bus advertising and RFCOMM, NetworkManager join, Avahi, .deb / AppImage / Flatpak. Lab: T‑19.
- **Estimate:** 4–6 sessions of coding · the pack allocates one desktop engineer for weeks 4–10 (≈ 6–7 weeks). Signing and notarisation credentials are human‑owned (roadmap: EV certificate lead time is weeks).

### WP11 — Bench, fuzz nightly, observability (architecture §14; testing §3, §5; handoff §7)
- **Build:** `tools/bench`: `bench send --mix <mix> --peer <Y>` driving two phones over `adb`, recording Offer / Accept / first‑byte / link‑ready (with frequency) / complete and writing the CSV row exactly as testing §3 step 3; nightly workflow runs the A–B cell at 3 m with the "big" mix and fails on a > 10% median regression (needs a self‑hosted runner attached to the rig — human setup). Ring‑buffer log (2,000 events) with export from Settings → About. Long fuzz run nightly on top of WP3's smoke.
- **Estimate:** 1–2 sessions · 2–3 days + rig setup (human).

### WP12 — Security and privacy closure (F‑J1–J4; architecture §13; T‑11, T‑12, T‑13, T‑26)
- **Build:** name sanitisation, confinement to the receive folder, size enforcement against the `Offer` (F‑D5, with path‑traversal test files); APK/executable warning; SQLCipher on Android (F‑J2, P1); privacy statement screen; egress guard (all sockets through the link `Network`; a unit test that the engine never opens a socket outside a `DataChannel`). Lab: packet capture T‑26; spoof and mismatch T‑11, T‑13.
- **Estimate:** 1 session · 2 days + one lab day.

### WP13 — Beta and Phase 1.1 (human‑led; sessions assist with fixes)
- Play internal testing → 50‑user beta, crash and feedback loop, OEM fixes, signed installers, beta metrics export, beta exit report (testing §6), Play production. Pack: ≈ 3 weeks.

### Later phases (headline only; plan them when Phase 1 exits)
- **Phase 2 — iPhone (≈ 6 weeks):** `BeaconRadio` over CoreBluetooth, GATT handshake (already the default channel after S10), `NEHotspotConfiguration` join, Multipeer iPhone↔iPhone, share extension; same protocol.
- **Phase 3 — internet mode (≈ 8 weeks):** `RemoteLinkProvider` (signalling, ICE, relays), push wake, encrypted cloud drop, share links, mobile‑data warnings, paid tier; engine unchanged (architecture §17).
- **Later:** courier mode (per‑file encryption to the recipient's identity key), Wi‑Fi Aware (F‑F7), Windows share target, clipboard sync.

---

## 5. Order and dependencies

```
WP0 → WP1 ─┐
       WP2 ─┴→ WP3 → WP4 ─┬→ WP9 → WP10a → WP10b/c/d
                    WP5 ─┤
                    WP6 ─┴→ WP8 (needs WP1 smoothing, WP5 badges, WP6 repos)
                          → WP7a → 7b → 7c → 7d → 7e → 7f (needs WP2–WP6)
WP11 (bench needs WP7c/d) → WP12 → WP13
```

Device‑free critical path (sessions only): WP0 → WP1/WP2 → WP3 → WP4 → WP9 / WP10a ≈ 8–11 sessions. Everything after that waits on hardware in someone's hands.

---

## 6. Time estimates

Engineer time per the pack's own team (2 Kotlin engineers from week 1, desktop engineer from week 4, QA from week 5, part‑time designer): Phase 1 ≈ 37 person‑weeks over 10 calendar weeks; Phase 1.1 ≈ 9 person‑weeks over 3 weeks. This plan does not shorten that calendar: lab weeks 4–10 are the critical path.

Claude Code session view (one WP‑sized PR per session, reviewed before the next):

| Group | WPs | Sessions | Verifiable inside a session? |
| --- | --- | --- | --- |
| Bootstrap + device‑free core | 0, 1, 2, 3, 4, 5, 6 | 8–11 | Yes, fully (CI) |
| Browser receive + desktop shell | 9, 10a | 2–3 | Yes (Playwright, loopback) |
| Shared / Android UI | 8 | 4–6 | Screenshot tests yes; 60 fps on device no |
| Android radios and service | 7a–7f | 3–4 coding + lab iteration | Compile yes; behaviour no |
| Desktop OS helpers | 10b–10d | 3–4 coding + lab | Compile partly; behaviour no |
| Bench, fuzz, observability | 11 | 1–2 | Fuzz yes; rig no |
| Security closure | 12 | 1 | Code yes; capture no |
| **Phase 1 total** | | **≈ 22–31 sessions** | |

Calendar if sessions run back to back with same‑day review: the device‑free groups take ≈ 2–3 weeks; the rest tracks the pack's weeks 4–10, because each Android or desktop WP needs a lab pass before the next is worth starting.

---

## 7. Verification (end to end)

- Every session: `./gradlew check` (unit, golden, Konsist, ktlint/detekt) green locally and in GitHub Actions on the pushed branch; from WP3 the fuzz smoke; from WP4 the loopback integration suite; from WP8 screenshot tests; from WP9 Playwright.
- Definition of done per feature = `docs/handoff.md` §6. Release gate = all P0 rows in `docs/features.md`, PRD §8 metrics on the matrix, a clean T‑26 capture, Wi‑Fi restore on every phone, 50 beta users.
- The lab matrix `docs/testing.md` §4 (T‑01…T‑28) is run by the QA owner; results feed the beta exit report (§6).

---

## 8. Session protocol for whoever picks this up

1. Read the root `README.md`, then your WP in `docs/implementation-plan.md`, then the pack sections it lists.
2. Branch per `docs/handoff.md` §3 (or the assigned `claude/*` branch); one WP per PR; feature IDs in the title and in test names.
3. Resolve the WP's items from §2 and §3 first; small spec edits go into `docs/architecture.md` in the same PR with a "Changed:" note at the top of the section.
4. Push only when `./gradlew check` is green. For device‑bound WPs, state plainly in the PR which acceptance criteria are compile‑only and which the lab must run.


---

## 9. Progress log

| WP | Status | Notes |
| --- | --- | --- |
| Plan + pack in `docs/` | Done | This file and the eight pack files |
| WP0 Bootstrap | Done | All modules compile; Android debug and release APKs build. Notes: compile and target SDK are 37 because Compose 1.12 requires it; detekt is not added yet (1.23.8 predates Kotlin 2.4 and 2.0 is alpha), ktlint is the lint gate; Android modules are skipped when no SDK is found |
| WP1 Discovery core | Done | Beacon body (14 B, 20 B with a Classic address), both carriers (S11), scan response, rotating IDs from the shared advertising secret (S3), network hint from link properties (N6), mDNS TXT without the permanent id (N4), radar maths and the `NearbyDevices` aggregator. 164 tests. Placeholders: service UUID `0xDF01`, company id `0xFFFF` (decision 5) |
| WP2 Crypto core | Done | Commit-then-reveal handshake (N1) with transcript signatures and Finished MACs (N2), pairing-attempt limiter, SAS, frame cipher with per-stream nonces (S7), signed QR payload, trusted proof bound to the beacon epoch, software identity store behind `SecretStorage` (N11). 136 tests; key schedule cross-checked against an independent Python implementation |
| WP3 Protocol core | Done | Frames and their associated data (N2), 15 CBOR control messages with golden bytes, summary `Offer` + paged `FileList` (N12), `FileDone` (S2), chunk header with block offset (S1), deterministic bundle plan (S4), state machine with `Reconnecting`/`Parked` (S8), fuzzer with a CI smoke run. 152 + 15 tests |
| WP5 Transport ladder core | Done | Planner, S5 negotiation, per-pair stable Wi‑Fi Direct credentials (N7), lifecycle reducer with 5 GHz verify and one re-form, `LadderRunner` racing the LAN probe against Wi‑Fi Direct (N9), desktops and browsers join the phone's group as legacy clients (N8, N10), badge and hint rules. 122 tests. New badge keys and hint copy need design sign-off |
| WP6 Data layer | Done | SQLDelight schema v1 with the S3 and N5 additions, repositories with Flow, stats that reconcile with History, 24 h resume-data cleaner, driver factory hook for SQLCipher. 134 tests |
| WP9 Browser receive | Done | Single-file page under 30 KB, Ktor server with token path and per-browser "Allow this computer?" approval (N15), streamed STORED zip with ZIP64, Range, uploads, idle shutdown, a small mDNS responder for `drop.local`, Playwright end-to-end script. 156 tests |
| WP4 Transfer engine core | Done | Secure sessions (handshake, Finished, StreamOpen), Bluetooth head start in 16 KiB blocks and the hop, work-stealing Wi‑Fi streams (4/8/2), batched acks, heartbeats, Resume and reconnects with a fresh handshake (N3), N5 durability order, file-name sanitising, `DirectoryFileStore`, TCP channels, and `LadderTransferBridge` wiring the ladder to the engine. Measured on loopback: about 104 MB/s, about 74 MB extra heap for a 256 MiB transfer. 99 + 3 tests |
| WP8 Shared UI and Android shell | Done | Radar, send and receive flows, drop animation, tray, dashboard tabs, onboarding, `en` + `hi` strings (Hindi needs native review), accessibility, presenters, screenshot tests; Android share sheet, direct-share shortcuts, just-in-time permissions. 157 + 20 tests. Not built yet: CameraX/ZXing scanning and progress notifications (need WP7's service) |

### Carried forward from WP1–WP3

- **WP4** adapts `FrameProtector` (core/protocol) to `FrameCipher` (core/crypto), sends `localFinished()` as the first frame on stream 0 and acts on nothing before `verifyPeerFinished`, computes XXH3-128 per frame, and runs a new handshake on every reconnect or `FrameLimitException` (N3). A file that fails after three strikes needs its remaining units released so `AllChunksAcked` can fire.
- **WP4** (moved from WP5) tries every candidate endpoint in `NearbyDevice.lanEndpoints` / `radioAddresses` and the Offer's LAN option behind the handshake identity check; unauthenticated LAN records are only hints.
- **WP6** stores the peer advertising secret next to `recognition_secret`, and the Classic address of trusted desktops (Trusted-only desktops stop advertising it).
- **WP7a / WP10** restart the advertising set at `BeaconAdvertisement.validUntilMillis` so the radio address rotates with the ID (N4), and verify in the lab whether dictionary-style scanners merge the scan response with the beacon under one UUID; if they do, send the nickname as manufacturer data.
- **WP8** animates a stranger's bubble being replaced at each 15-minute rotation, and shows the SAS as soon as the handshake result exists.
- **Docs:** reword F‑J1 in `features.md` to what the beacon guarantees (IDs unlinkable across epochs; the whole advertisement only in Trusted-only mode).

### Carried forward from WP5, WP6, WP9

- **WP4** implements the ladder's `LadderSession` (send `LinkReady`, open the first `StreamOpen` stream, `sendLinkSelected` / `onPeerSelected` so the receiver's view of the LAN check wins), feeds receiver-side throughput samples and link losses to `LadderRunner`, emits `ControlMoved` when `state.dataLink` changes (N13), and advances the generation base by 6 per ladder run. It persists in the N5 order: fsync the `.part`, then mark the manifest, batched at 100 ms or less.
- **Protocol gaps to close later:** battery level and "can host right now" are not on the wire, and there is no "candidate failed" message, so one side waits out its own timeout when the other's rung fails early.
- **WP7:** a SQLCipher `SqlDriverFactory`; a `MulticastLock` while the mDNS responder runs; lab checks that Android groups accept legacy WPA2 clients per OEM and that both ends report `getFrequency()`.
- **WP8:** approve the new badge keys (`badge.p2p`, `badge.p2p_6`, `badge.hotspot_plain`) and hint copy; build the "Allow this computer?" prompt; the QR and the "computer without the app" hint must carry the port and token path (`http://drop.local:<port>/t/<token>/`) and the real SSID and password; "Clear partial files" must stop parked transfers first.
- **WP10:** desktops add the SQLite JDBC driver themselves and can reuse `ReceiveServer` for the no-Bluetooth PC path.
- **WP11:** the nightly job runs the ZIP64 test (needs about 4.5 GB free disk) and can run the Playwright script.
- **WP12:** the stable per-pair SSID links a trusted pair's sessions on the air; note it in the privacy review.

### Carried forward from WP4 and WP8

- **WP7** supplies the Android adapters the engine needs: a MediaStore/SAF `FileStore` (N14), a `TcpSocketFactory` bound to the link's `Network`, the Bluetooth `DataChannel`s, a real `PowerPolicy`, and the `TransferService` that replaces the in-memory ports in `ui/android`'s `AppGraph`. It also builds CameraX + ZXing scanning (calling `onCodeScanned` with a verified device key), progress notifications, and the Language setting via `LocaleManager` on 13+.
- **App layer (WP7e, WP10a):** adapt core/data's manifest and file repositories to the engine's `ResumeStore`, and run the 24 h sweep of partials for transfers that are never resumed.
- **WP11:** run the nightly sizes (5,000 small files, a 1 GiB throughput run, a 2 GiB memory run with `-Pdrop.nightly=true`).
- **Crypto follow-up:** reconnect handshakes with a pinned identity are charged against the pairing-attempt limiter; decide whether a pinned expected identity may skip it.
- **Engine follow-ups:** control shares the first Wi‑Fi stream with chunks, so a control message can wait behind one 4 MiB write; a bundle of more than about 1,360 tiny files delays first progress to the second Bluetooth block.
- **Design sign-off:** the AA-contrast colour variants (primary button `#285EE8`), new copy keys, and the 150–200% font layouts.
