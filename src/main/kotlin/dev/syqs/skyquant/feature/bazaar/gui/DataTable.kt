package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.gui.Palette
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component

/**
 * Column-aligned table for the terminal screens.
 *
 * Numbers are right-aligned within fixed columns and text is left-aligned, which is the standard
 * for financial tables: digits then stack into a vertical line the eye can scan for magnitude,
 * where laying each value out from the row edge leaves the columns wandering row to row. Units
 * live in the header rather than in every cell, for the same reason - repeating "%" on forty
 * rows is ink that carries no information.
 */
class DataTable(
    private val columns: List<Column>,
    private val x: Int,
    private val width: Int,
) {

    /**
     * One column. [width] is fixed so values line up; [numeric] right-aligns it.
     *
     * [description] is shown when the cursor rests on the header - the terms here ("spread",
     * "vol 7d") are jargon, and a player who doesn't know them has nowhere else to find out.
     *
     * [key] names the column for the life of the table: it is how a [Row] finds which cell belongs
     * here, and how a hidden column is remembered between sessions. It is deliberately not the
     * title - a title is copy and gets reworded, where changing a key would silently unhide every
     * column a player had put away. Distinct from [sortKey] too, which is null on the columns that
     * can't be sorted and so can't identify them.
     */
    class Column(
        val title: String,
        val width: Int,
        val key: String,
        val numeric: Boolean = true,
        val description: String? = null,
        /** A narrow column of symbols (a pin, a rank) that the hover caret may take over. */
        val markerColumn: Boolean = false,
        /**
         * Identifies this column when it is clicked to sort by. Null means the column can't be
         * sorted on, which is the right answer for a marker column or a name.
         */
        val sortKey: String? = null,
    ) {
        /**
         * The same column, wider by [extra]. Used to hand the name column the width freed by a
         * column the player hid, so the table still reaches the right edge of the panel.
         */
        fun widened(extra: Int): Column =
            Column(title, width + extra, key, numeric, description, markerColumn, sortKey)
    }

    /** Which column a table is ordered by, and which way. */
    data class Sort(val key: String, val descending: Boolean = true) {
        /** Clicking the active column flips it; clicking another switches to it. */
        fun toggled(clicked: String): Sort =
            if (clicked == key) copy(descending = !descending) else Sort(clicked)
    }

    /**
     * A value plus how to colour it, so rows stay declarative at the call site.
     *
     * [parts] lets one cell carry several differently-coloured runs. A cell holding a pair -
     * "-2.6k/92.5k" - has to colour each half by its own sign: painting the whole thing by the
     * better of the two showed a loss in the same green as the gain beside it.
     */
    class Cell private constructor(val parts: List<Part>) {
        constructor(text: String, color: Int) : this(listOf(Part(text, color)))

        class Part(val text: String, val color: Int)

        /** The whole cell as one string, for measuring and truncation. */
        val text: String get() = parts.joinToString("") { it.text }

        companion object {
            /** A cell whose runs are coloured independently. */
            fun of(vararg parts: Part) = Cell(parts.toList())
        }
    }

    /**
     * Header row. Returns the y the first data row should start at.
     *
     * [sort] marks the active column, and [mouseX]/[mouseY] light up whichever sortable header
     * the cursor is over, so it reads as clickable before it is clicked.
     */
    fun drawHeader(
        graphics: GuiGraphicsExtractor,
        font: Font,
        y: Int,
        sort: Sort? = null,
        mouseX: Int = -1,
        mouseY: Int = -1,
    ): Int {
        val overHeader = mouseY in y..(y + 10)

        for ((index, column) in columns.withIndex()) {
            val active = sort != null && column.sortKey == sort.key

            // The arrow, not the colour, is what says "sorted by this": at this font size a
            // heading a shade brighter than its neighbours is not a difference anyone would
            // notice, and it would vanish outright in the high-contrast theme.
            val arrow = sortArrow(active, sort?.descending ?: true)

            // The arrow has to fit *inside* the column, not beside it. Sorting by "PER HOUR" made
            // the heading 57px wide in a 54px column, so it ran into its neighbour - and only on
            // that one column, and only while it was the active sort, which is why it survived
            // every other tab. Dropping characters off the title is the right sacrifice: the
            // arrow is what says which column you are sorted by.
            val available = (column.width - font.width(arrow)).coerceAtLeast(0)
            val text = font.plainSubstrByWidth(column.title.uppercase(), available) + arrow

            val hovered = overHeader && column.sortKey != null &&
                mouseX >= columnLeft(index) && mouseX < columnLeft(index) + column.width

            val color = when {
                active -> Palette.ACCENT
                hovered -> Palette.TEXT
                else -> Palette.HEADING
            }

            graphics.text(font, Component.literal(text), textX(font, index, text, column.numeric), y, color)
        }

        // Rule under the headers: the one separator that earns its ink, since without it the
        // header reads as just another row of values.
        graphics.fill(x, y + 10, x + width, y + 11, Palette.RULE)

        return y + HEADER_HEIGHT
    }

    /**
     * The cells of one row, each addressed by its column's [Column.key].
     *
     * Rows were a flat list matched to the columns by index, and the arrangement worked only while
     * every row carried every column. It cost this project a workaround already - the NPC tables
     * build their rows differently depending on whether the daily-total column exists, because a
     * row one cell short filed every figure after it under the wrong heading, with the widths still
     * adding up and nothing to see but wrong numbers under right titles.
     *
     * Once a column can be hidden that stops being an edge case, so the pairing is by name. A
     * column with no cell draws blank, and a cell no column claims is dropped rather than shifting
     * its neighbours along.
     */
    class Row private constructor(
        private val cells: Map<String, Cell>,
        /**
         * The Skyblock id whose icon is drawn beside this row's name, or null for a row that
         * isn't one item - the Status page's source headings, a total.
         */
        val iconId: String? = null,
    ) {

        fun cellFor(column: Column): Cell? = cells[column.key]

        /** The same row, with an icon drawn at the left of its name column. */
        fun withIcon(itemId: String?): Row = Row(cells, itemId)

        companion object {
            fun of(vararg cells: Pair<String, Cell>) = Row(cells.toMap())

            fun of(cells: Map<String, Cell>) = Row(cells)
        }
    }

    /**
     * A data row, highlighted when hovered. Returns its bounds so callers can handle clicks
     * without recomputing the layout.
     */
    fun drawRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        y: Int,
        row: Row,
        mouseX: Int,
        mouseY: Int,
    ): ScreenRectangle {
        val bounds = ScreenRectangle(x, y, width, ROW_HEIGHT)
        val hovered = bounds.containsPoint(mouseX, mouseY)

        if (hovered) {
            // Two marks for one state, because each covers where the other fails: the fill is
            // what the eye catches, and the accent bar still reads in a theme where the fill is
            // subtle, or for a player who can't separate the two colours at all.
            graphics.fill(x, y, x + width, y + ROW_HEIGHT, Palette.ROW_HOVER)
            graphics.fill(x, y, x + SELECTION_BAR_WIDTH, y + ROW_HEIGHT, Palette.ACCENT)
        }

        // Driven by the columns rather than by the cells: the columns are what is on screen, and a
        // row that has nothing for one of them leaves it blank instead of sliding the next figure
        // into its place.
        for ((index, column) in columns.withIndex()) {
            // The caret takes the first column's place rather than sitting beside it. Drawing
            // both put two symbols in a 12px column, where the pin diamond and the caret ran
            // into each other - and the pin is the one that can be spared, since a hovered row
            // is about to be clicked anyway.
            if (hovered && index == 0 && column.markerColumn) {
                graphics.text(font, CARET, x + SELECTION_BAR_WIDTH + 2, y + TEXT_OFFSET, Palette.ACCENT)
                continue
            }

            val cell = row.cellFor(column) ?: continue

            // The icon sits at the LEFT edge of the name column and the text starts after it.
            // Left, because that is the edge that stays put: the name column is the one that
            // absorbs the width freed by every column the player hides, so anything anchored to
            // its right edge walks across the row as columns are put away.
            val iconId = row.iconId?.takeIf { column.key == BazaarColumns.NAME_KEY }
            val indent = if (iconId != null) ItemIcon.WIDTH else 0

            if (iconId != null) {
                ItemIcon.draw(graphics, iconId, columnLeft(index), y, ROW_HEIGHT)
            }

            // Long names are cut rather than allowed to run into the next column, which would
            // break the alignment the layout exists for. Measured against what the icon leaves,
            // not the whole column, or a long name would reach exactly as far as it used to and
            // overrun the figures by the icon's width.
            val text = if (column.numeric) {
                cell.text
            } else {
                font.plainSubstrByWidth(cell.text, column.width - 6 - indent)
            }

            // The cell is positioned as a whole, then its runs are laid out left to right from
            // there, so a multi-part cell still right-aligns on its last character like every
            // single-part one beside it.
            var partX = textX(font, index, text, column.numeric) + indent

            if (cell.parts.size == 1 || !column.numeric) {
                graphics.text(font, Component.literal(text), partX, y + TEXT_OFFSET, cell.parts[0].color)
            } else {
                for (part in cell.parts) {
                    graphics.text(font, Component.literal(part.text), partX, y + TEXT_OFFSET, part.color)
                    partX += font.width(part.text)
                }
            }
        }

        return bounds
    }

    /**
     * Explanation of whichever header the cursor is over, or null. Drawn by the caller after
     * everything else, so it sits above the rows rather than behind them.
     */
    fun headerTooltipAt(font: Font, headerY: Int, mouseX: Int, mouseY: Int): Pair<String, String>? {
        if (mouseY < headerY || mouseY > headerY + 10) return null

        for ((index, column) in columns.withIndex()) {
            val description = column.description ?: continue
            val start = columnLeft(index)

            if (mouseX >= start && mouseX < start + column.width) {
                return column.title to description
            }
        }

        return null
    }

    /**
     * The sort key of the header at this point, or null if it isn't one. Lets the screen turn a
     * click into a new sort without knowing where the columns were drawn.
     */
    fun sortKeyAt(headerY: Int, mouseX: Int, mouseY: Int): String? {
        if (mouseY < headerY || mouseY > headerY + 10) return null

        for ((index, column) in columns.withIndex()) {
            val key = column.sortKey ?: continue
            val start = columnLeft(index)

            if (mouseX >= start && mouseX < start + column.width) return key
        }

        return null
    }

    /**
     * Left edge of each column, accumulated once when the table is built.
     *
     * This used to be a loop summing the widths before [index] on every call, which is quadratic
     * in the column count - and it is called for each cell of each row, so an eight-column table
     * of twenty rows walked ~57,000 column widths a second at 60fps to arrive at numbers that
     * never change. A table is rebuilt each frame, so the array is too, but building it is one
     * pass rather than one pass per cell.
     */
    private val columnLefts: IntArray = IntArray(columns.size).also { lefts ->
        var left = x
        for (index in columns.indices) {
            lefts[index] = left
            left += columns[index].width
        }
    }

    private fun columnLeft(index: Int): Int = columnLefts[index]

    /**
     * Right edge of a column, in screen coordinates.
     *
     * Public so a caller can put its own mark inside a column the table drew - the Status page's
     * progress bar sits at the right of the name column. Asking the table where that is keeps the
     * bar aligned when a column width changes, where a hand-computed offset would silently drift.
     */
    fun columnRight(index: Int): Int = columnLefts[index] + columns[index].width

    /**
     * Where a column starts and ends, found by key rather than by position.
     *
     * Preferred now that a table's columns depend on what the player hid: an index is only correct
     * while every column before it is present, which is a fact the caller cannot see. Both return
     * null when that column isn't on screen, so the caller can leave its mark off rather than put
     * it somewhere arbitrary.
     *
     * Which edge to ask for is a real choice. The name column is the one that absorbs the width
     * freed by everything the player hides, so its **right** edge moves as columns are put away
     * while its left edge stays put - anything meant to sit beside the text belongs off the left.
     */
    fun columnRight(key: String): Int? {
        val index = columns.indexOfFirst { it.key == key }
        return if (index < 0) null else columnRight(index)
    }

    fun columnLeft(key: String): Int? {
        val index = columns.indexOfFirst { it.key == key }
        return if (index < 0) null else columnLeft(index)
    }

    /** Left edge for the text itself: flush left for names, flush right for figures. */
    private fun textX(font: Font, index: Int, text: String, numeric: Boolean): Int {
        val left = columnLeft(index)
        val column = columns[index]

        return if (numeric) left + column.width - font.width(text) - COLUMN_PADDING else left
    }

    companion object {

        /**
         * The marker appended to the heading of the column a table is sorted by.
         *
         * Its own function so the width it costs can be checked without a font or a screen: the
         * heading has to be truncated to leave room for it, and getting that wrong is invisible
         * until a long title happens to be the active sort - which is how "PER HOUR ▼" spent a
         * release overlapping the column beside it while every other tab looked correct.
         */
        internal fun sortArrow(active: Boolean, descending: Boolean): String = when {
            !active -> ""
            descending -> " ▼"
            else -> " ▲"
        }

        const val ROW_HEIGHT = 12
        const val HEADER_HEIGHT = 16

        private const val TEXT_OFFSET = 2
        private const val COLUMN_PADDING = 6

        /** Accent bar down the left edge of the row under the cursor. */
        private const val SELECTION_BAR_WIDTH = 2

        /**
         * Filled and full-height rather than the smaller "▸": at this font size the small caret
         * read as a speck beside the row it was marking, which is the opposite of pointing at it.
         */
        private val CARET: Component = Component.literal("▶")
    }
}
