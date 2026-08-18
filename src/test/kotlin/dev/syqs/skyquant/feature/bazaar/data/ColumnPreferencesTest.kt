package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.feature.bazaar.gui.BazaarColumns
import dev.syqs.skyquant.feature.bazaar.gui.DataTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which columns a table shows, and what happens to a saved choice as the mod changes underneath it.
 *
 * The rules worth guarding are all about the *absent* case, because that is what a file written by
 * an older version looks like. Hidden keys are stored rather than visible ones precisely so that a
 * column shipped later appears by default: had it been the other way round, every existing save
 * would have listed the columns of its day and hidden every one added since - silently, and only
 * for players who had ever touched the chooser.
 */
class ColumnPreferencesTest {

    private val columns = listOf(
        DataTable.Column("", 12, key = BazaarColumns.PIN_KEY, markerColumn = true),
        DataTable.Column("Item", 100, key = BazaarColumns.NAME_KEY, numeric = false),
        DataTable.Column("Cost", 50, key = "cost"),
        DataTable.Column("Profit", 50, key = "profit"),
        DataTable.Column("Vol 7d", 50, key = "vol7d"),
    )

    @Test
    fun `nothing hidden shows every column`() {
        val visible = ColumnPreferences.visible(columns, hidden = emptySet())

        assertEquals(columns.map { it.key }, visible.map { it.key })
    }

    @Test
    fun `a hidden column is dropped and the rest keep their order`() {
        val visible = ColumnPreferences.visible(columns, hidden = setOf("profit"))

        assertEquals(listOf(BazaarColumns.PIN_KEY, BazaarColumns.NAME_KEY, "cost", "vol7d"), visible.map { it.key })
    }

    @Test
    fun `a column added since the file was written is visible`() {
        // The saved set names columns from an older release. "vol7d" isn't in it because it did not
        // exist then, and the player never chose to hide it.
        val visible = ColumnPreferences.visible(columns, hidden = setOf("cost"))

        assertTrue(visible.any { it.key == "vol7d" }, "a column nobody hid must show")
    }

    @Test
    fun `a stale key naming no column is harmless`() {
        // Left behind by a column that was removed or renamed. It must not take a real column with
        // it, and it must not stop the table drawing.
        val visible = ColumnPreferences.visible(columns, hidden = setOf("margin", "spread"))

        assertEquals(columns.map { it.key }, visible.map { it.key })
    }

    @Test
    fun `the name and marker columns cannot be hidden`() {
        // Hiding either leaves rows of figures with nothing saying which item they describe. Asked
        // for anyway - a hand-edited file, or a key that used to belong to something else.
        val visible = ColumnPreferences.visible(
            columns,
            hidden = setOf(BazaarColumns.PIN_KEY, BazaarColumns.NAME_KEY),
        )

        assertTrue(visible.any { it.key == BazaarColumns.NAME_KEY }, "the item name must survive")
        assertTrue(visible.any { it.key == BazaarColumns.PIN_KEY }, "the marker column must survive")
    }

    @Test
    fun `the column being sorted by cannot be hidden`() {
        // Sorting by a column that isn't on screen leaves the rows in an order with nothing to
        // explain it, and no way back short of guessing which heading to click.
        val sortable = columns.map {
            if (it.key == "profit") DataTable.Column(it.title, it.width, key = it.key, sortKey = "profit") else it
        }

        val visible = ColumnPreferences.visible(sortable, hidden = setOf("profit"), activeSort = "profit")

        assertTrue(visible.any { it.key == "profit" }, "the sorted column must stay visible")
    }

    @Test
    fun `a hidden column becomes visible again once the sort moves off it`() {
        val sortable = columns.map {
            if (it.key == "profit") DataTable.Column(it.title, it.width, key = it.key, sortKey = "profit") else it
        }

        // Still hidden in the saved set, but no longer propped up by the sort.
        val visible = ColumnPreferences.visible(sortable, hidden = setOf("profit"), activeSort = "cost")

        assertFalse(visible.any { it.key == "profit" }, "the choice to hide it should take effect again")
    }

    @Test
    fun `hideable columns are the ones a chooser should offer`() {
        val offered = ColumnPreferences.hideable(columns).map { it.key }

        assertEquals(listOf("cost", "profit", "vol7d"), offered)
    }
}
