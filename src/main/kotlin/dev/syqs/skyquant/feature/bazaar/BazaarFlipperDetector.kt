package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.feature.bazaar.data.BazaarTax
import dev.syqs.skyquant.util.stripFormatting
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents

/**
 * Reads the player's bazaar tax rate off the Community Shop menu.
 *
 * The alternative was a personal API key, which means visiting a website, generating one and
 * pasting it into the config - a lot to ask for a figure worth at most 0.25%. The menu is
 * already open when the player buys the upgrade, so reading it there costs them nothing.
 *
 * Hypixel states the rate outright ("Your Tax Rate: 1%"), so that line is what this looks for
 * first. Taking the stated number beats deriving one from the tier: it is what the server will
 * actually charge, and it stays correct even if Hypixel re-balances what a tier is worth.
 * Falling back to the tier numeral covers the case where the wording moves.
 */
object BazaarFlipperDetector {

    private const val PERK_NAME = "bazaar flipper"

    /** The line Hypixel puts the player's current rate on. */
    private const val RATE_LABEL = "your tax rate"

    /** How Hypixel marks the tier currently in force. */
    private val UNLOCKED_MARKERS = listOf("maxed out", "unlocked", "currently", "active")

    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3)

    /**
     * Scans an open container for the perk and records what it finds.
     *
     * Called per frame while a menu is open: the container arrives empty and is filled a moment
     * later, so reading once on open would usually read nothing.
     */
    fun scan(screen: AbstractContainerScreen<*>) {
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue

            val name = stack.hoverName.string
            if (!name.stripFormatting().lowercase().contains(PERK_NAME)) continue

            // `lines()`, not `lines` - the property without parentheses is the record's raw
            // component and compiles just as happily while never yielding the text. That is
            // what stopped this working the first time round.
            val lore = stack.get(DataComponents.LORE)
                ?.lines()
                ?.map { it.string }
                ?: emptyList()

            rateFrom(name, lore)?.let { BazaarTax.recordDetectedRate(it) }
            return
        }
    }

    /**
     * The tax rate this entry states, as a fraction, or null when it can't be read.
     *
     * Exposed for tests: the wording is the part most likely to drift, and the only part that
     * can be exercised without a running game.
     */
    fun rateFrom(rawName: String, rawLore: List<String>): Double? {
        // Stripped here rather than only at the call site, so the function is correct on its
        // own terms: the caller in `scan` is not the only one, and a helper that quietly
        // depends on pre-cleaned input is one bad call away from failing in the game while
        // every test passes - which is exactly how the first version of this shipped broken.
        val name = rawName.stripFormatting()
        val lore = rawLore.map { it.stripFormatting() }

        statedRate(lore)?.let { return it }

        // No stated rate: fall back to the tier, which the mod converts with its own formula.
        return levelFrom(name, lore)?.let { BazaarTax.rateForLevel(it).toDouble() }
    }

    /** "Your Tax Rate: 1%" -> 0.01. */
    private fun statedRate(lore: List<String>): Double? {
        for (line in lore) {
            val lower = line.lowercase()
            if (!lower.contains(RATE_LABEL)) continue

            val percent = lower.substringAfter(RATE_LABEL)
                .dropWhile { !it.isDigit() }
                .takeWhile { it.isDigit() || it == '.' }
                .toDoubleOrNull()
                ?: continue

            // A rate outside the range the upgrade can produce means the line was misread, and
            // a wrong rate is worse than none: it would silently skew every figure on screen.
            val fraction = percent / 100
            if (fraction !in MIN_PLAUSIBLE_RATE..BazaarTax.BASE_RATE) continue

            return fraction
        }
        return null
    }

    /** The tier this entry is at, for the case where no rate is stated. */
    fun levelFrom(name: String, lore: List<String>): Int? {
        for (line in lore) {
            val lower = line.lowercase()
            if (UNLOCKED_MARKERS.none { lower.contains(it) }) continue
            romanIn(lower)?.let { return it }
            levelNumberIn(lower)?.let { return it }
        }

        romanIn(name.lowercase())?.let { return it }

        for (line in lore) {
            levelNumberIn(line.lowercase())?.let { return it }
        }

        return null
    }

    /** The roman numeral following the perk's name, e.g. "bazaar flipper ii" -> 2. */
    private fun romanIn(text: String): Int? {
        val after = text.substringAfter(PERK_NAME, "").trim()
        if (after.isEmpty()) return null

        val token = after.takeWhile { !it.isWhitespace() }.trim(':', '.', ',')
        return ROMAN[token]?.takeIf { it <= BazaarTax.MAX_LEVEL }
    }

    /** "level 2" or "tier 2" -> 2. */
    private fun levelNumberIn(text: String): Int? {
        for (word in listOf("level", "tier")) {
            val after = text.substringAfter("$word ", "").trim()
            if (after.isEmpty()) continue

            val digits = after.takeWhile { it.isDigit() }
            if (digits.isNotEmpty()) return digits.toInt().takeIf { it <= BazaarTax.MAX_LEVEL }

            ROMAN[after.takeWhile { !it.isWhitespace() }]?.let { return it.takeIf { l -> l <= BazaarTax.MAX_LEVEL } }
        }
        return null
    }

    /** The upgrade cannot take the rate below this, so anything lower was misparsed. */
    private const val MIN_PLAUSIBLE_RATE = 0.005
}
