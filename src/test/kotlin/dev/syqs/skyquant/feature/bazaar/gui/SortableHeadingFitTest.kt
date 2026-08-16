package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a sortable heading still reads once its arrow is added.
 *
 * [DataTable] truncates the title to leave room for the arrow, so nothing ever overflows - it goes
 * quiet instead, dropping characters until the heading fits. That is the failure this guards
 * against: "VOL 7D ▼" turning into "VOL 7…" the moment a player sorts by it, which looks like a
 * rendering fault rather than a column that ran out of room.
 *
 * The arithmetic is done here rather than eyeballed in game because that lesson has been paid for
 * once already: "PER HOUR ▼" measured 57px in a 54px column and shipped, because it only misbehaved
 * on the one tab while it was the active sort.
 *
 * The font needs a running game, so widths are a deliberate over-estimate - every character
 * charged 6px, where real glyphs are 6px at most and most are 5px or less. A heading that fits
 * under this bound fits in the game.
 */
class SortableHeadingFitTest {

    private fun widthOf(text: String) = text.length * 6

    /** The arrow plus its leading space, at the same pessimistic 6px a character. */
    private val arrowWidth = widthOf(DataTable.sortArrow(active = true, descending = true))

    /**
     * Every sortable heading in a table, with the column width it has to live in.
     *
     * Built from the real column definitions rather than a list written out by hand, so a column
     * added later is covered without anyone remembering to add it here.
     */
    private fun sortableHeadings(columns: List<DataTable.Column>): List<Pair<String, Int>> =
        columns.filter { it.sortKey != null }.map { it.title.uppercase() to it.width }

    private fun assertHeadingsFit(columns: List<DataTable.Column>, table: String) {
        for ((title, width) in sortableHeadings(columns)) {
            assertTrue(
                widthOf(title) + arrowWidth <= width,
                "$table: '$title ▼' needs ${widthOf(title) + arrowWidth}px in a ${width}px column",
            )
        }
    }

    // The panel at its widest, which is what the terminal opens at on any ordinary window.
    private val panelWidth = 504 - 12 * 2

    @Test
    fun `every sortable heading on the Flip tab fits with its arrow`() {
        // Vol 7d became sortable here, and at 48px it is the narrowest sortable column in the
        // terminal - the one most likely to lose characters to the arrow.
        assertHeadingsFit(BazaarColumns.flips(panelWidth), "Flip")
    }

    @Test
    fun `every sortable heading on the recipe tabs fits with its arrow`() {
        assertHeadingsFit(BazaarColumns.crafts(panelWidth, forge = false), "Craft")
        assertHeadingsFit(BazaarColumns.crafts(panelWidth, forge = true), "Forge")
    }

    /**
     * The NPC tabs are checked by their headings rather than by building their columns.
     *
     * Building them reaches [dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit.default] for one
     * tooltip's wording, which reads the config, which loads a sound event, which needs a running
     * game - so the call dies in Minecraft's class initialiser long before any width is measured.
     *
     * The widths are the two files' shared constants and the titles are literals, so listing them
     * here checks the same arithmetic. It is a weaker test than the others in this class: it would
     * not notice a *new* sortable column on those tabs. That trade is deliberate, and the
     * alternative - threading a fake daily limit through the column definitions purely to let a
     * test build them - would complicate production code to suit the test rather than the player.
     */
    @Test
    fun `the NPC tabs' sortable headings fit with their arrows`() {
        val npcPriceWidth = 46
        val npcTotalWidth = 92

        for ((title, width) in listOf(
            "NOW" to npcPriceWidth,
            "OFFER" to npcPriceWidth,
            "ORDER" to npcPriceWidth,
            "PROFIT" to npcTotalWidth,
        )) {
            assertTrue(
                widthOf(title) + arrowWidth <= width,
                "NPC: '$title ▼' needs ${widthOf(title) + arrowWidth}px in a ${width}px column",
            )
        }
    }

    @Test
    fun `the check would catch the heading that once overflowed`() {
        // Proves this measures the thing that was actually wrong. "PER HOUR" in the 54px column it
        // used to sit in is exactly the case that shipped broken; it is 78px wide here.
        assertTrue(
            widthOf("PER HOUR") + arrowWidth > 54,
            "the bound is too generous to have caught the original fault",
        )
    }
}
