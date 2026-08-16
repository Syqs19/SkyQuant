package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one-line answer the auction screen leads with.
 *
 * Worth testing directly because it is the only thing on that screen a player is likely to act on
 * without reading further, and because both of its wrong answers are quiet: calling an ordinary
 * price a bargain, or staying silent when something really is cheap.
 */
class PriceVerdictTest {

    private fun verdict(current: Double, usual: Double) =
        PriceVerdict.of(PriceVerdict.differencePercent(current, usual))

    @Test
    fun `a clearly cheap listing is called cheap`() {
        // Divan's Drill, measured live: 1300M listed against a usual 1574M, -17.4%.
        assertEquals(PriceVerdict.CHEAP, verdict(current = 1_300_000_000.0, usual = 1_574_500_000.0))
    }

    @Test
    fun `ordinary drift is not called anything`() {
        // Hyperion at -7.3% is the closest ordinary item to the threshold. Across eleven items
        // the differences jumped from -7.3% straight to -16.3% with nothing in between, so 10%
        // sits in empty space - but only if this stays TYPICAL.
        assertEquals(PriceVerdict.TYPICAL, verdict(current = 482_000_000.0, usual = 520_000_000.0))
        assertEquals(PriceVerdict.TYPICAL, verdict(current = 449_000_000.0, usual = 436_800_000.0))
        assertEquals(PriceVerdict.TYPICAL, verdict(current = 334_000_000.0, usual = 335_300_000.0))
    }

    @Test
    fun `an inflated listing is called dear`() {
        // Aspect of the Dragon was listed at +90% of its usual price when sampled.
        assertEquals(PriceVerdict.DEAR, verdict(current = 1_900_000.0, usual = 1_000_000.0))
    }

    @Test
    fun `the threshold is symmetric`() {
        assertEquals(PriceVerdict.TYPICAL, verdict(current = 91.0, usual = 100.0))
        assertEquals(PriceVerdict.CHEAP, verdict(current = 89.0, usual = 100.0))
        assertEquals(PriceVerdict.TYPICAL, verdict(current = 109.0, usual = 100.0))
        assertEquals(PriceVerdict.DEAR, verdict(current = 111.0, usual = 100.0))
    }

    @Test
    fun `no listing means no verdict, not a neutral one`() {
        // TYPICAL would read as "in line with its usual price", which is a claim about an item
        // nothing is currently known about.
        assertNull(PriceVerdict.of(PriceVerdict.differencePercent(null, 100.0)))
        assertNull(PriceVerdict.of(PriceVerdict.differencePercent(100.0, null)))
    }

    @Test
    fun `a zero price yields no verdict rather than minus one hundred percent`() {
        // Coflnet answers 200 with a zero when nothing is listed. Treated as a price that would
        // be -100% and the panel would announce the bargain of the century.
        assertNull(PriceVerdict.differencePercent(0.0, 100.0))
        assertNull(PriceVerdict.differencePercent(100.0, 0.0))
    }

    @Test
    fun `the difference is measured against the usual price`() {
        // Not against the current one: dividing by the wrong side gives a different number that
        // still looks plausible, which is exactly the kind of error nobody notices.
        assertEquals(-20.0, PriceVerdict.differencePercent(80.0, 100.0)!!, 1e-9)
        assertEquals(25.0, PriceVerdict.differencePercent(125.0, 100.0)!!, 1e-9)
    }
}
