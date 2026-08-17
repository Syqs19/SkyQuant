package dev.syqs.skyquant.feature.bazaar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which menus the "Price graph" button may appear in.
 *
 * Every case below was read off a running game - a survey that logged the contents of 23 menus,
 * kept in this shape deliberately. Two earlier sets of tests here asserted rules about menu
 * *titles* and passed for weeks while the button was wrong in game: first that a bazaar menu says
 * "bazaar" (it doesn't - Hypixel titles a product page after the item), then that excluding
 * "forge" was enough (it also excludes "Reforge Stones"). Both were invented at the desk, and a
 * green suite proved only that the code matched the assumption.
 *
 * So the rule under test is no longer about names at all: a bazaar product page is the menu that
 * offers to buy and to sell instantly, which is what such a page is *for*. In the survey both
 * entries appeared in exactly the 6 product pages and in none of the other 17.
 */
class BazaarGraphButtonTest {

    /** Item names from a real product page - "Wheat & Seeds ➜ Wheat", trimmed of empty slots. */
    private val productPage = listOf(
        "Buy Instantly", "Sell Instantly", "Wheat", "Create Buy Order", "Create Sell Offer",
        "Go Back", "Go Back", "Manage Orders", "View Graphs", "Instasell Ignore",
    )

    /** Item names from the category list one level up - "Farming ➜ Wheat & Seeds". */
    private val categoryList = listOf(
        "Wheat", "Enchanted Bread", "Enchanted Wheat", "Enchanted Hay Bale",
        "Seeds", "Enchanted Seeds", "Box of Seeds", "Go Back",
    )

    @Test
    fun `a product page offers both trades, and is allowed`() {
        assertTrue(BazaarGraphButton.allowsButton(productPage))
    }

    @Test
    fun `a category list offers neither, and is rejected`() {
        // The near miss: same bazaar, one level up, and every item on it is a real bazaar product.
        // Nothing about the items themselves separates this from a product page - only the trades.
        assertFalse(BazaarGraphButton.allowsButton(categoryList))
    }

    @Test
    fun `the forge is rejected without being named`() {
        // The menu this gate existed to exclude, now excluded by the general rule rather than by a
        // list. Slot 13 holds Refined Umber, a genuine bazaar product, so no question about the
        // item alone can reject it - but the forge offers no trade, so this one does.
        val forge = listOf("Refined Umber", "Quick Forge", "Go Back", "Close")

        assertFalse(BazaarGraphButton.allowsButton(forge))
    }

    @Test
    fun `a menu merely containing the word forge is not rejected for it`() {
        // "Reforge Stones" was excluded by the old blocklist for containing "forge" inside
        // "Reforge" - a false positive that a title match could never avoid, and that this rule
        // does not have to think about, since it never reads the name.
        val reforgeStones = listOf("Hot Potato Book", "Fuming Potato Book", "Go Back")

        assertFalse(BazaarGraphButton.allowsButton(reforgeStones))
    }

    @Test
    fun `one trade alone is not a product page`() {
        // An NPC shop sells without buying. Requiring both is what keeps the button off them,
        // and asserting it here is what stops the rule being loosened to `any` by mistake.
        assertFalse(BazaarGraphButton.allowsButton(listOf("Buy Instantly", "Wheat", "Go Back")))
        assertFalse(BazaarGraphButton.allowsButton(listOf("Sell Instantly", "Wheat", "Go Back")))
    }

    @Test
    fun `matching survives the colour codes item names carry`() {
        // Hypixel's names arrive formatted - "§aBuy Instantly" - and a raw match would miss every
        // one of them, turning the button off everywhere with no error to show for it.
        val formatted = listOf("§a§lBuy Instantly", "§c§lSell Instantly", "§fWheat")

        assertTrue(BazaarGraphButton.allowsButton(formatted))
    }

    @Test
    fun `an empty menu is rejected rather than throwing`() {
        // Hypixel sends the container empty and fills it a moment later, so this is the state
        // every menu passes through on the way to being drawn.
        assertFalse(BazaarGraphButton.allowsButton(emptyList()))
    }
}
