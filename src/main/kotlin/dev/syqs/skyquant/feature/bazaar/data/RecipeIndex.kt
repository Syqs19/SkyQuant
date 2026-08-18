package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.GitHubArchive
import dev.syqs.skyquant.util.JsonFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Every craftable and forgeable recipe, from the NEU data repository.
 *
 * The same source and the same mechanics as [NpcShopPrices] - one streaming pass over the repo
 * tarball, a small derived index cached on disk, and an etag so an unchanged repo answers in a
 * few hundred bytes rather than 9MB. Recipes change only when Hypixel adds or rebalances one, so
 * the cached copy is almost always current.
 *
 * Prices are deliberately *not* stored here. They move by the minute and come from
 * [BazaarLivePrices]; this index holds only the shapes, which change with a game update.
 */
object RecipeIndex {

    private const val OWNER = "NotEnoughUpdates"
    private const val REPO = "NotEnoughUpdates-REPO"
    private const val BRANCH = "master"

    /**
     * Bumped whenever the parser starts reading something it previously ignored.
     *
     * Without it a cache written by an older build survives forever: the etag would still match
     * the repo, so nothing would be re-downloaded and the new parsing would never run. That is
     * exactly how the untyped-recipe fix could have shipped and done nothing for existing users.
     */
    private const val FORMAT_VERSION = 1

    private class Cache {
        var version: Int = 0
        var etag: String? = null
        var recipes: MutableList<Stored> = mutableListOf()
    }

    private class Stored {
        var output: String = ""
        var count: Double = 1.0
        var duration: Long = 0
        var ingredients: MutableMap<String, Double> = mutableMapOf()
    }

    private val file = JsonFile.of("recipes", { Cache() })

    /**
     * The two lists, split once when [recipes] is replaced rather than on every call.
     *
     * Both are read from `extractRenderState`, i.e. once per frame, and each call used to run a
     * `filter` over all 2528 recipes and allocate a fresh list for the result. Splitting on write
     * happens twice a session; splitting on read happened a hundred times a second.
     *
     * Holding them also gives each list a **stable identity**, which is what lets [CraftSummary]
     * tell "the live recipe index" apart from a list a test handed it - a cached ranking must
     * never answer a question asked about different inputs.
     *
     * Declared before [recipes] on purpose: the setter below writes to it, and a property
     * initialised later would overwrite that first split with an empty pair.
     */
    @Volatile
    private var split: Pair<List<Recipe>, List<Recipe>> = emptyList<Recipe>() to emptyList()

    @Volatile
    private var recipes: List<Recipe> = emptyList()
        set(value) {
            field = value
            // Kept in step with the list itself, so the two can never disagree about what is a
            // forge recipe - and so no caller has to remember to re-split after assigning.
            split = value.filter { !it.isForge } to value.filter { it.isForge }
        }

    @Volatile
    private var etag: String? = null

    private val refreshing = AtomicBoolean(false)

    val isLoaded: Boolean get() = recipes.isNotEmpty()

    val size: Int get() = recipes.size

    /** Every crafting recipe, i.e. the ones that complete instantly. */
    fun craftingRecipes(): List<Recipe> = split.first

    /** Every forge recipe, each occupying a slot for its own duration. */
    fun forgeRecipes(): List<Recipe> = split.second

    /**
     * Puts an index in place without a network, so the split can be tested.
     *
     * Tests must pass `emptyList()` afterwards: this is shared mutable state on an object, and an
     * index left behind would reach whichever test ran next.
     */
    internal fun loadForTest(entries: List<Recipe>) {
        recipes = entries
    }

    /**
     * Loads the cached index and checks GitHub for a newer one on a background thread.
     *
     * Returns immediately, so a page opening for the first time draws from cache rather than
     * waiting on a download.
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return

        val cached = file.load()
        if (recipes.isEmpty() && cached.version == FORMAT_VERSION && cached.recipes.isNotEmpty()) {
            recipes = cached.recipes.map {
                Recipe(it.output, it.count, it.ingredients.toMap(), it.duration)
            }
            etag = cached.etag
            SkyQuantMod.LOGGER.info("Loaded {} recipes from cache", recipes.size)
        } else if (cached.version != FORMAT_VERSION && cached.recipes.isNotEmpty()) {
            // Deliberately drops the etag too, so the next request is unconditional and the repo
            // is re-read with the current parser rather than answered with a 304.
            SkyQuantMod.LOGGER.info("Recipe cache was written by an older format, rebuilding")
        }

        // Asked here rather than left to the caller: this runs from the world-join warmup and
        // again whenever the terminal opens, and the check below is only meaningful once the
        // icons have had their chance to come off disk. Reading it in one place is also what
        // stops the terminal re-downloading the archive on every open.
        ItemIconIndex.loadFromCache()

        // The icons are filled by this pass, so a player whose recipes are already cached would
        // otherwise never get them: the etag would match, GitHub would answer 304, and there
        // would be no archive to walk. Dropping the etag costs one 9MB download, once, for
        // anyone upgrading - and without it the icons simply never appear for them.
        if (!ItemIconIndex.isLoaded) {
            SkyQuantMod.LOGGER.info("No item icons cached, re-reading the repository to collect them")
            etag = null
        }

        thread(name = "skyquant-recipes-refresh", isDaemon = true) {
            try {
                download()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun download() {
        val found = mutableListOf<Recipe>()

        // Collected on the same pass and handed to [ItemIconIndex] below. Every file here is
        // already open and parsed, so reading three more fields costs nothing, where letting that
        // index walk the archive itself would download the same 9MB a second time.
        val icons = mutableMapOf<String, ItemIconData>()

        val result = GitHubArchive.walk(OWNER, REPO, BRANCH, etag) { entry ->
            if (!entry.path.endsWith(".json") || !entry.path.contains("/items/")) return@walk

            val root = runCatching { JsonParser.parseString(entry.readText()) }
                .getOrNull() as? JsonObject ?: return@walk

            // The file's own name is the fallback output: most recipes don't state one, and an
            // untyped recipe never does.
            val itemId = root.get("internalname")?.takeIf { !it.isJsonNull }?.asString
                ?: entry.path.substringAfterLast('/').removeSuffix(".json")

            found += Recipe.parseAll(root, itemId)

            ItemIconData.parse(root)?.let { icons[itemId.uppercase()] = it }
        }

        when (result) {
            is GitHubArchive.Result.NotModified ->
                SkyQuantMod.LOGGER.debug("NEU repository unchanged, keeping {} cached recipes", recipes.size)

            is GitHubArchive.Result.Failed ->
                SkyQuantMod.LOGGER.warn("NEU repository fetch failed, using cached recipes", result.cause)

            is GitHubArchive.Result.Downloaded -> {
                // Handed over before the guard below, because the two indexes fail independently:
                // a change to how recipes are written would empty `found` while every icon on the
                // same pass parsed correctly, and returning early would throw those away too.
                // ItemIconIndex keeps the matching guard for its own empty case.
                ItemIconIndex.replace(icons)

                if (found.isEmpty()) {
                    // A download that parsed to nothing means the repo's shape changed. An empty
                    // index would empty both pages while looking like a working refresh.
                    SkyQuantMod.LOGGER.warn("NEU repository parsed to no recipes, keeping previous index")
                    return
                }

                recipes = found
                etag = result.etag
                save(found, result.etag)
                SkyQuantMod.LOGGER.info(
                    "Loaded {} recipes from the NEU repository ({} forge)",
                    found.size,
                    found.count { it.isForge },
                )
            }
        }
    }

    private fun save(found: List<Recipe>, newEtag: String?) {
        val cache = Cache()
        cache.version = FORMAT_VERSION
        cache.etag = newEtag
        // Built as locals then assigned: `Stored().apply { ingredients = ingredients.toMutableMap() }`
        // resolves both sides to the receiver's own field and silently saves an empty map.
        cache.recipes = found.mapTo(mutableListOf()) { recipe ->
            val stored = Stored()
            stored.output = recipe.outputId
            stored.count = recipe.outputCount
            stored.duration = recipe.durationSeconds
            stored.ingredients = recipe.ingredients.toMutableMap()
            stored
        }

        file.save(cache)
    }
}
