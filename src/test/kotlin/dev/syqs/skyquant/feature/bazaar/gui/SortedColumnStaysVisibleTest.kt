package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.ColumnPreferences
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The column a table is sorted by survives being hidden.
 *
 * Worth its own file because two different naming schemes meet here and look interchangeable. A
 * column's [DataTable.Column.key] identifies it for the chooser ("profit"), while its
 * [DataTable.Column.sortKey] names the ordering it applies ("orderProfit") - and on several tables
 * the two differ for the same column. Comparing the wrong one silently does nothing: the table
 * still draws, the rows are still sorted, and the only symptom is a ranking with no visible column
 * to explain it.
 *
 * Each tab's default sort is checked against its real layout, so a tab whose default moves to
 * another column is covered without editing this.
 */
class SortedColumnStaysVisibleTest {

    private val panelWidth = 504 - 12 * 2

    private fun assertSortSurvivesHiding(columns: List<DataTable.Column>, sortKey: String, table: String) {
        val sorted = columns.firstOrNull { it.sortKey == sortKey }
        assertNotNull(sorted, "$table has no column sorting by '$sortKey'")

        // The player hides every figure they are allowed to, the sorted one included.
        val everything = ColumnPreferences.hideable(columns).map { it.key }.toSet()
        val visible = ColumnPreferences.visible(columns, everything, activeSort = sortKey)

        assertTrue(
            visible.any { it.key == sorted.key },
            "$table: '${sorted.title}' carries the sort and must stay on screen",
        )
    }

    @Test
    fun `the Flip tab's default sort keeps its column`() {
        assertSortSurvivesHiding(BazaarColumns.flips(panelWidth), BazaarSort.MARGIN, "Flip")
    }

    @Test
    fun `the Craft tab's default sort keeps its column`() {
        assertSortSurvivesHiding(
            BazaarColumns.crafts(panelWidth, forge = false),
            BazaarSort.ORDER_PROFIT,
            "Craft",
        )
    }

    @Test
    fun `the Forge tab's default sort keeps its column`() {
        // The one where key and sortKey differ most visibly: the column is keyed "perHour" and
        // sorts by BazaarSort.PER_HOUR, which happens to match - where "Profit" is keyed "profit"
        // and sorts by "orderProfit". Both are checked so neither convention is assumed.
        assertSortSurvivesHiding(
            BazaarColumns.crafts(panelWidth, forge = true),
            BazaarSort.PER_HOUR,
            "Forge",
        )
        assertSortSurvivesHiding(
            BazaarColumns.crafts(panelWidth, forge = true),
            BazaarSort.ORDER_PROFIT,
            "Forge",
        )
    }

    @Test
    fun `the layout still fills the panel when the sort holds a column open`() {
        // withHidden re-solves the name column against what actually survived, so the column the
        // sort rescued has to be counted as present - not subtracted as hidden and then drawn.
        val columns = BazaarColumns.flips(panelWidth)
        val everything = ColumnPreferences.hideable(columns).map { it.key }.toSet()

        val laidOut = BazaarColumns.withHidden(columns, everything, activeSort = BazaarSort.MARGIN)

        assertTrue(laidOut.any { it.sortKey == BazaarSort.MARGIN }, "the sorted column should be drawn")
        kotlin.test.assertEquals(
            panelWidth,
            laidOut.sumOf { it.width },
            "the table must still reach the right edge",
        )
    }
}
