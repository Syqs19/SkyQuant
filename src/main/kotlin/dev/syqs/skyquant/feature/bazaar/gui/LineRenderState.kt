package dev.syqs.skyquant.feature.bazaar.gui

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2fc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Draws a polyline of arbitrary thickness in a GUI.
 *
 * Since 1.21.8 GUI rendering is split-phase: instead of drawing immediately, elements are
 * submitted as [GuiElementRenderState] and built into vertices later. [RenderPipelines.GUI]
 * uses [com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS], so each segment becomes a quad
 * whose corners sit perpendicular to the segment direction, flanked by two fringe quads that
 * fade out to approximate antialiasing.
 */
class LineRenderState(
    private val pose: Matrix3x2fc,
    private val points: List<Point>,
    private val thickness: Float,
    private val color: Int,
    private val scissor: ScreenRectangle?,
    private val bounds: ScreenRectangle,
) : GuiElementRenderState {

    data class Point(val x: Float, val y: Float)

    override fun buildVertices(consumer: VertexConsumer) {
        val half = thickness / 2f

        // Each segment is extended by half the thickness at both ends, so consecutive quads
        // overlap enough to hide the wedge-shaped gap left on the outside of a turn. Cheaper
        // and more robust than emitting a separate joint quad, which degenerates into an
        // invisible sliver whenever two segments are nearly collinear.
        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]

            val dx = to.x - from.x
            val dy = to.y - from.y
            val length = hypot(dx, dy)
            if (length < 1e-4f) continue

            val dirX = dx / length
            val dirY = dy / length
            // Unit vector perpendicular to the segment, scaled to half the line thickness.
            val offsetX = -dirY * half
            val offsetY = dirX * half
            // Capped at a fraction of the segment: interpolated points sit ~2px apart, and
            // extending by the full half-thickness at both ends would overshoot the segment,
            // flipping the quad inside out so it collapses to nothing.
            val extend = half.coerceAtMost(length * 0.5f)
            val extendX = dirX * extend
            val extendY = dirY * extend

            val startX = from.x - extendX
            val startY = from.y - extendY
            val endX = to.x + extendX
            val endY = to.y + extendY

            // Vertices must walk the quad's perimeter in one direction, like vanilla's
            // ColoredRectangleRenderState does: start side, along the segment, back on the
            // other side. Crossing over between the two sides makes a self-intersecting
            // "Z" that renders as nothing.
            quad(
                consumer,
                startX + offsetX, startY + offsetY, color,
                endX + offsetX, endY + offsetY, color,
                endX - offsetX, endY - offsetY, color,
                startX - offsetX, startY - offsetY, color,
            )

            // The GUI pipeline has no multisampling, so a bare quad edge stair-steps on any
            // diagonal. These two fringes fade to fully transparent over one pixel, which
            // reads as a smooth edge - the same trick used for antialiased 2D lines.
            //
            // They run between the raw points rather than the extended ones: being partly
            // transparent, any overlap between neighbouring segments blends twice and shows
            // up as a bright dot at every joint.
            val fringeX = -dirY * FRINGE_WIDTH
            val fringeY = dirX * FRINGE_WIDTH
            val transparent = color and 0x00FFFFFF

            quad(
                consumer,
                from.x + offsetX + fringeX, from.y + offsetY + fringeY, transparent,
                to.x + offsetX + fringeX, to.y + offsetY + fringeY, transparent,
                to.x + offsetX, to.y + offsetY, color,
                from.x + offsetX, from.y + offsetY, color,
            )

            quad(
                consumer,
                from.x - offsetX, from.y - offsetY, color,
                to.x - offsetX, to.y - offsetY, color,
                to.x - offsetX - fringeX, to.y - offsetY - fringeY, transparent,
                from.x - offsetX - fringeX, from.y - offsetY - fringeY, transparent,
            )
        }
    }

    private fun quad(
        consumer: VertexConsumer,
        x0: Float, y0: Float, c0: Int,
        x1: Float, y1: Float, c1: Int,
        x2: Float, y2: Float, c2: Int,
        x3: Float, y3: Float, c3: Int,
    ) {
        vertex(consumer, x0, y0, c0)
        vertex(consumer, x1, y1, c1)
        vertex(consumer, x2, y2, c2)
        vertex(consumer, x3, y3, c3)
    }

    private fun vertex(consumer: VertexConsumer, x: Float, y: Float, vertexColor: Int) {
        // RenderPipelines.GUI declares POSITION_TEX_COLOR, so every vertex must carry a UV even
        // though there's no texture bound; skipping setUv leaves the attributes misaligned and
        // the quad renders as garbage.
        consumer.addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(vertexColor)
    }

    override fun pipeline() = RenderPipelines.GUI

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle = bounds

    companion object {
        /**
         * Width of the faded edge on each side of the line, in pixels. Kept under a pixel:
         * the GUI has no multisampling, so this fringe is what softens the stair-stepping,
         * but any wider and the line reads as blurry rather than smooth.
         */
        private const val FRINGE_WIDTH = 0.6f

        /** Bounding box covering [points] grown by [thickness], as required by the render state. */
        fun boundsOf(points: List<Point>, thickness: Float): ScreenRectangle {
            if (points.isEmpty()) return ScreenRectangle.empty()

            val margin = ceil(thickness / 2f + FRINGE_WIDTH).toInt() + 1
            val minX = floor(points.minOf { it.x }).toInt() - margin
            val minY = floor(points.minOf { it.y }).toInt() - margin
            val maxX = ceil(points.maxOf { it.x }).toInt() + margin
            val maxY = ceil(points.maxOf { it.y }).toInt() + margin

            return ScreenRectangle(minX, minY, maxX - minX, maxY - minY)
        }
    }
}
