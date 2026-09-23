# core/data

SQLDelight database and repositories: devices and trust, transfers, their files, resume manifests, settings and
stats (architecture §12, F-B4, F-G2–G5, F-E10 persistence, F-J2 hook). Built in WP6 of
`docs/implementation-plan.md` with spec changes S1, S2, S3 and N5; the schema decisions are in the "Changed (WP6)"
note of architecture §12.

Package `com.constrivo.drop.core.data` (generated queries in `.db`; use the repositories, not the queries).
Kotlin Multiplatform: everything in `src/commonMain` except the JDBC driver factory and the java.time calendar in
`src/jvmMain`. No Android or desktop-UI imports; `:tools:arch-test` enforces this. Depends on `core/protocol` and
`core/discovery` (and `core/crypto` through them), never on `core/transfer` or `core/ladder`.

## What is where

| Need | API |
| --- | --- |
| Open the database | `DropData.open(driverFactory, dispatcher, crypto, clock, calendar, secretCipher)`; desktop and tests: `DropData.openJvm(path)` |
| Plug in encryption (F-J2) | implement `SqlDriverFactory` (SQLCipher); keys from `DatabaseKeys(secretStorage, crypto).sqlCipherKey()` / `.fieldKey()`; `AeadSecretFieldCipher` for per-value sealing |
| Schema version, migrations | `DropSchema.VERSION`, `DropSchema.createOrMigrate(driver)`; migrations are `src/commonMain/sqldelight/.../N.sqm` |
| Peer devices and trust | `DropData.devices`: `recordPeer`, `recordSighting`, `trust`, `storeAdvertisingSecret` (S3), `setClassicAddress`, `rename`, `setAutoAccept`, `forget`, `observeTrusted`, `observeTrustedKeys` |
| Radar and handshake trust input | `List<TrustedDeviceKeys>.toTrustedPeers()` (discovery `TrustState.peers`), `.toTrustedPeerLookup()` (crypto `TrustedPeerLookup`) |
| Transfers | `DropData.transfers`: `create`, `updateStatus`, `updateProgress`, `recordLink`, `addHint`, `finish`, `observe`, `observeActive` |
| History (F-G2) | `transfers.historyPage(cursor, limit)`, `observeHistory(limit)`, `delete`, `clearHistory`; `HistoryGrouping.byDay` / `append` |
| Files of a transfer | `DropData.transferFiles`: `add`, `page`, `updateStatus`, `setSha256`, `setSavedUri`, `complete`, `observeFiles` |
| Resume manifests (§7.6, N5) | `ChunkManifest` (bitmap, unit hashes, partial prefix) and `DropData.manifests`: `get`, `forTransfer`, `put`, `putAll`, `deleteForTransfer` |
| 24 h clean-up | `DropData.resumeDataCleaner(clock) { id -> fileStore.deletePartials(id.toHex()) }.runPeriodically()` |
| Settings (F-G5) | `DropData.settings`: `get` / `set` / `observe(SettingKeys.X)`, `observeAll()`, `setVisibility`, `observeEffectiveVisibility()` |
| Stats (F-G4) | `DropData.stats.stats()` / `observe()` → `TransferStats` |
| Column vocabularies | `TransferStatus`, `TransferDirection`, `TransferFileStatus`, `WifiBand`, `LinkKindColumn`, `DevicePlatformColumn`, `HintCodesColumn`, `MimeHistogramColumn` |

Stored data that does not decode raises `DataCorruptionException`; other data errors are `DatabaseVersionException`,
`IdentityConflictException`, `NoSuchRecordException` and `DuplicateRecordException` (all `DataException`s). Updates
that name a missing row return false. A setting that does not decode reads as its default.

**Durability rule (N5).** Set a manifest bit only after the unit's bytes are written and `fsync`ed: sync the
partial file, then `ChunkManifest.withReceived`, then `manifests.put`.

**Threading.** Every call is main-safe: repositories run on one database context (the given dispatcher limited to
one task at a time), and flows re-query on it after each commit that touches their tables.

**Dependencies for users of the JVM factory.** `app.cash.sqldelight:sqlite-driver` is `compileOnly` here, so the
SQLite JDBC natives stay out of the Android APK; desktop modules that call `JdbcSqliteDriverFactory` or `openJvm`
add `libs.sqldelight.sqlite.driver` themselves.
