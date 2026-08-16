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
     */
    class Column(
        val title: String,
        val width: Int,
        val numeric: Boolean = true,
        val description: String? = null,
        /** A narrow column of symbols (a pin, a rank) that the hover caret may take over. */
        val markerColumn: Boolean = false,
        /**
         * Identifies this column when it is clicked to sort by. Null means the column can't be
         * sorted on, which is the right answer for a marker column or a name.
         */
        val sortKey: String? = null,
    )

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
     * A data row, highlighted when hovered. Returns its bounds so callers can handle clicks
     * without recomputing the layout.
     */
    fun drawRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        y: Int,
        cells: List<Cell>,
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

        for ((index, cell) in cells.withIndex()) {
            if (index >= columns.size) break
            val column = columns[index]

            // The caret takes the first column's place rather than sitting beside it. Drawing
            // both put two symbols in a 12px column, where the pin diamond and the caret ran
            // into each other - and the pin is the one that can be spared, since a hovered row
            // is about to be clicked anyway.
            if (hovered && index == 0 && column.markerColumn) {
                graphics.text(font, CARET, x + SELECTION_BAR_WIDTH + 2, y + TEXT_OFFSET, Palette.ACCENT)
                continue
            }

            // Long names are cut rather than allowed to run into the next column, which would
            // break the alignment the layout exists for.
            val text = if (column.numeric) cell.text else font.plainSubstrByWidth(cell.text, column.width - 6)

            // The cell is positioned as a whole, then its runs are laid out left to right from
            // there, so a multi-part cell still right-aligns on its last character like every
            // single-part one beside it.
            var partX = textX(font, index, text, column.numeric)

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

    private fun columnLeft(index: Int): Int {
        var left = x
        for (i in 0 until index) left += columns[i].width
        return left
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
