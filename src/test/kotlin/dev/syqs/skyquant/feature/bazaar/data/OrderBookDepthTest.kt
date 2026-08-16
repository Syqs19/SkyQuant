package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How much of the order book counts as tradeable.
 *
 * This decides the size a player commits to a flip, so being wrong in either direction costs them:
 * too small and they leave the trade on the table, too large and they buy into the tail of the
 * book at a price that eats the margin.
 *
 * The shapes below are taken from the live API rather than invented - Hypixel sends up to 30
 * levels a side, ordered outwards from the best price, and the far end holds abandoned orders
 * (Enchanted Diamond's ran down to 1,000 against a market of 1,263).
 */
class OrderBookDepthTest {

    @Test
    fun `sums every level within the slippage bound`() {
        // Measured on ENCHANTED_DIAMOND: the first level held 2,262 units, but 68,429 were
        // available within 0.09% - the figure a flipper can actually use.
        val book = listOf(
            1313.5 to 2262L,
            1313.6 to 584L,
            1313.7 to 370L,
            1314.7 to 63925L,
        )

        assertEquals(67141, BazaarLivePrices.depthWithin(book))
    }

    @Test
    fun `stops at the first level past the bound`() {
        // The tail is abandoned orders, not a market. Counting it would answer "how much exists"
        // rather than "how much can I trade" - and on Enchanted Diamond that tail sits 20% away.
        val book = listOf(
            1000.0 to 500L,
            1005.0 to 300L,
            // 2% out: past the bound, and so is everything after it.
            1020.0 to 90_000L,
            1200.0 to 50_000L,
        )

        assertEquals(800, BazaarLivePrices.depthWithin(book))
    }

    @Test
    fun `a level exactly on the bound still counts`() {
        // The threshold is what a trader accepts paying, so the price they would accept is
        // included. Excluding it would make the figure quietly conservative for no stated reason.
        val book = listOf(100.0 to 10L, 101.0 to 5L)

        assertEquals(15, BazaarLivePrices.depthWithin(book, maxSlippagePercent = 1.0))
    }

    @Test
    fun `an empty book has no depth`() {
        // Rather than throwing: a product can genuinely have one side of its book empty, and the
        // ranking simply passes over it.
        assertEquals(0, BazaarLivePrices.depthWithin(emptyList()))
    }

    @Test
    fun `a single level is its own depth`() {
        assertEquals(4200, BazaarLivePrices.depthWithin(listOf(7.5 to 4200L)))
    }

    @Test
    fun `a nonsensical best price yields nothing rather than dividing by zero`() {
        // A price of zero would make every slippage calculation infinite. Zero depth is the honest
        // answer: there is no price to size a trade against.
        assertEquals(0, BazaarLivePrices.depthWithin(listOf(0.0 to 900L, 1.0 to 900L)))
    }

    @Test
    fun `the bound is applied against the best price, not the previous level`() {
        // Each level drifts 0.6% from the one before it, so a step-by-step comparison would accept
        // the whole list. Measured from the best price, the third is 1.2% out and stops the sum.
        val book = listOf(
            100.0 to 10L,
            100.6 to 10L,
            101.2 to 10L,
        )

        assertEquals(20, BazaarLivePrices.depthWithin(book))
    }

    @Test
    fun `works on a descending side of the book`() {
        // The bid side runs downwards from the best price. The rule uses the absolute distance, so
        // it holds either way round rather than needing to know which side it was handed.
        val book = listOf(
            1379.4 to 68468L,
            1375.0 to 500L,
            // 1.5% below the best: out.
            1358.8 to 99_999L,
        )

        assertEquals(68968, BazaarLivePrices.depthWithin(book))
    }
}
