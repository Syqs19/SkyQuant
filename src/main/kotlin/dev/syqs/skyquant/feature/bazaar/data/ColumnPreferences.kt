package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.feature.bazaar.gui.BazaarColumns
import dev.syqs.skyquant.feature.bazaar.gui.DataTable
import dev.syqs.skyquant.util.JsonFile

/**
 * Which figures the player has put away on each terminal table.
 *
 * The terminal shows up to eight columns at once, and which of them earn their place depends on
 * how somebody trades: a player working NPC flips has no use for the weekly volume, and one who
 * only wants to know whether a craft pays does not need both halves of every pair. Rather than a
 * Basic and an Advanced preset - a mode is a hidden state, and the player who most wants the
 * simpler view is the least likely to find the switch - each table remembers what its own player
 * chose to hide.
 *
 * Kept in its own file rather than in the settings, following [BazaarWatchlist]: this is player
 * data shaped by using the screen, not a setting anybody would go looking for in a config menu.
 *
 * **Hidden keys are stored, never visible ones.** An empty file then means "show everything", and
 * a column shipped in a later version appears by default. The other way round, every save written
 * today would list the columns of today and hide every one added since - silently, and only for
 * the players who had used the chooser at all.
 */
object ColumnPreferences {

    private class State {
        /** Table id -> the column keys hidden on it. */
        var hidden: MutableMap<String, MutableSet<String>> = mutableMapOf()
    }

    private val store = JsonFile.of("columns", { State() })
    private val state: State = store.load()

    /** The keys hidden on one table, empty when the player has not touched it. */
    fun hiddenOn(tableId: String): Set<String> = state.hidden[tableId].orEmpty()

    /** Hides the column if it is showing, shows it if hidden. Returns true when now hidden. */
    fun toggle(tableId: String, columnKey: String): Boolean {
        val hidden = state.hidden.getOrPut(tableId) { mutableSetOf() }

        val nowHidden = if (columnKey in hidden) {
            hidden.remove(columnKey)
            false
        } else {
            hidden.add(columnKey)
            true
        }

        // An empty entry and a missing one mean the same thing, so the empty one is dropped -
        // otherwise showing everything again would leave a file claiming a choice was made.
        if (hidden.isEmpty()) state.hidden.remove(tableId)

        store.save(state)
        return nowHidden
    }

    /** Puts every column of one table back, as the chooser's Reset does. */
    fun reset(tableId: String) {
        if (state.hidden.remove(tableId) != null) store.save(state)
    }

    /**
     * The columns to draw: [columns] less whatever the player hid, in their original order.
     *
     * [activeSort] overrides the choice for the column a table is currently sorted by. Sorting on a
     * column that isn't on screen leaves the rows in an order with nothing on the page to explain
     * it, and no obvious way back - so the sort wins, and the column reappears until the sort moves
     * off it. Unknown keys in [hidden] name no column and simply match nothing.
     */
    fun visible(
        columns: List<DataTable.Column>,
        hidden: Set<String>,
        activeSort: String? = null,
    ): List<DataTable.Column> {
        if (hidden.isEmpty()) return columns

        return columns.filter { column ->
            // activeSort is compared only when both sides are set. Testing `column.sortKey ==
            // activeSort` alone reads correctly and is wrong: on an unsorted table both are null,
            // null equals null, and every hidden column comes back.
            val carriesTheSort = activeSort != null && column.sortKey == activeSort

            column.key !in hidden || !isHideable(column) || carriesTheSort
        }
    }

    /** The columns a chooser should offer, which is every one the player is allowed to put away. */
    fun hideable(columns: List<DataTable.Column>): List<DataTable.Column> = columns.filter(::isHideable)

    /**
     * The item's name and the marker beside it stay whatever the player asks.
     *
     * Without them a row is a line of figures with nothing saying which item it describes, which
     * is not a simpler table but an unreadable one.
     */
    private fun isHideable(column: DataTable.Column): Boolean =
        column.key != BazaarColumns.NAME_KEY && column.key != BazaarColumns.PIN_KEY
}
