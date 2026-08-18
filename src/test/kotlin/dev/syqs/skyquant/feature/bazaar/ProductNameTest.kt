package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.feature.bazaar.data.ItemIconData
import dev.syqs.skyquant.feature.bazaar.data.ItemIconIndex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductNameTest {

    @Test
    fun `turns an id into words`() {
        assertEquals("Enchanted Diamond Block", ProductName.of("ENCHANTED_DIAMOND_BLOCK"))
    }

    @Test
    fun `handles a single word`() {
        assertEquals("Coal", ProductName.of("COAL"))
    }

    @Test
    fun `shortens the prefixes that crowd a narrow panel`() {
        assertEquals("Ench Diamond Block", ProductName.short("ENCHANTED_DIAMOND_BLOCK"))
    }

    @Test
    fun `leaves names without a known prefix alone`() {
        assertEquals("Coal", ProductName.short("COAL"))
    }

    @Test
    fun `uses the repository's name for a variant the id cannot describe`() {
        // The bazaar trades INK_SACK:3, the repo files it as INK_SACK-3, and the item is Cocoa
        // Beans. No rule applied to the id recovers that, which is the whole reason the name is
        // read from the repository at all - the id gives "Ink Sack:3", a thing that doesn't
        // exist in the game.
        ItemIconIndex.loadForTest(
            mapOf("INK_SACK-3" to ItemIconData(itemId = "dye", damage = 3, displayName = "Cocoa Beans")),
        )

        assertEquals("Cocoa Beans", ProductName.of("INK_SACK:3"))
        assertEquals("Cocoa Beans", ProductName.short("INK_SACK:3"))
    }

    @Test
    fun `falls back to the id when the repository has no name`() {
        // The ordinary case, and it has to keep working while the index is still downloading:
        // 2114 of the 2124 bazaar products are named correctly by their id alone.
        ItemIconIndex.loadForTest(emptyMap())

        assertEquals("Enchanted Diamond Block", ProductName.of("ENCHANTED_DIAMOND_BLOCK"))
    }

    @AfterTest
    fun clearIndex() {
        // Shared mutable state on an object: an index left behind reaches whichever test runs next.
        ItemIconIndex.loadForTest(emptyMap())
    }
}
