# core/ladder

Transport selection, link lifecycle, restore-previous-network policy, transport badge and speed hints
(architecture §4, §9; design §8.2–8.3; features F-E1–F-E4, F-E11, F-F2, F-F3). Built in WP5 of
`docs/implementation-plan.md`; the spec changes it applies (S5, N6–N10) are summarised in the "Changed (WP5)" note of
architecture §4.

Package `com.constrivo.drop.core.ladder`. Kotlin Multiplatform: pure logic in `src/commonMain`, tests in
`src/commonTest` (virtual time) and `src/jvmTest` (JCA crypto, design-copy checks). No Android or desktop-UI imports;
`:tools:arch-test` enforces this.

| Piece | What it does |
| --- | --- |
| `LadderPlanner` | Both devices' `LinkFacts` + radio state → ordered `LadderPlan` (LAN, Wi-Fi Direct or legacy join, hotspot, Bluetooth), the elected group owner and hotspot host, whether the LAN probe races Wi-Fi Direct, initial hints |
| `LadderNegotiation` | S5: `Offer.link_options` from the sender's plan, the receiver's `Accept.link` decision, the sender adopting it → `LinkAgreement` |
| `P2pCredentials` | `DIRECT-xy-…` names and passphrases: random per transfer, or stable per trusted pair from the recognition secret (N7); validators for peer-supplied values |
| `LinkLifecycle` | Pure reducer: timeouts, LAN throughput check, 5 GHz verification with one re-form, fall-through, teardown and restore budget, idle teardown |
| `LadderRunner` | Coroutine orchestrator over `WifiLinkProvider`s and a `LadderSession`; publishes `StateFlow<LadderState>` and a log `SharedFlow<LadderLogEvent>` |
| `TransportBadge` | (link kind, measured MHz) → badge key and the exact English text of design §8.3 |
| `HintRules`, `BandHints`, `LadderHint` | Which one-line hint to show (design §8.2), with a documented priority |

Platform modules implement `WifiLinkProvider` / `ActiveLink` (Android: `WifiP2pManager`, `LocalOnlyHotspot`,
`WifiNetworkSpecifier`; Windows `WiFiAdapter` legacy join; macOS CoreWLAN; Linux NetworkManager). A provider hands each
link to the ladder through `onUp` as soon as it exists, before resuming the caller, so a link whose setup is cancelled
at the last moment is still torn down (see the `WifiLinkProvider` KDoc).

The transfer engine implements `LadderSession` (send `LinkReady`, open the first authenticated stream, and on the
receiver name the selected link, which the engine carries in the N13 `ControlMoved`) and feeds the runner throughput
samples (the receiver's count of bytes that arrived decides the LAN check), late channel reports, link losses, the
peer's selection (`onPeerSelected`) and transfer start/end. Both devices run a `LadderRunner`; the receiver's decides
which link carries the data and the sender's follows it, so the two never settle on different links. End a run with
`closeAndAwait()` before cancelling the runner's scope, or the previous network may not be restored.

Not run here:

- **Browser plans** (`LadderPlan.isBrowserPlan`, N8). `LadderRunner` refuses them: no app answers `LinkReady`, and a
  person joins by hand. The browser receive path (WP9) hosts the plan's first rung directly with
  `WifiLinkProvider.host`, shows its credentials, and waits for the first HTTP request with its own timeout.
- **Endpoint selection.** Trying every `NearbyDevice.lanEndpoints` / `radioAddresses` entry behind the handshake
  identity check (the WP1–WP3 carry-forward item in `docs/implementation-plan.md`) belongs to the connection and
  handshake layer (WP4, WP7), which runs before the ladder: the LAN rung dials only the address the sender announced
  in its `LinkReady` over the authenticated session. The ladder's only use of discovery is `LadderInput.lanReachable`
  (`lanEndpoints` not empty for the verified peer). The plan owner should move that item to WP4/WP7.
