# ui/desktop

The Compose Desktop app for Windows, macOS and Linux (design §9, architecture §10.2). WP10a. Run it with
`./gradlew :ui:desktop:run`; package with `./gradlew :ui:desktop:packageDistributionForCurrentOS`.

It shows the shared app (`ui/shared`'s `DropApp`, its presenters driven by `DropAppController`) over a
`DesktopNode` from `:platform:desktop-common`, and adds what only a desktop has:

| Type | Role |
| --- | --- |
| `Main.kt` | Entry point: single instance, directories, LAN interface, platform services, node, window and tray. `--minimized` starts in the tray (the login entry). |
| `DesktopApp` | The app around a started node: the controller, the shell, tray view and notifications, window requests, shutdown. |
| `DesktopPorts`, `PortMappers` | The shared UI's ports over `DesktopNode` and `core/data` (radar, incoming card, Live, History, Devices, Stats, Settings, "Show my code", browser path). |
| `DesktopWindow`, `WindowPlacement` | The 420 × 640 resizable window; its placement is remembered in `window.properties` and restored while it is still on a screen. Closing hides to the tray when there is one. |
| `DesktopShell`, `ShellState`, `DropTargets` | The whole window as a drop zone: a drop target over each bubble sends to it, any other drop asks "Send to…" with the bubble list; the no-Bluetooth banner with the static QR (or the no-network banner). |
| `KeyboardShortcuts` | Ctrl/Cmd+O pick files, Esc cancels the current selection, Enter sends. |
| `TrayModel`, `TrayIcons`, `SystemTrayController` | The tray: idle, transferring (animated arc), attention; menu Open, Visibility, Received folder, Start at login, Quit. The model and icons are pure; the AWT binding only renders them. |
| `Notifications` | Completion notifications with "Open" on the drop's folder, and incoming cards while the window is in the background. |
| `SingleInstance` | One app per user: a lock file and a loopback activation port with an owner-only token. |
| `DesktopHost` | Opening files, showing folders, the file dialogs (AWT / Swing, `xdg-open` fallback). |
| `DesktopStrings` | The shell's own copy in English and Hindi (`strings.properties`, `strings_hi.properties`). |

## Tests

`./gradlew :ui:desktop:test` runs headless (`java.awt.headless=true`): the tray model and icons, drop targets and
shortcuts, notifications and their click, strings in both languages, single instance, window placement, the port
mappers, `MainWindowUiTest` (the window's content at 420 × 640 rendered offscreen with Skia: banner and QR, drop
targets checked against the rendered bubbles, "Send to…", a drop on a bubble, Esc and Enter, onboarding, the incoming
card) and `DesktopAppTest` (two real nodes on loopback: a drop on a bubble through the shell reaches the peer, both
trays ask for attention while the code waits, the completion notification opens the folder, History and the files
through the ports, the tray's Visibility menu).
