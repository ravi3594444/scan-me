# core/crypto

Identity keys, X25519/HKDF, AEAD, SAS, QR payload signing (architecture §6, §7.1, §13).

Package `com.constrivo.drop.core.crypto`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP2 of `docs/implementation-plan.md`.
