package com.constrivo.drop.web

/**
 * Endpoints of the browser receive page (architecture §10.3). Every path sits under the one-time token from the
 * QR (`/t/<token>/...`); requests without it are refused. The server stops 60 s after the last download.
 */
object ReceiveRoutes {
    const val PAGE = "/"
    const val FILES = "/files"
    const val FILE = "/file/{index}"
    const val ALL_ZIP = "/all.zip"
    const val UPLOAD = "/upload"
    const val IDLE_SHUTDOWN_SECONDS = 60
}
