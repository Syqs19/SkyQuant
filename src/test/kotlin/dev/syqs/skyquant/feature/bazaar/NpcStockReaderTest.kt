package dev.syqs.skyquant.feature.bazaar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading `Stock: 640 remaining` off a shop entry.
 *
 * Shop tooltips carry several numbers - a cost, a stack size, a collection count - so the
 * parser has to key off the label rather than the first digits it meets. Getting that wrong
 * would multiply every profit on the page by a price.
 */
class NpcStockReaderTest {

    @Test
    fun `reads the stock line`() {
        // Transcribed from the Mine Merchant's Gold Ingot entry.
        val lore = listOf(
            "Brewing Ingredient",
            "Collection Item",
            "",
            "COMMON ORE",
            "",
            "Cost",
            "12 Coins",
            "",
            "Stock",
            "640 remaining",
            "",
            "Click to trade!",
        )

        assertEquals(640, NpcStockReader.stockFrom(lore))
    }

    @Test
    fun `reads it from a single line too`() {
        assertEquals(640, NpcStockReader.stockFrom(listOf("Stock: 640 remaining")))
    }

    @Test
    fun `survives colour codes`() {
        assertEquals(6400, NpcStockReader.stockFrom(listOf("§7Stock", "§a6400 §7remaining")))
    }

    @Test
    fun `handles a thousands separator`() {
        assertEquals(6400, NpcStockReader.stockFrom(listOf("Stock", "6,400 remaining")))
    }

    @Test
    fun `does not mistake the cost for the stock`() {
        // The cost line comes first in the real tooltip, so a parser scanning for digits
        // without requiring the label would price the whole row off 12 coins.
        val lore = listOf("Cost", "12 Coins", "", "Stock", "640 remaining")

        assertEquals(640, NpcStockReader.stockFrom(lore))
    }

    @Test
    fun `reports nothing when the entry states no stock`() {
        // Not every shop entry has a limit; those must fall back to the default rather than
        // taking a number from somewhere else in the tooltip.
        assertNull(NpcStockReader.stockFrom(listOf("Cost", "12 Coins", "Click to trade!")))
    }

    @Test
    fun `reports nothing for a sold-out entry`() {
        // Zero left is not a stock figure worth multiplying by - it would zero the whole row
        // while the item is still perfectly tradeable tomorrow.
        assertNull(NpcStockReader.stockFrom(listOf("Stock", "0 remaining")))
    }
}
