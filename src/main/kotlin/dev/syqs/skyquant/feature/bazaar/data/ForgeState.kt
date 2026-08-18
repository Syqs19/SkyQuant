package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.util.stripFormatting

/**
 * What each forge slot is doing, read from Hypixel's `Forges:` tab list widget.
 *
 * The widget reports every slot at once without opening anything, which is why it is worth
 * parsing at all: the alternative is walking the player through The Forge menu.
 *
 * Formats were read off a running game (three surveys: all seven slots busy, all seven empty,
 * and the widget switched off) and the two states that giro could not produce - a locked slot and
 * a finished one - were taken from Skyblocker's `ForgeWidget`, which has parsed this widget for
 * years. Both sources agree on the header and on `EMPTY`.
 */
sealed interface ForgeSlot {

    /** Slot the player has not unlocked yet; needs a Heart of the Mountain level. */
    data object Locked : ForgeSlot

    /**
     * A line the parser did not recognise.
     *
     * Kept as its own state rather than folded into [Empty]. Hypixel can add a wording without
     * warning, and guessing "empty" would state something false about a slot that might be busy -
     * the caller can see an unknown and decline to answer, but it cannot see a wrong answer.
     */
    data class Unknown(val text: String) : ForgeSlot

    /** Unlocked and idle. */
    data object Empty : ForgeSlot

    /** Finished, waiting to be collected. */
    data class Ready(val item: String) : ForgeSlot

    /**
     * Still working.
     *
     * [remaining] is kept as Hypixel wrote it rather than parsed into a duration: the widget is
     * the only source, it rounds to the minute, and re-deriving a clock time from it would invent
     * a precision the figure never had.
     */
    data class Busy(val item: String, val remaining: String) : ForgeSlot
}

/**
 * The forge as the tab list last described it.
 *
 * [slots] is indexed by the widget's own numbering minus one, so `slots[0]` is slot `1)`.
 */
data class ForgeState(val slots: List<ForgeSlot>) {

    /** How many slots the player owns, locked ones included - the widget lists every one. */
    val slotCount: Int get() = slots.size

    /** True while anything is still being forged. */
    val anyBusy: Boolean get() = slots.any { it is ForgeSlot.Busy }

    /** Finished items waiting to be collected. */
    val ready: List<ForgeSlot.Ready> get() = slots.filterIsInstance<ForgeSlot.Ready>()

    companion object {

        /** The widget's own header, confirmed identical by SkyHanni's own widget table. */
        private const val HEADER = "Forges:"

        /**
         * ` 1) Tungsten Plate: 1h 25m` - the number, then the rest of the line.
         *
         * A pattern rather than a fixed offset. Skyblocker cuts the first three characters
         * (`substring(3)`), which is correct for the seven slots that exist today and silently
         * wrong for a two-digit slot number if Hypixel ever adds one.
         */
        private val SLOT_LINE = Regex("""^\s*(\d+)\)\s*(.*)$""")

        /**
         * Reads the widget out of the tab list, or null when it isn't there.
         *
         * Null means **unknown**, never "nothing is forging": the widget is off by default on some
         * profiles and is configured per island, so an absent panel says nothing about the forge.
         * The third survey confirmed the section disappears entirely rather than emptying, so the
         * two cases can't be told apart by content and the caller must handle null deliberately.
         */
        fun parse(tabList: List<String>): ForgeState? {
            val plain = tabList.map { it.stripFormatting() }

            // Located by content, not by position: across the three surveys the section moved
            // between columns as other widgets came and went, so any fixed index would read
            // whatever happened to sit there.
            val start = plain.indexOfFirst { it.trim() == HEADER }
            if (start < 0) return null

            val slots = mutableListOf<ForgeSlot>()
            for (line in plain.drop(start + 1)) {
                val match = SLOT_LINE.matchEntire(line) ?: break
                slots += slotOf(match.groupValues[2].trim())
            }

            // A header with nothing under it is a shape the surveys never produced; treating it as
            // unknown is safer than reporting a forge with no slots, which reads as "none owned".
            return if (slots.isEmpty()) null else ForgeState(slots)
        }

        private fun slotOf(body: String): ForgeSlot = when {
            body == "EMPTY" -> ForgeSlot.Empty
            body == "LOCKED" -> ForgeSlot.Locked
            else -> {
                // `substringBeforeLast`, since item names can themselves contain a colon; the
                // state is always the last field.
                val item = body.substringBeforeLast(": ", "").trim()
                val state = body.substringAfterLast(": ", "").trim()
                when {
                    item.isEmpty() || state.isEmpty() -> ForgeSlot.Unknown(body)
                    state == "Ready!" -> ForgeSlot.Ready(item)
                    else -> ForgeSlot.Busy(item, state)
                }
            }
        }
    }
}
