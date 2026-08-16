package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Flip ranking decides what the terminal recommends looking at, so a filter quietly
 * breaking would send the player at items they can't actually trade out of.
 *
 * Priced from the order book rather than from the summary figures, after the summary was found
 * to report prices nobody was offering - see the stale-order test below.
 */
class BazaarMarketSummaryTest {

    private fun quote(
        id: String,
        ask: Double,
        bid: Double,
        askDepth: Long = 10_000,
        bidDepth: Long = 10_000,
        weekly: Long = 1_000_000,
        summaryBuy: Double = ask,
        summarySell: Double = bid,
    ) = BazaarLivePrices.Quote(
        productId = id,
        buyPrice = summaryBuy,
        sellPrice = summarySell,
        buyVolume = 0,
        sellVolume = 0,
        buyMovingWeek = weekly,
        sellMovingWeek = weekly,
        topBid = bid,
        topBidAmount = bidDepth,
        topAsk = ask,
        topAskAmount = askDepth,
    )

    @Test
    fun `ranks by margin, widest first`() {
        val result = BazaarMarketSummary.bestFlips(
            quotes = listOf(
                quote("NARROW", ask = 100.0, bid = 104.0),
                quote("WIDE", ask = 100.0, bid = 160.0),
                quote("MIDDLE", ask = 100.0, bid = 120.0),
            ),
            taxRate = { 0.0 },
        )

        assertEquals(listOf("WIDE", "MIDDLE", "NARROW"), result.map { it.productId })
    }

    @Test
    fun `prices from the book, not from the summary`() {
        // SHARD_DRYBARK as it stood: the summary claimed a sell price of 22.7 while the
        // cheapest seller in the book was asking 7002. Taken from the summary it was the best
        // flip on the bazaar by a factor of ten, and entirely fictional.
        val flip = BazaarMarketSummary.bestFlips(
            quotes = listOf(
                quote(
                    "SHARD_DRYBARK",
                    ask = 7002.0,
                    bid = 12523.0,
                    summarySell = 22.7,
                    summaryBuy = 12523.0,
                ),
            ),
            taxRate = { 0.0 },
        ).single()

        assertEquals(7002.0, flip.buyAt, 1e-9)
        assertEquals(12523.0 - 7002.0, flip.profitPerUnit, 1e-9)
    }

    @Test
    fun `subtracts the tax from the sale`() {
        val flip = BazaarMarketSummary.bestFlips(
            quotes = listOf(quote("ITEM", ask = 100.0, bid = 200.0)),
            taxRate = { 0.0125 },
        ).single()

        assertEquals(200.0 * 0.9875 - 100.0, flip.profitPerUnit, 1e-9)
    }

    @Test
    fun `drops flips the tax turns into a loss`() {
        // Six items on the live bazaar were profitable gross and losing net. Showing them would
        // send the player into a trade that costs coins.
        val result = BazaarMarketSummary.bestFlips(
            quotes = listOf(quote("THIN", ask = 100.0, bid = 100.5)),
            taxRate = { 0.0125 },
        )

        assertTrue(result.isEmpty(), "a trade that loses money after tax was offered as a flip")
    }

    @Test
    fun `drops items nobody trades`() {
        // A wide margin on a dead item exists precisely because the order would never fill.
        val result = BazaarMarketSummary.bestFlips(
            quotes = listOf(
                quote("DEAD", ask = 60.0, bid = 100.0, weekly = 12),
                quote("LIQUID", ask = 95.0, bid = 100.0, weekly = 5_000_000),
            ),
            taxRate = { 0.0 },
        )

        assertEquals(listOf("LIQUID"), result.map { it.productId })
    }

    @Test
    fun `ignores products with an empty book`() {
        // Nothing quoted on one side means there is no price to trade against.
        val result = BazaarMarketSummary.bestFlips(
            quotes = listOf(quote("EMPTY", ask = 0.0, bid = 100.0)),
            taxRate = { 0.0 },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `reports the depth of both sides`() {
        val flip = BazaarMarketSummary.bestFlips(
            quotes = listOf(quote("ITEM", ask = 10.0, bid = 20.0, askDepth = 500, bidDepth = 90)),
            taxRate = { 0.0 },
        ).single()

        assertEquals(500, flip.buyDepth)
        assertEquals(90, flip.sellDepth)
    }

    @Test
    fun `respects the limit`() {
        val many = (1..20).map { quote("ITEM_$it", ask = 100.0, bid = 120.0) }

        assertEquals(3, BazaarMarketSummary.bestFlips(limit = 3, quotes = many, taxRate = { 0.0 }).size)
    }

    @Test
    fun `most traded ranks by the weaker side of the week`() {
        // Liquidity is the weak side: a flip needs to buy *and* sell, so an item with heavy
        // buying and no selling is not liquid.
        val lopsided = quote("LOPSIDED", ask = 90.0, bid = 100.0).copy(
            buyMovingWeek = 10_000_000,
            sellMovingWeek = 5,
        )
        val even = quote("EVEN", ask = 90.0, bid = 100.0, weekly = 1_000)

        val result = BazaarMarketSummary.mostTraded(quotes = listOf(lopsided, even))

        assertEquals(listOf("EVEN", "LOPSIDED"), result.map { it.productId })
    }

    @Test
    fun `returns nothing when prices have not arrived yet`() {
        assertTrue(BazaarMarketSummary.bestFlips(quotes = emptyList(), taxRate = { 0.0 }).isEmpty())
    }
}
