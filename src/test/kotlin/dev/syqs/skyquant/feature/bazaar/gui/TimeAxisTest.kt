package dev.syqs.skyquant.feature.bazaar.gui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The labels under the chart, which say *when* each part of the curve happened.
 *
 * Both faults guarded here were visible on screen and invisible in review, and both came from the
 * same root: the label's position was computed one way and its text another. A duplicate reads as
 * a broken renderer; a label naming the wrong moment reads as correct and isn't, which is worse.
 */
class TimeAxisTest {

    /** UTC, so the assertions don't depend on the machine's zone. */
    private fun hoursToMillis(hour: Long) = hour * 60 * 60 * 1000

    @Test
    fun `never more labels than the series has points`() {
        // A real day of Divan's Drill: four sales, in a plot wide enough for six labels. The cap
        // is what stops the sixth from being a repeat of the fifth.
        assertEquals(3, PriceChart.timeLabelCount(plotWidth = 1130, pointCount = 4))
    }

    @Test
    fun `a wide plot with plenty of points still caps at five`() {
        // The upper bound is about legibility rather than data: past five they crowd each other
        // at this font size.
        assertEquals(5, PriceChart.timeLabelCount(plotWidth = 2000, pointCount = 200))
    }

    @Test
    fun `a two point series still gets one gap rather than none`() {
        // Dividing by a label count of zero would put every label at the same place.
        assertTrue(PriceChart.timeLabelCount(plotWidth = 1130, pointCount = 2) >= 1)
    }

    @Test
    fun `labels do not repeat across an unevenly traded day`() {
        // The exact bug, with the real timestamps behind it: Divan's Drill sold at 00:00, 10:00,
        // 16:00 and 18:00 UTC on 15 August 2026. The axis read
        // "02:00 02:00 12:00 12:00 18:00 20:00" - the first four being two duplicated pairs.
        val first = hoursToMillis(0)
        val last = hoursToMillis(18)
        val count = PriceChart.timeLabelCount(plotWidth = 1130, pointCount = 4)

        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
        val labels = (0..count).map {
            formatter.format(Instant.ofEpochMilli(PriceChart.timeLabelMillis(first, last, it, count)))
        }

        assertEquals(labels.size, labels.toSet().size, "duplicate time labels: $labels")
    }

    @Test
    fun `a label names the moment at its own position, not a nearby point`() {
        // The subtler half. Positions have always been a fraction of the plot width, so the text
        // has to be too: with points at 00:00, 10:00, 16:00 and 18:00, the midpoint of the axis
        // is 09:00 - not 10:00, which is simply the point whose index the fraction truncated to.
        val first = hoursToMillis(0)
        val last = hoursToMillis(18)

        val middle = PriceChart.timeLabelMillis(first, last, step = 1, labelCount = 2)

        assertEquals(hoursToMillis(9), middle)
    }

    @Test
    fun `the ends of the axis are the ends of the window`() {
        // Whatever happens in between, the first and last labels must be the first and last
        // moments charted - otherwise the axis disagrees with the curve at its own edges.
        val first = hoursToMillis(3)
        val last = hoursToMillis(21)

        assertEquals(first, PriceChart.timeLabelMillis(first, last, step = 0, labelCount = 4))
        assertEquals(last, PriceChart.timeLabelMillis(first, last, step = 4, labelCount = 4))
    }

    @Test
    fun `a point above the ceiling is marked as off scale`() {
        // Titanium Drill, 15 August 2026: the 17:00 hour sold once at 620M against an axis
        // topping out at 395M. Clipping the curve stopped it running over the panel, but left it
        // ending in mid-air with nothing to say why - which reads as a broken chart.
        val values = listOf(338.8e6, 345.0e6, 620.0e6, 336.0e6)

        assertEquals(listOf(2), PriceChart.offScaleIndices(values, ceiling = 395.0e6))
    }

    @Test
    fun `a point exactly on the ceiling is not an excursion`() {
        // It is drawn at the top edge and is perfectly visible, so a caret over it would point at
        // a line that never left.
        assertEquals(emptyList(), PriceChart.offScaleIndices(listOf(395.0e6), ceiling = 395.0e6))
    }

    @Test
    fun `an ordinary chart has no markers at all`() {
        // The common case by far - nothing should be drawn over a well behaved curve.
        val values = listOf(330.0e6, 350.0e6, 340.0e6, 360.0e6)

        assertTrue(PriceChart.offScaleIndices(values, ceiling = 395.0e6).isEmpty())
    }

    @Test
    fun `every excursion is reported, not just the first`() {
        // The screenshot had three. Reporting one would leave the other two unexplained, which is
        // the exact fault being fixed.
        val values = listOf(330.0e6, 620.0e6, 340.0e6, 500.0e6, 480.0e6)

        assertEquals(listOf(1, 3, 4), PriceChart.offScaleIndices(values, ceiling = 395.0e6))
    }

    @Test
    fun `nothing is skipped when too little would be left to draw`() {
        // The line skips these hours, so skipping nearly all of them would leave one point and no
        // curve at all - a chart that renders nothing while reporting no error, which is the
        // failure mode this project has been bitten by before. Here the outliers stay in.
        val values = listOf(330.0e6, 620.0e6, 650.0e6, 700.0e6)

        assertTrue(PriceChart.offScaleIndices(values, ceiling = 395.0e6).isEmpty())
    }

    @Test
    fun `skipping still happens while enough points remain`() {
        // The guard must not be so eager that it disables the feature in the ordinary case: the
        // measured Titanium Drill day is 22 points with one excursion.
        val values = List(22) { 340.0e6 }.toMutableList().also { it[17] = 620.0e6 }

        assertEquals(listOf(17), PriceChart.offScaleIndices(values, ceiling = 395.0e6))
    }

    @Test
    fun `labels run forwards in time`() {
        // A sign slip here would draw the window backwards while everything still looked plausible.
        val first = hoursToMillis(0)
        val last = hoursToMillis(24)
        val count = 4

        val times = (0..count).map { PriceChart.timeLabelMillis(first, last, it, count) }

        assertEquals(times.sorted(), times, "the time axis ran backwards: $times")
    }
}
