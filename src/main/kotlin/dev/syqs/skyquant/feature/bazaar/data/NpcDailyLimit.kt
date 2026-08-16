package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.util.JsonFile
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * How many units of an item an NPC shop will still sell.
 *
 * Turns a per-unit margin into what a shop's whole stock is worth, which is the figure that
 * decides whether a trip is worth making: a 200-coin margin on something capped at 640 units is
 * a different proposition from the same margin on something you can buy all day.
 *
 * **The limit is per shop *and* per item.** Tested in-game: buying out the Mine Merchant's iron
 * leaves the Iron Forger's iron untouched, so two shops selling the same item carry two
 * separate stocks. The wiki's claim that merchants share a pool did not survive that test,
 * which is why [forProduct] multiplies by the number of sellers rather than trusting it.
 *
 * That also means rows are independent of each other: a total on one row does not eat into
 * another's.
 *
 * **The figure counts units, not purchases.** Shops sell in stacks - the Mine Merchant lists
 * Gold Ingot ×2 and Torch ×16 - and buying one of those draws that many units off the 640.
 * Confirmed in-game. So a per-unit profit multiplies by this number directly, with no stack
 * size involved; treating it as a purchase count would have overstated torches sixteenfold.
 *
 * Two sources, in order of trust:
 *
 * 1. **What the shop said.** Entries carry `Stock: 640 remaining`, read whenever the player
 *    opens a shop and remembered afterwards. Better than the default in two ways: it is that
 *    item's real figure, and it is what is *left today* rather than the daily maximum.
 * 2. **The documented default**, 640 a day, which the game applies to nearly everything. No
 *    shop needs to have been opened for this to be right - the reading above only refines it.
 *
 * Mayor Diaz's Shopping Spree multiplies shop limits tenfold. It applies to the default only:
 * a stock line read while he is in office already has the larger number in it.
 */
object NpcDailyLimit {

    /** Ten stacks, the documented limit for a normal day. */
    const val STANDARD = 640

    /** Mayor Diaz's Shopping Spree raises shop limits tenfold. */
    const val SHOPPING_SPREE_MULTIPLIER = 10

    private class Stored {
        var stock: MutableMap<String, Int> = mutableMapOf()

        /**
         * The shop day these readings belong to, as a GMT date.
         *
         * Shop stock resets at 00:00 GMT, so yesterday's readings describe a shop that has
         * since refilled. Without this the mod would keep reporting "412 left" on a shop back
         * up to 640 - worse than the assumed default, because the marker claims it was read.
         */
        var day: String = ""
    }

    /** Today in GMT, which is the clock the reset runs on. */
    private fun currentDay(): String =
        LocalDate.now(ZoneOffset.UTC).toString()

    private val file = JsonFile.of("npc_stock", { Stored() })

    @Volatile
    private var stock: Map<String, Int> = emptyMap()

    /**
     * Volatile alongside [stock], because the two are read from different threads.
     *
     * [forProduct] and [isKnown] are called while a row is drawn, i.e. on the render thread, while
     * [recordStock] runs from the screen-event callback that scans an open shop. Without this,
     * one thread could see `loaded = true` before the other's write to [stock] became visible and
     * read an empty map - reporting the assumed 640 for an item whose real stock had just been
     * read off the shop, which is the one case this class exists to improve on.
     */
    @Volatile
    private var loaded = false

    /** The default when a shop hasn't been seen, Diaz included. */
    val default: Int
        get() = STANDARD * if (shoppingSpree) SHOPPING_SPREE_MULTIPLIER else 1

    /**
     * Units buyable for [productId] in a day - what the shop said if it has ever been open,
     * otherwise the default, multiplied by how many shops sell it.
     *
     * [sellers] is the number of shops carrying the item, each with its own stock. For the 42
     * items with more than one seller, ignoring this understates a day's buying by a whole
     * shop's worth.
     */
    fun forProduct(productId: String, sellers: Int = 1): Int {
        ensureLoaded()
        val perShop = stock[productId.uppercase()] ?: default
        return perShop * sellers.coerceAtLeast(1)
    }

    /** True when this item's figure was read from a shop rather than assumed. */
    fun isKnown(productId: String): Boolean {
        ensureLoaded()
        return stock.containsKey(productId.uppercase())
    }

    /**
     * Records what a shop entry stated. Ignored when unchanged, so this can be called every
     * frame a shop is open without rewriting the file each time.
     *
     * Synchronised because it is a read-modify-write on [stock]: the map is replaced rather than
     * mutated, so two entries recorded at once would each build their new map from the same
     * starting point and the second would drop the first. A shop window holds dozens of items and
     * they are scanned in one pass, which is exactly when that collision is likely.
     */
    @Synchronized
    fun recordStock(productId: String, units: Int) {
        ensureLoaded()
        val id = productId.uppercase()
        if (stock[id] == units) return

        val updated = stock + (id to units)
        stock = updated

        // Held in a local first. Written as `Stored().apply { stock = stock ... }` the name
        // resolves to the receiver's own field on both sides, so the object was assigned its
        // own empty map and every reading was saved as `{"stock":{}}` - which compiles clean
        // and looks right until you open the file.
        val snapshot = Stored()
        snapshot.stock = updated.toMutableMap()
        snapshot.day = currentDay()
        file.save(snapshot)
    }

    private val shoppingSpree: Boolean
        get() = SkyQuantConfigManager.config.bazaar.shoppingSpree

    /**
     * Reads the file once, on whichever thread asks first.
     *
     * Synchronised and with the flag set *after* the load, not before: the earlier version marked
     * itself loaded first, so a second thread arriving mid-read returned immediately and used the
     * empty map as though it were the answer.
     */
    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return

        val saved = file.load()

        // Readings from an earlier shop day describe shops that have since refilled. Dropped
        // rather than shown, since a stale figure marked as "read from the shop" is worse than
        // the honest default - it invites more trust than the assumed number, not less.
        stock = if (saved.day == currentDay()) saved.stock else emptyMap()
        loaded = true
    }

    /** Reset for tests, which must not inherit readings left behind by another test. */
    internal fun forgetForTest() {
        stock = emptyMap()
        loaded = true
    }

    /**
     * Drops the in-memory copy so the next read comes off disk, for the test that checks a
     * reading actually survives a restart.
     */
    internal fun reloadFromDiskForTest() {
        stock = emptyMap()
        loaded = false
    }

    /**
     * What a fresh start would read for this item, without the config lookup [forProduct]
     * makes. Goes through the same day check as the real load, so a test can't pass on data
     * the game itself would discard.
     */
    internal fun savedStockForTest(productId: String): Int? {
        val saved = file.load()
        if (saved.day != currentDay()) return null
        return saved.stock[productId.uppercase()]
    }

    /** Writes a reading dated to an earlier day, for the reset test. */
    internal fun recordStaleReadingForTest(productId: String, units: Int) {
        val snapshot = Stored()
        snapshot.stock = mutableMapOf(productId.uppercase() to units)
        snapshot.day = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
        file.save(snapshot)
        reloadFromDiskForTest()
    }
}
