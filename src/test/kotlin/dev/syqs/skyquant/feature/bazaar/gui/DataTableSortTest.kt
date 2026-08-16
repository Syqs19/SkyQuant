package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clicking a heading is how every list in the terminal gets reordered, so the toggle rule has to
 * be exactly right: the wrong branch leaves a column that appears to do nothing when clicked.
 */
class DataTableSortTest {

    @Test
    fun `clicking the active column flips the direction`() {
        val sort = DataTable.Sort("margin", descending = true)

        assertFalse(sort.toggled("margin").descending)
    }

    @Test
    fun `clicking it again flips back`() {
        val sort = DataTable.Sort("margin", descending = true)

        assertTrue(sort.toggled("margin").toggled("margin").descending)
    }

    @Test
    fun `clicking another column switches to it, biggest first`() {
        // Ascending would open every new column on its least interesting end - the cheapest
        // item, the thinnest market - so switching always starts descending.
        val sort = DataTable.Sort("margin", descending = false)
        val next = sort.toggled("volume")

        assertEquals("volume", next.key)
        assertTrue(next.descending, "switching columns should start at the top, not the bottom")
    }

    @Test
    fun `switching away and back does not remember the old direction`() {
        val sort = DataTable.Sort("margin", descending = true).toggled("margin")

        assertTrue(sort.toggled("volume").toggled("margin").descending)
    }

    @Test
    fun `the sort arrow costs width that the heading has to give up`() {
        // The bug: "PER HOUR ▼" measures 57px in a 54px column, so sorting by that column - and
        // only that column - ran the heading into its neighbour. Every other tab looked fine,
        // which is why it shipped.
        //
        // This test guards the fact the fix depends on: the arrow is not free, so the title must
        // be truncated against the width left over rather than against the whole column.
        assertTrue(DataTable.sortArrow(active = true, descending = true).isNotEmpty())
        assertEquals(" ▼", DataTable.sortArrow(active = true, descending = true))
        assertEquals(" ▲", DataTable.sortArrow(active = true, descending = false))
    }

    @Test
    fun `an unsorted column pays nothing for an arrow it does not draw`() {
        // Otherwise every heading in the table would be truncated to leave room for a marker only
        // one of them ever shows.
        assertEquals("", DataTable.sortArrow(active = false, descending = true))
        assertEquals("", DataTable.sortArrow(active = false, descending = false))
    }
}
