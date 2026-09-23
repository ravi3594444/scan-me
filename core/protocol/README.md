# core/protocol

Framing, CBOR control messages, transfer state machine (architecture §7.1–7.3, §7.7–7.8).

Package `com.constrivo.drop.core.protocol`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP3 of `docs/implementation-plan.md`.
