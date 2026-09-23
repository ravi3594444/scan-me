# core/discovery

Beacon encode/decode, capability flags, ephemeral IDs, mDNS record model, radar placement maths (architecture §5, design §3.2).

Package `com.constrivo.drop.core.discovery`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP1 of `docs/implementation-plan.md`.
