package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A table still fills its panel once the player has put some columns away.
 *
 * Each layout in [BazaarColumns] gives the name column whatever is left after the fixed ones, so
 * simply dropping a column from the finished list leaves its width as a gap on the right - the
 * figures stop short of the edge and the table reads as though it failed to draw. The name column
 * has to be re-solved against whatever survived.
 *
 * Checked as arithmetic rather than in game because the gap is widest on the tabs with the fewest
 * columns hidden, which is the case least likely to look wrong in a screenshot: hiding one 48px
 * volume column on a 480px panel is a 10% gap, noticeable only when something is lined up beside
 * it.
 */
class HiddenColumnLayoutTest {

    // The panel at its widest, matching SortableHeadingFitTest.
    private val panelWidth = 504 - 12 * 2

    private fun totalWidth(columns: List<DataTable.Column>) = columns.sumOf { it.width }

    private fun assertFillsPanel(columns: List<DataTable.Column>, hidden: Set<String>, table: String) {
        val laidOut = BazaarColumns.withHidden(columns, hidden)

        assertEquals(
            panelWidth,
            totalWidth(laidOut),
            "$table with $hidden hidden should still fill the panel",
        )
    }

    @Test
    fun `the flip table fills the panel with any one column hidden`() {
        val columns = BazaarColumns.flips(panelWidth)

        // Every hideable column in turn, so a column added later is covered without editing this.
        for (column in columns.filter { it.key != BazaarColumns.NAME_KEY && it.key != BazaarColumns.PIN_KEY }) {
            assertFillsPanel(columns, setOf(column.key), "Flip")
        }
    }

    @Test
    fun `the recipe tables fill the panel with several columns hidden`() {
        val craft = BazaarColumns.crafts(panelWidth, forge = false)
        assertFillsPanel(craft, setOf("margin", "vol7d"), "Craft")

        val forge = BazaarColumns.crafts(panelWidth, forge = true)
        assertFillsPanel(forge, setOf("cost", "vol7d"), "Forge")
    }

    @Test
    fun `hiding every hideable column leaves the name filling the rest`() {
        val columns = BazaarColumns.flips(panelWidth)
        val everything = columns
            .filter { it.key != BazaarColumns.NAME_KEY && it.key != BazaarColumns.PIN_KEY }
            .map { it.key }
            .toSet()

        val laidOut = BazaarColumns.withHidden(columns, everything)

        assertEquals(2, laidOut.size, "only the marker and the name should be left")
        assertEquals(panelWidth, totalWidth(laidOut))
    }

    @Test
    fun `hiding nothing changes nothing`() {
        val columns = BazaarColumns.flips(panelWidth)
        val laidOut = BazaarColumns.withHidden(columns, emptySet())

        assertEquals(columns.map { it.key }, laidOut.map { it.key })
        assertEquals(columns.map { it.width }, laidOut.map { it.width })
    }

    @Test
    fun `the name column grows rather than shrinks when a figure is put away`() {
        val columns = BazaarColumns.flips(panelWidth)
        val before = columns.first { it.key == BazaarColumns.NAME_KEY }.width

        val after = BazaarColumns.withHidden(columns, setOf("depth"))
            .first { it.key == BazaarColumns.NAME_KEY }.width

        assertTrue(after > before, "the freed width should go to the item name, not to a gap")
    }

    @Test
    fun `a hidden column keeps every heading it left behind sortable`() {
        // The width is re-solved by rebuilding the name column, so the others must come through
        // untouched - same title, same sort key, same tooltip.
        val columns = BazaarColumns.flips(panelWidth)
        val laidOut = BazaarColumns.withHidden(columns, setOf("depth"))

        for (column in laidOut.filter { it.key != BazaarColumns.NAME_KEY }) {
            val original = columns.first { it.key == column.key }

            assertEquals(original.title, column.title)
            assertEquals(original.sortKey, column.sortKey)
            assertEquals(original.description, column.description)
            assertEquals(original.width, column.width, "only the name column may be resized")
        }
    }
}
