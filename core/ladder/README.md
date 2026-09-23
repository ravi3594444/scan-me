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
`WifiNetworkSpecifier`; Windows `WiFiAdapter` legacy join; macOS CoreWLAN; Linux NetworkManager). The transfer engine
implements `LadderSession` (send `LinkReady`, open the first authenticated stream) and feeds the runner throughput
samples, link losses and transfer start/end.
