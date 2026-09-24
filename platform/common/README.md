# platform/common

The app-layer adapters every platform shares (WP7ef; moved here from `platform/desktop-common`, where WP10a built
them). Plain JVM library with no desktop or Android API, package `com.constrivo.drop.platform.common`;
`platform/desktop-common` and `platform/android` both depend on it.

| Type | Role |
| --- | --- |
| `DataResumeStore` | The engine's `ResumeStore` (architecture §7.6 with N3, N5) over `core/data`: the sender's identity from the History row, the file list in `transfer_file`, unit manifests in `chunk_manifest`, per-file state; a record only for the peer the row names; failures reported, never thrown at the engine. |
| `ResumePlanStore`, `FileResumePlanStore` | The part of a resume record the schema has no column for (chunk size, bundling, bundle count), as `resume.plan` beside the transfer's partial files, so it goes wherever they go. |
| `PartialsSweeper` | The 24 h clean-up of resume state (§7.6, §7.7 with S8): `core/data`'s `ResumeDataCleaner` with the platform's `FileStore.deletePartials`, and "Clear partial files" (F‑G5). |

Tests: `./gradlew :platform:common:test` (the resume adapter against an in-memory SQLite database, the plan format,
the sweep's order of deleting partial files before their rows).
