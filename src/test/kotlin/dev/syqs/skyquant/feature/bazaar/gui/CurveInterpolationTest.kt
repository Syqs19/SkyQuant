package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveInterpolationTest {

    private fun points(vararg values: Pair<Float, Float>) =
        values.map { LineRenderState.Point(it.first, it.second) }

    @Test
    fun `passes through every original point`() {
        // The property that makes Catmull-Rom the right choice here: a smoothing that merely
        // approached the data would draw prices the player never saw.
        val original = points(0f to 10f, 10f to 30f, 20f to 20f, 30f to 40f)

        val smoothed = CurveInterpolation.smooth(original)

        for (point in original) {
            assertTrue(
                smoothed.any { abs(it.x - point.x) < 0.01f && abs(it.y - point.y) < 0.01f },
                "curve misses the original point (${point.x}, ${point.y})",
            )
        }
    }

    @Test
    fun `keeps points in order along the x axis`() {
        // Out-of-order points would make the line double back on itself, which reads as the
        // price having jumped backwards in time.
        val smoothed = CurveInterpolation.smooth(points(0f to 0f, 10f to 50f, 20f to 10f, 30f to 30f))

        for (i in 1 until smoothed.size) {
            assertTrue(smoothed[i].x >= smoothed[i - 1].x, "x went backwards at index $i")
        }
    }

    @Test
    fun `returns short series untouched`() {
        // Two points are already a straight line; there's nothing to interpolate.
        val two = points(0f to 0f, 10f to 10f)
        assertEquals(two, CurveInterpolation.smooth(two))
        assertEquals(emptyList(), CurveInterpolation.smooth(emptyList()))
    }

    @Test
    fun `ends exactly on the last point`() {
        // The live price sits at the end of the series, so the curve has to actually reach it.
        val original = points(0f to 0f, 10f to 20f, 20f to 40f)

        assertEquals(original.last(), CurveInterpolation.smooth(original).last())
    }

    @Test
    fun `does not overshoot far beyond the data on a flat series`() {
        // A spline with the wrong tension bulges past its inputs; on a flat price that would
        // draw a wobble where nothing happened.
        val flat = points(0f to 10f, 10f to 10f, 20f to 10f, 30f to 10f)

        val smoothed = CurveInterpolation.smooth(flat)

        assertTrue(smoothed.all { abs(it.y - 10f) < 0.01f }, "flat series was not drawn flat")
    }

    @Test
    fun `a point far above the scale does not escape the plot`() {
        // Titanium Drill, 15 August 2026: the 17:00 hour had a single sale at 620M against an
        // axis topping out at 395M - 296% of the plot's height above it. Single-sale hours are
        // kept out of the scale on purpose but still drawn, so the line ran straight up the panel
        // and back down, crossing the tooltip and the side panel on the way.
        val curve = points(0f to 50f, 10f to 40f, 20f to -900f, 30f to 45f, 40f to 55f)

        val runs = CurveInterpolation.clipVertically(curve, top = 0f, bottom = 100f)

        for (run in runs) {
            for (point in run) {
                assertTrue(point.y in 0f..100f, "a point escaped the plot at y=${point.y}")
            }
        }
    }

    @Test
    fun `the line is cut into runs rather than flattened onto the edge`() {
        // Clamping the stray points to the boundary would draw a flat stretch along the top of
        // the plot, which reads as "the price held exactly this level" - a fact that never
        // happened. A gap is honest: the line went somewhere the chart isn't showing.
        val curve = points(0f to 50f, 10f to 50f, 20f to -900f, 30f to 50f, 40f to 50f)

        val runs = CurveInterpolation.clipVertically(curve, top = 0f, bottom = 100f)

        assertEquals(2, runs.size, "the excursion should split the line in two, not bridge it")
    }

    @Test
    fun `a curve entirely inside the plot is left as one run`() {
        // The ordinary case - a well traded item never leaves the plot, and must not be chopped up.
        val curve = points(0f to 50f, 10f to 40f, 20f to 60f, 30f to 45f)

        val runs = CurveInterpolation.clipVertically(curve, top = 0f, bottom = 100f)

        assertEquals(1, runs.size)
        assertEquals(curve.size, runs.single().size)
    }

    @Test
    fun `dropping an outlying hour leaves the curve calm`() {
        // Why the outlying hour is left out of the series rather than capped at the ceiling.
        // Titanium Drill's 15:00 hour sold once at 620M among prices near 340M. Capping it kept
        // the line continuous but drew a tall rounded peak climbing to the top of the plot and
        // back - which reads as a price that rose to 400M, a movement that never happened.
        //
        // With the hour omitted the curve simply joins its neighbours, which is what the price
        // actually did. The dot and its label carry the sale itself.
        val ceiling = 100f
        val withoutOutlier = points(0f to 60f, 10f to 50f, 30f to 55f, 40f to 58f)

        val smoothed = CurveInterpolation.smooth(withoutOutlier)

        // Never rises anywhere near the ceiling, because nothing in the data does.
        assertTrue(
            smoothed.all { it.y < 70f },
            "the curve climbed to ${smoothed.maxOf { it.y }} with no data up there",
        )
    }

    @Test
    fun `a lone surviving point is dropped rather than drawn as a dot`() {
        // The renderer builds quads from pairs, so a run of one has nothing to draw and would
        // either vanish or produce a degenerate quad.
        val curve = points(0f to -900f, 10f to 50f, 20f to -900f)

        assertTrue(CurveInterpolation.clipVertically(curve, top = 0f, bottom = 100f).isEmpty())
    }
}
