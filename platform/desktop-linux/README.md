# platform/desktop-linux

What Linux adds to the shared desktop layer (architecture §8, §10.2). The LAN path, directories, secrets, database
and `DesktopNode` come from `:platform:desktop-common`; this module contributes `LinuxPlatform.services()`, the Linux
`DesktopPlatformServices`.

- **WP10a:** `XdgAutoStart`, the opt-in "Start at login" (F‑H5) as an XDG autostart entry
  (`$XDG_CONFIG_HOME/autostart/com.constrivo.drop.desktop`, `~/.config/autostart/…` by default) that starts the
  packaged launcher with `--minimized`. It is offered only when the app runs from its package (`jpackage.app-path`).
- **WP10d:** BlueZ (LE advertising and scanning, Classic RFCOMM) and NetworkManager over D-Bus, a libsecret
  `SecretWrap`, a logind keep-awake inhibitor and notifications without a tray. Until then the portable defaults of
  `DesktopPlatformServices` apply and the desktop is LAN-only.

Native helpers stay stateless and message-driven so the JVM engine stays authoritative.

`./gradlew :platform:desktop-linux:test` covers the autostart entry: its path, quoting of the command, enabling,
disabling, `Hidden=true` entries and the unpackaged case.
