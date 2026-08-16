package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cheapest live listing, and whether it can be trusted as the going rate.
 *
 * This is the only figure on the graph screen a player can act on directly, so both of its
 * failure modes matter: reporting a price when nothing is listed, and reporting one underpriced
 * listing as if it were the market.
 */
class AuctionBinTest {

    @Test
    fun `no listings gives no quote rather than a price of zero`() {
        // Coflnet answers 200 with a zero here rather than 404 - Rookie Pickaxe has no auctions
        // and never will. Taken at face value that puts "0" on screen as a real price.
        assertNull(AuctionBin.parse("ROOKIE_PICKAXE", lowest = 0, secondLowest = 0))
    }

    @Test
    fun `a normal item is not flagged`() {
        // Measured: nine of twelve sampled items sit within 1% of their second cheapest listing.
        val hyperion = AuctionBin.parse("HYPERION", lowest = 483_000_000, secondLowest = 488_000_000)!!

        assertEquals(1.04, hyperion.gapPercent, 0.05)
        assertFalse(hyperion.isLone, "an ordinary 1% gap must not be flagged")
    }

    @Test
    fun `a lone underpriced listing is flagged`() {
        // Daedalus Axe, measured live: 10.5M against a second cheapest of 13.5M. Buying it is a
        // one-off; treating 10.5M as the market price would misprice every decision built on it.
        val axe = AuctionBin.parse("DAEDALUS_AXE", lowest = 10_500_000, secondLowest = 13_500_000)!!

        assertEquals(28.57, axe.gapPercent, 0.05)
        assertTrue(axe.isLone)
    }

    @Test
    fun `the widest ordinary gap stays under the threshold`() {
        // Livid Dagger at 11.2% is the widest non-outlier in the sample. The threshold has to sit
        // above it and below Daedalus Axe's 28.6%, which is what 15% does.
        val livid = AuctionBin.parse("LIVID_DAGGER", lowest = 6_000_000, secondLowest = 6_670_000)!!

        assertTrue(livid.gapPercent < AuctionBin.OUTLIER_GAP_PERCENT)
        assertFalse(livid.isLone)
    }

    @Test
    fun `a sole listing is not reported as well supported`() {
        // With one listing Coflnet sends no second price. Copying the first into it would make
        // the gap zero, which reads as "several sellers agree" - the opposite of the truth.
        val only = AuctionBin.parse("DIVAN_DRILL", lowest = 1_300_000_000, secondLowest = 0)!!

        assertEquals(0.0, only.secondLowest, 1e-9)
        assertFalse(only.isLone, "isLone describes a gap, and there is no second price to gap from")
    }

    @Test
    fun `a sole listing is flagged as one, since isLone cannot see it`() {
        // The bug this guards, found by reading the screen against this class: the graph's warning
        // marker keyed off isLone, which is false here by design - so the single riskiest case, a
        // 1.3B price no other seller is matching, was the one case that drew no warning at all.
        val only = AuctionBin.parse("DIVAN_DRILL", lowest = 1_300_000_000, secondLowest = 0)!!

        assertTrue(only.isSoleListing)
        assertFalse(only.isLone, "the two flags describe different situations")
    }

    @Test
    fun `an item with a second listing is not a sole listing`() {
        // Including the underpriced case: Daedalus Axe has something to compare against, which is
        // precisely why its cheap price is readable as cheap rather than as unknown.
        val axe = AuctionBin.parse("DAEDALUS_AXE", lowest = 10_500_000, secondLowest = 13_500_000)!!
        val hyperion = AuctionBin.parse("HYPERION", lowest = 483_000_000, secondLowest = 488_000_000)!!

        assertFalse(axe.isSoleListing)
        assertFalse(hyperion.isSoleListing)
    }

    @Test
    fun `the price survives the round trip`() {
        val drill = AuctionBin.parse("TITANIUM_DRILL_4", lowest = 333_999_998, secondLowest = 333_999_999)!!

        assertEquals(333_999_998.0, drill.lowest, 1e-9)
        assertEquals("TITANIUM_DRILL_4", drill.itemId)
    }
}
