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

    @Volatile
    private var recipes: List<Recipe> = emptyList()

    @Volatile
    private var etag: String? = null

    private val refreshing = AtomicBoolean(false)

    val isLoaded: Boolean get() = recipes.isNotEmpty()

    val size: Int get() = recipes.size

    /** Every crafting recipe, i.e. the ones that complete instantly. */
    fun craftingRecipes(): List<Recipe> = recipes.filter { !it.isForge }

    /** Every forge recipe, each occupying a slot for its own duration. */
    fun forgeRecipes(): List<Recipe> = recipes.filter { it.isForge }

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

        val result = GitHubArchive.walk(OWNER, REPO, BRANCH, etag) { entry ->
            if (!entry.path.endsWith(".json") || !entry.path.contains("/items/")) return@walk

            val root = runCatching { JsonParser.parseString(entry.readText()) }
                .getOrNull() as? JsonObject ?: return@walk

            // The file's own name is the fallback output: most recipes don't state one, and an
            // untyped recipe never does.
            val itemId = root.get("internalname")?.takeIf { !it.isJsonNull }?.asString
                ?: entry.path.substringAfterLast('/').removeSuffix(".json")

            found += Recipe.parseAll(root, itemId)
        }

        when (result) {
            is GitHubArchive.Result.NotModified ->
                SkyQuantMod.LOGGER.debug("NEU repository unchanged, keeping {} cached recipes", recipes.size)

            is GitHubArchive.Result.Failed ->
                SkyQuantMod.LOGGER.warn("NEU repository fetch failed, using cached recipes", result.cause)

            is GitHubArchive.Result.Downloaded -> {
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
