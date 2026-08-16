package dev.syqs.skyquant.feature.bazaar.data

/**
 * The two trades that involve an NPC shop, each priced both ways round.
 *
 * Instant and order are separate trades, not two estimates of one: instant is a smaller profit
 * you are certain of, an order is a larger one that depends on somebody taking the other side.
 * On live data the gap is not marginal - SULPHUR paid 6 coins instantly against 230 on an
 * order, and several items lose money instantly while making it on an order. Showing one number
 * would either hide the opportunity or overstate its certainty, so both are shown and the
 * choice is left to the player.
 *
 * Which direction is worth trading is not close. Buying on the bazaar to sell to a shop clears
 * under 1% at best, because bots arbitrage it continuously. Buying from a shop to sell on the
 * bazaar reached +2762% on RAW_FISH - 10 coins from the NPC against 286 from the bazaar - on a
 * market trading 13.5M units a week.
 */
object NpcFlipSummary {

    /**
     * A trade with two possible exits.
     *
     * [instantProfit] is what you clear taking the price on offer now; [orderProfit] what you
     * clear placing an order and waiting. Both are per unit and net of any tax that applies.
     */
    data class Flip(
        val productId: String,
        /** The NPC involved, so the player knows where to go. */
        val npcId: String?,
        val cost: Double,
        val instantProfit: Double,
        val orderProfit: Double,
        val weeklyVolume: Long,
        /** How many shops sell it, each holding its own stock. */
        val sellers: Int = 1,
    ) {
        val instantMargin: Double get() = margin(instantProfit)
        val orderMargin: Double get() = margin(orderProfit)

        private fun margin(profit: Double): Double =
            if (cost > 1e-9) profit / cost * 100 else 0.0
    }

    /**
     * Buy from an NPC shop, sell on the bazaar.
     *
     * Both exits are taxed: the bazaar takes its cut whichever way you sell into it. Selling
     * instantly fills at [BazaarLivePrices.Quote.sellPrice] - what buyers are bidding - while a
     * sell offer eventually fills at the higher [BazaarLivePrices.Quote.buyPrice].
     */
    fun npcToBazaar(
        limit: Int = 60,
        quotes: Collection<BazaarLivePrices.Quote> = BazaarLivePrices.allQuotes(),
        shopPriceOf: (String) -> NpcShopPrices.ShopPrice? = NpcShopPrices::priceFor,
        // Passed as a function rather than a value so the default isn't evaluated at the call
        // site: reading the rate reaches the config, which pulls in the whole game and cannot
        // be touched from a test. Every caller here supplies its own, so it is never invoked
        // in one - but a default that merely *mentions* it would still be enough to fail.
        taxRate: () -> Double = { BazaarTax.rate.toDouble() },
    ): List<Flip> =
        quotes
            .asSequence()
            .filter { it.weeklyVolume >= MIN_WEEKLY_VOLUME }
            .mapNotNull { quote ->
                val shop = shopPriceOf(quote.productId) ?: return@mapNotNull null
                if (shop.pricePerUnit <= 0) return@mapNotNull null

                val kept = 1 - taxRate()

                Flip(
                    productId = quote.productId,
                    npcId = shop.npcId,
                    cost = shop.pricePerUnit,
                    instantProfit = quote.sellPrice * kept - shop.pricePerUnit,
                    orderProfit = quote.buyPrice * kept - shop.pricePerUnit,
                    weeklyVolume = quote.weeklyVolume,
                    sellers = shop.otherSellers + 1,
                )
            }
            // Kept when either exit works: several items lose money sold instantly and make it
            // on an order, and dropping those would hide the trade the page exists to show.
            .filter { it.instantProfit > 0 || it.orderProfit > 0 }
            .sortedByDescending { maxOf(it.orderMargin, it.instantMargin) }
            .take(limit)
            .toList()

    /**
     * Buy on the bazaar, sell to an NPC shop.
     *
     * Untaxed, since the sale is to a shop rather than into the bazaar. Here it is the *buying*
     * that has two forms: instantly at [BazaarLivePrices.Quote.buyPrice], or by placing a buy
     * order that fills at the lower [BazaarLivePrices.Quote.sellPrice].
     */
    fun bazaarToNpc(
        limit: Int = 60,
        quotes: Collection<BazaarLivePrices.Quote> = BazaarLivePrices.allQuotes(),
        npcPriceOf: (String) -> Double? = NpcSellPrices::priceFor,
    ): List<Flip> =
        quotes
            .asSequence()
            .filter { it.weeklyVolume >= MIN_WEEKLY_VOLUME }
            .filter { it.sellPrice > 0 && it.buyPrice > 0 }
            .filter { it.buyPrice / it.sellPrice <= MAX_PRICE_RATIO }
            .mapNotNull { quote ->
                val npcPrice = npcPriceOf(quote.productId) ?: return@mapNotNull null

                Flip(
                    productId = quote.productId,
                    npcId = null,
                    // The instant route is the one whose cost is quoted, since it is the
                    // certain one; the order route's own cost is the lower sellPrice.
                    cost = quote.buyPrice,
                    instantProfit = npcPrice - quote.buyPrice,
                    orderProfit = npcPrice - quote.sellPrice,
                    weeklyVolume = quote.weeklyVolume,
                )
            }
            .filter { it.instantProfit > 0 || it.orderProfit > 0 }
            .sortedByDescending { maxOf(it.orderMargin, it.instantMargin) }
            .take(limit)
            .toList()

    /**
     * Below this an order can sit unfilled for hours, which makes the margin theoretical. This
     * is the filter doing the real work: the fictional rows all had a few hundred units a week
     * behind them, while every genuine opportunity had six or seven figures.
     */
    private const val MIN_WEEKLY_VOLUME = 100_000L

    /**
     * How far the instant-buy price may exceed the order price before the order price is
     * treated as stale rather than real.
     *
     * Deliberately loose, because on cheap items a wide ratio is normal rather than suspicious:
     * the bazaar's minimum price step is 0.1 coins, so an item selling at 0.5 cannot have a
     * ratio near 1 no matter how healthy its market is. Measured across the live bazaar, liquid
     * items ran up to 81x - IRON_INGOT sits at 59x on 8.8M weekly volume. A tighter bound cut
     * real opportunities, and the volume floor above already removes what this is aimed at.
     *
     * Applied only to the bazaar-to-NPC direction, where a stale order price would be mistaken
     * for a cheap purchase. In the other direction the cost comes from a fixed shop price, so
     * there is nothing to be misled by.
     */
    private const val MAX_PRICE_RATIO = 200.0
}
