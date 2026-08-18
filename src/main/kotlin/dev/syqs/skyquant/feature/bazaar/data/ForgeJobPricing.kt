package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.feature.bazaar.ProductName

/**
 * Prices a forge job from its recipe: what its ingredients cost, and how long it runs.
 *
 * Split from [ForgeLedger] so the ledger stays about *remembering* and this stays about *looking
 * up* - and so both can be tested without the other.
 */
object ForgeJobPricing {

    /**
     * What the ingredients of one forge output cost to buy right now, or null if nothing prices it.
     *
     * `topAsk` rather than the summary buy price, matching what the Craft and Forge pages already
     * use: it is what an order actually fills at, and the summary can quote a price nobody is
     * offering.
     *
     * Null when *any* ingredient can't be priced, not zero for the missing one: a partial cost
     * would understate what the job took and inflate every profit computed from it.
     */
    fun ingredientCost(
        outputName: String,
        recipes: List<Recipe> = RecipeIndex.forgeRecipes(),
        quoteOf: (String) -> BazaarLivePrices.Quote? = { BazaarLivePrices.quoteFor(it) },
    ): Long? {
        val recipe = recipeFor(outputName, recipes) ?: return null

        var total = 0.0
        for ((id, amount) in recipe.ingredients) {
            val unit = quoteOf(id)?.topAsk?.takeIf { it > 0 } ?: return null
            total += unit * amount
        }
        return total.toLong()
    }

    /** How long the recipe takes, in millis, or null when the recipe isn't known. */
    fun durationMillis(outputName: String, recipes: List<Recipe> = RecipeIndex.forgeRecipes()): Long? =
        recipeFor(outputName, recipes)?.durationSeconds?.times(1000L)

    /**
     * The forge recipe producing an item the tab list named.
     *
     * The widget writes what a player reads ("Tungsten Plate") while recipes are keyed by id, so
     * the name is converted back. Matching on the derived id rather than searching by display name
     * keeps this to a map lookup, and an item whose name doesn't round-trip simply comes back null
     * - which surfaces as "not priced" rather than as a wrong recipe.
     */
    private fun recipeFor(outputName: String, recipes: List<Recipe>): Recipe? {
        val id = ProductName.idOf(outputName)
        return recipes.firstOrNull { it.outputId == id }
    }
}
