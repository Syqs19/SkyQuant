package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a side panel row's label and value fit side by side.
 *
 * This guards the fault visible in the panel as shipped: "Buy now 334.00M" needed 82px of a 72px
 * column, so the label was dropped and the panel showed two anonymous figures. Nothing failed -
 * the shorter rows still rendered correctly, which is what made it look intentional.
 *
 * The font itself needs a running game, so the widths here are a deliberate over-estimate of
 * Minecraft's default font: every character is charged 6px, where real glyphs are 6px at most and
 * usually less. A layout that fits under this model has room to spare under the real one.
 */
class SidePanelFitTest {

    private companion object {
        /** Mirrors BazaarGraphScreen: 128 wide, less the 12px gap and 12px padding. */
        const val USABLE_WIDTH = 128 - 12 - 12
        const val LABEL_VALUE_GAP = 4

        /** Upper bound on Minecraft's default font: no glyph is wider than 6px. */
        fun widthOf(text: String) = text.length * 6

        fun fitsOnOneLine(label: String, value: String) =
            widthOf(label) + LABEL_VALUE_GAP + widthOf(value) <= USABLE_WIDTH
    }

    @Test
    fun `the auction rows fit beside their values`() {
        // The exact pairs from the screenshot where the labels went missing.
        val rows = listOf(
            "Buy now" to "334.00M",
            "Usual 30d" to "319.00M",
            "Sold 30d" to "1.9k",
            "Trend 30d" to "-25.9%",
        )

        for ((label, value) in rows) {
            assertTrue(fitsOnOneLine(label, value), "'$label $value' does not fit the side panel")
        }
    }

    @Test
    fun `the widest realistic auction row still fits`() {
        // Divan's Drill is the dearest item sampled, at four figures of millions.
        assertTrue(fitsOnOneLine("Buy now", "1300.00M"))
        assertTrue(fitsOnOneLine("Usual 30d", "1574.5M"))
    }

    @Test
    fun `the bazaar rows fit too`() {
        // The panel is shared, so widening it for auctions must not have been at their expense.
        val rows = listOf(
            "Buy" to "1343.2",
            "Sell" to "1289.3",
            "Spread" to "54.0",
            "Vol 7d" to "7.02M",
            "Trend 1d" to "+2.1%",
        )

        for ((label, value) in rows) {
            assertTrue(fitsOnOneLine(label, value), "'$label $value' does not fit the side panel")
        }
    }

    @Test
    fun `the old width would have failed these`() {
        // Proves the test is measuring the thing that was actually wrong: at the previous 96px
        // the two price rows could not fit, which is exactly what the screenshot showed.
        val oldUsable = 96 - 12 - 12
        val needed = widthOf("Buy now") + LABEL_VALUE_GAP + widthOf("334.00M")

        assertTrue(needed > oldUsable, "the old panel would have fitted this, so nothing was wrong")
        assertTrue(needed <= USABLE_WIDTH, "the new panel still does not fit it")
    }
}
