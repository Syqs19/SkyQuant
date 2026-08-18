package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.8.9 to modern id table.
 *
 * The failure this guards against is a quiet one: a wrong id resolves to *some* item, so the row
 * still draws an icon and the table still looks finished - it is simply the wrong picture beside
 * the right name. Nothing about that reads as broken, which is why the mappings are asserted
 * against pairs taken from the repository rather than left to be noticed in game.
 */
class LegacyItemIdsTest {

    @Test
    fun `resolves every head to a player head`() {
        // 4927 of the repo's items are `skull`, by far the largest group. Damage chose between
        // skeleton, wither and player variants in 1.8.9; every SkyBlock item using it is a head.
        assertEquals("player_head", LegacyItemIds.modernise("skull", damage = 3))
        assertEquals("player_head", LegacyItemIds.modernise("skull", damage = 0))
        assertEquals("player_head", LegacyItemIds.modernise("minecraft:skull", damage = 3))
    }

    @Test
    fun `separates the items that shared the dye id`() {
        // ENCHANTED_LAPIS_LAZULI is `dye` damage 4. Reading the damage is the whole job here:
        // ignoring it would draw all sixteen as an ink sac.
        assertEquals("lapis_lazuli", LegacyItemIds.modernise("dye", damage = 4))
        assertEquals("ink_sac", LegacyItemIds.modernise("dye", damage = 0))
        assertEquals("cocoa_beans", LegacyItemIds.modernise("dye", damage = 3))
        assertEquals("bone_meal", LegacyItemIds.modernise("dye", damage = 15))
    }

    @Test
    fun `keeps the two colour orders apart`() {
        // Wool counts white from 0, dye counts it from 15 - the sequences run opposite ways. A
        // single shared list would be right for one family and inverted for the other.
        assertEquals("white_wool", LegacyItemIds.modernise("wool", damage = 0))
        assertEquals("black_wool", LegacyItemIds.modernise("wool", damage = 15))
        assertEquals("bone_meal", LegacyItemIds.modernise("dye", damage = 15))
        assertEquals("ink_sac", LegacyItemIds.modernise("dye", damage = 0))
    }

    @Test
    fun `renames the ids that lost their old names`() {
        assertEquals("firework_rocket", LegacyItemIds.modernise("fireworks"))
        assertEquals("cobweb", LegacyItemIds.modernise("web"))
        assertEquals("sugar_cane", LegacyItemIds.modernise("reeds"))
        assertEquals("lily_pad", LegacyItemIds.modernise("waterlily"))
        assertEquals("music_disc_cat", LegacyItemIds.modernise("record_cat"))
    }

    @Test
    fun `passes through the ids that never changed`() {
        // The majority case, and the reason the table is short. An id this doesn't know is far
        // more likely to be one that still works than a mistake worth substituting for.
        assertEquals("paper", LegacyItemIds.modernise("paper"))
        assertEquals("diamond_block", LegacyItemIds.modernise("minecraft:diamond_block"))
        assertEquals("stick", LegacyItemIds.modernise("stick", damage = 0))
    }

    @Test
    fun `falls back to the first variant for a damage past the end`() {
        // Not a case the current data produces, but the arithmetic here indexes a list with a
        // number from a file: a malformed entry should draw the family's plain member rather
        // than throw inside a render pass.
        assertEquals("poppy", LegacyItemIds.modernise("red_flower", damage = 99))
        assertEquals("ink_sac", LegacyItemIds.modernise("dye", damage = 99))
    }

    @Test
    fun `normalises case and namespace before looking up`() {
        // The repo is consistent about this, but the id also arrives from a disk cache written by
        // an older build, where it may not be.
        assertEquals("player_head", LegacyItemIds.modernise("MINECRAFT:SKULL", damage = 3))
        assertEquals("lapis_lazuli", LegacyItemIds.modernise("Dye", damage = 4))
    }
}
