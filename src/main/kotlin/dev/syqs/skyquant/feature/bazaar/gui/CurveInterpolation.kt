package dev.syqs.skyquant.feature.bazaar.gui

/**
 * Smooths a series of data points into a curve using a centripetal Catmull-Rom spline.
 *
 * Drawing straight lines between raw data points makes the graph look jagged; this inserts
 * interpolated points so the rendered polyline reads as a smooth curve instead. Catmull-Rom
 * is used because the curve passes exactly through every original point (unlike a Bezier),
 * so the graph never misrepresents the underlying prices.
 */
object CurveInterpolation {

    /**
     * Returns [points] with [segmentsPerPoint] interpolated points inserted between each pair.
     * Fewer than 3 points can't form a curve, so they're returned unchanged.
     */
    fun smooth(
        points: List<LineRenderState.Point>,
        segmentsPerPoint: Int = 8,
    ): List<LineRenderState.Point> {
        if (points.size < 3) return points

        val result = ArrayList<LineRenderState.Point>((points.size - 1) * segmentsPerPoint + 1)

        for (i in 0 until points.size - 1) {
            // The tangent at each point depends on its neighbours; the endpoints have none,
            // so they're duplicated to keep the curve anchored instead of overshooting.
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]

            for (step in 0 until segmentsPerPoint) {
                val t = step.toFloat() / segmentsPerPoint
                result += LineRenderState.Point(
                    x = catmullRom(p0.x, p1.x, p2.x, p3.x, t),
                    y = catmullRom(p0.y, p1.y, p2.y, p3.y, t),
                )
            }
        }

        result += points.last()
        return result
    }

    /**
     * Splits [curve] into the runs that fall inside [top]..[bottom], dropping what lies outside.
     *
     * A price can legitimately sit far above the chart's own scale: hours backed by a single sale
     * are deliberately excluded from [PriceSeries.scaleBounds] so one lucky sale can't flatten
     * everything else, yet they are still drawn, because a real sale that is hidden misreports
     * how the item traded. Titanium Drill measured on 15 August 2026 had one such hour at 620M
     * against an axis topping out at 395M - 296% of the plot's height above it.
     *
     * Without this the polyline simply ran off, straight up the panel and back down, over the
     * tooltip and the side panel alike, since nothing else clips it. The band beside it has always
     * clamped its own edges; the curve never did.
     *
     * Runs are cut rather than clamped to the edge on purpose. Pinning the stray points to the
     * boundary would draw a flat stretch along the top of the plot, which reads as "the price sat
     * at exactly this level for an hour" - inventing a fact. A gap reads as what it is: the line
     * went somewhere the chart isn't showing.
     */
    fun clipVertically(
        curve: List<LineRenderState.Point>,
        top: Float,
        bottom: Float,
    ): List<List<LineRenderState.Point>> {
        val runs = ArrayList<List<LineRenderState.Point>>()
        var current = ArrayList<LineRenderState.Point>()

        for (point in curve) {
            if (point.y in top..bottom) {
                current += point
            } else if (current.isNotEmpty()) {
                runs += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) runs += current

        // A single point is a dot, not a line, and the renderer needs two to build a quad from.
        return runs.filter { it.size >= 2 }
    }

    /** Standard Catmull-Rom basis for one axis, with the usual 0.5 tension. */
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * p1 +
                (p2 - p0) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (3f * p1 - p0 - 3f * p2 + p3) * t3
            )
    }
}
