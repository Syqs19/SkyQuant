package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The figure a pinned auction item shows on the HUD.
 *
 * Worth testing away from the network because it is the number a player acts on, and because the
 * failure it replaces was silent: the row simply read "…" forever, which looks like loading.
 */
class AuctionLivePricesTest {

    private fun point(avg: Double, volume: Long, hour: Int, min: Double = avg, max: Double = avg) =
        AuctionHistory.Point(
            timestamp = hour * 3_600_000L,
            low = min,
            high = max,
            average = avg,
            volume = volume,
        )

    @Test
    fun `the price shown is the most recent one, not the average`() {
        // A player reads this to decide whether to buy or sell now. An average of the whole day
        // would be a number nobody can trade at.
        val quote = AuctionLivePrices.summarize(
            "TITANIUM_DRILL_4",
            listOf(
                point(avg = 395_000_000.0, volume = 2, hour = 1),
                point(avg = 620_000_000.0, volume = 1, hour = 2),
            ),
        )!!

        assertEquals(620_000_000.0, quote.price, 1e-9)
        assertEquals("TITANIUM_DRILL_4", quote.itemId)
    }

    @Test
    fun `the change spans the charted day`() {
        // Same window the graph screen reports for the same item: two different percentages
        // under the same heading in two places would be worse than either alone.
        val quote = AuctionLivePrices.summarize(
            "HYPERION",
            listOf(
                point(avg = 400_000_000.0, volume = 3, hour = 1),
                point(avg = 600_000_000.0, volume = 3, hour = 2),
            ),
        )!!

        assertEquals(50.0, quote.changePercent, 1e-9)
        assertEquals(6, quote.soldToday)
    }

    @Test
    fun `an item with no auction sales gives no quote`() {
        // Rather than a quote of zero, which would be drawn as a real price of zero coins.
        assertNull(AuctionLivePrices.summarize("ROOKIE_PICKAXE", emptyList()))
    }

    @Test
    fun `a single recorded sale is still a usable price`() {
        // The slowest items sell a handful of times a day. One sale is thin, but it is the only
        // evidence there is, and it beats showing nothing.
        val quote = AuctionLivePrices.summarize("DIVAN_DRILL", listOf(point(avg = 950_000_000.0, volume = 1, hour = 5)))!!

        assertEquals(950_000_000.0, quote.price, 1e-9)
        assertEquals(0.0, quote.changePercent, 1e-9)
        assertEquals(1, quote.soldToday)
    }

    @Test
    fun `a quiet final hour does not report a price of zero`() {
        // Coflnet emits rows for hours with no sales. The last such row still carries its stale
        // price, which is what should be shown - not the zero volume beside it.
        val quote = AuctionLivePrices.summarize(
            "NECRON_HANDLE",
            listOf(
                point(avg = 800_000_000.0, volume = 4, hour = 1),
                point(avg = 800_000_000.0, volume = 0, hour = 2),
            ),
        )!!

        assertTrue(quote.price > 0.0, "a quiet hour must not zero the price")
        assertEquals(4, quote.soldToday)
    }
}
