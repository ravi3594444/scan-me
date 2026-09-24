package com.constrivo.drop.ui.shared.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's outline icons (design §1: outline, 24 dp; §12: platform, trust and transport glyphs), drawn here as
 * original artwork on a 24-unit grid with 1.75-unit round strokes. Tint them with `Icon(tint = …)`.
 */
object DropIcons {
    val Phone: ImageVector by lazy {
        icon("phone", "M8 2.75h8a2 2 0 0 1 2 2v14.5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4.75a2 2 0 0 1 2-2z", "M11 18h2")
    }
    val Laptop: ImageVector by lazy { icon("laptop", "M5.5 5.5h13a1 1 0 0 1 1 1V15h-15V6.5a1 1 0 0 1 1-1z", "M2.75 18.25h18.5") }
    val Desktop: ImageVector by lazy {
        icon("desktop", "M4.5 4h15a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M12 15.5V20", "M8.5 20h7")
    }
    val Browser: ImageVector by lazy {
        icon(
            "browser",
            "M4.5 4.5h15a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z",
            "M3.5 8.5h17",
            "M6.5 6.5h.01",
            "M9 6.5h.01",
        )
    }
    val Device: ImageVector by lazy {
        icon("device", "M7 3.5h10a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z", "M9.5 12h5")
    }
    val Shield: ImageVector by lazy {
        icon("shield", "M12 2.75l7 2.75v5.25c0 4.4-2.9 8.3-7 9.75-4.1-1.45-7-5.35-7-9.75V5.5z", "M9 11.75l2.1 2.1 3.9-3.9")
    }
    val Wifi: ImageVector by lazy {
        icon("wifi", "M2.5 9a14 14 0 0 1 19 0", "M5.5 12.25a9.5 9.5 0 0 1 13 0", "M8.75 15.5a4.8 4.8 0 0 1 6.5 0", "M12 19h.01")
    }
    val WifiOff: ImageVector by lazy {
        icon(
            "wifi-off",
            "M2.5 9a14 14 0 0 1 4.3-2.9",
            "M11 4.55A14 14 0 0 1 21.5 9",
            "M5.5 12.25a9.5 9.5 0 0 1 4.1-2.3",
            "M15.6 10.4a9.5 9.5 0 0 1 2.9 1.85",
            "M8.75 15.5a4.8 4.8 0 0 1 6.5 0",
            "M12 19h.01",
            "M3 3l18 18",
        )
    }
    val Bluetooth: ImageVector by lazy { icon("bluetooth", "M7 7.25l10 9.5-5 4.5V2.75l5 4.5-10 9.5") }
    val BluetoothOff: ImageVector by lazy {
        icon("bluetooth-off", "M12 8.5V2.75l5 4.5-2.4 2.3", "M12 13.8v7.45l3.1-2.8", "M7 7.25l5 4.75", "M7 16.75l3-2.85", "M3 3l18 18")
    }
    val Hotspot: ImageVector by lazy {
        icon(
            "hotspot",
            "M12 11a1.25 1.25 0 1 0 0 2.5a1.25 1.25 0 1 0 0-2.5z",
            "M8.8 15.2a4.5 4.5 0 0 1 0-6.4",
            "M15.2 8.8a4.5 4.5 0 0 1 0 6.4",
            "M6 18a8.5 8.5 0 0 1 0-12",
            "M18 6a8.5 8.5 0 0 1 0 12",
        )
    }
    val Network: ImageVector by lazy {
        icon("network", "M9.5 3h5v4.5h-5z", "M12 7.5v4", "M5.5 11.5h13", "M5.5 11.5V15", "M18.5 11.5V15", "M3 15h5v5H3z", "M16 15h5v5h-5z")
    }
    val Lock: ImageVector by lazy {
        icon(
            "lock",
            "M6.5 10.5h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z",
            "M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3",
            "M12 14.5v2",
        )
    }
    val EyeOff: ImageVector by lazy {
        icon(
            "eye-off",
            "M3 3l18 18",
            "M10.5 5.6A9.6 9.6 0 0 1 12 5.5c5.2 0 9 6.5 9 6.5a16 16 0 0 1-2.9 3.5",
            "M6.6 6.9C4.3 8.6 3 12 3 12s3.8 6.5 9 6.5a9 9 0 0 0 4-.95",
            "M9.9 9.9a3 3 0 0 0 4.2 4.2",
        )
    }
    val Check: ImageVector by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    val Close: ImageVector by lazy { icon("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11") }
    val Qr: ImageVector by lazy {
        icon(
            "qr",
            "M4 4h6v6H4z",
            "M14 4h6v6h-6z",
            "M4 14h6v6H4z",
            "M14 14h2.5v2.5H14z",
            "M18.5 18.5H20V20h-1.5z",
            "M14 20h2",
            "M20 14v2",
            "M6.75 6.75h.5v.5h-.5z",
            "M16.75 6.75h.5v.5h-.5z",
            "M6.75 16.75h.5v.5h-.5z",
        )
    }
    val Scan: ImageVector by lazy {
        icon(
            "scan",
            "M4 8.5V5a1 1 0 0 1 1-1h3.5",
            "M15.5 4H19a1 1 0 0 1 1 1v3.5",
            "M20 15.5V19a1 1 0 0 1-1 1h-3.5",
            "M8.5 20H5a1 1 0 0 1-1-1v-3.5",
            "M7 12h10",
        )
    }
    val Dashboard: ImageVector by lazy { icon("dashboard", "M4 4h7v8H4z", "M13 4h7v5h-7z", "M13 11h7v9h-7z", "M4 14h7v6H4z") }
    val ArrowUp: ImageVector by lazy { icon("arrow-up", "M12 19.5v-15", "M6 10.5l6-6 6 6") }
    val ArrowDown: ImageVector by lazy { icon("arrow-down", "M12 4.5v15", "M6 13.5l6 6 6-6") }
    val Back: ImageVector by lazy { icon("back", "M19.5 12h-15", "M10.5 6l-6 6 6 6") }
    val Folder: ImageVector by lazy {
        icon("folder", "M3.5 6.5a1 1 0 0 1 1-1h4.8l2 2h8.2a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z")
    }
    val File: ImageVector by lazy {
        icon("file", "M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M14 3v5h5", "M9 13h6", "M9 16.5h6")
    }
    val Image: ImageVector by lazy {
        icon(
            "image",
            "M5 4.5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z",
            "M4 16l4.5-4.5 4 4 2-2 5.5 5.5",
            "M15.5 8.5h.01",
        )
    }
    val Video: ImageVector by lazy {
        icon("video", "M4.5 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-10a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z", "M15.5 10.5l5-3v9l-5-3")
    }
    val Audio: ImageVector by lazy {
        icon("audio", "M9.5 17.5V6l10-2v11.5", "M7.5 15.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z", "M17.5 13.5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")
    }
    val Archive: ImageVector by lazy { icon("archive", "M3.5 4h17v4h-17z", "M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8", "M10 12h4") }
    val App: ImageVector by lazy { icon("app", "M5 5h5v5H5z", "M14 5h5v5h-5z", "M5 14h5v5H5z", "M14 14h5v5h-5z") }
    val Pause: ImageVector by lazy { icon("pause", "M8.5 5.5v13", "M15.5 5.5v13") }
    val Play: ImageVector by lazy { icon("play", "M8 5.5v13l10.5-6.5z") }
    val Plus: ImageVector by lazy { icon("plus", "M12 5v14", "M5 12h14") }
    val Live: ImageVector by lazy { icon("live", "M3 12h4l2.5-6.5 5 13L17 12h4") }
    val History: ImageVector by lazy { icon("history", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M12 7.5V12l3 2") }
    val Devices: ImageVector by lazy {
        icon(
            "devices",
            "M3.5 6h11a1 1 0 0 1 1 1v8h-13V7a1 1 0 0 1 1-1z",
            "M1.75 18h11",
            "M18 9h3a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z",
        )
    }
    val Stats: ImageVector by lazy { icon("stats", "M5 20V11", "M10 20V5", "M15 20v-6", "M20 20V8") }
    val Settings: ImageVector by lazy {
        icon(
            "settings",
            "M4 7h8",
            "M16 7h4",
            "M14 5v4",
            "M4 12h3",
            "M11 12h9",
            "M9 10v4",
            "M4 17h10",
            "M18 17h2",
            "M16 15v4",
        )
    }
    val Edit: ImageVector by lazy { icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Trash: ImageVector by lazy { icon("trash", "M4.5 7h15", "M9 7V4.5h6V7", "M6.5 7l1 13h9l1-13", "M10 11v5.5", "M14 11v5.5") }
    val Share: ImageVector by lazy { icon("share", "M12 15V3.5", "M8 7.5l4-4 4 4", "M5.5 11.5v8h13v-8") }
    val Person: ImageVector by lazy { icon("person", "M12 4a3.75 3.75 0 1 0 0 7.5a3.75 3.75 0 1 0 0-7.5z", "M4.5 20a7.5 7.5 0 0 1 15 0") }
    val Camera: ImageVector by lazy {
        icon(
            "camera",
            "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
            "M12 9.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z",
        )
    }
    val Battery: ImageVector by lazy {
        icon("battery", "M3.5 8h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-14a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M21 11v2", "M6 11v2")
    }
    val Bell: ImageVector by lazy { icon("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", "M10 20.5h4") }
    val Info: ImageVector by lazy { icon("info", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M12 11v5", "M12 8h.01") }
    val Radar: ImageVector by lazy {
        icon("radar", "M4 19a11 11 0 0 1 16 0", "M7.5 16a6.5 6.5 0 0 1 9 0", "M12 13V4", "M9.5 6.5L12 4l2.5 2.5")
    }

    /** A 24-unit outline icon from SVG path strings. */
    private fun icon(
        name: String,
        vararg paths: String,
    ): ImageVector {
        val builder =
            ImageVector.Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private const val STROKE = 1.75f
}
