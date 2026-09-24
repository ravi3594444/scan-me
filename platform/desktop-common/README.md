# platform/desktop-common

The platform layer every desktop shares (architecture §10.2 as changed by WP10a): the LAN path, app directories,
secrets at rest, the database, and `DesktopNode`, the composition root the desktop app runs. Plain JVM, no UI
toolkit, no OS-specific API: Windows, macOS and Linux add their radios, keychain and helpers through
`DesktopPlatformServices` from `:platform:desktop-win`, `-mac` and `-linux` (WP10b–d). WP10a.

## Pieces

| Type | Role |
| --- | --- |
| `DesktopOs`, `AppDirectories` | The OS, and its config / data / cache folders (XDG, `%APPDATA%` / `%LOCALAPPDATA%`, `~/Library`), the Received folder `~/Received/<App>/`, the database, partials, secrets and single-instance lock paths. Device-bound state (secrets, database, lock) is under the local data folder, never a roaming or synced one. |
| `OwnerOnlyFiles`, `FileSecretStorage`, `SecretWrap` | `SecretStorage` as owner-only files written atomically; a `SecretWrap` (the OS keychain, WP10b–d) seals each value, and values stored before a wrap existed are re-sealed on read. |
| `DesktopPlatformServices` | What an OS adds: `SecretWrap`, `KeepAwake`, `DownloadMarker` (Mark-of-the-Web, quarantine), `AutoStart` (F‑H5), beacon radios. `portable()` is the no-OS fallback. |
| `DesktopPowerPolicy` | `PowerPolicy` for desktops: thermal level none, a reference-counted keep-awake hook. |
| `lan.LanInterfaces` | Picks the one interface the LAN path binds: the default-route interface first; loopback, VPN, container and other virtual interfaces skipped by name and by display name (Windows calls every adapter `ethN` / `wlanN`). |
| `lan.LanWatcher` | Asks `LanInterfaces` every 3 s and publishes a changed answer (Wi‑Fi joined after login, another network, a new address); the app moves the node with `DesktopNode.setLanAddress`, and the no-network banner follows `available`. |
| `lan.JmdnsLanDiscovery` | `RebindableLanDiscovery` over JmDNS (`_drop._tcp`), bound to that interface: records with RFC 6762's 120 s TTL, `rebind` to a new address, a browse that fails retried with back-off. `lan.InMemoryLanNetwork` is the same contract in memory, for tests. |
| `lan.LanLinkProvider` | The ladder's LAN rung: a `WifiLinkProvider` whose links are TCP from the LAN address, joined only at the session peer's own address (an IP literal). |
| `data.DesktopDatabase` | `DropData` over the SQLite JDBC driver, secrets field-encrypted under `DatabaseKeys`; `PartialsSweeper` runs the 24 h clean-up (§7.7). |
| `data.DataResumeStore`, `data.FileResumePlanStore` | The engine's `ResumeStore` over `core/data`, with the unit plan beside the partial files (`resume.plan`); a record is made only for the peer the transfer's History row names. Free of desktop-only APIs, so Android can reuse it. |
| `files.SendItems` | Dropped or picked files and folders to the send list (F‑C6: recursive, deterministic, symlinks skipped, at most 100,000 files, the protocol's per-transfer maximum; the walk stops at the limit and can be interrupted; a path too long for the wire keeps its file name). |
| `files.MarkingFileStore` | Wraps the receiver's `FileStore`: marks every published file as downloaded and reports it (the tray, History open). |
| `node.DesktopNode` | The composition root: identity, `k_adv`, database, the LAN radar (`NearbyDevices`), announce and control listener (moved by `setLanAddress`), `TransferEngine` + ladder per transfer, resume by offering a transfer again on a new session (S8, T‑07), SAS pairing on mutual proof only and the trust-sync session, static QR and the five-minute code with the LAN address, auto-accept, History, the browser receive path, and `StateFlow`s for the UI. |
| `node.TransferTracker`, `node.NodeAttempts` | One transfer across its attempts (one session each): its UI state, History row, pairing code and resume store. |
| `node.InboundRouter` | Reads an inbound connection's `Hello` under a deadline and replays it to the handshake; `InboundGate` limits connections that have not authenticated, in total and per address. |

Typical use (the desktop app's `main`):

```kotlin
val dirs = AppDirectories.forOs(DesktopOs.current, home, System.getenv()).also { it.ensureCreated() }
val lan = LanWatcher({ LanInterfaces.selectCurrent() })
val address = lan.selection.value?.address ?: InetAddress.getLoopbackAddress()
val node = DesktopNode(
    DesktopNodeConfig(dirs, FileSecretStorage(dirs.secrets, services.secretWrap), JmdnsLanDiscovery(address), address, "Studio"),
)
node.start()
lan.launchIn(scope)
lan.selection.collect { node.setLanAddress(it?.address ?: InetAddress.getLoopbackAddress()) }   // follow the network
node.devices.collect { … }            // the radar
node.send(deviceKey, SendItems.expand(droppedPaths).files)
node.offers.collect { … }             // incoming cards; node.accept(id, alwaysAccept) / node.decline(id)
node.stop()
```

## Tests

`./gradlew :platform:desktop-common:test`: directories per OS, secret files (permissions, atomicity, wrap and
re-seal, refused foreign wraps), the power hook, interface selection (Windows display names, the default route), the
LAN watcher, the in-memory and the real JmDNS discovery (TTL, rebind; the real one skips itself where multicast does
not loop back), LAN links and the addresses they may join, the resume adapter, folder expansion (limit, long names,
interruption), `Hello` peeking and the admission gate, and `DesktopNode` flows (decline, a decline for want of
storage, sender cancel, Trusted-only and Hidden, pair and forget, static and five-minute codes, a flood of silent
connections, moving to another address, clearing partials an earlier run left, browser share over HTTP).
`DesktopNodeTrustTest` covers a code confirmed after a small first send ended, a dismissed code, and a one-sided Forget
healed by the SAS. `DesktopNodeResumeTest` cuts the link through a throttled proxy: two transfers to one peer resume
on new sessions without a card, a new send from a reconnecting peer shows its card, and a restarted receiver (resume
card) and a restarted sender (offered again to its trusted peer) complete with fewer bytes sent again (S8, T‑07).
`DesktopNodeEndToEndTest` runs two nodes over loopback: SAS pairing, a folder of 1,000 small files plus two larger
files, bundled (the `Offer` and History say so) over the LAN primary and the ladder's LAN rung, every byte in a
per-drop subfolder, History on both sides, and auto-accept of the second send once the pair trusts each other.
