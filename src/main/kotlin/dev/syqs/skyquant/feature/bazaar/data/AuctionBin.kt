package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.HttpJson
import dev.syqs.skyquant.util.rateLimit
import java.util.concurrent.ConcurrentHashMap

/**
 * The cheapest Buy It Now listing for an auction item - what it costs to buy one right now.
 *
 * This answers a question the price history cannot. History says what people paid; only the live
 * book says what is on sale this minute, and for an auction item those routinely differ by more
 * than any margin worth acting on.
 *
 * The obvious route to it is Hypixel's own `/v2/skyblock/auctions`, and it was measured before
 * being rejected: 48 pages, 47,293 auctions, ~57MB gzipped (105MB parsed), of which 54% is
 * `item_bytes` that would never be read. It carries no ETag, so every refresh costs the whole
 * download again, and the items worth charting are scattered across every page rather than
 * clustered - so there is no partial fetch to make either.
 *
 * Coflnet already computes the answer and serves it in **87 bytes**. That is the entire reason
 * this class is thirty lines rather than a paging downloader with a disk cache.
 */
object AuctionBin {

    private const val BASE_URL = "https://sky.coflnet.com/api/item/price"

    /**
     * A minute, matching how fast the underlying book actually turns over. Auctions are listed
     * and bought continuously, so this is much shorter than [AuctionLivePrices]'s ten minutes -
     * that one summarises completed hourly sales, which cannot change faster than hourly.
     */
    private const val REFRESH_INTERVAL_MILLIS = 60_000L

    /** As in [AuctionLivePrices]: items with no listings shouldn't be asked about every minute. */
    private const val FAILURE_BACKOFF_MILLIS = 10 * 60_000L

    /**
     * How far the cheapest listing may sit below the second before it stops being treated as the
     * going rate.
     *
     * Sampled across twelve items: nine sit within 1% of the second cheapest, Livid Dagger at
     * 11.2% is the widest ordinary case, and Daedalus Axe at 28.6% is the one genuine outlier -
     * a single underpriced listing that nobody else is matching. 15% separates those cleanly.
     *
     * A gap this wide doesn't make the price wrong, and it is not hidden: it makes it *one
     * listing* rather than a market, which is a different thing to act on. The UI flags it and
     * shows both figures rather than picking for the player.
     */
    const val OUTLIER_GAP_PERCENT = 15.0

    private class Response {
        @SerializedName("lowest")
        var lowest: Long = 0

        @SerializedName("secondLowest")
        var secondLowest: Long = 0
    }

    /**
     * [lowest] is the cheapest Buy It Now listing; [secondLowest] the next one up, which is what
     * says whether the first is the market or a fluke.
     */
    data class Quote(
        val itemId: String,
        val lowest: Double,
        val secondLowest: Double,
    ) {
        /** How much dearer the second cheapest listing is, as a percentage of the cheapest. */
        val gapPercent: Double
            get() = if (lowest > 1e-9 && secondLowest > 0) (secondLowest - lowest) / lowest * 100 else 0.0

        /**
         * True when the cheapest listing stands well clear of everything else, so buying it is a
         * one-off rather than the going rate - and, more to the point, so *selling* at that price
         * would be leaving coins behind.
         *
         * A sole listing reports false, which falls out of [gapPercent] returning zero when there
         * is no second price to compare against. That is the right answer: with one listing there
         * is no gap to call wide, and flagging it would tell the player something the data
         * doesn't say. What a sole listing *is* worth flagging for is [isSoleListing].
         */
        val isLone: Boolean get() = gapPercent > OUTLIER_GAP_PERCENT

        /**
         * True when this is the only one on sale, so the price is one seller's asking price rather
         * than a market at all.
         *
         * Deliberately separate from [isLone] rather than folded into it. The two describe
         * different situations and only one of them has a second figure to show: Daedalus Axe is
         * cheap *against other listings*, while Divan's Drill has no other listings. Sharing one
         * flag between them is what left the riskiest case - a lone 1.3B listing - carrying no
         * warning at all, because a sole listing has no gap and so never satisfied [isLone].
         */
        val isSoleListing: Boolean get() = secondLowest <= 0
    }

    private class Entry(
        @Volatile var quote: Quote? = null,
        @Volatile var fetchedAtMillis: Long = 0,
        @Volatile var inFlight: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The cached quote, or null while one is in flight or if nothing is listed. Never blocks. */
    fun quoteFor(itemId: String): Quote? = entries[itemId.uppercase()]?.quote

    /** Requests [itemId]'s cheapest listing if the cache has nothing recent. Safe every frame. */
    fun refreshIfStale(itemId: String) {
        val id = itemId.uppercase()
        val entry = entries.computeIfAbsent(id) { Entry() }
        val now = System.currentTimeMillis()

        if (entry.inFlight) return

        val interval = if (entry.quote == null && entry.fetchedAtMillis > 0) {
            FAILURE_BACKOFF_MILLIS
        } else {
            REFRESH_INTERVAL_MILLIS
        }
        if (now - entry.fetchedAtMillis < interval) return

        // Shares one budget with every other Coflnet caller - see [CoflnetRateLimit]. Left
        // untouched rather than stamped when there is no room, so the next pass retries.
        if (!CoflnetRateLimit.tryAcquire()) return

        entry.inFlight = true

        HttpJson.get("$BASE_URL/$id/bin", Response::class.java).whenComplete { response, error ->
            // As in AuctionSellPrice: a 429 says nothing about this item, so it must not be
            // recorded as an answer. Doing so would put "not for sale" on an item that is.
            var recordAnswer = true

            try {
                val rateLimited = error?.rateLimit()
                if (rateLimited != null) {
                    CoflnetRateLimit.backOff(rateLimited.retryAfterMillis)
                    recordAnswer = false
                    return@whenComplete
                }

                if (error != null) {
                    // Debug, not warn: "nothing listed" is an ordinary answer here - Rookie
                    // Pickaxe has no auctions and never will - not a fault worth a player's log.
                    SkyQuantMod.LOGGER.debug("Lowest BIN lookup failed for {}", id, error)
                    entry.quote = null
                    return@whenComplete
                }

                entry.quote = parse(id, response.lowest, response.secondLowest)
            } finally {
                if (recordAnswer) entry.fetchedAtMillis = System.currentTimeMillis()
                entry.inFlight = false
            }
        }
    }

    /**
     * Builds the quote, or null when nothing is listed.
     *
     * Internal so the rules can be tested without a network: whether a zero means "no listings",
     * and what counts as a lone listing, both decide what a player is told to pay.
     */
    internal fun parse(itemId: String, lowest: Long, secondLowest: Long): Quote? {
        // Coflnet answers 200 with a zero rather than 404 when an item has no active auction, so
        // this is the real "not for sale" check. Taken at face value it would put a price of
        // zero coins on screen.
        if (lowest <= 0) return null

        return Quote(
            itemId = itemId,
            lowest = lowest.toDouble(),
            // Zero when the item has exactly one listing, which is left as zero rather than
            // copied from `lowest`: those are different situations, and `isLone` must not
            // report a sole listing as a well-supported price.
            secondLowest = secondLowest.coerceAtLeast(0).toDouble(),
        )
    }
}
