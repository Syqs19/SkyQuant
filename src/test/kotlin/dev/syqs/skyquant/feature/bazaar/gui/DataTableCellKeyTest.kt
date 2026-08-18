package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.gui.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cells find their column by key, never by their position in the list.
 *
 * Rows used to be a flat list matched to the columns by index, which meant a row that skipped a
 * column filed every figure after it under the wrong heading - silently, since the widths still
 * added up and the table still drew. [BazaarHomeScreen] worked around it on the NPC tabs by
 * rebuilding the row differently depending on whether a "Profit" column existed, and the comment
 * there is the record of how that was found.
 *
 * Hiding a column on demand makes a skipped column the ordinary case rather than a special one, so
 * the pairing had to stop depending on position first. These tests describe that: a row that omits
 * a middle column leaves the later ones where they belong.
 */
class DataTableCellKeyTest {

    private val columns = listOf(
        DataTable.Column("Item", 100, key = "item", numeric = false),
        DataTable.Column("Cost", 50, key = "cost"),
        DataTable.Column("Profit", 50, key = "profit"),
        DataTable.Column("Vol 7d", 50, key = "vol7d"),
    )

    @Test
    fun `a row that omits a middle column keeps the later ones under their own headings`() {
        val row = DataTable.Row.of(
            "item" to DataTable.Cell("Enchanted Flint", Palette.NAME),
            // "cost" deliberately absent - the case that used to shift everything left.
            "profit" to DataTable.Cell("+949", Palette.POSITIVE),
            "vol7d" to DataTable.Cell("2.1M", Palette.MUTED),
        )

        assertEquals("+949", row.cellFor(columns[2])?.text, "Profit must stay under PROFIT")
        assertEquals("2.1M", row.cellFor(columns[3])?.text, "Vol 7d must stay under VOL 7D")
        assertNull(row.cellFor(columns[1]), "the omitted column has no cell, rather than borrowing one")
    }

    @Test
    fun `the order cells are listed in does not decide where they land`() {
        // Written back to front on purpose: with keys the builder is free to add cells in whatever
        // order the calling code finds natural.
        val row = DataTable.Row.of(
            "vol7d" to DataTable.Cell("2.1M", Palette.MUTED),
            "item" to DataTable.Cell("Wheat", Palette.NAME),
            "cost" to DataTable.Cell("6.2k", Palette.TEXT),
        )

        assertEquals("Wheat", row.cellFor(columns[0])?.text)
        assertEquals("6.2k", row.cellFor(columns[1])?.text)
        assertEquals("2.1M", row.cellFor(columns[3])?.text)
    }

    @Test
    fun `a cell whose key matches no column is ignored rather than drawn somewhere`() {
        // A stale key left behind by a rename should lose its figure, not push the others along.
        val row = DataTable.Row.of(
            "item" to DataTable.Cell("Wheat", Palette.NAME),
            "margin" to DataTable.Cell("4.1%", Palette.POSITIVE),
        )

        assertEquals("Wheat", row.cellFor(columns[0])?.text)
        for (column in columns.drop(1)) {
            assertNull(row.cellFor(column), "'${column.title}' must stay empty")
        }
    }

    @Test
    fun `every column of every real table has a key, and no table repeats one`() {
        // A duplicate key would make one column shadow the other, which no test above would catch
        // because they use a hand-built table. Checked against the real definitions so a column
        // added later is covered without anyone remembering this file.
        val panelWidth = 504 - 12 * 2

        val tables = mapOf(
            "Flip" to BazaarColumns.flips(panelWidth),
            "Craft" to BazaarColumns.crafts(panelWidth, forge = false),
            "Forge" to BazaarColumns.crafts(panelWidth, forge = true),
            "Watchlist" to BazaarColumns.watchlist(panelWidth, "15min"),
            "Status" to BazaarColumns.status(panelWidth),
        )

        for ((name, table) in tables) {
            val keys = table.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "$name reuses a column key: $keys")
        }
    }
}
