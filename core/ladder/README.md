# core/ladder

Transport selection, link lifecycle, restore-previous-network policy, transport badge (architecture §4, §9).

Package `com.constrivo.drop.core.ladder`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP5 of `docs/implementation-plan.md`.
