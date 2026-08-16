package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.gui.Palette

/**
 * Which palette role a profit figure takes.
 *
 * A one-line rule with its own file because it needs a test and the screens that apply it can't
 * have one - they reach into Minecraft. Keeping it here means the rule "a negative number is
 * never drawn in the positive colour" is enforced by the build rather than by remembering.
 *
 * It matters most where one cell carries two figures: a pair coloured by the better of the two
 * showed Minnow Bait as "-2.6k/92.5k" entirely in green, which contradicts the minus sign
 * sitting right there.
 */
object ProfitColor {

    /** [Palette.POSITIVE] for a gain, [Palette.NEGATIVE] otherwise. Zero is not a gain. */
    fun of(profit: Double): Int = if (profit > 0) Palette.POSITIVE else Palette.NEGATIVE

    /** The arrow that carries the same meaning without colour, for the themes built on that. */
    fun arrow(profit: Double): String = if (profit > 0) "▲" else "▼"
}
