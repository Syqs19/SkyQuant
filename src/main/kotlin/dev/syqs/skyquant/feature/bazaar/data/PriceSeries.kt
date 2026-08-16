package dev.syqs.skyquant.feature.bazaar.data

/**
 * A price history to chart, whichever market it came from.
 *
 * The two markets report genuinely different things - the bazaar has two live sides of an order
 * book, the auction house has what completed sales went for - but both reduce to the same
 * drawable shape: an upper line, a lower line, something in between, and a traded quantity.
 * Unifying them here keeps that mapping in one place instead of spreading `if (auction)` through
 * every part of the chart.
 *
 * What the lines *mean* still differs, so [kind] travels with the series and the screen labels
 * accordingly. Presenting an auction spread as if it were a bazaar spread would invite a
 * comparison that doesn't hold: one is the cost of trading now, the other is how widely the item
 * has been selling.
 */
data class PriceSeries(
    val kind: Kind,
    val points: List<Point>,
) {

    enum class Kind {
        /** Live order book: [Point.high] is the instant-buy price, [Point.low] the instant-sell. */
        BAZAAR,

        /** Completed sales in an hour: [Point.high] and [Point.low] bracket what people paid. */
        AUCTION,

        /**
         * A day of bazaar prices, from the item-price endpoint rather than the bazaar one.
         *
         * Its own kind rather than either of the others, because it is genuinely a third thing:
         * the market is the bazaar, so every unit is identical and there is no "base example" to
         * find - but the shape of the data is the item-price endpoint's min/max/avg, so there is
         * no buy and sell side to draw either. Filing it under AUCTION would apply the outlier
         * floor and the base-price line to a market where neither means anything; filing it
         * under BAZAAR would have the chart look for buy and sell prices that aren't there.
         */
        BAZAAR_DAILY,
    }

    /**
     * One moment. [middle] is the figure a single number would be - the midpoint of a bazaar
     * spread, or the average of an hour's sales.
     */
    data class Point(
        val timestamp: Long,
        val low: Double,
        val high: Double,
        val middle: Double,
        val volume: Long,
    )

    /**
     * Prices below this share of the window's median are treated as accidents rather than sales.
     *
     * Auction items are not fungible the way bazaar goods are: a Titanium Drill with good
     * reforges and enchantments is a different product from a bare one, and only the bare one is
     * what "the price of a Titanium Drill" means. The cheapest sale each hour is the closest
     * thing to that bare item, which is why the chart line follows it.
     *
     * The catch, found by measuring rather than by reasoning: a week of Titanium Drill contains
     * one hour whose minimum is 1.0M against a median of 336M, and Daedalus Axe has one at 0.04M
     * against 12.8M. Mispriced listings, or lots. Following the raw minimum would let one of
     * those flatten the whole chart for that hour.
     *
     * Half the median is deliberately generous. Checked across five items and 619 traded hours:
     * it removes exactly those two absurdities and nothing else - Hyperion, Divan's Drill and
     * Necron's Handle lose no hours at all, even though Hyperion's genuine spread runs to 2.29x.
     * A tighter threshold would start deleting real cheap sales, which are the very thing the
     * line is meant to find.
     */
    val basePrices: List<Double> by lazy {
        if (!hasVariants || points.isEmpty()) return@lazy points.map { it.low }

        // From hours that actually traded. Including the quiet ones let their standing prices -
        // which can sit far above anything paid - pull the median up, and with it the floor
        // derived from it, until genuine cheap hours fell below it and were "corrected" away.
        // The guard against outliers was quietly creating them.
        val traded = points.filter { it.volume > 0 }.map { it.low }.ifEmpty { points.map { it.low } }

        val median = median(traded)
        val floor = median * OUTLIER_FLOOR_RATIO

        // A rejected hour takes the median in place of its own minimum rather than being dropped
        // from the series: removing the point would shift every later point leftwards along the
        // time axis, so a mispriced listing would visibly distort *when* everything happened.
        points.map { if (it.low >= floor) it.low else median }
    }

    /**
     * The band drawn behind the line: from the base price up to the hour's average.
     *
     * Deliberately not up to the maximum. The top of an hour's range is the most enchanted,
     * best-reforged example that happened to sell, and it moves around far more than anything
     * else - measured across a week of Titanium Drill, the maximum varies by 26.5% against the
     * minimum's 19.2%. Drawing to it made the band mostly a picture of how lucky the best-kitted
     * seller got that hour. The average is where the bulk of sales actually sit, so the band now
     * shows the range a normal example trades in, and the maximum is reported as a figure in the
     * side panel where it can be read without dominating the picture.
     */
    fun bandTop(index: Int): Double {
        val point = points[index]
        // Without variants there is no premium to exclude - a month of bazaar prices has a
        // genuine daily high, and every unit that traded at it was the same item - so the band
        // runs the full range.
        val top = if (hasVariants) point.middle else point.high

        return maxOf(top, bandBottom(index))
    }

    /**
     * The bottom of the band, which is also where the line runs.
     *
     * These differ by market for the same reason [bandTop] does. At auction the line follows the
     * cheapest example, because that is the one figure that means "an unmodified one of these".
     * On a month of bazaar prices every unit is identical, so the representative figure is the
     * day's average and the band simply shows how far the price swung around it - putting the
     * line on the daily minimum there would report the single cheapest moment of each day as if
     * it were the price.
     */
    fun bandBottom(index: Int): Double =
        if (hasVariants) basePrices[index] else minOf(points[index].low, points[index].middle)

    /** The figure the chart's line follows, whichever market this is. */
    fun linePrice(index: Int): Double =
        if (hasVariants) basePrices[index] else points[index].middle

    /**
     * The value range the chart's vertical axis should cover, which is not the same as the range
     * of the data.
     *
     * An hour with a single sale reports that one item as its minimum, its average and its
     * maximum alike. If that item happened to be a well-enchanted example, the whole hour reads
     * as a price spike that never existed - and because the axis is scaled to fit everything, one
     * such hour flattens the rest of the chart into a narrow strip.
     *
     * Measured on a day of Titanium Drill: four of twenty-two hours had a single sale, one of
     * them at 620M against a typical 335M, and excluding them from the scale takes the axis from
     * 301M tall to 134M - the same data, drawn more than twice as legibly. Daedalus Axe improves
     * by 61%.
     *
     * Two things this deliberately is not. It does not *drop* those hours: they are still drawn,
     * clipped at the plot edge if need be, because they are real sales and hiding them would
     * misreport how the item traded. And it is not a fixed ceiling on how far a price may sit
     * above the median, which was tried first and rejected on the evidence - a 1.5x cap clipped
     * 21 of Hyperion's 23 hours, where that spread is genuine and worth seeing. Thin hours are
     * the thing that misleads; wide ones are information.
     */
    fun scaleBounds(): ClosedFloatingPointRange<Double>? {
        if (points.isEmpty()) return null

        val low = points.indices.minOf { bandBottom(it) }

        // Hours backed by more than one sale. Fewer than two of them is not enough to scale by:
        // with one such hour the maximum is that single point while the minimum still looks at
        // every hour, so the two ends can meet and the axis collapses to zero height.
        //
        // Measured on Divan's Drill, which sold four times in a day with only the 10:00 hour
        // clearing more than once: both ends landed on 1355M, the plot had no height, and the
        // chart drew no curve while printing the same price on all four axis labels. The earlier
        // guard only caught the case where *no* hour was busy, which is the milder one.
        val representative = points.indices.filter { points[it].volume > 1 }
            .takeIf { it.size >= 2 } ?: points.indices.toList()

        // The top follows whatever is drawn. With the auction band gone that is just the line,
        // so scaling to the band's old top would leave the curve sitting in the lower half of an
        // empty plot - the chart would look emptier than before rather than clearer.
        val high = if (hasVariants) {
            representative.maxOf { linePrice(it) }
        } else {
            representative.maxOf { bandTop(it) }
        }

        // A window can still be genuinely flat - one hour, or several at an identical price - and
        // a zero-height axis draws nothing at all. Give it a little room so the line has
        // somewhere to sit; 1% keeps a real flat stretch looking flat rather than inventing a
        // swing that never happened.
        if (high - low < low * FLAT_SCALE_EPSILON) {
            val padding = if (low > 0) low * FLAT_SCALE_EPSILON else 1.0
            return (low - padding)..(high + padding)
        }

        return low..maxOf(high, low)
    }

    val isEmpty: Boolean get() = points.size < 2

    /**
     * Whether the two sides of an order book are available, i.e. whether there is a spread.
     *
     * The question nearly every drawing decision actually wants answered - asked directly rather
     * than by testing `kind == BAZAAR`, which quietly gave the wrong answer for [Kind.BAZAAR_DAILY]:
     * that is a bazaar market whose *data* has no sides, so the chart would have drawn two curves
     * from a single one and labelled a nonexistent spread.
     */
    val hasOrderBook: Boolean get() = kind == Kind.BAZAAR

    /**
     * Whether individual items differ enough that the cheapest one is worth singling out.
     *
     * True only at auction. Bazaar goods are fungible - one Enchanted Diamond is every other
     * Enchanted Diamond - so there is no "unmodified example" to look for, and applying the
     * base-price line to them would dress a plain minimum up as something it isn't.
     */
    val hasVariants: Boolean get() = kind == Kind.AUCTION

    /**
     * What the whole charted window looks like, as opposed to its final point.
     *
     * This exists because reading only the last point was actively misleading at auction. An
     * hour in which one Titanium Drill sold reports that sale as its minimum, its maximum and
     * its average alike - three panel rows all showing 620M, which reads as a broken display
     * rather than as "one sale". The window as a whole always has a real spread to report.
     *
     * [average] is weighted by [Point.volume] rather than a plain mean of the hourly averages:
     * an hour with forty sales says more about the going rate than an hour with one, and
     * treating them equally lets a single outlier sale drag the figure around. Weighting also
     * disposes of the quiet hours for free - Coflnet emits a row for them carrying a stale
     * price, and a weight of zero is exactly the influence such a row deserves.
     */
    data class Summary(
        val low: Double,
        val high: Double,
        val average: Double,
        val latest: Double,
        val totalVolume: Long,
        /** Change from the first point to the last, as a percentage. */
        val changePercent: Double,
        /**
         * The cheapest example the window saw, ignoring mispriced listings - what an unmodified
         * item costs. Equal to [low] on the bazaar, where every unit is identical anyway.
         */
        val base: Double,
        /** The most recent base price, which is the figure the chart line ends on. */
        val latestBase: Double,
        /**
         * What one of these normally costs: the median of the window's base prices.
         *
         * The median, not [base], which is the single cheapest hour the window happened to
         * contain and therefore drifts downwards the longer you look. Comparing a live price
         * against the cheapest moment of a week would call almost everything expensive.
         */
        val usual: Double,
    )

    fun summarize(): Summary? {
        if (points.isEmpty()) return null

        // Weighting alone already excludes hours with no sales - they contribute a weight of
        // zero - so there is no filtering step here. The only case it can't handle is a window
        // where *nothing* sold, where the total weight is zero and the division would produce
        // NaN; that falls back to a plain mean, which at least reports the standing prices.
        val totalWeight = points.sumOf { it.volume }.takeIf { it > 0 }
        val average = if (totalWeight != null) {
            points.sumOf { it.middle * it.volume } / totalWeight
        } else {
            points.sumOf { it.middle } / points.size
        }

        val first = points.first().middle
        val last = points.last().middle

        return Summary(
            // The floor-corrected prices, so the panel can't report a 1.0M drill as the low.
            low = basePrices.min(),
            high = points.maxOf { it.high },
            average = average,
            latest = last,
            totalVolume = points.sumOf { it.volume },
            changePercent = if (kotlin.math.abs(first) > 1e-9) (last - first) / first * 100 else 0.0,
            base = basePrices.min(),
            latestBase = basePrices.last(),
            // Only hours that actually traded, for the same reason the average is volume
            // weighted: a quiet hour carries a standing price, not a price anyone paid.
            usual = median(
                points.indices.filter { points[it].volume > 0 }.map { basePrices[it] }
                    .ifEmpty { basePrices },
            ),
        )
    }

    companion object {
        /** See [basePrices]: calibrated against 619 traded hours across five auction items. */
        private const val OUTLIER_FLOOR_RATIO = 0.5

        /**
         * How close the two ends of the scale may be before the axis is padded apart.
         *
         * A proportion rather than a fixed number of coins, because this has to work for an item
         * worth 0.9 coins and one worth 2.2 billion alike.
         */
        private const val FLAT_SCALE_EPSILON = 0.01

        /**
         * Median rather than mean, because the thing being guarded against is exactly the kind
         * of extreme value that drags a mean towards itself. A 1.0M sale barely moves the median
         * of 144 hours; it moves a mean enough to lower the very floor meant to catch it.
         */
        internal fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val middle = sorted.size / 2

            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2
            }
        }

        fun ofBazaar(points: List<BazaarHistory.Point>) = PriceSeries(
            Kind.BAZAAR,
            points.map {
                Point(
                    timestamp = it.timestamp,
                    low = it.sell,
                    high = it.buy,
                    middle = (it.buy + it.sell) / 2,
                    // The weaker side, which is what a flip is actually limited by.
                    volume = minOf(it.buyVolume, it.sellVolume),
                )
            },
        )

        fun ofAuction(points: List<AuctionHistory.Point>) = ofItemPrice(points, Kind.AUCTION)

        /**
         * The same endpoint's data for a bazaar product, which the month window uses.
         *
         * Identical parsing, different meaning - see [Kind.BAZAAR_DAILY].
         */
        fun ofBazaarDaily(points: List<AuctionHistory.Point>) = ofItemPrice(points, Kind.BAZAAR_DAILY)

        private fun ofItemPrice(points: List<AuctionHistory.Point>, kind: Kind) = PriceSeries(
            kind,
            points.map {
                Point(
                    timestamp = it.timestamp,
                    low = it.low,
                    high = it.high,
                    middle = it.average,
                    volume = it.volume,
                )
            },
        )
    }
}
