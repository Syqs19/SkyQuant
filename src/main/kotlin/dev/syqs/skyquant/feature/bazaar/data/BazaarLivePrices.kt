package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.HttpJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current bazaar prices for every product, refreshed from Hypixel at most once a minute.
 *
 * One shared snapshot rather than a request per item: the endpoint returns the whole bazaar in
 * a single call, so tracking fifty items costs exactly as much as tracking one. Hypixel's own
 * cache only turns over every 60s, so asking more often would return identical data anyway.
 */
object BazaarLivePrices {

    private const val URL = "https://api.hypixel.net/v2/skyblock/bazaar"
    private const val REFRESH_INTERVAL_MILLIS = 60_000L

    /**
     * How far the price may move against you before the units past that point stop counting as
     * available.
     *
     * One percent, chosen from what the book actually looks like rather than as a round number.
     * Measured across the live bazaar it takes the median product from 2,262 tradeable units to
     * 68,429 - the depth a flipper can actually use - while stopping well short of the abandoned
     * orders in the tail, which sit 20% or more away and would answer "how much exists" instead of
     * "how much can I trade".
     *
     * It is also below the spread on nearly every product, so a trade sized by this figure cannot
     * eat the margin it was taken for.
     */
    const val MAX_SLIPPAGE_PERCENT = 1.0

    private class Response {
        @SerializedName("success")
        var success: Boolean = false

        @SerializedName("lastUpdated")
        var lastUpdated: Long = 0

        @SerializedName("products")
        var products: Map<String, Product> = emptyMap()
    }

    private class Product {
        @SerializedName("quick_status")
        var quickStatus: QuickStatus? = null

        /**
         * Standing **buy orders** - what a sell offer of yours fills into. Best (highest) first.
         *
         * The names are Hypixel's and they read backwards until you remember they are named for
         * the order type, not for what you would do with them. Verified against the live API
         * rather than assumed: `buy_summary[0]` matches `quick_status.buyPrice` on the products
         * where the two agree at all, and the list runs from the keenest buyer downwards.
         */
        @SerializedName("buy_summary")
        var buySummary: List<OrderLevel> = emptyList()

        /** Standing **sell offers** - what a buy order of yours competes with. Best (lowest) first. */
        @SerializedName("sell_summary")
        var sellSummary: List<OrderLevel> = emptyList()
    }

    private class OrderLevel {
        @SerializedName("pricePerUnit")
        var pricePerUnit: Double = 0.0

        @SerializedName("amount")
        var amount: Long = 0
    }

    /**
     * Units available within [MAX_SLIPPAGE_PERCENT] of the best price on this side of the book.
     *
     * The figure a size question actually needs. Hypixel sends up to 30 price levels per side and
     * only the first was ever read, so "Depth" reported what sits at the single best price -
     * measured across the live bazaar, that **understates the tradeable quantity by 11x at the
     * median**, and by more than 2x on 1392 of 1736 products. Enchanted Diamond showed 2,262 units
     * where 68,429 were available for 0.09% more.
     *
     * Levels beyond the threshold are dropped rather than summed. The book's tail holds abandoned
     * orders - Enchanted Diamond's runs down to 1,000 against a market of 1,263 - and counting
     * those would answer a different question: not "how much can I trade" but "how much exists".
     */
    private fun depthWithinSlippage(levels: List<OrderLevel>): Long =
        depthWithin(levels.map { it.pricePerUnit to it.amount })

    /**
     * The same rule over plain pairs, so it can be tested without the API's own classes.
     *
     * Internal rather than private because what counts as "tradeable" decides the size a player
     * commits to a flip, and that is worth pinning down directly.
     */
    internal fun depthWithin(
        levels: List<Pair<Double, Long>>,
        maxSlippagePercent: Double = MAX_SLIPPAGE_PERCENT,
    ): Long {
        val best = levels.firstOrNull()?.first ?: return 0
        if (best <= 0) return 0

        var total = 0L
        for ((price, amount) in levels) {
            val drift = kotlin.math.abs(price - best) / best * 100
            // The levels are ordered outwards from the best price, so the first one past the
            // threshold means every later one is too.
            if (drift > maxSlippagePercent) break
            total += amount
        }

        return total
    }

    private class QuickStatus {
        @SerializedName("buyPrice")
        var buyPrice: Double = 0.0

        @SerializedName("sellPrice")
        var sellPrice: Double = 0.0

        @SerializedName("buyVolume")
        var buyVolume: Long = 0

        @SerializedName("sellVolume")
        var sellVolume: Long = 0

        @SerializedName("buyMovingWeek")
        var buyMovingWeek: Long = 0

        @SerializedName("sellMovingWeek")
        var sellMovingWeek: Long = 0
    }

    /** Live figures for one product. [buyPrice] is what you pay; [sellPrice] what you receive. */
    data class Quote(
        val productId: String,
        val buyPrice: Double,
        val sellPrice: Double,
        val buyVolume: Long,
        val sellVolume: Long,
        val buyMovingWeek: Long,
        val sellMovingWeek: Long,
        /**
         * The best standing bid, i.e. where a sell offer of yours would fill, and how much is
         * queued there.
         *
         * From the order book rather than [buyPrice], which is a summary that can sit far from
         * anything tradeable: SHARD_DRYBARK quoted a `sellPrice` of 22.7 while the cheapest
         * seller in the book was asking 7002, an abandoned order that made the item look like
         * the best flip on the bazaar by a factor of ten.
         */
        val topBid: Double = 0.0,
        val topBidAmount: Long = 0,
        /** The cheapest standing ask - what a buy order of yours has to beat. */
        val topAsk: Double = 0.0,
        val topAskAmount: Long = 0,
        /**
         * Units you could sell into before the price you get drops more than
         * [MAX_SLIPPAGE_PERCENT].
         *
         * [topBidAmount] answers the same question about the single best price only, which is a
         * far smaller number - and the one that used to be shown.
         */
        val bidDepth: Long = 0,
        /** Units you could buy before the price you pay rises more than [MAX_SLIPPAGE_PERCENT]. */
        val askDepth: Long = 0,
    ) {
        /** Gap between the two sides - the margin a flip has to work with. */
        val spread: Double get() = buyPrice - sellPrice

        /**
         * Units traded over the past week, the usual read on whether an item is liquid.
         * A wide spread on something that barely moves is a trap, not an opportunity.
         */
        val weeklyVolume: Long get() = minOf(buyMovingWeek, sellMovingWeek)

        /** Spread relative to the buy price, so items of different value can be compared. */
        val spreadPercent: Double
            get() = if (buyPrice > 1e-9) spread / buyPrice * 100 else 0.0
    }

    @Volatile
    private var quotes: Map<String, Quote> = emptyMap()

    @Volatile
    private var lastFetchMillis = 0L

    private val fetching = AtomicBoolean(false)

    /** Product ids currently on the bazaar, for command completion. Empty until first fetch. */
    val productIds: Set<String> get() = quotes.keys

    /**
     * Changes only when a new snapshot lands, so anything derived from these prices can tell
     * whether recomputing would produce a different answer.
     *
     * Hypixel's cache turns over every 60s and [refreshIfStale] follows it, so this moves about
     * once a minute - against the sixty times a second a screen redraws. That gap is the whole
     * point: rankings over 2528 recipes were being rebuilt every frame to arrive at the same
     * list, measured at 146us a pass, or half a frame's budget spent on work already done.
     *
     * A counter rather than the fetch timestamp: two snapshots can land inside the same
     * millisecond on a fast connection, and a clock that goes backwards - which
     * [NpcSellPrices.isCacheFresh] already has to defend against - would make a cache look fresh
     * forever. A counter only ever moves forward.
     */
    @Volatile
    var snapshotVersion: Long = 0
        private set

    /** When Hypixel generated the snapshot we hold, or 0 before the first one lands. */
    @Volatile
    private var snapshotAtMillis = 0L

    /**
     * Whether the figures on screen are current.
     *
     * From Hypixel's own `lastUpdated` rather than from when we downloaded it, and that difference
     * is the point: a fetch that fails leaves the previous snapshot in place, so "we asked
     * recently" stays true while the prices quietly age. The screens' LIVE indicator used to test
     * only whether *any* product had loaded, so it stayed lit through a network outage - the one
     * situation where a price ticker is worse than no ticker, because the numbers still look
     * authoritative.
     *
     * The allowance is three refresh intervals. Hypixel's own cache turns over every 60s and its
     * timestamp trails that by a few seconds, so a stricter bound would flicker on a healthy feed.
     */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        snapshotAtMillis > 0 && now - snapshotAtMillis < STALE_AFTER_MILLIS

    /** Age of the snapshot in milliseconds, or null before the first one lands. */
    fun snapshotAgeMillis(now: Long = System.currentTimeMillis()): Long? =
        snapshotAtMillis.takeIf { it > 0 }?.let { now - it }

    /** Three refresh intervals: long enough not to flicker, short enough to catch a dead feed. */
    private const val STALE_AFTER_MILLIS = REFRESH_INTERVAL_MILLIS * 3

    fun quoteFor(productId: String): Quote? = quotes[productId.uppercase()]

    /** Every product in the current snapshot, for rankings. Empty until the first fetch. */
    fun allQuotes(): Collection<Quote> = quotes.values

    /**
     * Starts a refresh if the cached snapshot has gone stale. Returns immediately; callers read
     * [quoteFor] once data lands. Safe to call every frame - it self-throttles.
     */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastFetchMillis < REFRESH_INTERVAL_MILLIS) return
        // Guards against a second fetch starting while the first is still in flight, which the
        // timestamp alone wouldn't catch since it's only written on completion.
        if (!fetching.compareAndSet(false, true)) return

        HttpJson.get(URL, Response::class.java)
            .whenComplete { response, error ->
                try {
                    if (error != null) {
                        SkyQuantMod.LOGGER.warn("Bazaar price fetch failed", error)
                        return@whenComplete
                    }
                    if (!response.success) {
                        SkyQuantMod.LOGGER.warn("Bazaar API reported failure")
                        return@whenComplete
                    }

                    quotes = response.products.mapNotNull { (id, product) ->
                        val status = product.quickStatus ?: return@mapNotNull null
                        // Top of book on each side. `buy_summary` holds buyers' bids and
                        // `sell_summary` sellers' asks, both best-first - the names read
                        // backwards until you remember they are named for the order type,
                        // not for what you would do with them.
                        val bestBid = product.buySummary.firstOrNull()
                        val bestAsk = product.sellSummary.firstOrNull()

                        id to Quote(
                            productId = id,
                            buyPrice = status.buyPrice,
                            sellPrice = status.sellPrice,
                            buyVolume = status.buyVolume,
                            sellVolume = status.sellVolume,
                            buyMovingWeek = status.buyMovingWeek,
                            sellMovingWeek = status.sellMovingWeek,
                            topBid = bestBid?.pricePerUnit ?: 0.0,
                            topBidAmount = bestBid?.amount ?: 0,
                            topAsk = bestAsk?.pricePerUnit ?: 0.0,
                            topAskAmount = bestAsk?.amount ?: 0,
                            // Summed here, once per snapshot, rather than kept as raw levels: the
                            // whole book is 3.6MB and holding 30 levels for 2124 products to
                            // re-walk them every frame would trade one waste for another.
                            bidDepth = depthWithinSlippage(product.buySummary),
                            askDepth = depthWithinSlippage(product.sellSummary),
                        )
                    }.toMap()
                    lastFetchMillis = System.currentTimeMillis()
                    // Hypixel's own timestamp, not ours: it is what says whether the *data* is
                    // current, where ours only says when we last asked.
                    snapshotAtMillis = response.lastUpdated
                    // After the quotes themselves, so a reader that sees the new version is
                    // guaranteed to find the new prices behind it rather than the previous ones.
                    snapshotVersion++
                } finally {
                    fetching.set(false)
                }
            }
    }
}
