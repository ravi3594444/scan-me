# core/transfer

Chunker, bundler, stream scheduler, ack window, resume manifest, throughput meter, head start and hop (architecture §7.4–7.6, §9).

Package `com.constrivo.drop.core.transfer`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP4 of `docs/implementation-plan.md`.
