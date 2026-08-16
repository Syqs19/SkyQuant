package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.deferred
import dev.syqs.skyquant.util.rateLimit
import java.util.concurrent.ConcurrentHashMap

/**
 * Recent auction prices for pinned items the bazaar doesn't trade.
 *
 * The overlay showed a permanent `…` for these. It asked [BazaarLivePrices], which knows only
 * bazaar products by definition, so a pinned drill or sword could never resolve - a row that
 * looks like it is loading forever, which reads as a bug rather than as an absence.
 *
 * The shape of this cache is dictated by what the two markets cost to read. Hypixel returns the
 * entire bazaar in one call, so [BazaarLivePrices] holds a single snapshot of everything.
 * Auction history has no such endpoint: it is one request per item, so this is a per-item cache
 * that only ever fetches what someone has actually pinned. Pinning is deliberate and rare, which
 * is what makes that affordable.
 *
 * Refreshed far less often than the bazaar for the same reason plus a better one: these are
 * completed sales grouped by hour, so the underlying figure only changes hourly. Asking every
 * minute would spend requests to receive the same number back.
 */
object AuctionLivePrices {

    /**
     * Ten minutes. Long enough that a pinned row costs six requests an hour, short enough that a
     * new hour's sales appear without the player wondering whether the row is stuck.
     */
    private const val REFRESH_INTERVAL_MILLIS = 10 * 60_000L

    /**
     * How long a failed lookup is remembered before trying again.
     *
     * Without this an item that is on neither market - a quest item, a soulbound one - would be
     * requested every ten minutes forever. Failures are much cheaper to remember than to repeat.
     */
    private const val FAILURE_BACKOFF_MILLIS = 30 * 60_000L

    /**
     * The last sale price and how the day went, for one item.
     *
     * [changePercent] spans the charted day rather than the last hour, matching what the graph
     * screen reports for the same item - two different numbers under the same heading in two
     * places would be worse than either.
     */
    data class Quote(
        val itemId: String,
        val price: Double,
        val changePercent: Double,
        val soldToday: Long,
    )

    private class Entry(
        @Volatile var quote: Quote? = null,
        @Volatile var fetchedAtMillis: Long = 0,
        @Volatile var inFlight: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * The cached quote, or null while one is being fetched or if the item never had auction
     * sales. Never blocks: callers draw a placeholder and the row fills in on a later frame.
     */
    fun quoteFor(itemId: String): Quote? = entries[itemId.uppercase()]?.quote

    /**
     * Requests [itemId]'s price if the cache has nothing recent. Safe to call every frame.
     *
     * Deliberately separate from [quoteFor] so a caller that merely reads a price - a test, a
     * table being sorted - can't start network traffic as a side effect of being drawn.
     */
    fun refreshIfStale(itemId: String) {
        val id = itemId.uppercase()
        val entry = entries.computeIfAbsent(id) { Entry() }
        val now = System.currentTimeMillis()

        if (entry.inFlight) return

        // A miss is held for longer than a hit, so items with no auction market at all settle
        // down to two requests an hour instead of six.
        val interval = if (entry.quote == null && entry.fetchedAtMillis > 0) {
            FAILURE_BACKOFF_MILLIS
        } else {
            REFRESH_INTERVAL_MILLIS
        }
        if (now - entry.fetchedAtMillis < interval) return

        entry.inFlight = true

        AuctionHistory.fetch(id, AuctionHistory.Range.DAY).whenComplete { points, error ->
            // A rate limit is not an answer about this item. Stamping it would hold a pinned row
            // blank for the thirty-minute miss backoff over a refusal that had nothing to do with
            // it - and pinned rows are the ones the player is actually watching.
            var recordAnswer = true

            try {
                if (error != null) {
                    recordAnswer = answersTheItem(error)

                    if (recordAnswer) {
                        // Debug rather than warn: an item with no auction history is the ordinary
                        // case here, not a fault worth putting in a player's log at every pin.
                        SkyQuantMod.LOGGER.debug("Auction price lookup failed for {}", id, error)
                        entry.quote = null
                    }
                    return@whenComplete
                }

                entry.quote = summarize(id, points)
            } finally {
                if (recordAnswer) entry.fetchedAtMillis = System.currentTimeMillis()
                entry.inFlight = false
            }
        }
    }

    /**
     * Whether a failure says anything about *this item*, and may therefore be cached.
     *
     * Only two kinds don't. A **429** is the server refusing our request rate, and a
     * [CoflnetRateLimit.Deferred] is our own pacing declining to send one at all - neither is a
     * statement about whether the item has auction sales, so recording either as an answer marks
     * a tradeable item "no data" for the whole thirty-minute miss backoff. Pinned rows are the
     * only ones this class fetches, so the items it would blank are precisely the ones the player
     * chose to watch.
     *
     * The `Deferred` half is the one that was missing. It reads as an ordinary failure -
     * `rateLimit()` returns null for it - so it fell into the generic branch and was cached. The
     * sibling caches never hit it because they check the budget before starting a request and
     * return early; this one delegates to [AuctionHistory.fetch], which checks it *inside*, so the
     * refusal comes back as a completed future instead of never starting.
     *
     * Extracted and internal because the difference is invisible in the game: a wrongly cached
     * refusal looks exactly like an item that genuinely has no sales.
     */
    internal fun answersTheItem(error: Throwable): Boolean =
        error.rateLimit() == null && !error.deferred()

    /**
     * Reduces a day of hourly sales to the one row the overlay has space for.
     *
     * Extracted and internal so it can be tested without a network: the arithmetic here decides
     * what number a player sees on their HUD, which is worth pinning down directly.
     */
    internal fun summarize(itemId: String, points: List<AuctionHistory.Point>): Quote? {
        if (points.isEmpty()) return null

        val series = PriceSeries.ofAuction(points)
        val summary = series.summarize() ?: return null

        return Quote(
            itemId = itemId,
            price = summary.latest,
            changePercent = summary.changePercent,
            soldToday = summary.totalVolume,
        )
    }
}
