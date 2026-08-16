package dev.syqs.skyquant.feature.bazaar.data

/**
 * The priced, ranked rows behind the Craft and Forge pages.
 *
 * Recipes come from [RecipeIndex] and prices from [BazaarLivePrices]; this is where the two meet
 * and where the rules about what is worth showing live. Both pages ask the same question - is
 * making this worth more than buying it - and differ only in what they rank on, so they share
 * everything except that.
 */
object CraftSummary {

    /**
     * Weekly volume below which a row is dropped.
     *
     * A recipe whose ingredients barely trade shows a wonderful margin that cannot be acted on:
     * the order simply never fills. The figure matches the one the plan measured its sample
     * against, and it is applied to the scarcest ingredient rather than the output, since that
     * is the side that has to be bought.
     */
    const val MIN_WEEKLY_VOLUME = 50_000L

    /**
     * How many rows a page holds.
     *
     * The list is ranked, so anything past this is a worse trade than something already on
     * screen - the cap is a floor under the scrolling, not a performance measure. The cost of a
     * refresh is pricing all 2528 recipes, which happens before the cap and so doesn't move with
     * it: measured at well under a millisecond against a 16.7ms frame.
     *
     * 100 rather than 60 because the number of profitable recipes moves with the market. Today
     * only about 30 clear their costs, so neither figure bites; the point is that when the market
     * turns and 80 do, the page shows them instead of hiding the tail behind an arbitrary line.
     */
    private const val MAX_ROWS = 100

    /**
     * Roughly how many rows fit on screen at once, used to decide when the page is settled enough
     * to stop chasing candidates that cannot reach it.
     *
     * Derived from the layout rather than picked: the panel is 296px tall, of which the title bar,
     * tab strip, column header and footer take about 96, leaving ~200px at
     * [dev.syqs.skyquant.feature.bazaar.gui.DataTable.ROW_HEIGHT] of 12 - about 16 rows, with a
     * few spare so a taller window doesn't fall below it.
     *
     * An estimate is the right shape here. It decides how eagerly the page fetches, not what it
     * shows, so being a couple of rows out costs a few requests either way and nothing else.
     */
    private const val VISIBLE_ROWS = 20

    /**
     * How many auction outputs one pass offers to the fetcher.
     *
     * **This is not the rate limit** - this runs once per frame, so a cap here is a cap per
     * frame. The real pacing is [dev.syqs.skyquant.util.CoflnetRateLimit], which counts requests
     * over time and is shared with every other Coflnet caller; this number only decides how far
     * down the candidate list one pass looks.
     *
     * Lowered from 12 after watching it in play: a browse through these pages would fill the
     * ten-second budget by itself, and then opening a chart - one request - found nothing left
     * and had to wait for it. A chart the player is looking at right now cannot wait, so the
     * shared budget is left with room in it.
     *
     * Eight rather than six: the budget allows 24 requests per ten seconds and the frames come far
     * faster than that, so this number stopped being the binding constraint the moment
     * [dev.syqs.skyquant.util.CoflnetRateLimit] started counting over time. What it still decides
     * is how deep into the candidate list one pass looks, and a slightly longer look means fewer
     * passes wasted re-walking recipes already answered.
     */
    private const val AUCTION_REQUESTS_PER_PASS = 8

    /**
     * The last ranking handed out, and the market it was computed from.
     *
     * Screens ask for these lists from `extractRenderState`, i.e. **once per frame**, while the
     * answer can only change when a new bazaar snapshot lands (about once a minute) or when an
     * auction price arrives. Measured over 2528 recipes with pricing stubbed out: **146us a
     * call**, which at 60fps is 8.8ms a second - over half of a single 16.7ms frame's budget,
     * spent rebuilding a list identical to the one already on screen. The real cost is higher,
     * since the live [CraftProfit.price] walks each recipe's ingredients instead of reading a map.
     *
     * Keyed on both counters because both change the answer, and neither implies the other -
     * see [AuctionSellPrice.answerCount] for why the bazaar's version alone would freeze the
     * Craft page between snapshots.
     */
    private class Ranking(
        val snapshotVersion: Long,
        val answerCount: Long,
        /**
         * The exact list this was computed from, compared by identity.
         *
         * [RecipeIndex] hands out a stable list and replaces it when a download lands, so an
         * index that has been rebuilt fails this check even though neither price counter moved -
         * without it, a refreshed recipe set would be ignored until the next snapshot.
         */
        val source: List<Recipe>,
        val rows: List<CraftProfit.Craft>,
    )

    @Volatile
    private var cachedCrafts: Ranking? = null

    @Volatile
    private var cachedForges: Ranking? = null

    /**
     * Whether [cached] still describes the current market and was built from [recipes].
     *
     * Null on a miss, which is also what a caller passing its own recipes or pricing gets: a test
     * driving explicit inputs must never be answered from a ranking computed for the live bazaar.
     */
    private fun reusable(cached: Ranking?, recipes: List<Recipe>): List<CraftProfit.Craft>? = cached
        ?.takeIf {
            it.source === recipes &&
                it.snapshotVersion == BazaarLivePrices.snapshotVersion &&
                it.answerCount == AuctionSellPrice.answerCount
        }
        ?.rows

    /**
     * The instant crafts, best first.
     *
     * Ranked on the order profit rather than the instant one: crafting is not a race, and the
     * patient exit is the one a player choosing what to make would act on. Both figures are
     * carried in the row, so the page can show them side by side.
     *
     * The result is cached against the market it was priced from, so calling this every frame
     * costs a pair of comparisons once the first pass has run.
     */
    fun crafts(
        recipes: List<Recipe> = RecipeIndex.craftingRecipes(),
        minVolume: Long = MIN_WEEKLY_VOLUME,
        price: (Recipe) -> CraftProfit.Craft? = { CraftProfit.price(it) },
    ): List<CraftProfit.Craft> {
        // Only the live configuration may be served from cache. A caller supplying its own
        // recipes or pricing is asking a different question, and answering it with the bazaar's
        // ranking would be wrong in exactly the way a test exists to catch.
        val live = recipes === RecipeIndex.craftingRecipes()
        if (live) reusable(cachedCrafts, recipes)?.let { return it }

        // Ranked first, then used to decide what is still worth asking about: the weakest row on
        // a full page is the bar a new candidate has to clear.
        val ranked = recipes
            .mapNotNull(price)
            .filter { it.weeklyVolume >= minVolume && it.orderProfit > 0 }
            .sortedByDescending { it.orderProfit }
            .take(MAX_ROWS)

        // Outside the cache check on purpose: this is what *makes* the auction answers arrive, so
        // skipping it on a hit would stall the very sweep that fills the page. It is cheap by
        // comparison - a sequence that stops at the profit ceiling - and self-throttling, since
        // AuctionSellPrice ignores anything already answered or in flight.
        requestAuctionPrices(recipes, ranked)

        if (live) {
            cachedCrafts = Ranking(
                BazaarLivePrices.snapshotVersion,
                AuctionSellPrice.answerCount,
                recipes,
                ranked,
            )
        }

        return ranked
    }

    /**
     * The forge recipes, best first, ranked on **profit per hour**.
     *
     * The ordering is nothing like ranking on profit: measured, Tungsten Key makes 259k in
     * 30 seconds where Gleaming Crystal makes 11.65M in six hours, so the smaller profit is
     * sixteen times the better trade. A bare profit column would recommend the wrong one.
     *
     * No volume floor by default. Forge outputs are slow, expensive items that trade in tens
     * rather than tens of thousands, so the crafting threshold would empty the page - and unlike
     * a craft, a forge recipe you can only run a few times a day is still worth running.
     */
    fun forges(
        recipes: List<Recipe> = RecipeIndex.forgeRecipes(),
        price: (Recipe) -> CraftProfit.Craft? = { CraftProfit.price(it) },
    ): List<CraftProfit.Craft> {
        val live = recipes === RecipeIndex.forgeRecipes()
        if (live) reusable(cachedForges, recipes)?.let { return it }

        val ranked = recipes
            .mapNotNull(price)
            .filter { it.orderProfit > 0 }
            .sortedByDescending { it.profitPerHour(it.orderProfit) }
            .take(MAX_ROWS)

        // The bar is read off `orderProfit` here too, even though the page ranks on profit per
        // hour: the ceiling compares against an ingredient cost, which is a sum of coins, and a
        // rate per hour is not. With only 33 forge outputs the page fills in seconds anyway, so
        // this is a safeguard rather than the fix crafting needed.
        requestAuctionPrices(recipes, ranked)

        if (live) {
            cachedForges = Ranking(
                BazaarLivePrices.snapshotVersion,
                AuctionSellPrice.answerCount,
                recipes,
                ranked,
            )
        }

        return ranked
    }

    /**
     * Asks the auction house about the outputs the bazaar can't price - a few at a time.
     *
     * The circular problem this solves: ranking needs prices, prices need requests, and there are
     * **533 such outputs** - 502 of them on the Craft page alone, against the forge's 33.
     *
     * Two things keep that from being a wait. The candidates are ordered by something already
     * known without asking anyone - **what their ingredients cost** - so the dearest go first: an
     * expensive recipe is not necessarily a profitable one, but it is where the large profits
     * live, since a talisman costing 40M can clear millions where one costing 400 coins cannot,
     * whatever its margin. And [profitCeiling] stops the sweep once the rest cannot reach the
     * page at all.
     *
     * That second part is what fixed the Craft page. Measured against the shared budget of 24
     * requests per ten seconds, asking about all 502 is **around three and a half minutes**, which
     * is what a player saw: the forge settled in seconds while crafting appeared to hang. Stopping
     * at the bar cuts it to the handful that can actually change what is on screen.
     *
     * Recipes whose output the bazaar prices never reach here, and cost nothing.
     */
    private fun requestAuctionPrices(recipes: List<Recipe>, ranked: List<CraftProfit.Craft>) {
        // Without a price snapshot every output looks unpriceable, so this would fire requests
        // for items the bazaar may well cover once the first snapshot lands.
        if (BazaarLivePrices.allQuotes().isEmpty()) return

        val ceiling = profitCeiling(ranked)

        recipes.asSequence()
            .filter { BazaarLivePrices.quoteFor(it.outputId) == null }
            .filterNot { AuctionSellPrice.hasAnswerFor(it.outputId) }
            .mapNotNull { recipe -> ingredientCost(recipe)?.let { recipe.outputId to it } }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            // Stops as soon as the candidates can no longer reach the page. Sorted by ingredient
            // cost descending, so the first one that cannot clear the bar means none after it can
            // either - see [profitCeiling] for what the bar is.
            .takeWhile { (_, cost) -> cost >= ceiling }
            .take(AUCTION_REQUESTS_PER_PASS)
            .forEach { (outputId, _) -> AuctionSellPrice.refreshIfStale(outputId) }
    }

    /**
     * The ingredient cost below which an unpriced recipe cannot reach the visible page, so asking
     * about it would spend a request on a row nobody will see.
     *
     * **This is what stops the Craft page taking minutes to settle.** There are 502 unpriced
     * crafting outputs against 33 on the forge - fifteen times as many - and the previous version
     * asked about every one of them at the shared budget's pace. The forge finished in seconds
     * and crafting took minutes, for no reason the player could see.
     *
     * The reasoning: an item's auction price is at least what its ingredients cost, so a recipe
     * whose ingredients cost less than the weakest *visible* row's profit cannot beat it however
     * the auction prices it. That is deliberately generous - it assumes the output sells for its
     * ingredients plus the entire profit, i.e. the best case - so it only rules out candidates
     * that genuinely cannot compete.
     *
     * **[VISIBLE_ROWS], not [MAX_ROWS].** Only about 30 recipes clear their costs in a typical
     * market, so a page that waits to hold 100 rows never fills and the bar stays at zero
     * forever - which is the version of this that would have changed nothing. What matters is
     * what the player can actually see without scrolling; rows below that keep filling in on
     * later passes, they just stop delaying the ones on screen.
     */
    internal fun profitCeiling(ranked: List<CraftProfit.Craft>): Double {
        if (ranked.size < VISIBLE_ROWS) return 0.0

        return ranked[VISIBLE_ROWS - 1].orderProfit
    }

    /**
     * What a recipe's ingredients cost, or null when the bazaar can't price them all.
     *
     * Null matters: a recipe with an unpriceable ingredient can never produce a row, so spending
     * one of the pass's requests on its output would be wasted.
     */
    private fun ingredientCost(recipe: Recipe): Double? {
        var total = 0.0

        for ((id, amount) in recipe.ingredients) {
            val ask = BazaarLivePrices.quoteFor(id)?.topAsk?.takeIf { it > 0 } ?: return null
            total += ask * amount
        }

        return total
    }
}
