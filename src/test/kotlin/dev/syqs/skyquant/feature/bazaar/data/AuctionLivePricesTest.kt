package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.HttpJson
import java.util.concurrent.CompletionException
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

    /**
     * Which failures may be cached as "this item has nothing".
     *
     * The distinction is invisible in the game - a wrongly cached refusal looks exactly like an
     * item that genuinely never sells - and the cost falls on pinned rows, i.e. the only items
     * this class ever fetches and the ones the player deliberately chose to watch.
     */
    @Test
    fun `a genuine failure is an answer about the item`() {
        // No listings, a 404, a malformed body: these really do say the item has nothing to show,
        // and remembering that is what stops it being asked about every ten minutes forever.
        assertTrue(AuctionLivePrices.answersTheItem(IllegalStateException("HTTP 404")))
    }

    @Test
    fun `a rate limit is not an answer about the item`() {
        // The server refusing our request rate says nothing about this item.
        assertTrue(
            AuctionLivePrices.answersTheItem(HttpJson.RateLimited(retryAfterMillis = 5_000)).not(),
        )
    }

    @Test
    fun `our own pacing is not an answer about the item`() {
        // The bug this pins down. Deferred means the request was never sent, so caching it marks a
        // tradeable item "no auction data" for the full thirty-minute miss backoff. It reads as an
        // ordinary failure - rateLimit() returns null for it - which is exactly why it slipped
        // through the 429 check and into the generic branch.
        assertTrue(AuctionLivePrices.answersTheItem(CoflnetRateLimit.Deferred()).not())
    }

    @Test
    fun `a refusal still counts as one when the future wrapped it`() {
        // CompletableFuture hands callers a CompletionException wrapping the real cause, so a
        // check testing only the top-level type catches one shape and silently misses the other -
        // which is the whole reason the rateLimit() and deferred() helpers exist.
        assertTrue(
            AuctionLivePrices.answersTheItem(
                CompletionException(CoflnetRateLimit.Deferred()),
            ).not(),
        )
        assertTrue(
            AuctionLivePrices.answersTheItem(
                CompletionException(HttpJson.RateLimited(retryAfterMillis = null)),
            ).not(),
        )
    }
}
