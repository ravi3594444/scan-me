# ui/android

The Android app (WP8; architecture §10.1, design §4.3): one activity hosting the shared UI, the share sheet entry,
direct-share targets, the just-in-time permission flow and the other platform ports the shared UI needs. Build a
debug APK with `./gradlew :ui:android:assembleDebug` (needs an Android SDK; without one the module is skipped).

| File | Role |
| --- | --- |
| `MainActivity` | Hosts `DropApp`; `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for any type; owns the share it received (restored across recreation, released when it finishes); activity-result launchers; Back through the controller; reduced motion from "Remove animations"; "Keep screen awake" |
| `DropApplication`, `AppGraph` | One `DropAppController` per process and the ports behind it; a logging exception handler on its scope |
| `AppLocales` | Settings → Language: `LocaleManager` on 13+ (with `locales_config.xml`), the shared UI's in-place switch on 12 |
| `ActivityBridge` | Lets app-wide ports use the activity in front (launchers, starting system screens) |
| `PermissionMatrix`, `AndroidPermissions` | Architecture §11 per API level; system dialog; "don't ask again" detection; app settings recovery; battery optimisation |
| `RadioMonitor` | Bluetooth / Wi‑Fi on or off and the Nearby grant, for the radar's notices |
| `AndroidPlatformActions` | Bluetooth enable dialog, Wi‑Fi panel, opening and sharing received files, Downloads, the document picker |
| `MediaStoreLibrary` | The picker's Photos grid (recent first, 120 per page as the grid scrolls, only while the picker is open) and the Apps tab (behind decision 8's flag) |
| `SharedFiles`, `UriReader`, `ShareIntent`, `ShareRestore` | Shared and picked URIs to picker items: only streams the sender could grant (in `ClipData` with the read grant, never this app's own providers), `content:` only, names cleaned, duplicates dropped, a failing provider skipped |
| `DirectShareTargets`, `DirectShareIds`, `DirectSharePublisher` | F‑C3: trusted nearby devices as sharing shortcuts with unguessable ids |
| `AndroidOnboarding` | Nickname prefill from the device name, avatar from the photo picker, OEM background screens (F‑I2) |
| `AndroidSystem` | Haptics (`CONFIRM`), the avatar decoder, URI reading |

Until WP7's `TransferService` is connected, the engine ports (devices, transfers, offers, History, trusted devices,
stats, settings, the QR code) are the shared UI's in-memory backend, so the radar shows "No devices yet". The
manifest declares the runtime permissions the UI asks for; the service's install-time permissions come with it in
`platform/android`.

Unit tests (`./gradlew :ui:android:testDebugUnitTest`) cover the pure parts: the permission matrix and denial rules,
share parsing and the grant rule, what a recreated activity does with its share, direct-share target selection and ids,
and the OEM screen table. Everything that talks to the system
(dialogs, MediaStore, shortcuts in the share sheet, OEM screens) is verified on the lab phones.
