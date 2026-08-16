package dev.syqs.skyquant.feature.bazaar.data

/**
 * Whether the price being asked right now is cheap, dear, or ordinary.
 *
 * This exists because the auction screen had stopped answering the question people open it for.
 * A bazaar product arrives with the answer built in - two prices, and the gap between them is the
 * margin - so the chart only has to say whether now is a good moment. An auction item has no such
 * pair, and the screen had grown to four prices (cheapest listing, window average, top, low)
 * without any of them saying *whether to buy*. More figures, less opinion.
 *
 * One comparison recovers it: what one costs now against what one usually costs. Everything else
 * on the panel is context for that sentence.
 */
enum class PriceVerdict {
    /** Well below its usual price. */
    CHEAP,

    /** Around its usual price - the ordinary case, and deliberately unremarkable. */
    TYPICAL,

    /** Well above its usual price. */
    DEAR,
    ;

    companion object {
        /**
         * How far from the usual price counts as notable, either way.
         *
         * Measured rather than picked: across eleven auction items the differences fell at
         * -18.0, -17.4, -16.3, -7.3, -1.1, -0.4, 0.0, +1.1, +2.8, +9.1 and +90.0 percent. There
         * is a clean gap between -7.3 and -16.3 with nothing in it, so a threshold of 10 lands
         * in empty space and separates the three genuinely cheap items from the ordinary ones
         * without splitting any cluster. At 5% it would have called Hyperion cheap at -7.3%,
         * which is within its normal day-to-day drift.
         */
        const val NOTABLE_PERCENT = 10.0

        /**
         * [current] against [usual], as a percentage difference, or null when either is missing.
         *
         * Null rather than zero for an unpriced item: zero would read as "in line with its usual
         * price", which is a statement about an item nothing is known about.
         */
        fun differencePercent(current: Double?, usual: Double?): Double? {
            if (current == null || usual == null) return null
            if (current <= 0 || usual <= 0) return null

            return (current - usual) / usual * 100
        }

        fun of(differencePercent: Double?): PriceVerdict? = when {
            differencePercent == null -> null
            differencePercent < -NOTABLE_PERCENT -> CHEAP
            differencePercent > NOTABLE_PERCENT -> DEAR
            else -> TYPICAL
        }
    }
}
