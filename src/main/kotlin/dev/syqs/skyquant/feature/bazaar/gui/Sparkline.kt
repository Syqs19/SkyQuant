package dev.syqs.skyquant.feature.bazaar.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f

/**
 * A word-sized price curve, drawn inline beside a figure.
 *
 * Stripped of everything except the line: no axes, no gridlines, no labels. What survives is the
 * shape of the move - whether the current price is the top of a climb, a dip in a flat stretch,
 * or the tail of a slide - which a number and a percentage cannot express between them. A single
 * "+2.1%" reads the same whether the price rose steadily all hour or spiked and fell back.
 *
 * The vertical scale is per-sparkline, fitted to that item's own range, so the shape is readable
 * on a product that moved a few coins as much as on one that moved thousands. That makes the
 * height meaningless across rows on purpose: these are shapes to compare, not magnitudes.
 */
object Sparkline {

    /**
     * Draws [values] into the given box. Needs at least two points; anything less has no shape
     * and is skipped rather than drawn as a misleading flat line.
     */
    fun draw(
        graphics: GuiGraphicsExtractor,
        pose: Matrix3x2f,
        values: List<Double>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
        scissor: ScreenRectangle? = null,
    ) {
        if (values.size < 2 || width < 2 || height < 2) return

        val lowest = values.min()
        val highest = values.max()
        val span = highest - lowest

        val points = values.mapIndexed { index, value ->
            // A perfectly flat series has no range to normalise against; it's drawn down the
            // middle, which is what a flat price should look like.
            val fraction = if (span > 1e-9) (value - lowest) / span else 0.5

            LineRenderState.Point(
                x = x + width * index.toFloat() / (values.size - 1),
                // Inset by the line's own thickness so the extremes aren't clipped in half at
                // the top and bottom of the box.
                y = (y + INSET + (height - INSET * 2) * (1.0 - fraction)).toFloat(),
            )
        }

        graphics.guiRenderState.addGuiElement(
            LineRenderState(
                pose,
                points,
                THICKNESS,
                color,
                scissor,
                LineRenderState.boundsOf(points, THICKNESS),
            ),
        )
    }

    /** Width a sparkline needs to read as a trend rather than as a couple of strokes. */
    const val WIDTH = 28

    /** Roughly the height of a line of text, which is what keeps it inline rather than a chart. */
    const val HEIGHT = 8

    // Thinner than the chart's line: at this size a heavier stroke fills the box and the shape
    // stops being legible.
    private const val THICKNESS = 1f
    private const val INSET = 1f
}
