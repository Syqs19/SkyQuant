package dev.syqs.skyquant.feature.bazaar.gui

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Both bugs these guard against were shipped once and found by eye in-game, which is exactly the
 * kind of thing that should have failed here instead.
 */
class NumberFormatsTest {

    private lateinit var original: Locale

    /**
     * Every test runs under an Italian locale on purpose: that's where the decimal separator bug
     * came from, and under the default English locale the formatting would look correct while
     * still being broken for the person actually using it.
     */
    @BeforeTest
    fun useItalianLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.ITALY)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `uses a dot for decimals regardless of system locale`() {
        // "1,40k" reads as one million four hundred thousand: in a compact figure the comma is
        // the thousands separator, so the Italian decimal comma changes the number's meaning.
        assertEquals("1.40k", NumberFormats.price(1400.0))
        assertTrue(',' !in NumberFormats.price(1400.0))
    }

    @Test
    fun `compacts prices by magnitude`() {
        assertEquals("1.35k", NumberFormats.price(1350.0))
        assertEquals("2.50M", NumberFormats.price(2_500_000.0))
        assertEquals("73.9", NumberFormats.price(73.85))
        assertEquals("0.50", NumberFormats.price(0.5))
    }

    @Test
    fun `the compact form fits two figures in one column`() {
        // Used where a cell carries a pair, e.g. "174.5k/307.3k". At two decimals that string
        // overflows the column it has to sit in.
        assertEquals("174.5k", NumberFormats.priceCompact(174_530.0))
        assertEquals("12.3M", NumberFormats.priceCompact(12_345_678.0))
        assertEquals("-3.9k", NumberFormats.priceCompact(-3_900.0))

        val pair = "${NumberFormats.priceCompact(174_530.0)}/${NumberFormats.priceCompact(307_280.0)}"
        assertTrue(pair.length <= 14, "the pair \"$pair\" is too wide for the column")
    }

    @Test
    fun `the compact form keeps the dot decimal too`() {
        assertTrue(',' !in NumberFormats.priceCompact(1400.0))
    }

    @Test
    fun `states a tax rate exactly, without rounding it away`() {
        // 1.25% displayed at one decimal reads "1.3%", which disagrees with what the game
        // tells the player - the worst possible impression for a figure the mod is asking to
        // be trusted on.
        assertEquals("1.25%", NumberFormats.exactPercent(1.25))
        assertEquals("1.125%", NumberFormats.exactPercent(1.125))
    }

    @Test
    fun `drops trailing zeros from an exact percentage`() {
        // "1%" rather than "1.00%": the decimals are there when they carry something.
        assertEquals("1%", NumberFormats.exactPercent(1.0))
    }

    @Test
    fun `keeps a huge percentage inside its column`() {
        // Order-to-order margins reach six figures. 1239587.9% was printing through the
        // heading next to it at sixty pixels in a forty-eight pixel column.
        assertEquals("1240k%", NumberFormats.percentCompact(1_239_587.9))
        assertEquals("48.2k%", NumberFormats.percentCompact(48_212.0))

        val widest = NumberFormats.percentCompact(9_999_999.0)
        assertTrue(widest.length <= 7, "\"$widest\" is too wide for the margin column")
    }

    @Test
    fun `keeps precision on percentages small enough to need it`() {
        // Below a hundred the decimal is the whole difference between two rows.
        assertEquals("12.5%", NumberFormats.percentCompact(12.5))
        assertEquals("3.2%", NumberFormats.percentCompact(3.2))
        assertEquals("132%", NumberFormats.percentCompact(132.4))
    }

    @Test
    fun `keeps a decimal on volumes so neighbouring values stay distinct`() {
        // Without one, both print "1k" while their bars are visibly different heights, which
        // reads as the number being wrong rather than as the label being coarse.
        assertNotEquals(NumberFormats.volume(1_200), NumberFormats.volume(1_800))
    }

    @Test
    fun `leaves small volumes exact`() {
        assertEquals("999", NumberFormats.volume(999))
    }

    @Test
    fun `axis labels stay distinct when gridlines are close together`() {
        // The real case that shipped broken: a chart spanning 1262..1400 printed "1.3k" three
        // times over, leaving the axis saying nothing.
        val span = 1400.0 - 1262.0
        val step = span / 3

        val labels = (0..3).map { NumberFormats.axisPrice(1262.0 + span * it / 3, step) }

        assertEquals(labels.size, labels.distinct().size, "axis repeated a label: $labels")
    }

    @Test
    fun `axis labels stay coarse when gridlines are far apart`() {
        // The opposite direction: on a wide range the extra decimals would be noise.
        assertEquals("1.0k", NumberFormats.axisPrice(1000.0, step = 500.0))
    }

    @Test
    fun `change carries a direction that survives without colour`() {
        assertTrue(NumberFormats.change(2.1).startsWith("▲"))
        assertTrue(NumberFormats.change(-0.8).startsWith("▼"))
        // The sign is kept as well as the arrow, so the text alone is unambiguous.
        assertTrue("+" in NumberFormats.change(2.1))
    }

    @Test
    fun `treats zero change as a rise rather than showing no direction`() {
        assertTrue(NumberFormats.change(0.0).startsWith("▲"))
    }

    @Test
    fun `thirty seconds does not round to zero hours`() {
        // 22 of the repo's 120 forge recipes take 30 seconds, and they are the best trades on the
        // page - 31M an hour. An hours-only column would print "0h" for exactly those.
        assertEquals("30s", NumberFormats.duration(30))
    }

    @Test
    fun `durations read in the unit that suits them`() {
        // The repo's real spread, from the plan's survey of all 120 forge recipes.
        assertEquals("30m", NumberFormats.duration(30 * 60))
        assertEquals("6h", NumberFormats.duration(6 * 3600))
        assertEquals("20h", NumberFormats.duration(20 * 3600))
    }

    @Test
    fun `half hours survive`() {
        // Four recipes take 4.5 hours. Truncating to "4h" would misreport the figure the whole
        // per-hour ranking is computed from.
        assertEquals("4h30", NumberFormats.duration(4 * 3600 + 30 * 60))
    }

    @Test
    fun `past a day it says days, not a large hour count`() {
        // Seven recipes take a week. "168h" makes the reader do arithmetic to find that out.
        assertEquals("7d", NumberFormats.duration(168 * 3600))
        assertEquals("1d6h", NumberFormats.duration(30 * 3600))
    }

    @Test
    fun `no duration reads as a dash rather than a zero`() {
        // A crafting recipe is instant, and "0s" would suggest it was timed and took none.
        assertEquals("-", NumberFormats.duration(0))
    }
}
