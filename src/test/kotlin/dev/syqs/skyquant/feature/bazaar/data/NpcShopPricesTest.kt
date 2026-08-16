package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing the NEU repo's shop offers. Every shape here was taken from the real repository -
 * the coupon-priced offers in particular are over half of all `npc_shop` entries, so treating
 * them as coin prices would fill the page with trades that cost no coins at all.
 */
class NpcShopPricesTest {

    private fun npcFile(vararg recipes: String) = """
        {
          "internalname": "ADVENTURER_NPC",
          "recipes": [ ${recipes.joinToString(",")} ]
        }
    """.trimIndent()

    private fun shop(cost: String, result: String) =
        """{"type":"npc_shop","cost":["$cost"],"result":"$result"}"""

    @Test
    fun `reads a coin-priced offer`() {
        val offers = NpcShopPrices.offersForTest(
            npcFile(shop("SKYBLOCK_COIN:8.0", "ROTTEN_FLESH:1")),
        )

        assertEquals(listOf("ROTTEN_FLESH" to 8.0), offers)
    }

    @Test
    fun `divides the price over the stack size`() {
        // Buying 32 for 320 coins is 10 each, and the per-unit figure is what a bazaar price
        // can be compared against.
        val offers = NpcShopPrices.offersForTest(
            npcFile(shop("SKYBLOCK_COIN:320.0", "WHEAT:32")),
        )

        assertEquals(listOf("WHEAT" to 10.0), offers)
    }

    @Test
    fun `prices the real Mine Merchant stack offers per unit`() {
        // Transcribed from the shop. The per-unit price is the only figure comparable with a
        // bazaar quote, and it pairs with a daily stock counted in units rather than purchases -
        // so profit times stock needs no stack size anywhere in it.
        val offers = NpcShopPrices.offersForTest(
            npcFile(
                shop("SKYBLOCK_COIN:12", "GOLD_INGOT:2"),
                shop("SKYBLOCK_COIN:22", "IRON_INGOT:4"),
                shop("SKYBLOCK_COIN:16", "TORCH:16"),
            ),
        )

        assertEquals(
            listOf("GOLD_INGOT" to 6.0, "IRON_INGOT" to 5.5, "TORCH" to 1.0),
            offers,
        )
    }

    @Test
    fun `ignores offers paid in items rather than coins`() {
        // Over half the npc_shop entries in the repo are these - Agatha's coupons, event
        // tokens. They cost no coins, so there is no coin profit to compute.
        val offers = NpcShopPrices.offersForTest(
            npcFile(
                shop("AGATHA_COUPON:20.0", "SMALL_FROG_TREAT:1"),
                shop("SKYBLOCK_COIN:14.0", "SLIME_BALL:1"),
            ),
        )

        assertEquals(listOf("SLIME_BALL" to 14.0), offers)
    }

    @Test
    fun `ignores offers costing several things`() {
        val json = """
            {"internalname":"X","recipes":[
              {"type":"npc_shop","cost":["SKYBLOCK_COIN:10.0","STRING:4"],"result":"BOW:1"}
            ]}
        """.trimIndent()

        assertTrue(NpcShopPrices.offersForTest(json).isEmpty())
    }

    @Test
    fun `ignores recipes that are not shop offers`() {
        val json = """
            {"internalname":"X","recipes":[
              {"type":"forge","inputs":["ENCHANTED_MITHRIL:160"],"count":1,"duration":21600},
              {"type":"npc_shop","cost":["SKYBLOCK_COIN:4.0"],"result":"COAL:1"}
            ]}
        """.trimIndent()

        assertEquals(listOf("COAL" to 4.0), NpcShopPrices.offersForTest(json))
    }

    @Test
    fun `handles a result with no explicit count`() {
        val offers = NpcShopPrices.offersForTest(
            npcFile(shop("SKYBLOCK_COIN:5.0", "STRING")),
        )

        assertEquals(listOf("STRING" to 5.0), offers)
    }

    @Test
    fun `handles an item id containing a semicolon variant`() {
        // Real ids like "FROG;0" carry a variant suffix; the count separator is the last colon,
        // not the first, or the id would be truncated.
        val offers = NpcShopPrices.offersForTest(
            npcFile(shop("SKYBLOCK_COIN:20.0", "RAW_FISH;1:2")),
        )

        assertEquals(listOf("RAW_FISH;1" to 10.0), offers)
    }

    @Test
    fun `returns nothing for a file with no recipes at all`() {
        assertTrue(NpcShopPrices.offersForTest("""{"internalname":"PLAIN"}""").isEmpty())
    }
}
