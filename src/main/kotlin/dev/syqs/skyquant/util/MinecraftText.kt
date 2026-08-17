package dev.syqs.skyquant.util

/**
 * Removes the `§` formatting codes the game carries inline in item names, lore and menu titles.
 *
 * One copy because three had drifted apart waiting to happen: [dev.syqs.skyquant.feature.bazaar.BazaarFlipperDetector],
 * [dev.syqs.skyquant.feature.bazaar.NpcStockReader] and
 * [dev.syqs.skyquant.feature.bazaar.BazaarGraphButton] each carried the same regex and their own
 * `strip`. Hypixel's text is the one thing here that can change without warning, and a fix applied
 * to one of three identical copies leaves the other two silently broken - which is exactly the
 * failure mode this codebase has already been bitten by, since a parser that stops matching finds
 * nothing rather than erroring.
 *
 * Without it a numeral reads as "§dii" and matches nothing, a title as "§9Bazaar", and a stock
 * line's digits sit behind a colour code.
 */
private val FORMATTING = Regex("§[0-9a-fk-orA-FK-OR]")

/** This text with its formatting codes removed. */
fun String.stripFormatting(): String = replace(FORMATTING, "")

/**
 * This text with every non-printable-ASCII character written as `\uXXXX`, for logging.
 *
 * Diagnostic only. A menu title read off Hypixel can carry a non-breaking space, a lookalike
 * glyph or a stray control character, and in a log every one of those renders as the plain
 * character it imitates - so a title that a match rejects still *reads* like it should have
 * matched, and the next fix goes back to guessing. Printing the codepoints is what turns
 * "the title looks right" into a fact.
 */
fun String.escapeNonAscii(): String = buildString {
    for (char in this@escapeNonAscii) {
        if (char in ' '..'~') append(char) else append("\\u%04x".format(char.code))
    }
}
