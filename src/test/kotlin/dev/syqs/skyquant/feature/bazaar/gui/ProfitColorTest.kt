package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.gui.Palette
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule that a loss is never drawn as a gain.
 *
 * Extracted from the screens purely so it can be tested: the first version of the paired total
 * coloured the whole cell by the better of its two figures, so Minnow Bait's "-2.6k/92.5k"
 * printed entirely in green. Every test around it passed, because they all checked that the
 * table *could* hold two colours rather than that the screen *used* two.
 */
class ProfitColorTest {

    @Test
    fun `a gain is positive`() {
        assertEquals(Palette.POSITIVE, ProfitColor.of(92_500.0))
    }

    @Test
    fun `a loss is negative`() {
        assertEquals(Palette.NEGATIVE, ProfitColor.of(-2_600.0))
    }

    @Test
    fun `breaking even is not a gain`() {
        assertEquals(Palette.NEGATIVE, ProfitColor.of(0.0))
    }

    @Test
    fun `the two halves of a real pair get different colours`() {
        // Minnow Bait exactly as it appeared on screen.
        assertEquals(Palette.NEGATIVE, ProfitColor.of(-2_600.0))
        assertEquals(Palette.POSITIVE, ProfitColor.of(92_500.0))
    }

    @Test
    fun `the arrow carries the sign without colour`() {
        // The colour-blind themes rest on this: strip every colour out and the direction still
        // has to read.
        assertEquals("▲", ProfitColor.arrow(1.0))
        assertEquals("▼", ProfitColor.arrow(-1.0))
    }
}
