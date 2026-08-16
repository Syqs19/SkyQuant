package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.gui.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A cell carrying two figures has to colour each by its own sign.
 *
 * Found in-game: Minnow Bait showed "-2.6k/92.5k" entirely in green, because the cell was
 * coloured once by the better of the two numbers. Green on a minus sign says the opposite of
 * what the minus sign says, and it is the kind of thing a player acts on before noticing.
 */
class DataTableCellTest {

    @Test
    fun `a single-part cell reads back as its text`() {
        val cell = DataTable.Cell("640", Palette.TEXT)

        assertEquals("640", cell.text)
        assertEquals(1, cell.parts.size)
    }

    @Test
    fun `a paired cell keeps each half's own colour`() {
        val cell = DataTable.Cell.of(
            DataTable.Cell.Part("-2.6k", Palette.NEGATIVE),
            DataTable.Cell.Part("/", Palette.FAINT),
            DataTable.Cell.Part("92.5k", Palette.POSITIVE),
        )

        assertEquals("-2.6k/92.5k", cell.text)
        assertEquals(Palette.NEGATIVE, cell.parts[0].color)
        assertEquals(Palette.POSITIVE, cell.parts[2].color)
        assertNotEquals(
            cell.parts[0].color,
            cell.parts[2].color,
            "a loss and a gain in one cell must not share a colour",
        )
    }

    @Test
    fun `the joined text is what gets measured for column width`() {
        // Truncation and right-alignment both work off the whole string, so it has to include
        // every part rather than just the first.
        val cell = DataTable.Cell.of(
            DataTable.Cell.Part("117.2k", Palette.POSITIVE),
            DataTable.Cell.Part("/", Palette.FAINT),
            DataTable.Cell.Part("186.9k", Palette.POSITIVE),
        )

        assertEquals(13, cell.text.length)
    }
}
