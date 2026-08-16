package dev.syqs.skyquant.feature.bazaar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which menus the "Price graph" button may appear in.
 *
 * These tests replace a set that asserted the opposite rule and passed for weeks while the button
 * was dead in game. The old rule required "bazaar" in the menu title, and the old tests asserted
 * exactly that against titles like "Bazaar" and "Farming Bazaar" - titles never checked against
 * the game. Hypixel names a bazaar product page after the **item** ("Enchanted Diamond"), so the
 * check rejected every real page, and the tests confirmed a rule rather than a behaviour.
 *
 * The lesson is in what these assert instead: the button is allowed everywhere except the few
 * menus known to trip it. A test can only be as right as the fact it encodes, so the facts here
 * are the ones actually observed in game - the forge showing a button it shouldn't, and product
 * pages showing none when they should.
 */
class BazaarGraphButtonTest {

    @Test
    fun `product pages are titled after the item, and must be allowed`() {
        // The case the old rule broke. None of these contain the word "bazaar".
        assertTrue(BazaarGraphButton.allowsButton("Enchanted Diamond"))
        assertTrue(BazaarGraphButton.allowsButton("Enchanted Lapis Lazuli"))
        assertTrue(BazaarGraphButton.allowsButton("Titanium Drill DR-X655"))
    }

    @Test
    fun `the bazaar's own menus are still allowed`() {
        assertTrue(BazaarGraphButton.allowsButton("Bazaar"))
        assertTrue(BazaarGraphButton.allowsButton("Bazaar Orders"))
        assertTrue(BazaarGraphButton.allowsButton("Farming Bazaar"))
    }

    @Test
    fun `the forge is excluded, which is the one case that needed excluding`() {
        // Slot 13 there holds Refined Umber - a real bazaar product whose lore says "Currently
        // making" rather than "Click to view", so no question about the item alone can reject it.
        assertFalse(BazaarGraphButton.allowsButton("The Forge"))
        assertFalse(BazaarGraphButton.allowsButton("Quick Forge"))
    }

    @Test
    fun `exclusion survives the colour codes menu titles carry`() {
        assertFalse(BazaarGraphButton.allowsButton("§8The Forge"))
        assertTrue(BazaarGraphButton.allowsButton("§6Bazaar §7➜ §aFarming"))
        assertTrue(BazaarGraphButton.allowsButton("§aEnchanted Diamond"))
    }

    @Test
    fun `matches whatever case Hypixel uses`() {
        assertFalse(BazaarGraphButton.allowsButton("THE FORGE"))
        assertFalse(BazaarGraphButton.allowsButton("the forge"))
        assertTrue(BazaarGraphButton.allowsButton("BAZAAR"))
    }

    @Test
    fun `other menus are allowed here and rejected by the item check instead`() {
        // Deliberately allowed: a chest or a shop passes this gate, and `bazaarProductOf` is what
        // keeps the button off them. Splitting the job that way is what the old rule got wrong -
        // it tried to identify the bazaar from the title alone and identified nothing.
        assertTrue(BazaarGraphButton.allowsButton("Your Chest"))
        assertTrue(BazaarGraphButton.allowsButton("Mine Merchant"))
    }
}
