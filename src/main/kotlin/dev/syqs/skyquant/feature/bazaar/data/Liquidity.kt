package dev.syqs.skyquant.feature.bazaar.data

/**
 * Whether a bazaar price is one anybody is actually trading at.
 *
 * Exists because the Forge page recommended AMBER_MATERIAL as its best row: it quoted a 10.0M
 * ask against a 1.1M highest bid, on a market that had sold **15 units in a week**. Every figure
 * was read correctly - the price was simply meaningless, and because the page ranks on profit,
 * the most meaningless price led the list. A screen that ranks by a number has to know when that
 * number is not a market.
 *
 * How the other tools handle this was checked before choosing. Coflnet compares the live price
 * against its own long history and flags what sits far outside the usual range, hiding it only
 * behind an opt-in setting; their guides warn that a wide spread with no trades is "dead, not
 * valuable". That shape is followed here: **mark it, rank it down, never silently drop it** - a
 * warning the player can overrule, since a genuine demand spike looks the same from the outside.
 */
enum class Liquidity {
    /** Trades often enough that the quoted price means something. */
    NORMAL,

    /**
     * Barely trades. The quoted price is what somebody is asking, not what anyone is paying.
     *
     * Measured against the live bazaar rather than picked: at fewer than 1000 sales a week, 44%
     * of the bazaar's 2124 products qualify, and every item involved in this bug falls inside it
     * - AMBER_MATERIAL at 15 a week and REFINED_AMBER at 6, against TUNGSTEN_PLATE at 1663 and
     * ENCHANTED_DIAMOND at 23.7 million.
     */
    THIN,
    ;

    val isThin: Boolean get() = this == THIN

    companion object {

        /**
         * Weekly sales below which a price stops describing a market.
         *
         * The volume figure is the one that decides, deliberately, and spread alone is not used:
         * ROUGH_AMBER_GEM shows a 360% spread on 182 million weekly sales - a wide book on a very
         * liquid market, which is not the same fault at all and must not be flagged as one.
         */
        const val THIN_WEEKLY_SALES = 1000L

        /**
         * How far above its weekly average an ask can sit before it reads as a spike.
         *
         * AMBER_MATERIAL's ask was 10.0M against a 2.89M weekly average - 3.5x - while
         * TUNGSTEN_PLATE sat at 10.79M against 11.04M. Two and a half times leaves the ordinary
         * item alone with room to spare and still catches the case this was built for.
         */
        const val SPIKE_MULTIPLE = 2.5

        /**
         * From the live snapshot alone: the figure every screen already has.
         *
         * Uses the existing `weeklyVolume`, which is the **smaller** of the two weekly figures.
         * That is the stricter reading and the right one here: an item bought 134 times and sold
         * 15 times in a week is not liquid in the direction that matters to somebody holding one.
         *
         * An unknown quote is NORMAL rather than THIN - the flag has to mean "measured and found
         * thin", or every item would wear it for the first minute of a session while prices load.
         */
        fun of(quote: BazaarLivePrices.Quote?): Liquidity {
            val weekly = quote?.weeklyVolume ?: return NORMAL
            return if (weekly < THIN_WEEKLY_SALES) THIN else NORMAL
        }

        /**
         * True when the ask is far above what the item has been asking all week.
         *
         * Separate from [of] because it needs history the live snapshot doesn't carry, and
         * because it is a different claim: [of] says nobody trades this, while this says the
         * price moved somewhere it doesn't usually sit. Either is reason enough to distrust a
         * profit computed from it.
         */
        fun isSpike(ask: Double, weeklyAverageAsk: Double?): Boolean {
            val average = weeklyAverageAsk?.takeIf { it > 0 } ?: return false
            return ask > average * SPIKE_MULTIPLE
        }
    }
}
