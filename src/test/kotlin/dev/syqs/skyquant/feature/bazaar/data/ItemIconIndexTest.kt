package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The icon index, and the one rule that decides whether icons appear at all.
 *
 * This index is filled while [RecipeIndex] walks the NEU archive, and that walk only happens when
 * GitHub actually sends the archive. The first build of this feature shipped without noticing
 * what that implies for anyone who already had recipes cached: their etag still matched, GitHub
 * answered 304, no archive was walked, and every row drew a blank where its icon should be. The
 * table even reserved the space, so the names sat indented beside nothing.
 *
 * The tests below pin the behaviour that fixes it, since none of it is visible to the compiler.
 */
class ItemIconIndexTest {

    @AfterTest
    fun clear() {
        // Shared mutable state on an object: an index left behind reaches whichever test runs
        // next, and [isLoaded] is exactly what other code branches on.
        ItemIconIndex.loadForTest(emptyMap())
    }

    private val lapis = ItemIconData(
        itemId = "dye",
        damage = 4,
        itemModel = "minecraft:lapis_lazuli",
    )

    @Test
    fun `reports whether it has anything to draw with`() {
        assertTrue(!ItemIconIndex.isLoaded)

        ItemIconIndex.loadForTest(mapOf("ENCHANTED_LAPIS_LAZULI" to lapis))

        assertTrue(ItemIconIndex.isLoaded)
        assertEquals(1, ItemIconIndex.size)
    }

    @Test
    fun `looks an item up regardless of the case it is asked in`() {
        ItemIconIndex.loadForTest(mapOf("ENCHANTED_LAPIS_LAZULI" to lapis))

        val found = assertNotNull(ItemIconIndex.iconFor("enchanted_lapis_lazuli"))

        assertEquals("minecraft:lapis_lazuli", found.itemModel)
        assertEquals(4, found.damage)
    }

    @Test
    fun `finds a damage variant the bazaar spells with a colon`() {
        // The bazaar trades INK_SACK:3; the repository files it as INK_SACK-3. Ten of the 2124
        // products are affected, and before this they were the only rows on the Flip page with
        // no icon - and with "Ink Sack:3" where the name should be.
        val cocoa = ItemIconData(itemId = "dye", damage = 3, displayName = "Cocoa Beans")
        ItemIconIndex.loadForTest(mapOf("INK_SACK-3" to cocoa))

        assertNotNull(ItemIconIndex.iconFor("INK_SACK:3"))
        assertEquals("Cocoa Beans", ItemIconIndex.nameFor("INK_SACK:3"))

        // The repository's own spelling has to keep working: the recipe pages address items that
        // way, since that is the key the archive is walked with.
        assertNotNull(ItemIconIndex.iconFor("INK_SACK-3"))
    }

    @Test
    fun `answers null for an item the repository never described`() {
        ItemIconIndex.loadForTest(mapOf("ENCHANTED_LAPIS_LAZULI" to lapis))

        // Not an error: the drawing side turns this into "no icon", which leaves the name where
        // it would have been anyway.
        assertNull(ItemIconIndex.iconFor("SOMETHING_HYPIXEL_ADDED_TODAY"))
    }

    @Test
    fun `refuses an empty result rather than storing it`() {
        ItemIconIndex.loadForTest(mapOf("ENCHANTED_LAPIS_LAZULI" to lapis))

        // A pass that parsed to nothing means the repo's shape changed. Keeping what we have is
        // the honest answer - replacing it would blank every icon while looking like a refresh
        // that worked. Asserted through the empty case alone, which returns before writing
        // anything: the success path saves to the config folder, and a test that exercised it
        // would leave a cache file in the working directory.
        ItemIconIndex.replace(emptyMap())

        assertTrue(ItemIconIndex.isLoaded)
        assertNotNull(ItemIconIndex.iconFor("ENCHANTED_LAPIS_LAZULI"))
    }

    @Test
    fun `leaves anything derived from the index alone when nothing was replaced`() {
        var cleared = 0
        ItemIconIndex.onReplaced = { cleared++ }

        try {
            // Built item stacks are derived from this index, so a real replacement has to drop
            // them - but a refused one must not, or a failed refresh would throw away icons that
            // are still correct.
            ItemIconIndex.replace(emptyMap())

            assertEquals(0, cleared)
        } finally {
            ItemIconIndex.onReplaced = null
        }
    }
}
