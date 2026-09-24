# Design Specification (UX / UI)

**Version:** 0.1 draft · **Date:** 23 Sep 2026 · **Platforms:** Android 12+ (Jetpack Compose), desktop (Compose Multiplatform), browser receive page (plain HTML/CSS)

Design principle: **the radar is the app.** One screen does 90% of the job; everything else is a sheet or a tab
that slides over it. Every send is three taps or fewer. Motion explains what is happening (files fly *into* a
device), and the transport badge plus one-line hints make speed understandable, never mysterious.

The gesture people already know from UPI is the mental model: **"Scan to send."**

---

## 1. Visual language

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| `bg` | #F7F8FA | #0E1116 | Screen background |
| `surface` | #FFFFFF | #171B22 | Cards, sheets, tray |
| `text` | #14171C | #ECEFF3 | Primary text |
| `text-muted` | #5F6773 | #9AA3AF | Secondary text, timestamps |
| `accent` | #2F6BFF | #4C82FF | Rings, progress, primary buttons (placeholder brand colour) |
| `accent-soft` | #E6EDFF | #1B2540 | Bubble fill when idle |
| `success` | #1FA971 | #2ECC8A | Completion tick, "done" |
| `warning` | #E0A400 | #F2B824 | Hints, slow mode |
| `danger` | #D9433B | #FF6B61 | Decline, failed |
| `ring-1/2/3` | accent at 35% / 20% / 10% opacity | same | Radar rings |

- **Typography:** system font (Roboto on Android; Segoe UI / SF Pro / Inter on desktop). Sizes: title 22 sp, body 16 sp, caption 13 sp, speed readout 28 sp tabular numerals.
- **Shape:** 16 dp corner radius on cards and sheets; bubbles are circles; buttons 12 dp.
- **Elevation:** one level only (sheets over radar); no stacked shadows.
- **Dark mode:** follows system; radar rings brighten slightly in dark mode so they read on OLED.
- **Iconography:** outline icons, 24 dp. App icon concept: a single radar arc with a small upward arrow, original artwork; do not imitate AirDrop's concentric-wave icon or any competitor mark.

---

## 2. Screen map

```
Radar (home)
├── Bottom bar: [Scan QR] [Show my QR] [Dashboard]
├── Visibility chip (top-right): Everyone · 10 min · Trusted · Hidden
├── Tap bubble → File picker (sheet) → Sending (bubble state)
├── Incoming card (sheet, on receiver) → Receiving (bubble state) → Tray
├── Long-press bubble → Device options (sheet)
├── Scan QR (full-screen camera)
├── Show my QR (sheet)
└── Dashboard (full screen, 5 tabs): Live · History · Devices · Stats · Settings
Onboarding (first launch): Nickname → Permissions (just-in-time later) → Brand steps (if needed)
Desktop window: Radar + drop zone + tray icon menu
Browser receive page (served by phone)
```

---

## 3. Radar screen

### 3.1 Layout

- Your avatar (initials or photo, 56 dp) sits at bottom-centre, 96 dp above the bottom bar.
- Three concentric rings centred on the avatar: radii 30%, 55%, 80% of the shorter screen side.
- Bubbles (56 dp, 64 dp when trusted) carry initials or avatar, a 12 sp name label below, a small platform glyph (phone / laptop) and a trusted badge (shield, 14 dp) at top-right.
- Bottom bar: three actions. Visibility chip at top-right shows the current mode and remaining minutes for "Everyone · 10 min".

### 3.2 Bubble placement (signal → ring)

| RSSI (dBm, smoothed) | Ring | Meaning shown |
| --- | --- | --- |
| ≥ −55 | Inner | "Right here" |
| −56 to −70 | Middle | "Nearby" |
| < −70 | Outer | "Far" |

- Smoothing: exponential moving average over 250 ms samples, α = 0.2 (≈ 1–2 s settle).
- Hysteresis: 5 dBm between rings so a bubble does not oscillate.
- Angle: derived from a hash of the device ID so a device keeps its position between sessions; simple repulsion (min 72 dp centre-to-centre) resolves overlaps; positions ease over 400 ms.
- Devices found only via mDNS (no RSSI) sit on the middle ring with a small "network" glyph.
- More than 12 devices: outer ring collapses into a "+N more" bubble that opens a list.

### 3.3 Motion

| Element | Animation | Duration | Easing |
| --- | --- | --- | --- |
| Rings | Pulse outward from avatar, opacity 0.35 → 0, scale 0.9 → 1.05, staggered 800 ms | 2.4 s loop | ease-out |
| Bubble appear | Fade 0 → 1, scale 0.8 → 1 | 200 ms | standard decelerate |
| Bubble leave | Fade to 0, scale to 0.9 (after 5 s without beacon) | 200 ms | accelerate |
| Bubble selected | Scale 1 → 1.3, ring appears at 0% | 180 ms | overshoot 1.05 |
| Reposition | Ease to new position | 400 ms | standard |

Reduced motion (system setting): rings static at 20% opacity, no fly animation, progress ring still animates.

---

## 4. Send flow

### 4.1 File picker (sheet, 80% height)

Tabs: **Photos** (grid, 3 columns, recent first, multi-select with count badges) · **Files** (system picker via SAF for documents) · **Apps** (installed apps as APKs — only if decision F‑J/APK is "yes").
Footer: "Send 12 items · 48 MB" primary button; disabled at zero selection. Selection total updates live.

### 4.2 Sending state (on radar)

> Changed: the transfer states gained AirDrop-like motion (all of it off, or still, under reduced motion). While bytes move, a faint dashed arc joins the avatar and the busy bubble and glowing dots flow along it (out of the avatar for a send, into it for a receive) and a soft glow breathes behind the bubble (and behind your avatar while receiving). While the other side is awaited (connecting, "Waiting for them to accept", reconnecting) a bright head circles the bubble's ring. The flyers travel on upward arcs that fan out, with a small spin that straightens as they land, and a splash ring marks the landing; a receive plays the same flight from the sender's bubble into your avatar when its first bytes arrive. Completion adds a success ring and ten sparks behind the pop. The incoming card's sender avatar ripples while it waits for an answer (§5.1).

- Selected bubble at 1.3× with a 4 dp progress ring (accent) that fills clockwise from 12 o'clock.
- Under the bubble: `44 MB/s · 45 s left`, then a transport badge chip: `Wi‑Fi Direct · 5 GHz`.
- Below the badge, an optional hint line (warning colour) when speed is limited (see §8).
- "Drop" animation: up to 8 file thumbnails (48 dp) fly from the bottom-centre to the bubble, staggered 60 ms, 400 ms each, cubic-bezier(0.2, 0.8, 0.2, 1), shrinking to 0.3 scale as they enter. More than 8 items shows a "+N" tile as the last flyer.
- Cancel: a small × at the bubble's bottom-right; confirm sheet only if > 100 MB has already transferred.
- Completion: ring snaps full, bubble scales 1.15 → 1.0 (250 ms), tick icon on success colour, light haptic (Android `CONFIRM`), then bubble returns to idle after 1.5 s.

### 4.3 Share-sheet entry

Gallery/Files → Share → app. The app opens directly on the radar with a top banner "Sending 12 photos · 48 MB — tap a device". Nearby trusted devices also appear as **direct share targets** inside the Android share sheet (icon + name), so gallery → device is two taps.

### 4.4 Scan to send

- **Scan QR:** full-screen camera with a rounded viewfinder and the line "Scan the receiver's code". On success: haptic, viewfinder turns success colour, immediately returns to the radar with that device selected and the picker open (or sends the attached files if arriving from the share sheet).
- **Show my QR:** sheet with a large QR (min 240 dp), the nickname, a 6-digit fallback code, and a footer "Open on a computer without the app: join Wi‑Fi `DROP‑7F3A`, password shown, then visit `http://drop.local`" (exact wording in §9). QR refreshes every 5 minutes with a subtle progress arc.

---

## 5. Receive flow

### 5.1 Incoming card (sheet, ~45% height)

- Header: sender avatar (48 dp), "**Dev** wants to send", line 2 "12 photos · 48 MB".
- Thumbnail strip (up to 6 + "+N"); documents show file-type glyphs.
- Buttons: **Accept** (primary, full width), **Decline** (text). Checkbox: "Always accept from Dev".
- 30 s countdown as a thin bar under the header; on timeout the card slides down and the sender sees "No answer".
- Sender's trust state: verified badge or "New device — first time" caption; first-time pairing shows the 6-digit code above the buttons with "Same code on both screens?".

### 5.2 Receiving state and tray

- The sender's bubble shows the same ring/speed/badge as the sending side.
- Finished files land in a **tray** at the bottom (above the bar): thumbnails slide in with a spring bounce (300 ms, damping 0.7), newest on the right. Tap opens; long-press shares onward; "Open folder" text button.
- Tray clears when the user leaves the radar; History keeps everything.

---

## 6. Dashboard

Full-screen, five tabs across the bottom.

| Tab | Layout |
| --- | --- |
| **Live** | List of active transfers: row = device, files summary, progress bar, speed, ETA, badge, hint; actions pause/cancel/add |
| **History** | Grouped by day; row = direction arrow, device, files summary, size, duration, average speed, status chip; tap → detail sheet with per-file open |
| **Devices** | Cards for trusted devices: avatar, nickname (editable), platform, last seen, auto-accept switch, Forget; top button "Show my QR" |
| **Stats** | Four stat tiles (Total moved · Average speed · Transfers this week · Hours saved vs Bluetooth); one bar chart transfers per week (last 12 weeks); "Share stats card" |
| **Settings** | Sections: Visibility · Speed (Prefer 5 GHz, Keep screen awake, Bundle small files) · Storage (Save location, Clear partial files) · Profile (Nickname, Avatar, Language) · Privacy (Statement, Crash reports opt-in) · About |

Stat tiles use tabular numerals; the chart uses the accent colour only (single series), with axis labels in `text-muted`.

---

## 7. Onboarding

1. **Welcome**: one screen, one sentence ("Send anything to anyone nearby. No internet, no data, no limits."), nickname field prefilled from the device name, avatar picker (optional). Button: "Start".
2. **Permissions** are asked *when first needed*, each with a one-line reason in a small sheet (see `architecture.md` §11 for the list).
3. **Brand step** (shown only on Xiaomi/Redmi/POCO, Vivo/iQOO, Oppo/Realme/OnePlus, Samsung): "To keep transfers running with the screen off, allow the app to run in the background." One button opens the exact system screen; "Skip for now" text link. Re-offered once if a transfer is later killed in background.

---

## 8. States, hints and copy

### 8.1 Empty and error states (radar)

| State | Illustration | Text |
| --- | --- | --- |
| No devices yet | Rings pulsing, no bubbles | "No one nearby yet. Ask them to open the app, or scan their code." |
| Bluetooth off | Ring dimmed, BT glyph | "Turn on Bluetooth to find people nearby." [Turn on] |
| Wi‑Fi off | Ring dimmed, Wi‑Fi glyph | "Turn on Wi‑Fi for fast transfers. No network needed." [Turn on] |
| Permission missing | Lock glyph | "Allow Nearby devices so the app can find phones around you." [Allow] |
| Hidden mode | Eye-off chip | "You're hidden. Others can't see you until you change visibility." |

### 8.2 Speed hints (one line, warning colour, under the badge)

| Condition | Hint |
| --- | --- |
| Link on 2.4 GHz but both support 5 GHz | "Move closer for full speed" |
| Other device is 2.4 GHz only | "{Name}'s device supports 2.4 GHz only" |
| Destination is a microSD card | "Saving to SD card is limiting speed" |
| Thermal throttling reported | "Phone is warm and slowing down" |
| Bundling many small files | "Bundling {N} small files" |
| Bluetooth fallback | "Slow mode: Wi‑Fi is off on one device" |
| LAN path slower than 10 MB/s | "Switching to a direct link…" |

### 8.3 Core copy strings (English; Hindi to follow in `strings-hi.xml`)

| Key | Text |
| --- | --- |
| `radar.title` | "Nearby" |
| `action.scan` | "Scan to send" |
| `action.show_qr` | "Show my code" |
| `action.dashboard` | "Dashboard" |
| `send.button` | "Send {count} items · {size}" |
| `incoming.title` | "{name} wants to send" |
| `incoming.accept` | "Accept" |
| `incoming.decline` | "Decline" |
| `incoming.always` | "Always accept from {name}" |
| `pair.code` | "Same code on both screens?" |
| `pair.confirm` | "Yes, it matches" |
| `transfer.speed` | "{speed} MB/s · {eta} left" |
| `transfer.done` | "Sent" / "Received" |
| `transfer.resumed` | "Resumed" |
| `badge.p2p_5` | "Wi‑Fi Direct · 5 GHz" |
| `badge.p2p_24` | "Wi‑Fi Direct · 2.4 GHz" |
| `badge.lan` | "Same network" |
| `badge.hotspot` | "Hotspot · {band}" |
| `badge.bt` | "Bluetooth" |
| `visibility.everyone` / `.ten_min` / `.trusted` / `.hidden` | "Everyone" / "Everyone for 10 min" / "Trusted only" / "Hidden" |
| `stats.saved` | "{hours} h saved vs Bluetooth" |
| `privacy.statement` | "No account, no cloud, no tracking. Nothing leaves your phone except the file you chose to send." |

Tone: short, plain, no exclamation marks, no jargon on user-facing surfaces (the words "Wi‑Fi Direct" appear only in the badge).

---

## 9. Desktop window (Windows, macOS, Linux)

- Default size 420 × 640 px, resizable; remembers position. Same radar, with the computer's own avatar at the bottom.
- **Drop zone:** the whole window accepts drag-and-drop; dropping onto a bubble sends to that device; dropping onto empty space asks "Send to…" with the bubble list.
- **Tray / menu-bar icon:** states idle, transferring (animated arc), attention (incoming card waiting). Menu: Open, Visibility, Received folder, Quit.
- **Received folder:** `~/Received/<App name>/` by default; per-drop subfolder when > 20 files; notification with "Open" action on completion.
- **No-Bluetooth PC:** a banner "Bluetooth not available — phones on the same network still appear. Or scan the code shown here from your phone." with a static QR.
- **Keyboard:** Ctrl/Cmd+O pick files, Esc cancels current selection, Enter sends.

---

## 10. Browser receive page

Served by the phone at `http://drop.local` (with the IP shown as fallback) once the computer joins the phone's hotspot.

- One column, max-width 560 px, system font, light/dark via `prefers-color-scheme`.
- Header: sender avatar and name, "wants to send you 12 photos · 48 MB".
- File list: name, size, type glyph; "Download all (zip)" primary button; individual download links.
- Progress bar with MB/s while downloading (streamed zip, no server-side temp file).
- Footer: "Send files back" dropzone (optional upload, P1) and the privacy sentence.
- No JavaScript frameworks; single HTML file under 30 KB; works in Chrome, Edge, Safari, Firefox.

---

## 11. Accessibility

- Touch targets ≥ 48 dp; bubbles ≥ 56 dp.
- TalkBack/VoiceOver labels: "Rohan's Pixel, nearby, trusted, double-tap to send"; progress announced every 25%.
- Contrast AA for all text; badge chips have text, never colour alone.
- Reduced motion honoured (see §3.3); haptics can be disabled in Settings.
- Font scaling up to 200% without clipping the radar bar or cards.

---

## 12. Assets to produce (week 1)

- App icon (adaptive, Android) and desktop icons (ico/icns/png set), original artwork.
- Avatar placeholder set (initials on 8 muted background colours derived from the device ID hash).
- Platform glyphs (phone, laptop, desktop, browser), trust shield, transport glyphs (Wi‑Fi, hotspot, Bluetooth, network).
- Lottie or Compose-native animations for: ring pulse, completion tick, tray bounce.
- Empty-state illustrations (5, §8.1), simple line style.
