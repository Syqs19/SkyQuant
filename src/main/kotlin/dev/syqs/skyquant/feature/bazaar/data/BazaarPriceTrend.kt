package dev.syqs.skyquant.feature.bazaar.data

import java.util.concurrent.ConcurrentHashMap

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
     * Per product, oldest first, and never mutated once stored - [record] replaces a product's
     * list rather than appending to it.
     *
     * Concurrent on both counts because the two sides run on different threads: [record] is driven
     * from the client tick, while [seriesFor], [changePercentFor] and [spanMillis] are read while
     * a row is drawn. A plain LinkedHashMap resized under a concurrent read can hand back a
     * corrupted view or spin, and with ~1400 products it resizes repeatedly in the first minute of
     * play. The immutability is the other half: a concurrent map protects its *entries*, not a
     * mutable list held inside one, so an appendable deque would still be read mid-write.
     */
    private val samples = ConcurrentHashMap<String, List<Sample>>()

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
            // Upper-cased on the way in because every reader looks the id up that way. The two
            // sides disagreeing would leave a product recorded under one key and searched for
            // under another, i.e. a change column that stays blank forever.
            val id = quote.productId.uppercase()
            val history = samples[id]

            // Nothing changes between snapshots, so a sample per frame would just be the same
            // number thousands of times over.
            val newest = history?.lastOrNull()
            if (newest != null && now - newest.timestamp < MIN_SAMPLE_INTERVAL_MILLIS) continue

            // Rebuilt rather than mutated in place. The list is read from the render thread while
            // this runs on the client tick, and a deque being appended to mid-iteration is exactly
            // the race a concurrent map does *not* protect against - the map guards its entries,
            // not the mutable object inside one. Replacing the value outright means a reader holds
            // either the old list or the new one, both of them complete.
            //
            // The copy is cheap because the window is short: an hour at one sample a minute caps a
            // product at 60 entries, and only products in the snapshot are touched.
            val trimmed = history.orEmpty().filterTo(ArrayList(MAX_SAMPLES)) { it.timestamp >= cutoff }
            trimmed.add(Sample(now, quote.buyPrice))

            samples[id] = trimmed
        }
    }

    /**
     * Percentage move in the buy price over the recorded window, or null when there isn't yet
     * enough history to say - which the caller should show as unknown rather than as zero.
     */
    fun changePercentFor(productId: String): Double? {
        // Read once into a local: the field can be replaced by [record] between two reads, and
        // taking `first` from one list and `last` from another would compare across snapshots.
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

    /**
     * How many samples are held for a product. Exists so the window's trimming can be asserted
     * directly rather than inferred from the figures it produces.
     */
    internal fun sampleCountForTest(productId: String): Int =
        samples[productId.uppercase()]?.size ?: 0

    private const val MIN_SAMPLE_INTERVAL_MILLIS = 60_000L

    /**
     * Upper bound on a product's history, used to size the copy [record] builds.
     *
     * Falls out of the other two constants rather than being chosen: an hour of window at one
     * sample a minute is sixty, plus the one being added. Derived so it cannot drift out of step
     * with them.
     */
    private const val MAX_SAMPLES = (WINDOW_MILLIS / MIN_SAMPLE_INTERVAL_MILLIS).toInt() + 1

    // Below a couple of minutes the two samples are usually the same snapshot twice over, and
    // the resulting "0.0%" reads as a real answer rather than as missing data.
    private const val MIN_USABLE_SPAN_MILLIS = 2 * 60_000L
}
