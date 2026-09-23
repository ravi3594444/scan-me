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
| Plug in encryption (F-J2) | implement `SqlDriverFactory` (SQLCipher); keys from one shared `DatabaseKeys(secretStorage, crypto)`: `.sqlCipherKey()` / `.fieldKey()`; `AeadSecretFieldCipher` for per-value sealing (secrets stored with `PLAINTEXT` are re-sealed on open) |
| Schema version, migrations | `DropSchema.VERSION`, `DropSchema.createOrMigrate(driver)`; migrations are `src/commonMain/sqldelight/.../N.sqm` and run with foreign keys off (`SqlDriverFactory` contract) |
| Peer devices and trust | `DropData.devices`: `recordPeer`, `recordSighting`, `trust`, `storeAdvertisingSecret` (S3), `setClassicAddress`, `rename`, `setAutoAccept`, `forget`, `observeTrusted`, `observeTrustedKeys` |
| Own `k_adv` generation (S3) | `devices.ownAdvertisingGeneration()` for `TrustShare`; `devices.advanceOwnAdvertisingGeneration()` before `AdvertisingSecretStore.rotate()` |
| Radar and handshake trust input | `List<TrustedDeviceKeys>.toTrustedPeers()` (discovery `TrustState.peers`), `.toTrustedPeerLookup()` (crypto `TrustedPeerLookup`) |
| Transfers | `DropData.transfers`: `create`, `updateStatus`, `updateProgress`, `recordLink`, `addHint`, `finish`, `observe`, `observeActive` |
| History (F-G2) | `transfers.historyPage(cursor, limit)`, `observeHistory(limit)`, `delete(id, deletePartials)`, `clearHistory(deletePartials)`; `HistoryGrouping.byDay` / `append` |
| Files of a transfer | `DropData.transferFiles`: `add`, `page`, `updateStatus`, `setSha256`, `setSavedUri`, `complete`, `observeFiles` |
| Resume manifests (§7.6, N5) | `ChunkManifest` (bitmap, unit hashes, partial prefix) and `DropData.manifests`: `get`, `forTransfer`, `put`, `putAll`, `deleteForTransfer` |
| 24 h clean-up | `DropData.resumeDataCleaner(clock) { id -> fileStore.deletePartials(id.toHex()) }.runPeriodically()` |
| "Clear partial files" (F-G5) | the same cleaner's `clearPartials(released)`: finished transfers plus the unfinished ones the caller released |
| Settings (F-G5) | `DropData.settings`: `get` / `set` / `observe(SettingKeys.X)`, `observeAll()`, `setVisibility`, `observeEffectiveVisibility(recheck)`, `effectiveVisibility()` |
| Stats (F-G4) | `DropData.stats.stats()` / `observe()` → `TransferStats` |
| Column vocabularies | `TransferStatus`, `TransferDirection`, `TransferFileStatus`, `WifiBand`, `LinkKindColumn`, `DevicePlatformColumn`, `HintCodesColumn`, `MimeHistogramColumn` |

Stored data that does not decode raises `DataCorruptionException`; other data errors are `DatabaseVersionException`,
`IdentityConflictException`, `NoSuchRecordException` and `DuplicateRecordException` (all `DataException`s). Updates
that name a missing row return false. A setting that does not decode reads as its default.

**Durability rule (N5).** Set a manifest bit only after the unit's bytes are written and `fsync`ed: sync the
partial file, then `ChunkManifest.withReceived`, then `manifests.put`. A finished transfer takes no manifest (`put`
returns false), so a flush that loses the race with the clean-up cannot describe deleted bytes.

**Partial files.** Everything that removes a received transfer's resume state deletes its partial files first, with
the platform's `FileStore.deletePartials`: the cleaner, `transfers.delete` and `transfers.clearHistory`.

**Visibility.** "Everyone for 10 min" ends on the wall clock. The platform schedules an exact wake-up at
`VisibilityPreference.expiresAtMillis`, feeds it (and each beacon rebuild) into `observeEffectiveVisibility(recheck)`,
and builds every beacon from `effectiveVisibility()`, since coroutine delays stop while the CPU sleeps.

**Threading.** Every call is main-safe: repositories run on one database context (the given dispatcher limited to
one task at a time), and flows re-query on it after each commit that touches their tables.

**Dependencies for users of the JVM factory.** `app.cash.sqldelight:sqlite-driver` is `compileOnly` here, so the
SQLite JDBC natives stay out of the Android APK; desktop modules that call `JdbcSqliteDriverFactory` or `openJvm`
add `libs.sqldelight.sqlite.driver` themselves.
