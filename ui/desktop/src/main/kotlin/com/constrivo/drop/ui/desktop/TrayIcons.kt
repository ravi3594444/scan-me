package com.constrivo.drop.ui.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/**
 * The tray icon, drawn with Java2D at the size the OS asks for (works headless, so it is tested without a display):
 * the radar's rings with the device in the middle (design §1 tokens), a quarter arc that turns while transferring,
 * and a filled accent badge when something waits for the user (design §9).
 */
object TrayIcons {
    /** Design §1 primary (`#2F6BFF`). */
    val ACCENT: Color = Color(0x2F, 0x6B, 0xFF)

    /** Design §1 warning, for the attention badge. */
    val ATTENTION: Color = Color(0xF5, 0x9E, 0x0B)

    private val RING: Color = Color(0x2F, 0x6B, 0xFF, 0x80)

    /**
     * The icon for [state] at [size] × [size] pixels; [frame] picks the arc position while transferring.
     *
     * @throws IllegalArgumentException for a size below 8 pixels.
     */
    fun render(
        state: TrayState,
        size: Int,
        frame: Int = 0,
    ): BufferedImage {
        require(size >= MIN_SIZE) { "tray icons are at least $MIN_SIZE pixels" }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val s = size.toDouble()
            val stroke = maxOf(1.0f, size / 14f)
            g.stroke = BasicStroke(stroke)
            // Outer ring and the device dot in the middle.
            val inset = stroke * 1.0
            g.color = RING
            g.draw(Ellipse2D.Double(inset, inset, s - 2 * inset, s - 2 * inset))
            g.color = ACCENT
            val dot = s * 0.34
            g.fill(Ellipse2D.Double((s - dot) / 2, (s - dot) / 2, dot, dot))
            // Idle is the rings and the dot alone.
            when (state) {
                TrayState.IDLE -> {}

                TrayState.TRANSFERRING -> {
                    g.stroke = BasicStroke(stroke * 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    // Arc2D angles run counter-clockwise from three o'clock; the model's run clockwise from the top.
                    val start = 90.0 - TrayModel.arcStartDegrees(frame)
                    g.draw(Arc2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, start, -90.0, Arc2D.OPEN))
                }

                TrayState.ATTENTION -> {
                    val badge = s * 0.42
                    g.color = ATTENTION
                    g.fill(Ellipse2D.Double(s - badge, 0.0, badge, badge))
                }
            }
        } finally {
            g.dispose()
        }
        return image
    }

    private const val MIN_SIZE = 8
}
