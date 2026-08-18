package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the `Forges:` widget out of the tab list.
 *
 * The lines here are copied from three surveys of a live game - all seven slots forging, all seven
 * empty, and the widget switched off - rather than composed to suit the parser. `LOCKED` and
 * `Ready!` are the two states that giro could not produce, and come from Skyblocker's ForgeWidget,
 * which has read this widget for years; they are marked as such so their weaker provenance is
 * visible if one of them ever misbehaves.
 */
class ForgeStateTest {

    /** Survey 1: the forge island with every slot working. Neighbouring widgets kept for realism. */
    private val busyTabList = listOf(
        "               Info",
        "Forges:",
        " 1) Tungsten Plate: 1h 25m",
        " 2) Tungsten Plate: 1h 25m",
        " 3) Tungsten Plate: 1h 25m",
        " 4) Tungsten Plate: 1h 25m",
        " 5) Tungsten Plate: 1h 25m",
        " 6) Tungsten Plate: 1h 25m",
        " 7) Tungsten Plate: 1h 25m",
        "",
        "Commissions:",
        " Lava Springs Titanium: 40%",
    )

    /** Survey 2: same island, nothing being forged. */
    private val emptyTabList = listOf(
        "               Info",
        "Forges:",
        " 1) EMPTY",
        " 2) EMPTY",
        " 3) EMPTY",
        " 4) EMPTY",
        " 5) EMPTY",
        " 6) EMPTY",
        " 7) EMPTY",
        "",
        "Skills:",
        " Farming 50: 100%",
    )

    @Test
    fun `reads every busy slot with its item and time`() {
        val state = ForgeState.parse(busyTabList)!!

        assertEquals(7, state.slotCount)
        assertTrue(state.anyBusy)
        assertEquals(ForgeSlot.Busy("Tungsten Plate", "1h 25m"), state.slots[0])
        assertEquals(ForgeSlot.Busy("Tungsten Plate", "1h 25m"), state.slots[6])
    }

    @Test
    fun `reads an idle forge without calling it unknown`() {
        val state = ForgeState.parse(emptyTabList)!!

        assertEquals(7, state.slotCount)
        assertFalse(state.anyBusy)
        assertTrue(state.slots.all { it == ForgeSlot.Empty })
    }

    @Test
    fun `an absent widget is unknown, not an idle forge`() {
        // Survey 3, with the widget switched off: the section is gone entirely rather than left
        // empty, so nothing in the tab list distinguishes "off" from "nothing forging". Returning
        // null is what keeps a caller from reporting an idle forge to someone whose forge is full.
        val withoutWidget = listOf(
            "               Info",
            "Area: Dwarven Mines",
            " Server: mini64BV",
            "Skills:",
            " Mining 60: MAX",
        )

        assertNull(ForgeState.parse(withoutWidget))
    }

    @Test
    fun `finds the widget wherever the column puts it`() {
        // Between survey 1 and survey 3 the section moved column as other widgets appeared, so the
        // parser locates it by its header. Indexing a fixed position would read a neighbour.
        val shifted = listOf("Pet:", " [Lvl 98] Rock", "", "Forges:", " 1) EMPTY")

        assertEquals(1, ForgeState.parse(shifted)?.slotCount)
    }

    @Test
    fun `stops at the end of the widget rather than resuming later`() {
        // This asserts the parser *stops* at the blank line, which needs a numbered line further
        // down to detect - the real tab list has one, since Commissions and Bestiary count their
        // entries the same way. Written first against busyTabList alone, where every following
        // line is unnumbered, it passed whether the parser stopped or merely skipped: the two
        // behaviours are indistinguishable until something later looks like a slot.
        val widgetThenAnother = listOf(
            "Forges:",
            " 1) Tungsten Plate: 1h 25m",
            " 2) EMPTY",
            "",
            "Bestiary:",
            " 1) Glacite Mage 11: 164/300",
            " 2) Goblin 17: 1,671/2,000",
        )

        assertEquals(2, ForgeState.parse(widgetThenAnother)?.slotCount)
    }

    @Test
    fun `reads a finished slot as ready rather than as a time`() {
        // From Skyblocker's parser, not from the survey: the giro had nothing finished.
        val ready = listOf("Forges:", " 1) Refined Umber: Ready!", " 2) EMPTY")
        val state = ForgeState.parse(ready)!!

        assertEquals(ForgeSlot.Ready("Refined Umber"), state.slots[0])
        assertEquals(listOf(ForgeSlot.Ready("Refined Umber")), state.ready)
        assertFalse(state.anyBusy)
    }

    @Test
    fun `reads a locked slot as locked rather than as empty`() {
        // Also from Skyblocker: this player owns all seven, so no locked slot could be observed.
        // Locked and empty must not collapse - one can be used now, the other cannot.
        val locked = listOf("Forges:", " 1) EMPTY", " 2) LOCKED")
        val state = ForgeState.parse(locked)!!

        assertEquals(ForgeSlot.Empty, state.slots[0])
        assertEquals(ForgeSlot.Locked, state.slots[1])
    }

    @Test
    fun `keeps hours past a day as hours`() {
        // Confirmed in game: the widget never rolls over to days, so "29h" is a real reading and
        // not a truncated "1d 5h". Parsing it into a duration would be inventing precision.
        val long = listOf("Forges:", " 1) Drill Motor: 29h")

        assertEquals(ForgeSlot.Busy("Drill Motor", "29h"), ForgeState.parse(long)!!.slots[0])
    }

    @Test
    fun `strips the colour codes Hypixel writes inline`() {
        // Item names carry rarity colours. A raw match would fail to find the header at all and
        // report the forge as unknown on every profile that colours it.
        val coloured = listOf("§9Forges:", " §e1) §6Tungsten Plate§f: §a1h 25m")
        val state = ForgeState.parse(coloured)!!

        assertEquals(ForgeSlot.Busy("Tungsten Plate", "1h 25m"), state.slots[0])
    }

    @Test
    fun `an unrecognised line is unknown rather than empty`() {
        // If Hypixel adds a wording, saying "empty" would state something false about a slot that
        // may well be busy. Unknown lets a caller decline to answer instead.
        val odd = listOf("Forges:", " 1) Something New")
        val slot = ForgeState.parse(odd)!!.slots[0]

        assertTrue(slot is ForgeSlot.Unknown, "expected Unknown, was $slot")
    }
}
