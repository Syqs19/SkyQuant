package dev.syqs.skyquant.feature.bazaar.gui

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc
import kotlin.math.sqrt

/**
 * Filled rectangle with rounded corners, for panels that shouldn't look like hard-edged boxes.
 *
 * [RenderPipelines.GUI] draws quads only, so the shape is built as a stack of thin horizontal
 * quads whose width follows the corner radius, rather than as an outline or a triangle fan.
 */
class RoundedRectRenderState(
    private val pose: Matrix3x2fc,
    private val x0: Float,
    private val y0: Float,
    private val x1: Float,
    private val y1: Float,
    private val radius: Float,
    private val color: Int,
    private val scissor: ScreenRectangle?,
    /**
     * Length of the diagonal chamfer on the bottom-right corner, or 0 for none.
     *
     * One corner rather than four: a chamfer on every side reads as a decorative frame, while a
     * single cut reads as the panel having a front. It's the mod's one deliberate flourish, so it
     * appears once per panel.
     */
    private val bottomRightCut: Float = 0f,
    /**
     * Whether the curved edges get their faded fringe.
     *
     * Off for the plate behind a border: there the fringe fades the very line it is drawing, and
     * the panel's own fringe then covers what little survives - so along a corner the border
     * thinned to nothing. A shape that exists to *be* an edge wants a hard one.
     */
    private val softEdges: Boolean = true,
) : GuiElementRenderState {

    override fun buildVertices(consumer: VertexConsumer) {
        val r = radius.coerceAtMost(minOf(x1 - x0, y1 - y0) / 2f)
        val cut = bottomRightCut.coerceAtMost(minOf(x1 - x0, y1 - y0) / 2f)

        // Horizontal scanlines: every row is a plain 4-vertex quad, inset near the top and
        // bottom to follow the corner arcs. Only real quads are emitted - collapsing one into
        // a triangle by repeating a vertex makes the whole primitive disappear.
        val transparent = color and 0x00FFFFFF

        var y = y0
        while (y < y1) {
            val rowHeight = minOf(ROW_HEIGHT, y1 - y)
            // Sampled at the row's midpoint rather than its widest edge: taking the extreme
            // makes each row visibly wider than its neighbour, which is what reads as stairs.
            val midpoint = y + rowHeight / 2f
            val inset = cornerInset(midpoint, r)

            val left = x0 + inset
            // The chamfer eats into the right edge on the last rows, growing linearly, which is
            // what makes it read as a straight diagonal rather than as another curve.
            val right = x1 - maxOf(inset, chamferInset(midpoint, cut))

            // Same vertex order as vanilla's ColoredRectangleRenderState: top-left, down to
            // bottom-left, across, then back up. The opposite direction gets culled away.
            vertex(consumer, left, y, color)
            vertex(consumer, left, y + rowHeight, color)
            vertex(consumer, right, y + rowHeight, color)
            vertex(consumer, right, y, color)

            // Only the curved part needs softening; the straight sides are already pixel-aligned
            // and a fringe there would just blur the panel edge.
            if (softEdges && inset > 0f) {
                vertex(consumer, left - FRINGE_WIDTH, y, transparent)
                vertex(consumer, left - FRINGE_WIDTH, y + rowHeight, transparent)
                vertex(consumer, left, y + rowHeight, color)
                vertex(consumer, left, y, color)

                vertex(consumer, right, y, color)
                vertex(consumer, right, y + rowHeight, color)
                vertex(consumer, right + FRINGE_WIDTH, y + rowHeight, transparent)
                vertex(consumer, right + FRINGE_WIDTH, y, transparent)
            }

            y += rowHeight
        }
    }

    /** How far this row pulls back from the right edge to form the diagonal cut. */
    private fun chamferInset(y: Float, cut: Float): Float {
        if (cut <= 0f) return 0f

        val distanceFromBottom = y1 - y
        if (distanceFromBottom > cut) return 0f

        return cut - distanceFromBottom
    }

    /**
     * How far this row must pull back from the straight edge to stay inside the corner arc.
     *
     * The arc is centred [r] in from both edges, so a border drawn as a larger rounded rectangle
     * behind a smaller one keeps an even thickness only if both arcs share that centre. Growing
     * the outer radius by the border width does exactly that: the outer shape starts a border
     * width further out and curves on a radius a border width larger, leaving the two arcs
     * concentric and the gap between them constant the whole way round.
     */
    private fun cornerInset(y: Float, r: Float): Float {
        if (r <= 0f) return 0f

        val distanceIntoCorner = when {
            y < y0 + r -> y0 + r - y
            y > y1 - r -> y - (y1 - r)
            else -> return 0f
        }

        val clamped = distanceIntoCorner.coerceIn(0f, r)
        return r - sqrt(r * r - clamped * clamped)
    }

    private fun vertex(consumer: VertexConsumer, x: Float, y: Float, vertexColor: Int) {
        consumer.addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(vertexColor)
    }

    override fun pipeline() = RenderPipelines.GUI

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle =
        ScreenRectangle(x0.toInt(), y0.toInt(), (x1 - x0).toInt() + 1, (y1 - y0).toInt() + 1)

    private companion object {
        /** Row thickness: 1px keeps the corner arcs smooth without excessive geometry. */
        const val ROW_HEIGHT = 1f

        /** Faded edge along the curved part of each row, to hide the 1px stair-stepping. */
        const val FRINGE_WIDTH = 0.7f
    }
}
