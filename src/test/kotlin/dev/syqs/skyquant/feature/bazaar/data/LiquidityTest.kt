package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling a price that describes a market from one that merely exists.
 *
 * Every figure below was read off the live bazaar on the day this was written, including the one
 * that caused the bug: the Forge page ranked AMBER_MATERIAL top on a 10.0M ask against a 1.1M
 * best bid, on a market that had sold fifteen units in a week.
 */
class LiquidityTest {

    /** Only the weekly figures matter here; the prices are placeholders the rule never reads. */
    private fun quote(buyWeek: Long, sellWeek: Long) = BazaarLivePrices.Quote(
        productId = "TEST_ITEM",
        buyPrice = 100.0,
        sellPrice = 90.0,
        buyVolume = 0,
        sellVolume = 0,
        buyMovingWeek = buyWeek,
        sellMovingWeek = sellWeek,
    )

    @Test
    fun `the item that caused this reads as thin`() {
        // 134 bought and 15 sold in a week. The ask was real and meant nothing.
        assertTrue(Liquidity.of(quote(134, 15)).isThin)
    }

    @Test
    fun `an ordinary forge output is not flagged`() {
        // Tungsten Plate, 3627 / 1663 - the item actually in the player's forge, and the closest
        // ordinary case to the threshold. Flagging this would make the warning meaningless.
        assertFalse(Liquidity.of(quote(3627, 1663)).isThin)
    }

    @Test
    fun `a staple is nowhere near the threshold`() {
        assertFalse(Liquidity.of(quote(5_811_736, 23_763_285)).isThin)
    }

    @Test
    fun `the smaller of the two weekly figures decides`() {
        // Refined Umber: bought 80 times, sold 6. Judging on the buy figure alone would pass an
        // item that cannot actually be sold, which is the direction that matters when holding one.
        assertTrue(Liquidity.of(quote(80, 6)).isThin)
        assertTrue(Liquidity.of(quote(50_000, 6)).isThin, "high buy volume must not excuse it")
    }

    @Test
    fun `a wide spread on a busy market is not thin`() {
        // Rough Amber Gem: 360% spread on 79,869 / 182 million weekly. A wide book on a very
        // liquid market is a different thing from an untraded one, and flagging it would train
        // the player to ignore the flag.
        assertFalse(Liquidity.of(quote(79_869, 182_896_293)).isThin)
    }

    @Test
    fun `an unknown price is not accused of being thin`() {
        // Prices arrive a moment after the screen opens. Every row wearing a warning for that
        // moment would teach the player that the warning means nothing.
        assertFalse(Liquidity.of(null).isThin)
    }

    @Test
    fun `an ask far above its weekly average reads as a spike`() {
        // Amber Material asked 10.0M against a 2.89M weekly average - 3.5 times.
        assertTrue(Liquidity.isSpike(ask = 10_000_000.0, weeklyAverageAsk = 2_885_460.0))
    }

    @Test
    fun `an ask in line with its week is not a spike`() {
        // Tungsten Plate: 10.79M against an 11.04M average, slightly below its own week.
        assertFalse(Liquidity.isSpike(ask = 10_790_000.0, weeklyAverageAsk = 11_043_261.0))
    }

    @Test
    fun `no history means no spike claim`() {
        // Absent history must not read as "unusual"; it reads as "not known", and the volume
        // check is what still applies.
        assertFalse(Liquidity.isSpike(ask = 10_000_000.0, weeklyAverageAsk = null))
        assertFalse(Liquidity.isSpike(ask = 10_000_000.0, weeklyAverageAsk = 0.0))
    }
}
