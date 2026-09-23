package com.constrivo.drop.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.constrivo.drop.core.data.db.DropDatabase
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.core.discovery.WallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * User settings (F-G5, design §6) as typed keys ([SettingKeys]) over the `settings` table.
 *
 * Every setting takes effect without a restart: each key has a [Flow] ([observe]) that emits the current value
 * and then every change, so the engine and the UI follow the stored value instead of reading it once.
 *
 * A stored value this build cannot read (edited by hand, or damaged) reads as the key's default and is replaced by the
 * next [set]; a setting never keeps the app from starting. Every function is main-safe.
 */
class SettingsRepository internal constructor(
    private val database: DropDatabase,
    private val context: CoroutineContext,
    private val clock: WallClock,
) {
    private val queries get() = database.settingsQueries

    suspend fun <T> get(key: SettingKey<T>): T = withContext(context) { read(key, queries.selectValue(key.name).executeAsOneOrNull()) }

    /**
     * Stores [value] (normalised by the key; null on a nullable key goes back to the default).
     *
     * @throws IllegalArgumentException if the key rejects [value], such as a nickname with no visible character.
     */
    suspend fun <T> set(
        key: SettingKey<T>,
        value: T,
    ) {
        val text = key.encode(key.prepare(value))
        withContext(context) { write(key, text) }
    }

    /** Removes the stored value, so [key] reads as its default. */
    suspend fun reset(key: SettingKey<*>) {
        withContext(context) { queries.remove(key.name) }
    }

    /** The value of [key] now and after every change (distinct values only). */
    fun <T> observe(key: SettingKey<T>): Flow<T> =
        queries
            .selectValue(key.name)
            .asFlow()
            .mapToOneOrNull(context)
            .map { read(key, it) }
            .distinctUntilChanged()

    suspend fun snapshot(): SettingsSnapshot = withContext(context) { snapshotOf(storedValues()) }

    /** Every setting now and after every change: the Settings tab. */
    fun observeAll(): Flow<SettingsSnapshot> =
        queries
            .selectAll()
            .asFlow()
            .mapToList(context)
            .map { rows -> snapshotOf(rows.associate { row -> row.key to row.value_ }) }
            .distinctUntilChanged()

    suspend fun visibility(): VisibilityPreference = snapshot().visibility

    /** The visibility setting now and after every change. */
    fun observeVisibility(): Flow<VisibilityPreference> = observeAll().map { it.visibility }.distinctUntilChanged()

    /** The visibility in force at [nowMillis] (wall clock): what the advertiser reads at every beacon rebuild. */
    suspend fun effectiveVisibility(nowMillis: Long = clock.nowMillis()): Visibility = visibility().effectiveAt(nowMillis)

    /**
     * The visibility in force (F-A5): follows [observeVisibility] and also switches back when a 10-minute window
     * ends, without any write. This is what the advertiser and the mDNS announcer follow.
     *
     * The window's end is a wall-clock time, but coroutine delays run on a monotonic clock that stops while the CPU
     * sleeps, and the Bluetooth controller keeps advertising the last beacon meanwhile. So while a window is open the
     * wall clock is re-read at least every [VISIBILITY_RECHECK_MILLIS] of awake time and at every [recheck] emission,
     * and the platform must make the window end on time even in deep sleep:
     * - schedule an exact wake-up at [VisibilityPreference.expiresAtMillis] (Android: an `AlarmManager` exact alarm
     *   allowed while idle) and emit into [recheck] when it fires, as well as at every epoch or advertisement rebuild;
     * - at every rebuild, build the beacon from [effectiveVisibility] (or [VisibilityPreference.effectiveAt] with the
     *   wall-clock time), never from a value cached before the device slept.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEffectiveVisibility(recheck: Flow<Unit> = emptyFlow()): Flow<Visibility> =
        combine(observeVisibility(), recheck.onStart { emit(Unit) }) { preference, _ -> preference }
            .transformLatest { preference ->
                while (true) {
                    val now = clock.nowMillis()
                    emit(preference.effectiveAt(now))
                    val remaining = preference.remainingMillis(now) ?: break
                    delay(remaining.coerceAtMost(VISIBILITY_RECHECK_MILLIS))
                }
            }.distinctUntilChanged()

    /**
     * Chooses the visibility [mode] at [atMillis]. [Visibility.EVERYONE_TEN_MINUTES] opens a 10-minute window from
     * [atMillis] (choosing it again restarts the window) and remembers the mode in force before it, which applies
     * again when the window ends. Any other mode applies until changed. Returns the stored preference.
     */
    suspend fun setVisibility(
        mode: Visibility,
        atMillis: Long = clock.nowMillis(),
    ): VisibilityPreference =
        withContext(context) {
            database.transactionWithResult {
                val current = snapshotOf(storedValues()).visibility
                val next =
                    if (mode == Visibility.EVERYONE_TEN_MINUTES) {
                        val before = current.effectiveAt(atMillis)
                        val revertTo = if (before == Visibility.EVERYONE_TEN_MINUTES) current.revertTo else before
                        VisibilityPreference(mode, atMillis + VisibilityPreference.EVERYONE_WINDOW_MILLIS, revertTo)
                    } else {
                        VisibilityPreference(mode, null, mode)
                    }
                write(SettingKeys.VISIBILITY_MODE, SettingKeys.VISIBILITY_MODE.encode(next.mode))
                write(SettingKeys.VISIBILITY_EXPIRES_AT, SettingKeys.VISIBILITY_EXPIRES_AT.encode(next.expiresAtMillis))
                write(SettingKeys.VISIBILITY_REVERT_TO, SettingKeys.VISIBILITY_REVERT_TO.encode(next.revertTo))
                next
            }
        }

    /** Every stored key and its text. */
    private fun storedValues(): Map<String, String> = queries.selectAll().executeAsList().associate { row -> row.key to row.value_ }

    private fun write(
        key: SettingKey<*>,
        text: String?,
    ) {
        if (text == null) queries.remove(key.name) else queries.put(key.name, text)
    }

    private fun <T> read(
        key: SettingKey<T>,
        text: String?,
    ): T {
        if (text == null) return key.defaultValue
        return try {
            key.decode(text)
        } catch (e: DataCorruptionException) {
            key.defaultValue
        }
    }

    private fun snapshotOf(values: Map<String, String>): SettingsSnapshot {
        fun <T> value(key: SettingKey<T>): T = read(key, values[key.name])
        return SettingsSnapshot(
            visibility =
                visibilityOf(
                    value(SettingKeys.VISIBILITY_MODE),
                    value(SettingKeys.VISIBILITY_EXPIRES_AT),
                    value(SettingKeys.VISIBILITY_REVERT_TO),
                ),
            prefer5Ghz = value(SettingKeys.PREFER_5_GHZ),
            keepScreenAwake = value(SettingKeys.KEEP_SCREEN_AWAKE),
            bundleSmallFiles = value(SettingKeys.BUNDLE_SMALL_FILES),
            saveLocation = value(SettingKeys.SAVE_LOCATION),
            nickname = value(SettingKeys.NICKNAME),
            avatar = value(SettingKeys.AVATAR),
            language = value(SettingKeys.LANGUAGE),
            crashReports = value(SettingKeys.CRASH_REPORTS),
            haptics = value(SettingKeys.HAPTICS),
        )
    }

    /** Reads the three stored parts leniently: an inconsistent combination falls back to what can be trusted. */
    private fun visibilityOf(
        mode: Visibility,
        expiresAt: Long?,
        revertTo: Visibility,
    ): VisibilityPreference {
        val safeRevert = if (revertTo == Visibility.EVERYONE_TEN_MINUTES) VisibilityPreference.DEFAULT.revertTo else revertTo
        return if (mode == Visibility.EVERYONE_TEN_MINUTES) {
            VisibilityPreference(mode, expiresAt, safeRevert)
        } else {
            VisibilityPreference(mode, null, safeRevert)
        }
    }

    companion object {
        /** While a 10-minute window is open, [observeEffectiveVisibility] re-reads the wall clock at least this often. */
        const val VISIBILITY_RECHECK_MILLIS: Long = 30_000
    }
}
