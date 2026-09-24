# platform/desktop-common

The platform layer every desktop shares (architecture §10.2 as changed by WP10a): the LAN path, app directories,
secrets at rest, the database, and `DesktopNode`, the composition root the desktop app runs. Plain JVM, no UI
toolkit, no OS-specific API: Windows, macOS and Linux add their radios, keychain and helpers through
`DesktopPlatformServices` from `:platform:desktop-win`, `-mac` and `-linux` (WP10b–d). WP10a.

## Pieces

| Type | Role |
| --- | --- |
| `DesktopOs`, `AppDirectories` | The OS, and its config / data / cache folders (XDG, `%APPDATA%` / `%LOCALAPPDATA%`, `~/Library`), the Received folder `~/Received/<App>/`, the database, partials, secrets and single-instance lock paths. |
| `OwnerOnlyFiles`, `FileSecretStorage`, `SecretWrap` | `SecretStorage` as owner-only files written atomically; a `SecretWrap` (the OS keychain, WP10b–d) seals each value, and values stored before a wrap existed are re-sealed on read. |
| `DesktopPlatformServices` | What an OS adds: `SecretWrap`, `KeepAwake`, `DownloadMarker` (Mark-of-the-Web, quarantine), `AutoStart` (F‑H5), beacon radios. `portable()` is the no-OS fallback. |
| `DesktopPowerPolicy` | `PowerPolicy` for desktops: thermal level none, a reference-counted keep-awake hook. |
| `lan.LanInterfaces` | Picks the one interface the LAN path binds (skips loopback, VPN, container and other virtual interfaces). |
| `lan.JmdnsLanDiscovery` | `LanDiscovery` over JmDNS (`_drop._tcp`), bound to that interface. `lan.InMemoryLanNetwork` is the same contract in memory, for tests. |
| `lan.LanLinkProvider` | The ladder's LAN rung: a `WifiLinkProvider` whose links are TCP on the LAN address. |
| `data.DesktopDatabase` | `DropData` over the SQLite JDBC driver, secrets field-encrypted under `DatabaseKeys`; `PartialsSweeper` runs the 24 h clean-up (§7.7). |
| `data.DataResumeStore`, `data.FileResumePlanStore` | The engine's `ResumeStore` over `core/data`, with the unit plan beside the partial files (`resume.plan`). Free of desktop-only APIs, so Android can reuse it. |
| `files.SendItems` | Dropped or picked files and folders to the send list (F‑C6: recursive, deterministic, symlinks skipped, at most 1,000 files). |
| `files.MarkingFileStore` | Wraps the receiver's `FileStore`: marks every published file as downloaded and reports it (the tray, History open). |
| `node.DesktopNode` | The composition root: identity, `k_adv`, database, the LAN radar (`NearbyDevices`), announce and control listener, `TransferEngine` + ladder per transfer, SAS pairing and the trust-sync session, static QR, auto-accept, History, the browser receive path, and `StateFlow`s for the UI. |
| `node.InboundRouter` | Peeks an inbound connection's `Hello` so a waiting transfer gets its reconnecting peer (`ReconnectWaiters`). |

Typical use (the desktop app's `main`):

```kotlin
val dirs = AppDirectories.forOs(DesktopOs.current, home, System.getenv()).also { it.ensureCreated() }
val lan = LanInterfaces.selectCurrent()
val node = DesktopNode(
    DesktopNodeConfig(dirs, FileSecretStorage(dirs.secrets, services.secretWrap), JmdnsLanDiscovery(lan!!.address), lan.address, "Studio"),
)
node.start()
node.devices.collect { … }            // the radar
node.send(deviceKey, SendItems.expand(droppedPaths).files)
node.offers.collect { … }             // incoming cards; node.accept(id, alwaysAccept) / node.decline(id)
node.stop()
```

## Tests

`./gradlew :platform:desktop-common:test`: directories per OS, secret files (permissions, atomicity, wrap and
re-seal, refused foreign wraps), the power hook, interface selection, the in-memory and the real JmDNS discovery (the
latter skips itself where multicast does not loop back), LAN links, the resume adapter, folder expansion, `Hello`
peeking and reconnect hand-over, and `DesktopNode` flows (decline, sender cancel, Trusted-only and Hidden, pair and
forget, static QR, browser share over HTTP). `DesktopNodeEndToEndTest` runs two nodes over loopback: SAS pairing, a
folder of 1,000 small files plus two larger files with bundling, every byte in a per-drop subfolder, History on both
sides, and auto-accept of the second send once the pair trusts each other.
