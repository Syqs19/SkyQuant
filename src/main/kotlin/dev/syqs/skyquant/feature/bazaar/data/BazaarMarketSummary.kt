package dev.syqs.skyquant.feature.bazaar.data

/**
 * Rankings computed from the current bazaar snapshot.
 *
 * Everything here comes from the one live snapshot, so it can only describe the market *now*.
 * Questions like "what rose the most today" need a history of every product, which Hypixel
 * doesn't expose and Coflnet only serves one item at a time - answering those would mean
 * recording our own snapshots over time.
 */
object BazaarMarketSummary {

    /**
     * One order-to-order flip: buy with an order, sell with an offer, keep the difference less
     * the bazaar's cut.
     *
     * This is the trade people actually make. Buying and selling instantly crosses the spread
     * twice and clears almost nothing, which is why the old spread ranking flattered items
     * nobody could profit from.
     */
    data class Flip(
        val productId: String,
        /** Where a buy order fills: the cheapest standing ask. */
        val buyAt: Double,
        /** Where a sell offer fills: the best standing bid. */
        val sellAt: Double,
        /** Units queued at those two prices - how much room there is before the price moves. */
        val buyDepth: Long,
        val sellDepth: Long,
        val weeklyVolume: Long,
        /** Coins kept per unit after tax. */
        val profitPerUnit: Double,
    ) {
        /** Profit against what it costs to get in, so cheap and expensive items compare. */
        val marginPercent: Double
            get() = if (buyAt > 1e-9) profitPerUnit / buyAt * 100 else 0.0
    }

    /**
     * Flip candidates, best margin first.
     *
     * Priced from the **order book** rather than from `quick_status`. The summary prices can sit
     * a long way from anything tradeable: SHARD_DRYBARK reported a `sellPrice` of 22.7 while the
     * cheapest seller in the book was asking 7002 - an abandoned order that made it look like
     * the best opportunity on the bazaar by a factor of ten. The top of book is where an order
     * would really have to compete.
     */
    fun bestFlips(
        limit: Int = 8,
        // Defaulted rather than read inside, so the ranking rules can be exercised against a
        // known set of products instead of whatever the live bazaar happens to be doing.
        quotes: Collection<BazaarLivePrices.Quote> = BazaarLivePrices.allQuotes(),
        taxRate: () -> Double = { BazaarTax.rate.toDouble() },
    ): List<Flip> {
        val kept = 1 - taxRate()

        return quotes
            .asSequence()
            .filter { it.weeklyVolume >= MIN_WEEKLY_VOLUME }
            .filter { it.topAsk > 0 && it.topBid > 0 }
            .map { quote ->
                Flip(
                    productId = quote.productId,
                    buyAt = quote.topAsk,
                    sellAt = quote.topBid,
                    buyDepth = quote.topAskAmount,
                    sellDepth = quote.topBidAmount,
                    weeklyVolume = quote.weeklyVolume,
                    profitPerUnit = quote.topBid * kept - quote.topAsk,
                )
            }
            .filter { it.profitPerUnit > 0 }
            .sortedByDescending { it.marginPercent }
            .take(limit)
            .toList()
    }

    /** The most heavily traded products, i.e. where orders fill quickly. */
    fun mostTraded(
        limit: Int = 8,
        quotes: Collection<BazaarLivePrices.Quote> = BazaarLivePrices.allQuotes(),
    ): List<BazaarLivePrices.Quote> =
        quotes
            .sortedByDescending { it.weeklyVolume }
            .take(limit)

    /**
     * Below this an order can sit unfilled for hours, which makes any margin theoretical.
     * The only filter left: pricing from the book already removes the stale-order rows that
     * a spread ceiling used to be needed for.
     */
    private const val MIN_WEEKLY_VOLUME = 100_000L
}
