# ui/shared

Compose Multiplatform UI shared by the Android and desktop apps (WP8): tokens and theme (design §1), the radar with
its motion table and reduced motion (§3), the send flow (picker, sending state, drop animation, share-sheet banner,
"Show my QR" and the scanner chrome, §4), the receive flow (incoming card with the 30 s bar and the code row, tray,
"Allow this computer?", §5), the dashboard's five tabs (§6), onboarding and the just-in-time permission sheet (§7),
every empty and error state and copy key of §8, and the accessibility rules of §11.

## Structure

| Package | What it holds |
| --- | --- |
| `theme` | `DropColors` (design tokens plus the AA text variants), `DropTheme`, `DropMotion` (the §3.3 table) |
| `model` | Immutable UI state (`RadarUiState`, `IncomingCardUi`, `DashboardUiState` parts, …) and pure formatters |
| `presenter` | Plain classes over `StateFlow` that turn engine ports into UI state; `DropAppController` wires them and holds navigation |
| `radar`, `send`, `receive`, `dashboard`, `onboarding`, `components` | Stateless composables: state in, callbacks out |
| `text` | Maps models to string resources (plurals, badges, hints) |
| `qr` | QR matrix for "Show my QR" (ZXing) |
| `platform` | Hooks the apps implement (haptics) |
| `fake` | `InMemoryDrop`, an in-memory implementation of every port, for tests, previews and the apps until the engine is wired |

App layers implement the ports in `DropDependencies` (devices, transfers, offers, History, trusted devices, stats,
settings, the QR code, media, permissions, platform actions, calendar and clocks), create a `DropAppController` and
call `DropApp(controller, …)`. The browser-receive approval plugs in as
`BrowserApprover { r -> controller.browserApproval.ask(r.browserNumber, r.remoteAddress, r.userAgent) }`.

## Strings

Every user-visible string is in `src/commonMain/composeResources/values/strings.xml` with the Hindi translation in
`values-hi` (decision 9; marked for native-speaker review). Placeholders are positional (`%1$s`, `%1$d`) and counts use
plurals. `UserTextLiteralTest` fails on a string literal with letters in the composable packages;
`StringResourcesTest` checks that English and Hindi match key for key and placeholder for placeholder, and that the
design §8 copy and the `core/ladder` badge and hint texts are mirrored exactly.

## Tests

`./gradlew :ui:shared:desktopTest` runs, on the JVM:

- presenter and model tests (`commonTest`): state mapping, timers on virtual time, the F‑G1 250 ms budget;
- screenshot tests (`ScreenshotTest`): radar states, cards, sheets, the dashboard tabs, onboarding, light and dark,
  and 200% font, rendered at 360 × 760 dp and density 1 in the bundled DejaVu Sans font (so images do not depend on the
  machine's fonts);
- accessibility tests on the semantics tree: 48 dp targets, spoken labels, progress announced every 25%, text at
  200% not clipped, the Hindi locale, radar geometry unchanged in right-to-left layouts;
- the string and literal checks above.

### Screenshots

The references are `src/desktopTest/resources/screenshots/*.png`. A pixel differs when a colour channel differs by
more than 24; a case passes while at most 0.3% of its pixels differ (anti-aliasing noise between Skia builds). A
failing case writes the actual image and a diff (differing pixels in magenta) to `build/reports/screenshots/`.

After an intended visual change, re-record, look at every changed PNG, and commit them:

```sh
./gradlew :ui:shared:desktopTest -Pdrop.recordScreenshots=true
git status ui/shared/src/desktopTest/resources/screenshots
```

A new case fails until its reference is recorded the same way.
