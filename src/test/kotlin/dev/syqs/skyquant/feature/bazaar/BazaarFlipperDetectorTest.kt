package dev.syqs.skyquant.feature.bazaar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the bazaar tax off menu text nobody publishes a spec for.
 *
 * The first version of this failed in-game while every test passed, because it read the lore
 * through a property that compiles but never yields the text. What the tests were missing was a
 * case built from the real entry, so that is the first one here now.
 *
 * The other rule: when the wording is unfamiliar the parser reports nothing rather than a
 * guess. Nothing leaves the assumed 1.25%, which understates profit; a wrong reading would
 * overstate every row on two screens.
 */
class BazaarFlipperDetectorTest {

    /** The real Community Shop entry, transcribed from the game. */
    private val realLore = listOf(
        "Account Upgrade",
        "",
        "Manage more orders at the same time",
        "and reduce the Bazaar tax.",
        "",
        "Your Limit: 14 + 14 orders",
        "Your Tax Rate: 1%",
        "",
        "Each tier: +7 orders & -0.125% tax",
        "",
        "Maxed out!",
    )

    @Test
    fun `reads the rate Hypixel states outright`() {
        // Preferred over deriving one from the tier: this is the number the server will charge,
        // and it stays right even if Hypixel re-balances what a tier is worth.
        assertEquals(0.01, BazaarFlipperDetector.rateFrom("Bazaar Flipper II", realLore)!!, 1e-9)
    }

    @Test
    fun `survives the colour codes the game puts in the text`() {
        // Menu text carries section-sign formatting inline, so a parser matching raw substrings
        // sees "§61%" where it expected "1%".
        val rate = BazaarFlipperDetector.rateFrom(
            "§dBazaar Flipper II",
            listOf("§7Your Tax Rate: §61%", "§aMaxed out!"),
        )

        assertEquals(0.01, rate!!, 1e-9)
    }

    @Test
    fun `reads a fractional rate`() {
        val rate = BazaarFlipperDetector.rateFrom(
            "Bazaar Flipper I",
            listOf("Your Tax Rate: 1.125%"),
        )

        assertEquals(0.01125, rate!!, 1e-9)
    }

    @Test
    fun `ignores the per-tier reduction, which is not the player's rate`() {
        // "Each tier: -0.125% tax" sits in the same lore and would parse as a rate of 0.125%,
        // which is below anything the upgrade can produce.
        val rate = BazaarFlipperDetector.rateFrom(
            "Bazaar Flipper II",
            listOf("Each tier: +7 orders & -0.125% tax", "Your Tax Rate: 1%"),
        )

        assertEquals(0.01, rate!!, 1e-9)
    }

    @Test
    fun `falls back to the tier when no rate is stated`() {
        // Covers Hypixel rewording the line: the numeral on the title still gives a level, and
        // the mod's own formula converts it.
        assertEquals(0.01, BazaarFlipperDetector.rateFrom("Bazaar Flipper II", listOf("Maxed out!"))!!, 1e-9)
    }

    @Test
    fun `falls back to the tier through colour codes`() {
        // The fallback path is where stripping actually matters: the title arrives as
        // "§dBazaar Flipper §fII", so the numeral reads as "§fii" and matches nothing unless
        // the formatting is removed first. The stated-rate path survives without stripping,
        // which is why this case needs its own test rather than relying on the one above.
        val rate = BazaarFlipperDetector.rateFrom("§dBazaar Flipper §fII", listOf("§aMaxed out!"))

        assertEquals(0.01, rate!!, 1e-9)
    }

    @Test
    fun `reports nothing for wording it does not recognise`() {
        val rate = BazaarFlipperDetector.rateFrom(
            "Bazaar Flipper",
            listOf("Increases your maximum orders", "Reduces bazaar tax"),
        )

        assertNull(rate, "an unreadable menu should leave the rate assumed, not guessed")
    }

    @Test
    fun `reports nothing for an unrelated item`() {
        assertNull(BazaarFlipperDetector.rateFrom("Diamond Sword", listOf("Damage: +35")))
    }

    @Test
    fun `rejects a rate above the untaxed base`() {
        // A number larger than 1.25% cannot be a bazaar tax, so it was a misread line.
        assertNull(BazaarFlipperDetector.rateFrom("Bazaar Flipper", listOf("Your Tax Rate: 50%")))
    }
}
