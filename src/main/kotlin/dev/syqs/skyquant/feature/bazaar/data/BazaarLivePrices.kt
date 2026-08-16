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

        /** Buyers' standing bids, best first - where a *sell* offer fills. */
        @SerializedName("buy_summary")
        var buySummary: List<OrderLevel> = emptyList()

        /** Sellers' standing asks, best first - what a *buy* order competes with. */
        @SerializedName("sell_summary")
        var sellSummary: List<OrderLevel> = emptyList()
    }

    private class OrderLevel {
        @SerializedName("pricePerUnit")
        var pricePerUnit: Double = 0.0

        @SerializedName("amount")
        var amount: Long = 0
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
                        )
                    }.toMap()
                    lastFetchMillis = System.currentTimeMillis()
                } finally {
                    fetching.set(false)
                }
            }
    }
}
