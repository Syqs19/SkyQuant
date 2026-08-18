package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.JsonFile

/**
 * What each item's icon is drawn from, keyed by Skyblock id.
 *
 * Filled during [RecipeIndex]'s pass over the NEU archive rather than by a walk of its own. That
 * pass already opens and parses all 8745 item files, so collecting three more fields from each is
 * effectively free, where a second walk would mean downloading the same 9MB again for data that
 * was in hand the first time.
 *
 * The consequence is that this index is written by [RecipeIndex] and only read here. It carries
 * its own cache file all the same: the two have separate [FORMAT_VERSION]s, so a fix to the icon
 * parser can force icons to be re-read without discarding a recipe index that was already
 * correct.
 */
object ItemIconIndex {

    /**
     * Bumped when the parser starts reading something it previously ignored.
     *
     * Same reasoning as [RecipeIndex]'s: without it, a cache written by an older build survives
     * forever, because the etag still matches the repo and nothing is re-downloaded.
     */
    private const val FORMAT_VERSION = 2

    private class Cache {
        var version: Int = 0
        var icons: MutableMap<String, Stored> = mutableMapOf()
    }

    private class Stored {
        var itemId: String = ""
        var damage: Int = 0
        var model: String? = null
        var skull: String? = null
        var name: String? = null
        var glint: Boolean = false
    }

    private val file = JsonFile.of("item_icons", { Cache() })

    @Volatile
    private var icons: Map<String, ItemIconData> = emptyMap()

    val isLoaded: Boolean get() = icons.isNotEmpty()

    val size: Int get() = icons.size

    /**
     * Run when the index is replaced, so anything derived from it can be rebuilt.
     *
     * A callback rather than a direct call because the thing that needs telling is the drawing
     * side, which holds built item stacks - and this package is read by the screens, never the
     * other way round. Without it a repo update would leave every icon on screen built from the
     * data it replaced.
     */
    @Volatile
    var onReplaced: (() -> Unit)? = null

    /** What to draw for [itemId], or null when the repo has never described it. */
    fun iconFor(itemId: String): ItemIconData? = icons[key(itemId)]

    /**
     * The repository's key for a bazaar product id.
     *
     * The two disagree on one point of punctuation: Hypixel trades the damage variants as
     * `INK_SACK:3`, where the repo files them as `INK_SACK-3`. Ten of the 2124 bazaar products
     * are affected, and without this they simply aren't found - which is what left Cocoa Beans
     * and Red Sand as the only rows on the Flip page with no icon.
     */
    private fun key(itemId: String): String = itemId.uppercase().replace(':', '-')

    /**
     * What Hypixel calls [itemId], or null when the repo doesn't say.
     *
     * The reason this index carries names at all: ten bazaar products are damage variants whose
     * id says nothing about what they are - `INK_SACK:3` is Cocoa Beans, `SAND:1` is Red Sand -
     * and no rule applied to the id can recover that.
     */
    fun nameFor(itemId: String): String? = icons[key(itemId)]?.displayName

    /** Loads the cached index, if this build wrote it. Cheap enough to call on the client thread. */
    fun loadFromCache() {
        if (icons.isNotEmpty()) return

        val cached = file.load()
        if (cached.version != FORMAT_VERSION || cached.icons.isEmpty()) return

        icons = cached.icons.mapValues { (_, stored) ->
            ItemIconData(stored.itemId, stored.damage, stored.model, stored.skull, stored.name, stored.glint)
        }

        SkyQuantMod.LOGGER.info("Loaded {} item icons from cache", icons.size)
    }

    /**
     * Replaces the index with what a repository pass found, and writes it to disk.
     *
     * An empty result is refused rather than stored. A download that parsed to nothing means the
     * repo's shape changed, and an empty index would leave every row without an icon while
     * looking exactly like a successful refresh - the same guard [RecipeIndex] keeps.
     */
    fun replace(found: Map<String, ItemIconData>) {
        if (found.isEmpty()) {
            SkyQuantMod.LOGGER.warn("NEU repository parsed to no item icons, keeping previous index")
            return
        }

        icons = found
        save(found)
        onReplaced?.invoke()
        SkyQuantMod.LOGGER.info("Loaded {} item icons from the NEU repository", found.size)
    }

    /** Puts an index in place without a network, for tests. Pass an empty map to clear it. */
    internal fun loadForTest(entries: Map<String, ItemIconData>) {
        icons = entries
    }

    private fun save(found: Map<String, ItemIconData>) {
        val cache = Cache()
        cache.version = FORMAT_VERSION
        cache.icons = found.mapValuesTo(mutableMapOf()) { (_, icon) ->
            Stored().also {
                it.itemId = icon.itemId
                it.damage = icon.damage
                it.model = icon.itemModel
                it.skull = icon.skullTexture
                it.name = icon.displayName
                it.glint = icon.enchanted
            }
        }

        file.save(cache)
    }
}
