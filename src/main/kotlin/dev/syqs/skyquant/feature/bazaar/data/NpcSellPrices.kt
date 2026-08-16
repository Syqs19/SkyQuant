package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.HttpJson
import dev.syqs.skyquant.util.JsonFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What NPC shops pay for each item, from Hypixel's item resource.
 *
 * Fetched once per session rather than on a timer: these are fixed shop prices set by the game,
 * not a market, so unlike bazaar quotes they only move when Hypixel patches something. The
 * response is ~5MB for 5600 items, which is another reason not to poll it.
 *
 * Only the sell price is kept. The endpoint carries some forty fields per item - stats, museum
 * data, gemstone slots - and holding all of it would cost memory for data nothing reads.
 */
object NpcSellPrices {

    private const val URL = "https://api.hypixel.net/v2/resources/skyblock/items"

    private class Response {
        @SerializedName("success")
        var success: Boolean = false

        @SerializedName("items")
        var items: List<Item> = emptyList()
    }

    private class Item {
        @SerializedName("id")
        var id: String? = null

        /** Absent for most items - only about 2400 of 5600 can be sold to an NPC at all. */
        @SerializedName("npc_sell_price")
        var npcSellPrice: Double? = null
    }

    /**
     * The derived index on disk, with when it was written.
     *
     * Only the ~2400 items that have a price are kept, which is a few tens of KB against the
     * 4.9 MB the endpoint sends - the saving is in never having to parse those 4.9 MB again more
     * than it is in the bytes.
     */
    private class Cache {
        var fetchedAtMillis: Long = 0
        var prices: MutableMap<String, Double> = mutableMapOf()
    }

    private val file = JsonFile.of("npc_sell_prices", { Cache() })

    /**
     * How long a cached catalogue is used before checking for a newer one.
     *
     * These are shop prices set by the game, not a market: they move when Hypixel patches
     * something, which is a matter of weeks. A day is far shorter than that and still means a
     * player who plays daily downloads this once.
     *
     * Unlike the NEU repo there is no etag to ask with - Hypixel's resource endpoint sends none -
     * so "has it changed?" costs the whole 4.9 MB. That is exactly why the interval is a day
     * rather than an hour.
     */
    private const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L

    @Volatile
    private var prices: Map<String, Double> = emptyMap()

    private val fetching = AtomicBoolean(false)

    /** True once the catalogue has landed, so screens can tell "loading" from "nothing to show". */
    val isLoaded: Boolean get() = prices.isNotEmpty()

    /** What an NPC pays for one unit, or null if this item can't be sold to one. */
    fun priceFor(itemId: String): Double? = prices[itemId.uppercase()]

    /**
     * Loads the catalogue - from disk if a recent copy is there, otherwise from Hypixel.
     *
     * Returns immediately and is safe to call every frame. The disk copy is read on the calling
     * thread because it is a few tens of KB; the 4.9 MB download never blocks anything.
     */
    fun loadOnce() {
        if (isLoaded) return
        if (!fetching.compareAndSet(false, true)) return

        val cached = file.load()

        // A cached copy is shown even when stale, and only then is a download started. NPC prices
        // change with a game patch, so yesterday's figures are right far more often than an empty
        // page is useful - the download replaces figures that already worked.
        if (cached.prices.isNotEmpty()) {
            prices = cached.prices.toMap()
        }

        if (isCacheFresh(cached.fetchedAtMillis, cached.prices.size)) {
            fetching.set(false)
            SkyQuantMod.LOGGER.info("Loaded {} NPC sell prices from cache", prices.size)
            return
        }

        download()
    }

    /**
     * Whether the cache on disk can be used without asking Hypixel again.
     *
     * Internal so the rule can be tested without a network or a disk: it decides whether a player
     * pays for a 4.9 MB download, and Hypixel's resource endpoint sends no etag, so there is no
     * cheap way to check - the interval *is* the whole policy.
     *
     * A future timestamp counts as stale rather than fresh-forever. It means the clock moved
     * backwards, and the alternative is a cache that never refreshes again.
     */
    internal fun isCacheFresh(
        fetchedAtMillis: Long,
        entryCount: Int,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (entryCount == 0) return false

        val age = now - fetchedAtMillis
        return age in 0 until MAX_CACHE_AGE_MILLIS
    }

    private fun download() {
        HttpJson.get(URL, Response::class.java)
            .whenComplete { response, error ->
                try {
                    if (error != null) {
                        SkyQuantMod.LOGGER.warn("NPC price fetch failed", error)
                        return@whenComplete
                    }
                    if (!response.success) {
                        SkyQuantMod.LOGGER.warn("Hypixel item resource reported failure")
                        return@whenComplete
                    }

                    val parsed = response.items.mapNotNull { item ->
                        val id = item.id ?: return@mapNotNull null
                        val price = item.npcSellPrice ?: return@mapNotNull null
                        // A price of 1 is the endpoint's placeholder for "not really sellable"
                        // rather than a real one-coin shop price, and it shows up on things like
                        // furniture and quest items. Kept out here so every caller doesn't have
                        // to know about it.
                        if (price <= PLACEHOLDER_PRICE) return@mapNotNull null
                        id to price
                    }.toMap()

                    if (parsed.isEmpty()) {
                        // An empty parse means the response shape changed. Keeping whatever was
                        // loaded beats replacing working figures with nothing.
                        SkyQuantMod.LOGGER.warn("Item catalogue parsed to no prices, keeping previous")
                        return@whenComplete
                    }

                    prices = parsed
                    save(parsed)
                } finally {
                    fetching.set(false)
                }
            }
    }

    private fun save(parsed: Map<String, Double>) {
        val cache = Cache()
        cache.fetchedAtMillis = System.currentTimeMillis()
        cache.prices = parsed.toMutableMap()
        file.save(cache)
    }

    private const val PLACEHOLDER_PRICE = 1.0
}
