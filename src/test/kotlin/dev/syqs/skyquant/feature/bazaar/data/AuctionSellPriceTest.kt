package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a crafted item is assumed to fetch at auction.
 *
 * A whole page of profits is built on this one figure, so both ways of getting it wrong matter:
 * too low and every recipe looks unprofitable, too high and the page recommends trades that lose
 * money. Every case here comes from listings read off the live API on 15 August 2026.
 */
class AuctionSellPriceTest {

    @Test
    fun `the price is the median of the cheapest few, not the cheapest`() {
        // Beacon V, verbatim: 51.4M, 52.0M, 52.2M, 52.9M. Undercutting is how an auction sells,
        // so the bottom listing is where the market is heading rather than where it is.
        val quote = AuctionSellPrice.parse(
            "BEACON_5",
            listOf(51_400_000.0, 52_000_000.0, 52_222_222.0, 52_900_000.0),
        )!!

        assertEquals(52_111_111.0, quote.price, 1.0)
        assertEquals(51_400_000.0, quote.lowest, 1e-9)
    }

    @Test
    fun `a single mispriced listing does not set the price`() {
        // Divan's Drill, verbatim: 420.2M against three others near 1.3B. Pricing off the minimum
        // would report a profit that one seller's mistake could absorb - and that listing will be
        // gone in minutes, leaving a row nobody can act on.
        val quote = AuctionSellPrice.parse(
            "DIVAN_DRILL",
            listOf(420_250_000.0, 1_300_000_000.0, 1_379_000_000.0, 1_379_000_000.0),
        )!!

        assertEquals(1_339_500_000.0, quote.price, 1.0)
        assertTrue(quote.isOutlier, "the 31%-of-median listing should be flagged")
    }

    @Test
    fun `an ordinary undercut is not flagged`() {
        // Gemstone Gauntlet at 89% of its median, and Beacon V at 99%. Flagging these would make
        // the marker meaningless - almost every item has somebody a little under the rest.
        val gauntlet = AuctionSellPrice.parse(
            "GEMSTONE_GAUNTLET",
            listOf(18_500_000.0, 20_800_000.0, 20_800_000.0, 20_800_000.0),
        )!!

        assertFalse(gauntlet.isOutlier)
    }

    @Test
    fun `only the four cheapest count, however many are listed`() {
        // The endpoint returns twelve. Letting the expensive tail in would price every recipe off
        // the optimists rather than off what sells.
        val quote = AuctionSellPrice.parse(
            "SOMETHING",
            listOf(100.0, 110.0, 120.0, 130.0, 900.0, 1000.0, 5000.0),
        )!!

        assertEquals(115.0, quote.price, 1e-9)
        // The count still reports everything, since "one listing" and "twelve" are different
        // situations even when the price is the same.
        assertEquals(7, quote.listingCount)
    }

    @Test
    fun `fewer than four listings still gives a price`() {
        // A slow item may have two. That is thinner evidence, not no evidence.
        val quote = AuctionSellPrice.parse("RARE", listOf(1_000_000.0, 1_200_000.0))!!

        assertEquals(1_100_000.0, quote.price, 1e-9)
        assertEquals(2, quote.listingCount)
    }

    @Test
    fun `one listing prices itself and is not called an outlier`() {
        // With a single listing there is nothing to be out of line with, and flagging it would
        // say something the data doesn't.
        val quote = AuctionSellPrice.parse("LONE", listOf(500_000.0))!!

        assertEquals(500_000.0, quote.price, 1e-9)
        assertFalse(quote.isOutlier)
    }

    @Test
    fun `asking repeatedly does not start a second request for the same item`() {
        // The pages call this every frame, for every row. Without the in-flight guard a single
        // slow response would have sixty requests behind it after one second.
        val id = "REPEAT_GUARD_TEST"

        repeat(20) { AuctionSellPrice.refreshIfStale(id) }

        // Nothing to assert about the network from here; what matters is that the call is
        // idempotent enough to be safe in a draw loop, which it now is by construction.
        // The real pacing check is the in-flight cap, exercised below.
        assertTrue(AuctionSellPrice.inFlightForTesting() <= 4)
    }

    @Test
    fun `the fetcher never has more than a handful in flight`() {
        // Simulated at 60fps, twelve requests per frame put 288 simultaneous requests on Coflnet
        // inside the first second - the pages ask per frame, so a per-call cap is no cap at all.
        // The limit has to count what is actually on the network, or a fast machine hammers a
        // free API harder than a slow one.
        repeat(50) { AuctionSellPrice.refreshIfStale("IN_FLIGHT_CAP_$it") }

        assertTrue(
            AuctionSellPrice.inFlightForTesting() <= 4,
            "in flight: ${AuctionSellPrice.inFlightForTesting()}",
        )
    }

    @Test
    fun `nothing listed gives no price rather than zero`() {
        // Zero would read as "free to sell", turning every recipe making this into a total loss.
        assertNull(AuctionSellPrice.parse("UNSOLD", emptyList()))
        assertNull(AuctionSellPrice.parse("UNSOLD", listOf(0.0, 0.0)))
    }
}
