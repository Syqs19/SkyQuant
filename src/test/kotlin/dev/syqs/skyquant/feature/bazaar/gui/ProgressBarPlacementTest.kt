package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Status page's progress bar stays beside the item it describes.
 *
 * Found in game, and only once columns could be hidden: the bar used to be placed off the *right*
 * edge of the name column, which reads as correct until you notice that the name column is the one
 * absorbing whatever the player puts away. Hiding Cost and Value widened it by ~110px, and the bar
 * walked that far across the row - still inside its column, still perfectly aligned, and nowhere
 * near the item name.
 *
 * The arithmetic is checked here rather than in game because the failure is a matter of degree: at
 * one hidden column the bar is merely a little adrift, which looks like a spacing choice rather
 * than a fault.
 */
class ProgressBarPlacementTest {

    /** Upper bound on Minecraft's default font: no glyph is wider than 6px. */
    private fun widthOf(text: String) = text.length * 6

    // The Status name column with nothing hidden, at the panel's usual width.
    private val nameLeft = 24
    private val nameRight = 200

    @Test
    fun `the bar sits just after the item name`() {
        val name = "  Refined Diamond"
        val left = BazaarHomeScreen.progressBarLeft(nameLeft, nameRight, widthOf(name))

        assertEquals(nameLeft + widthOf(name) + 6, left, "the bar should follow the text by one gap")
    }

    @Test
    fun `hiding columns does not move the bar`() {
        // The exact case reported: two columns hidden, so the name column's right edge moves out
        // by their combined width while its left edge and the text stay put.
        val name = "  Refined Diamond"
        val before = BazaarHomeScreen.progressBarLeft(nameLeft, nameRight, widthOf(name))
        val after = BazaarHomeScreen.progressBarLeft(nameLeft, nameRight + 110, widthOf(name))

        assertEquals(before, after, "the bar must not follow the column's right edge")
    }

    @Test
    fun `a long name pushes the bar no further than its column`() {
        // Long enough that following the text would put the bar under the figures beside it.
        val name = "  Enchanted Hardened Diamond Block"
        val left = BazaarHomeScreen.progressBarLeft(nameLeft, nameRight, widthOf(name))

        assertTrue(
            left + 40 <= nameRight - 6,
            "the bar ends at $left+40, past the column's $nameRight edge",
        )
    }

    @Test
    fun `a short name leaves the bar clear of the text`() {
        val name = "  Coal"
        val left = BazaarHomeScreen.progressBarLeft(nameLeft, nameRight, widthOf(name))

        assertTrue(left > nameLeft + widthOf(name), "the bar must not overlap the name")
    }
}
