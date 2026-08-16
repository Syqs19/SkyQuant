package dev.syqs.skyquant.feature.bazaar.data

/**
 * What a recipe is worth: buy the ingredients, make it, sell the result.
 *
 * **One level deep only.** The cost of an ingredient is its own market price, never "what it
 * would cost to craft *that*". Decided deliberately - it matches the action a player actually
 * takes, and the nested version needs cycle handling and a second figure in every row before it
 * can say anything the flat one doesn't.
 *
 * Forge recipes are the same trade with a slot occupied for a stated time, so they carry
 * [profitPerHour] and are ranked on it. Their durations run from 30 seconds to a week - a bare
 * profit column would put a week-long recipe above a thirty-second one earning ten times as much.
 */
object CraftProfit {

    /**
     * One priced recipe.
     *
     * [instantProfit] and [orderProfit] are separate trades rather than two guesses at one, the
     * same distinction [NpcFlipSummary] draws: instant is smaller and certain, an order is
     * larger and depends on somebody taking the other side. Both are net of [BazaarTax].
     */
    data class Craft(
        val outputId: String,
        val outputCount: Double,
        /**
         * What the ingredients cost if you place buy orders and wait for them to fill.
         *
         * Kept as the plain `cost` because it is the one the profit figures are ranked on, and
         * because it is what a player planning a craft actually pays - nobody buys 160 diamonds
         * at the instant price on purpose.
         */
        val cost: Double,
        /**
         * What the same ingredients cost bought outright, right now.
         *
         * Measured across 622 liquid products: the median gap is 0%, so for most items this
         * equals [cost] - but the tail is savage, and it falls exactly on the cheap raw materials
         * recipes use by the hundred. GRAVEL costs 19x more bought instantly than ordered.
         * That is why this is its own figure rather than a footnote: 8 of the 29 rows the Craft
         * page shows today turn into a loss when you buy the fast way.
         */
        val instantCost: Double,
        /** Profit buying and selling the fast way, i.e. the trade you can complete in a minute. */
        val instantProfit: Double,
        /** Profit with a buy order and a sell offer - cheaper to get in, more to get out, slower. */
        val orderProfit: Double,
        /**
         * Profit buying the ingredients outright and selling the result with a sell offer.
         *
         * The trade a **forge** recipe actually calls for, and the reason it is not simply one of
         * the two above. Those pair speed with speed and patience with patience, which is right
         * when a craft completes instantly: paying a premium to buy quickly and then queuing a
         * sell offer would hand back the speed you paid for.
         *
         * A forge slot changes that, because the wait is already there. Measured against the
         * recipes in the repo, forge durations run from 30 seconds to a week with a **median of
         * six hours** - so the minutes a sell offer takes are nothing next to the recipe itself,
         * while a buy order that has to fill delays the one thing that is genuinely scarce: the
         * moment the slot starts working.
         *
         * The two sides are also worth very different amounts. Across the liquid bazaar, buying
         * outright costs 0.2% more at the median, where a sell offer earns up to 6.7% more at the
         * 90th percentile. Haste is cheap on the way in and expensive on the way out, which is
         * exactly the trade this figure describes.
         *
         * Equal to [orderProfit] on an instant craft, where there is no wait to exploit.
         */
        val forgeProfit: Double,
        val durationSeconds: Long,
        /** The weakest link: how many of the scarcest ingredient trade in a week. */
        val weeklyVolume: Long,
        /** True when the output is priced from the auction house rather than the bazaar. */
        val fromAuction: Boolean = false,
        /**
         * True when the cheapest listing behind an auction price sits far below the rest.
         *
         * The figure is still the median of the four cheapest, so the row is sound - this says
         * the market underneath it is thin enough that one seller's mistake is visible in it.
         */
        val hasOutlierListing: Boolean = false,
    ) {
        val isForge: Boolean get() = durationSeconds > 0

        // Each margin divides by the cost its own trade pays, not by a shared one: the fast trade
        // puts up more money for less return, and dividing both by the order cost would hide half
        // of that difference.
        val instantMargin: Double get() = margin(instantProfit, instantCost)
        val orderMargin: Double get() = margin(orderProfit, cost)

        /** Against [instantCost], since that is the money this trade actually puts up. */
        val forgeMargin: Double get() = margin(forgeProfit, instantCost)

        private fun margin(profit: Double, against: Double): Double =
            if (against > 1e-9) profit / against * 100 else 0.0

        /**
         * Profit per hour, which is the only fair way to rank a forge recipe.
         *
         * Zero duration returns the profit itself rather than dividing: an instant craft has no
         * per-hour rate, and the alternative is a division by zero that sorts as infinity and
         * puts every crafting recipe above every forge one.
         */
        fun profitPerHour(profit: Double): Double =
            if (durationSeconds > 0) profit / (durationSeconds / 3600.0) else profit
    }

    /**
     * Prices [recipe], or null when any part of it has no market price.
     *
     * Null rather than a partial figure: a craft missing one ingredient's cost would report a
     * profit that is too high by exactly the amount nobody can see. Measured across the repo,
     * roughly 300 of 2528 crafting recipes price entirely on the bazaar - the rest involve items
     * no market trades, which is a property of the game rather than a limit of this code.
     *
     * [quoteFor] and [taxRate] are passed as functions so this can be tested without the game
     * running: a plain default is evaluated at the call site and would drag in the whole client.
     */
    fun price(
        recipe: Recipe,
        quoteFor: (String) -> BazaarLivePrices.Quote? = { BazaarLivePrices.quoteFor(it) },
        taxRate: () -> Double = { BazaarTax.rate.toDouble() },
        auctionFor: (String) -> AuctionSellPrice.Quote? = { AuctionSellPrice.quoteFor(it) },
    ): Craft? {
        if (recipe.ingredients.isEmpty() || recipe.outputCount <= 0) return null

        var orderCost = 0.0
        var instantCost = 0.0
        var scarcest = Long.MAX_VALUE

        for ((id, amount) in recipe.ingredients) {
            val quote = quoteFor(id) ?: return null

            // The cheapest standing ask - where a buy order fills - not `quick_status`, which can
            // quote a price nobody is offering. One stale order made SHARD_DRYBARK look ten times
            // better than it was.
            val orderUnit = quote.topAsk.takeIf { it > 0 } ?: return null

            // What buying it outright costs. Falls back to the ask when the summary has nothing,
            // which keeps a missing figure from reading as a free ingredient.
            val instantUnit = quote.buyPrice.takeIf { it > 0 } ?: orderUnit

            orderCost += orderUnit * amount
            instantCost += instantUnit * amount
            scarcest = minOf(scarcest, quote.weeklyVolume)
        }

        val output = quoteFor(recipe.outputId)
            ?: return auctionPriced(recipe, orderCost, instantCost, scarcest, auctionFor)

        // A sale is taxed, so both exits lose the same cut. sellPrice is the LOWER of the two and
        // is what buyers are bidding: it is what you receive selling instantly. topBid is where a
        // sell *offer* fills, which is the patient exit.
        val tax = taxRate()
        val instantUnit = output.sellPrice.takeIf { it > 0 } ?: return null
        val orderUnit = output.topBid.takeIf { it > 0 } ?: instantUnit

        val revenueInstant = instantUnit * recipe.outputCount * (1 - tax)
        val revenueOrder = orderUnit * recipe.outputCount * (1 - tax)

        // Each profit pairs the matching cost, so both describe a trade somebody could actually
        // make. They used to share the order cost, which made the "instant" figure a hybrid:
        // fast on the way out, patient on the way in, and true of no trade at all. It flattered
        // 8 of the 29 rows on the Craft page into showing a profit where buying the fast way
        // loses money - and flattering is the wrong direction to be wrong in.
        //
        // One of the two crosses *is* offered, and only where it describes a real trade: buying
        // outright and then selling on an offer is a mistake on an instant craft - you pay for
        // speed and immediately give it back - but it is the sensible play on a forge recipe,
        // where the wait is already there. See [Craft.forgeProfit].
        //
        // The other cross stays out. Placing a buy order and then dumping the result at the
        // standing bid is patient where it costs you and hasty where it pays, which is the wrong
        // way round on any recipe.
        return Craft(
            outputId = recipe.outputId,
            outputCount = recipe.outputCount,
            cost = orderCost,
            instantCost = instantCost,
            instantProfit = revenueInstant - instantCost,
            orderProfit = revenueOrder - orderCost,
            // Buy fast, sell patiently. Falls back to the order profit where there is no wait to
            // exploit, so an instant craft's two columns stay the coherent pair they were.
            forgeProfit = if (recipe.durationSeconds > 0) {
                revenueOrder - instantCost
            } else {
                revenueOrder - orderCost
            },
            durationSeconds = recipe.durationSeconds,
            // The ingredient that trades least is what caps how often this can actually be run.
            // Taking the output's own volume instead would flatter a recipe whose result sells
            // briskly but whose inputs nobody stocks.
            weeklyVolume = if (scarcest == Long.MAX_VALUE) 0 else scarcest,
        )
    }

    /**
     * The same craft where the output is sold at auction instead of on the bazaar.
     *
     * This is what roughly doubles the pages: 502 crafting recipes and 33 forge ones have
     * ingredients the bazaar prices and an output it doesn't - weapons, armour, talismans, most
     * forge plates. Without it every one of them was silently dropped.
     *
     * **One price, not two.** The bazaar has an instant side and a patient side because it has an
     * order book; an auction has neither. Listing is the only way to sell, and it fills when
     * somebody buys - so the same figure serves both columns, and the page's fast/patient pair
     * then describes only how the *ingredients* were bought. That is still a real distinction,
     * and it is the one that varies.
     */
    private fun auctionPriced(
        recipe: Recipe,
        orderCost: Double,
        instantCost: Double,
        scarcest: Long,
        auctionFor: (String) -> AuctionSellPrice.Quote?,
    ): Craft? {
        val auction = auctionFor(recipe.outputId) ?: return null
        val unit = auction.price.takeIf { it > 0 } ?: return null

        val gross = unit * recipe.outputCount
        // The auction's own tiered cut, which is more than double the bazaar's on an expensive
        // item - see AuctionSellPrice.taxFor. Applied per unit, since the tier is decided by the
        // listing's own price rather than by the value of the whole craft.
        val revenue = gross * (1 - AuctionSellPrice.taxFor(unit))

        return Craft(
            outputId = recipe.outputId,
            outputCount = recipe.outputCount,
            cost = orderCost,
            instantCost = instantCost,
            instantProfit = revenue - instantCost,
            orderProfit = revenue - orderCost,
            // With one revenue figure the mixed trade is simply "buy fast, then list", which is
            // what `instantProfit` already is - an auction has no patient exit to pair it with.
            // On a forge recipe that is still the figure worth showing, since the choice the row
            // presents is only ever about how the ingredients were bought.
            forgeProfit = if (recipe.durationSeconds > 0) {
                revenue - instantCost
            } else {
                revenue - orderCost
            },
            durationSeconds = recipe.durationSeconds,
            weeklyVolume = if (scarcest == Long.MAX_VALUE) 0 else scarcest,
            fromAuction = true,
            // Carried so the page can mark the row: the price rests on four listings, and one of
            // them being a mistake is a thing the reader should be able to see.
            hasOutlierListing = auction.isOutlier,
        )
    }
}
