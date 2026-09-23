# core/data

SQLDelight schema and repositories: device, transfer, transfer_file, chunk_manifest, settings (architecture §12).

Package `com.constrivo.drop.core.data`. Kotlin Multiplatform: pure logic in `src/commonMain`, JVM-only code
(JCA, java.nio, file I/O) in `src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this.
Built out in WP6 of `docs/implementation-plan.md`.
