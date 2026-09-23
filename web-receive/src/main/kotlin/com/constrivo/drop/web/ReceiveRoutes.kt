package com.constrivo.drop.web

/**
 * Endpoints of the browser receive page (architecture §10.3 as changed by spec change N15).
 *
 * Every endpoint sits under the per-session token prefix `/t/<token>/` from the QR code ([prefix]); the paths below
 * are relative to it, so the page links to them with relative URLs. A request whose path does not start with the
 * right prefix gets 404, whatever follows it. `/t/<token>` without the trailing slash redirects to the prefix.
 *
 * | Method | Path | Serves |
 * | --- | --- | --- |
 * | GET | [PAGE] (the prefix itself) | the static page (`index.html`) |
 * | GET | [FILES] | the file list as JSON, once the phone allowed this browser |
 * | GET | [FILE] | one file, with `Content-Length`, RFC 6266 `Content-Disposition` and single-range support |
 * | GET | [ALL_ZIP] | every file as one streamed STORED zip |
 * | POST | [UPLOAD] | one file sent back to the phone (P1), only when the server has an upload sink |
 *
 * The server stops [IDLE_SHUTDOWN_SECONDS] after the last transfer finished with nothing in progress.
 */
object ReceiveRoutes {
    /** First path segment of every endpoint: `/t/<token>/`. */
    const val TOKEN_SEGMENT = "t"
    const val PAGE = ""
    const val FILES = "files"
    const val FILE = "file/{index}"
    const val ALL_ZIP = "all.zip"
    const val UPLOAD = "upload"

    /** Query parameter of [UPLOAD] carrying the file name (percent-encoded UTF-8). */
    const val UPLOAD_NAME_PARAMETER = "name"
    const val IDLE_SHUTDOWN_SECONDS = 60

    /** The path prefix for [token]: `/t/<token>/`. */
    fun prefix(token: ReceiveToken): String = "/$TOKEN_SEGMENT/${token.value}/"

    /** The path of file [index] relative to the prefix. */
    fun file(index: Int): String = "file/$index"
}
