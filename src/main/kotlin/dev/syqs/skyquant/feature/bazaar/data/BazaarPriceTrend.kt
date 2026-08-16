package dev.syqs.skyquant.feature.bazaar.data

/**
 * Short rolling history of buy prices, recorded from the snapshots that are fetched anyway.
 *
 * The live API only reports the price now, so a change figure needs an earlier price to compare
 * against. Coflnet has one, but only a single product per request - a watchlist of twenty items
 * would mean twenty requests every time the screen opens. Since a fresh snapshot of the whole
 * bazaar already arrives once a minute, keeping the last few of those costs nothing extra.
 *
 * The trade-off is that history only covers the current session: right after launch there is
 * nothing to compare against, and [changePercentFor] returns null until enough time has passed.
 */
object BazaarPriceTrend {

    /**
     * How far back the comparison reaches once enough samples exist.
     *
     * An hour rather than the fifteen minutes this started at: the overlay draws the shape of
     * the move, and a handful of points describes a jagged line rather than a trend.
     */
    const val WINDOW_MILLIS = 60 * 60_000L

    private class Sample(val timestamp: Long, val buyPrice: Double)

    /**
     * Per product, oldest first. A deque rather than a list: expired samples are removed from
     * the front every minute, which on an ArrayList would shift the whole remainder each time,
     * and this holds every product on the bazaar rather than only the followed ones.
     */
    private val samples = mutableMapOf<String, ArrayDeque<Sample>>()

    /**
     * Records the current snapshot. Safe to call every frame - it only stores a sample when the
     * snapshot itself has actually been refreshed.
     */
    fun record(
        // Both defaulted rather than read inside: the behaviour worth checking is what happens
        // as time passes and prices move, and neither can be arranged with a live clock.
        quotes: Collection<BazaarLivePrices.Quote> = BazaarLivePrices.allQuotes(),
        now: Long = System.currentTimeMillis(),
    ) {
        if (quotes.isEmpty()) return

        val cutoff = now - WINDOW_MILLIS

        for (quote in quotes) {
            val history = samples.getOrPut(quote.productId) { ArrayDeque() }

            // Nothing changes between snapshots, so a sample per frame would just be the same
            // number thousands of times over.
            val newest = history.lastOrNull()
            if (newest != null && now - newest.timestamp < MIN_SAMPLE_INTERVAL_MILLIS) continue

            history.addLast(Sample(now, quote.buyPrice))

            // Dropped from the front rather than filtered: samples are in time order, so
            // everything expired is at the head and scanning the rest finds nothing.
            while (history.isNotEmpty() && history.first().timestamp < cutoff) {
                history.removeFirst()
            }
        }
    }

    /**
     * Percentage move in the buy price over the recorded window, or null when there isn't yet
     * enough history to say - which the caller should show as unknown rather than as zero.
     */
    fun changePercentFor(productId: String): Double? {
        val history = samples[productId.uppercase()] ?: return null

        val oldest = history.firstOrNull() ?: return null
        val newest = history.lastOrNull() ?: return null

        if (newest.timestamp - oldest.timestamp < MIN_USABLE_SPAN_MILLIS) return null
        if (oldest.buyPrice <= 1e-9) return null

        return (newest.buyPrice - oldest.buyPrice) / oldest.buyPrice * 100
    }

    /** How long the available history actually spans, for labelling the column honestly. */
    fun spanMillis(productId: String): Long {
        val history = samples[productId.uppercase()] ?: return 0
        val oldest = history.firstOrNull() ?: return 0
        val newest = history.lastOrNull() ?: return 0
        return newest.timestamp - oldest.timestamp
    }

    /**
     * Recorded buy prices, oldest first, for drawing the shape of the move rather than just its
     * endpoints. Empty until there are at least two samples, since one point has no shape.
     */
    fun seriesFor(productId: String): List<Double> {
        val history = samples[productId.uppercase()] ?: return emptyList()
        if (history.size < 2) return emptyList()

        return history.map { it.buyPrice }
    }

    /** Drops every recorded sample. Only for tests, which must not inherit each other's state. */
    internal fun reset() = samples.clear()

    private const val MIN_SAMPLE_INTERVAL_MILLIS = 60_000L

    // Below a couple of minutes the two samples are usually the same snapshot twice over, and
    // the resulting "0.0%" reads as a real answer rather than as missing data.
    private const val MIN_USABLE_SPAN_MILLIS = 2 * 60_000L
}
