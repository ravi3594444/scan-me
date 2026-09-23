package com.constrivo.drop.web

/**
 * Tunables of a [ReceiveSession] (architecture §10.3, spec change N15). The defaults are the production values.
 *
 * @property idleTimeoutMillis the server stops this long after the last transfer finished with nothing in progress
 *   (60 s, [ReceiveRoutes.IDLE_SHUTDOWN_SECONDS]).
 * @property idleCheckIntervalMillis how often the idle watchdog looks at the clock.
 * @property maxBrowsers how many browsers may present the token; each needs the phone's approval. Set 1 to make the
 *   token strictly single-browser.
 * @property approvalTimeoutMillis the phone's prompt for a browser is withdrawn after this long without an answer; the
 *   browser is then [BrowserState.EXPIRED] and reloading the page asks again.
 * @property approvalWaitMillis how long `GET files` holds a pending browser before answering 202 (the page asks
 *   again at once), so the list appears as soon as the phone says yes.
 * @property bufferSize copy buffer for downloads, the zip and uploads.
 * @property forceZip64 write every ZIP64 structure even for small archives (tests only).
 */
data class ReceiveSettings(
    val idleTimeoutMillis: Long = ReceiveRoutes.IDLE_SHUTDOWN_SECONDS * 1000L,
    val idleCheckIntervalMillis: Long = 1000,
    val maxBrowsers: Int = 4,
    val approvalTimeoutMillis: Long = 120_000,
    val approvalWaitMillis: Long = 20_000,
    val bufferSize: Int = 64 * 1024,
    val forceZip64: Boolean = false,
) {
    init {
        require(idleTimeoutMillis > 0 && idleCheckIntervalMillis > 0) { "idle timings must be positive" }
        require(maxBrowsers >= 1) { "at least one browser must be allowed" }
        require(approvalTimeoutMillis > 0 && approvalWaitMillis >= 0) { "approval timings must be positive" }
        require(bufferSize in 1024..(4 shl 20)) { "buffer size must be in 1 KiB..4 MiB" }
    }
}
