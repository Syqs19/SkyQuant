package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.GitHubArchive
import dev.syqs.skyquant.util.JsonFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * What NPC shops *charge* for an item, from the NEU data repository.
 *
 * The counterpart to [NpcSellPrices], which only knows what a shop pays you. Hypixel's API has
 * no field for this at all, so it comes from the community repository - the same source
 * SkyHanni and Firmament use.
 *
 * Kept fresh without a mod release: the derived index is cached on disk, and a conditional
 * request against GitHub reports "unchanged" in a few hundred bytes, so the 9MB archive is only
 * downloaded when it has actually changed.
 */
object NpcShopPrices {

    private const val OWNER = "NotEnoughUpdates"
    private const val REPO = "NotEnoughUpdates-REPO"
    private const val BRANCH = "master"

    /**
     * What an NPC charges for one unit, and which NPC sells it at that price.
     *
     * [otherSellers] counts the *additional* shops selling the same item. Their stock is
     * separate - buying out the Mine Merchant's iron leaves the Iron Forger's untouched, which
     * was confirmed in-game - so for these items a day's buying is worth more than one shop's
     * limit. 42 of the 474 buyable items have more than one seller.
     */
    data class ShopPrice(
        val pricePerUnit: Double,
        val npcId: String,
        val otherSellers: Int = 0,
    )

    /** The cached index, plus the etag identifying the archive it came from. */
    private class Cache {
        var etag: String? = null
        var prices: MutableMap<String, Entry> = mutableMapOf()
    }

    private class Entry {
        var price: Double = 0.0
        var npc: String = ""
        var others: Int = 0
    }

    private val file = JsonFile.of("npc_shops", { Cache() })

    @Volatile
    private var prices: Map<String, ShopPrice> = emptyMap()

    @Volatile
    private var etag: String? = null

    private val refreshing = AtomicBoolean(false)

    /** True once prices are available, from cache or from a download. */
    val isLoaded: Boolean get() = prices.isNotEmpty()

    val size: Int get() = prices.size

    fun priceFor(itemId: String): ShopPrice? = prices[itemId.uppercase()]

    /**
     * Loads the cached index and, on a background thread, checks GitHub for a newer one.
     *
     * Returns immediately. The cached copy is used straight away, so the terminal has data on
     * the first frame and a download - when one is needed at all - only ever replaces figures
     * that were already usable.
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return

        val cached = file.load()
        if (prices.isEmpty() && cached.prices.isNotEmpty()) {
            prices = cached.prices.mapValues { (_, e) -> ShopPrice(e.price, e.npc, e.others) }
            etag = cached.etag
            SkyQuantMod.LOGGER.info("Loaded {} NPC shop prices from cache", prices.size)
        }

        thread(name = "skyquant-neu-refresh", isDaemon = true) {
            try {
                download()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun download() {
        val found = mutableMapOf<String, ShopPrice>()

        val result = GitHubArchive.walk(OWNER, REPO, BRANCH, etag) { entry ->
            if (!entry.path.endsWith(".json") || !entry.path.contains("/items/")) return@walk

            val json = runCatching { JsonParser.parseString(entry.readText()) }.getOrNull()
            val root = json as? JsonObject ?: return@walk
            val npcId = root.get("internalname")?.asString ?: return@walk

            for (offer in shopOffers(root)) {
                val (itemId, price) = offer
                val existing = found[itemId]

                // The cheapest seller sets the price, since that is where you would buy first,
                // but the others are counted rather than discarded: their stock is separate,
                // so they extend how much can be bought in a day.
                found[itemId] = when {
                    existing == null -> ShopPrice(price, npcId)
                    price < existing.pricePerUnit ->
                        ShopPrice(price, npcId, existing.otherSellers + 1)
                    else -> existing.copy(otherSellers = existing.otherSellers + 1)
                }
            }
        }

        when (result) {
            is GitHubArchive.Result.NotModified ->
                SkyQuantMod.LOGGER.debug("NEU repository unchanged, keeping {} cached prices", prices.size)

            is GitHubArchive.Result.Failed ->
                // Not an error worth bothering the player with: the cached index is still there,
                // and NPC prices are fixed shop prices rather than a moving market.
                SkyQuantMod.LOGGER.warn("NEU repository fetch failed, using cached prices", result.cause)

            is GitHubArchive.Result.Downloaded -> {
                if (found.isEmpty()) {
                    // A successful download that parsed to nothing means the repo's shape
                    // changed. Keeping the old index is better than serving an empty one.
                    SkyQuantMod.LOGGER.warn("NEU repository parsed to no shop prices, keeping previous index")
                    return
                }

                prices = found
                etag = result.etag
                save(found, result.etag)
                SkyQuantMod.LOGGER.info("Loaded {} NPC shop prices from the NEU repository", found.size)
            }
        }
    }

    private fun save(found: Map<String, ShopPrice>, newEtag: String?) {
        val cache = Cache()
        cache.etag = newEtag
        cache.prices = found.mapValues { (_, p) ->
            Entry().apply { price = p.pricePerUnit; npc = p.npcId; others = p.otherSellers }
        }.toMutableMap()

        file.save(cache)
    }

    /**
     * The coin-priced shop offers in one NPC's file, as itemId to price per unit.
     *
     * Offers costing items rather than coins are dropped: over half of them want coupons or
     * event tokens, which are not a profit measurable in coins.
     */
    private fun shopOffers(root: JsonObject): List<Pair<String, Double>> {
        val recipes = root.getAsJsonArray("recipes") ?: return emptyList()

        return recipes.mapNotNull { element ->
            val recipe = element as? JsonObject ?: return@mapNotNull null
            if (recipe.get("type")?.asString != "npc_shop") return@mapNotNull null

            val cost = recipe.getAsJsonArray("cost") ?: return@mapNotNull null
            if (cost.size() != 1) return@mapNotNull null

            val coins = coinAmount(cost[0].asString) ?: return@mapNotNull null
            val (itemId, count) = parseStack(recipe.get("result")?.asString ?: return@mapNotNull null)
            if (count <= 0) return@mapNotNull null

            itemId to coins / count
        }
    }

    /** "SKYBLOCK_COIN:8.0" -> 8.0, or null for anything paid in items. */
    private fun coinAmount(cost: String): Double? {
        if (!cost.startsWith(COIN_PREFIX)) return null
        return cost.removePrefix(COIN_PREFIX).toDoubleOrNull()
    }

    /** "ROTTEN_FLESH:1" -> id and count; a missing count means one. */
    private fun parseStack(stack: String): Pair<String, Double> {
        val separator = stack.lastIndexOf(':')
        if (separator < 0) return stack to 1.0

        val count = stack.substring(separator + 1).toDoubleOrNull() ?: return stack to 1.0
        return stack.substring(0, separator) to count
    }

    private const val COIN_PREFIX = "SKYBLOCK_COIN:"

    /** Exposed for tests: parses one NPC file's offers without touching the network. */
    internal fun offersForTest(json: String): List<Pair<String, Double>> =
        shopOffers(Gson().fromJson(json, JsonObject::class.java))
}
