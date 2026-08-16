package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every case here came from the live bazaar while this was being built. The stale-order one
 * especially: without that filter the top of the list was entirely fictional rows, and they
 * outranked every real opportunity.
 */
class NpcFlipSummaryTest {

    private fun quote(
        id: String,
        buy: Double,
        sell: Double,
        weekly: Long = 1_000_000,
    ) = BazaarLivePrices.Quote(
        productId = id,
        buyPrice = buy,
        sellPrice = sell,
        buyVolume = 0,
        sellVolume = 0,
        buyMovingWeek = weekly,
        sellMovingWeek = weekly,
    )

    private fun shops(vararg pairs: Pair<String, Double>): (String) -> NpcShopPrices.ShopPrice? {
        val map = pairs.toMap()
        return { id -> map[id]?.let { NpcShopPrices.ShopPrice(it, "TEST_NPC") } }
    }

    private fun npcPrices(vararg pairs: Pair<String, Double>): (String) -> Double? {
        val map = pairs.toMap()
        return { map[it] }
    }

    // --- NPC -> bazaar ------------------------------------------------------------------

    @Test
    fun `prices both exits from the shop price`() {
        // RAW_FISH as it stood: 10 from the NPC, bazaar bidding 286, asking 496.
        val flip = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("RAW_FISH", buy = 496.0, sell = 286.0)),
            shopPriceOf = shops("RAW_FISH" to 10.0),
            taxRate = { 0.0 },
        ).single()

        assertEquals(276.0, flip.instantProfit, 1e-9)
        assertEquals(486.0, flip.orderProfit, 1e-9)
        assertEquals(2760.0, flip.instantMargin, 1e-6)
    }

    @Test
    fun `subtracts the bazaar tax from both exits`() {
        // Selling into the bazaar is taxed whichever way you do it.
        val flip = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("ITEM", buy = 200.0, sell = 100.0)),
            shopPriceOf = shops("ITEM" to 50.0),
            taxRate = { 0.0125 },
        ).single()

        assertEquals(100.0 * 0.9875 - 50.0, flip.instantProfit, 1e-9)
        assertEquals(200.0 * 0.9875 - 50.0, flip.orderProfit, 1e-9)
    }

    @Test
    fun `keeps an item that only profits on an order`() {
        // MINNOW_BAIT: a loss sold instantly, a gain on an order. Dropping it would hide
        // exactly the trade this page exists to surface.
        val flips = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("MINNOW_BAIT", buy = 178.8, sell = 11.2)),
            shopPriceOf = shops("MINNOW_BAIT" to 15.0),
            taxRate = { 0.0125 },
        )

        assertEquals(1, flips.size)
        assertTrue(flips.single().instantProfit < 0, "instant should be a loss here")
        assertTrue(flips.single().orderProfit > 0, "the order exit should still profit")
    }

    @Test
    fun `drops items that lose money both ways`() {
        val flips = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("PORK", buy = 9.0, sell = 8.4)),
            shopPriceOf = shops("PORK" to 10.0),
            taxRate = { 0.0125 },
        )

        assertTrue(flips.isEmpty(), "an item losing money on both exits was offered as a flip")
    }

    @Test
    fun `ranks by the better of the two exits`() {
        val flips = NpcFlipSummary.npcToBazaar(
            quotes = listOf(
                quote("MODEST", buy = 30.0, sell = 25.0),
                quote("STRONG", buy = 300.0, sell = 12.0),
            ),
            shopPriceOf = shops("MODEST" to 10.0, "STRONG" to 10.0),
            taxRate = { 0.0 },
        )

        assertEquals(listOf("STRONG", "MODEST"), flips.map { it.productId })
    }

    @Test
    fun `ignores items no NPC sells`() {
        val flips = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("UNSOLD", buy = 100.0, sell = 90.0)),
            shopPriceOf = shops(),
            taxRate = { 0.0 },
        )

        assertTrue(flips.isEmpty())
    }

    @Test
    fun `drops items nobody trades`() {
        val flips = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("DEAD", buy = 900.0, sell = 800.0, weekly = 400)),
            shopPriceOf = shops("DEAD" to 10.0),
            taxRate = { 0.0 },
        )

        assertTrue(flips.isEmpty(), "an untradeable item was offered as an opportunity")
    }

    @Test
    fun `carries the npc through, so the player knows where to go`() {
        val flip = NpcFlipSummary.npcToBazaar(
            quotes = listOf(quote("ITEM", buy = 100.0, sell = 90.0)),
            shopPriceOf = shops("ITEM" to 10.0),
            taxRate = { 0.0 },
        ).single()

        assertEquals("TEST_NPC", flip.npcId)
    }

    // --- bazaar -> NPC ------------------------------------------------------------------

    @Test
    fun `prices both ways of buying, untaxed`() {
        // Selling to a shop is not a bazaar sale, so no tax applies either way.
        val flip = NpcFlipSummary.bazaarToNpc(
            quotes = listOf(quote("SEEDS", buy = 3.4, sell = 1.3)),
            npcPriceOf = npcPrices("SEEDS" to 3.0),
        ).single()

        assertEquals(-0.4, flip.instantProfit, 1e-9)
        assertEquals(1.7, flip.orderProfit, 1e-9)
    }

    @Test
    fun `drops stale buy orders even when the item looks liquid`() {
        // MEDIUM_FROG_TREAT quoted an order price of 2.9 against an instant-buy of 620,775 -
        // an abandoned lowball order that scored as the best opportunity in the bazaar.
        // Given volume here so this exercises the ratio guard rather than the volume floor.
        val flips = NpcFlipSummary.bazaarToNpc(
            quotes = listOf(
                quote("FROG_TREAT", buy = 620_775.0, sell = 2.9, weekly = 5_000_000),
                quote("REAL", buy = 2.8, sell = 0.3, weekly = 5_000_000),
            ),
            npcPriceOf = npcPrices("FROG_TREAT" to 50_000.0, "REAL" to 10.0),
        )

        assertEquals(listOf("REAL"), flips.map { it.productId })
    }

    @Test
    fun `keeps cheap items whose wide ratio is normal`() {
        // The counterweight to the test above. The bazaar's price step is 0.1 coins, so
        // anything under a coin has a wide ratio by construction. IRON_INGOT is real: 59x on
        // 8.8M units a week.
        val flips = NpcFlipSummary.bazaarToNpc(
            quotes = listOf(quote("IRON_INGOT", buy = 29.6, sell = 0.5, weekly = 8_806_110)),
            npcPriceOf = npcPrices("IRON_INGOT" to 2.0),
        )

        assertEquals(listOf("IRON_INGOT"), flips.map { it.productId })
    }

    @Test
    fun `ignores items the NPC pays less for than the bazaar`() {
        val flips = NpcFlipSummary.bazaarToNpc(
            quotes = listOf(quote("LOSS", buy = 120.0, sell = 100.0)),
            npcPriceOf = npcPrices("LOSS" to 40.0),
        )

        assertTrue(flips.isEmpty(), "a losing trade was offered as profitable")
    }

    @Test
    fun `respects the limit`() {
        val many = (1..30).map { quote("ITEM_$it", buy = 2.0, sell = 1.0) }
        val prices = many.associate { it.productId to 5.0 }

        assertEquals(5, NpcFlipSummary.bazaarToNpc(limit = 5, quotes = many, npcPriceOf = prices::get).size)
    }

    @Test
    fun `both directions return nothing before prices arrive`() {
        assertTrue(NpcFlipSummary.npcToBazaar(quotes = emptyList(), shopPriceOf = shops()).isEmpty())
        assertTrue(NpcFlipSummary.bazaarToNpc(quotes = emptyList(), npcPriceOf = npcPrices()).isEmpty())
    }
}
