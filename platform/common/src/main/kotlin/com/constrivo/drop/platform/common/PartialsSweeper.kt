package com.constrivo.drop.platform.common

import com.constrivo.drop.core.data.DropData
import com.constrivo.drop.core.data.ResumeDataCleaner
import com.constrivo.drop.core.discovery.WallClock
import com.constrivo.drop.core.protocol.TransferId
import com.constrivo.drop.core.transfer.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The 24-hour sweep of resume state for transfers that are never resumed (architecture §7.6, §7.7 with S8; the WP4
 * and WP8 carry-forward): `core/data`'s [ResumeDataCleaner] with the platform's [FileStore.deletePartials], run once
 * at start and then every [intervalMillis]. The partial directory holds the resume plan too ([FileResumePlanStore]),
 * so it goes in the same step. Shared by the desktops and Android (moved here from `platform/desktop-common` in WP7ef).
 */
class PartialsSweeper(
    data: DropData,
    private val fileStore: FileStore,
    clock: WallClock,
    retentionMillis: Long = ResumeDataCleaner.RETENTION_MILLIS,
    private val intervalMillis: Long = ResumeDataCleaner.DEFAULT_INTERVAL_MILLIS,
    private val onReport: (ResumeDataCleaner.Report) -> Unit = {},
    private val onError: (Exception) -> Unit = {},
) {
    /** The cleaner; also serves "Clear partial files" ([ResumeDataCleaner.clearPartials]). */
    val cleaner: ResumeDataCleaner = data.resumeDataCleaner(clock, retentionMillis) { id -> deletePartials(id) }

    /** Deletes the partial files (and resume plan) of [id]; idempotent. */
    suspend fun deletePartials(id: TransferId) = fileStore.deletePartials(id.toHex())

    /** Runs the sweep in [scope] until the scope is cancelled. */
    fun launchIn(scope: CoroutineScope): Job = scope.launch { cleaner.runPeriodically(intervalMillis, onReport, onError) }
}
