package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.HttpJson
import dev.syqs.skyquant.util.rateLimit
import java.util.concurrent.ConcurrentHashMap

/**
 * What a crafted item would sell for at auction, for the recipes the bazaar doesn't price.
 *
 * The bazaar covers materials; weapons, armour, talismans and most forge outputs are sold at
 * auction instead, and until now every recipe producing one was simply dropped. Measured against
 * the repo: **502 crafting recipes and 33 forge recipes** have ingredients the bazaar prices and
 * an output it doesn't, which is roughly double the coverage of the bazaar-only pages.
 *
 * Distinct from [AuctionBin], which answers "what does one cost right now" for a single item the
 * player is looking at. This answers "what would mine fetch" for a page of recipes, so it takes
 * the **median of the four cheapest listings** rather than the cheapest: undercutting the market
 * by one coin is how an auction sells, and pricing a whole page off the single lowest listing
 * would build every profit on whatever mistake somebody made this minute.
 */
object AuctionSellPrice {

    private const val BASE_URL = "https://sky.coflnet.com/api/auctions/tag"

    /**
     * Ten minutes. Longer than [AuctionBin]'s minute because this feeds a ranked page rather than
     * a figure the player is about to act on, and because a page can hold a hundred rows - each
     * one its own request.
     */
    private const val REFRESH_INTERVAL_MILLIS = 10 * 60_000L

    /** Items with no listings shouldn't be asked about on every refresh. */
    private const val FAILURE_BACKOFF_MILLIS = 30 * 60_000L

    /** How many of the cheapest listings the median is taken over. */
    private const val SAMPLE_SIZE = 4

    /**
     * Below this share of the median, the cheapest listing is treated as an outlier rather than
     * as the going rate.
     *
     * Measured on live data: Divan's Drill listed at 420.2M against a median of 1339.5M - 31% -
     * while Beacon V sits at 99% and Gemstone Gauntlet at 89%. 60% separates the mistake from the
     * ordinary undercut, and the row is flagged rather than hidden: the listing is real, and
     * somebody willing to check it should be told it exists.
     */
    private const val OUTLIER_FLOOR_RATIO = 0.6

    /**
     * What the auction house keeps out of a Buy It Now sale, as a fraction of the price.
     *
     * **Not the bazaar's flat cut.** Listing a BIN costs 1% under 10M, 2% from 10M to 100M and
     * 2.5% above it, and claiming the coins costs a further 1% on anything over a million. Using
     * [BazaarTax] here would understate the cut by more than double on exactly the expensive
     * items this endpoint exists to price - a 200M forge output loses 3.5%, not 1.25%.
     *
     * The claim fee is capped so it cannot take a sale below a million coins, which is why it
     * only applies above that.
     */
    internal fun taxFor(price: Double): Double {
        val listing = when {
            price > 100_000_000 -> 0.025
            price >= 10_000_000 -> 0.02
            else -> 0.01
        }
        val claim = if (price > 1_000_000) 0.01 else 0.0

        return listing + claim
    }

    private class Listing {
        @SerializedName("price")
        var price: Double = 0.0
    }

    /**
     * [price] is the median of the cheapest few listings, i.e. what one of these realistically
     * fetches. [lowest] is the single cheapest, kept only so [isOutlier] can be explained.
     */
    data class Quote(
        val itemId: String,
        val price: Double,
        val lowest: Double,
        val listingCount: Int,
    ) {
        /**
         * True when the cheapest listing sits far below the rest, so a profit computed from it
         * would rest on one mispriced auction.
         */
        val isOutlier: Boolean get() = price > 0 && lowest < price * OUTLIER_FLOOR_RATIO
    }

    private class Entry(
        @Volatile var quote: Quote? = null,
        @Volatile var fetchedAtMillis: Long = 0,
        @Volatile var inFlight: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * How many lookups may be waiting on the network at once.
     *
     * The limit that actually matters, and the one that was missing. Callers ask per *frame*, so
     * a cap on requests-per-call is no cap at all: simulated at 60fps, twelve per frame put **288
     * simultaneous requests** on Coflnet within the first second. Counting what is in flight
     * instead makes the pacing independent of frame rate, which is the only thing that keeps a
     * fast machine from hammering a free API harder than a slow one.
     *
     * Four matches what was measured as comfortable: twelve items in 0.6s.
     */
    private const val MAX_IN_FLIGHT = 4

    private val inFlightCount = java.util.concurrent.atomic.AtomicInteger()

    /** The cached price, or null while one is in flight or if nothing is listed. Never blocks. */
    fun quoteFor(itemId: String): Quote? = entries[itemId.uppercase()]?.quote

    /** How many lookups are on the network right now. Exists so the cap can be tested. */
    internal fun inFlightForTesting(): Int = inFlightCount.get()

    /**
     * True while the page is still being priced, so it can say so rather than reporting what it
     * has as the final answer.
     *
     * The Craft page showed "Nothing worth making right now" during this - a finished-sounding
     * verdict on a list that had not been priced yet, and the reason a page that was about to fill
     * looked like an empty one.
     *
     * Not simply `inFlightCount > 0`: requests come in bursts paced by the shared budget, so
     * between two bursts the count is legitimately zero for a moment and the message would flicker
     * on and off. Anything answered within the last few seconds means work is still ongoing.
     */
    val isBusy: Boolean
        get() = inFlightCount.get() > 0 ||
            System.currentTimeMillis() - lastAnswerAtMillis < BUSY_LINGER_MILLIS

    /** Long enough to bridge the gap between bursts, short enough not to outlast the work. */
    private const val BUSY_LINGER_MILLIS = 4_000L

    @Volatile
    private var lastAnswerAtMillis = 0L

    /** True once this item has been asked about, whatever the answer was. */
    fun hasAnswerFor(itemId: String): Boolean =
        entries[itemId.uppercase()]?.fetchedAtMillis?.let { it > 0 } ?: false

    /**
     * How many answers have arrived, so a page built on these prices can tell whether recomputing
     * would change anything.
     *
     * Needed alongside [BazaarLivePrices.snapshotVersion] rather than instead of it. These arrive
     * between snapshots, a few per pass, and a cache keyed only on the bazaar's version would
     * freeze the Craft page for a whole minute while the very prices it is waiting for landed
     * unnoticed - trading a cost the player can't see for an emptiness they can.
     *
     * Counts every answer including "nothing listed": that outcome removes a candidate from the
     * page just as surely as a price adds one.
     */
    @Volatile
    var answerCount: Long = 0
        private set

    /**
     * Requests [itemId]'s listings if nothing recent is cached.
     *
     * Safe to call every frame and for every row on screen: an in-flight or recent entry returns
     * immediately. Callers should only ask for rows they are about to draw - there are 533
     * priceable auction outputs, and fetching all of them would be half a minute of requests for
     * a page showing a hundred.
     */
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

        // Claimed before the request is built, and released in the completion handler. Callers
        // ask every frame, so without this the "twelve per pass" they think they are asking for
        // becomes twelve per frame.
        if (inFlightCount.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlightCount.decrementAndGet()
            return
        }

        // The rate limit proper. The in-flight cap above bounds how many requests are open at
        // once, which is not the same thing: four completing in 100ms each is forty in ten
        // seconds, over Coflnet's published limit. This is the check that counts over time.
        //
        // Returning without touching `fetchedAtMillis` is deliberate - the item is left untouched
        // rather than recorded as asked-about, so the next pass retries it instead of writing a
        // 30-minute "nothing listed" for an item nobody ever asked about.
        if (!CoflnetRateLimit.tryAcquire()) {
            inFlightCount.decrementAndGet()
            return
        }

        entry.inFlight = true

        HttpJson.get("$BASE_URL/$id/active/overview", Array<Listing>::class.java)
            .whenComplete { response, error ->
                // Set unless a rate limit says otherwise: a 429 is a statement about our request
                // rate, not about this item, so stamping it would cache "nothing listed" for a
                // perfectly tradeable item and hide it for the whole failure backoff.
                var recordAnswer = true

                try {
                    val rateLimited = error?.rateLimit()
                    if (rateLimited != null) {
                        CoflnetRateLimit.backOff(rateLimited.retryAfterMillis)
                        recordAnswer = false
                        return@whenComplete
                    }

                    if (error != null) {
                        // Debug rather than warn: "nothing listed" is an ordinary answer for an
                        // item nobody is selling this minute, not a fault worth a player's log.
                        SkyQuantMod.LOGGER.debug("Auction listings lookup failed for {}", id, error)
                        entry.quote = null
                        return@whenComplete
                    }

                    entry.quote = parse(id, response.map { it.price })
                } finally {
                    if (recordAnswer) {
                        entry.fetchedAtMillis = System.currentTimeMillis()
                        // Drives [isBusy], which is why it is stamped for every real answer rather
                        // than only for successful ones: an item with no listings is still work
                        // done, and a page mid-sweep should not claim to be finished.
                        lastAnswerAtMillis = entry.fetchedAtMillis
                        // After the quote, so a reader seeing the new count finds the new price.
                        answerCount++
                    }
                    entry.inFlight = false
                    inFlightCount.decrementAndGet()
                }
            }
    }

    /**
     * Builds the quote from a set of listing prices, or null when there is nothing to price from.
     *
     * Internal so the rule can be tested without a network: which figure a whole page of profits
     * is built on is worth pinning down directly.
     */
    internal fun parse(itemId: String, prices: List<Double>): Quote? {
        val usable = prices.filter { it > 0 }.sorted()
        if (usable.isEmpty()) return null

        val sample = usable.take(SAMPLE_SIZE)

        return Quote(
            itemId = itemId,
            // Median rather than mean, for the reason the sample exists at all: a mean of the four
            // cheapest is dragged towards a single mispriced one, which is exactly the value being
            // guarded against. Divan's Drill's 420M would pull a mean of 1.13B down to 869M; the
            // median ignores it.
            //
            // Shared with PriceSeries rather than kept as a second copy. The two differed in a way
            // that does not show up until someone reuses the wrong one: this file's version assumed
            // its input was already sorted, which was true only because the one caller happened to
            // sort first. The shared one sorts for itself and so cannot be called wrongly.
            price = PriceSeries.median(sample),
            lowest = usable.first(),
            listingCount = usable.size,
        )
    }
}
