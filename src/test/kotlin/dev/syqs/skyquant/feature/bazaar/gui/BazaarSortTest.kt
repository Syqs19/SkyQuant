package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.BazaarMarketSummary
import dev.syqs.skyquant.feature.bazaar.data.CraftProfit
import dev.syqs.skyquant.feature.bazaar.data.NpcFlipSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The orderings behind each table heading.
 *
 * These could not be tested at all while they lived inside `BazaarHomeScreen`: a comparator there
 * is reachable only through a `Screen`, which needs a running game. The first test below is the
 * reason that mattered - it fails against the ordering this file was extracted from.
 */
class BazaarSortTest {

    private fun flip(
        id: String,
        instant: Double,
        order: Double,
        sellers: Int = 1,
    ) = NpcFlipSummary.Flip(
        productId = id,
        npcId = "MERCHANT",
        cost = 10.0,
        instantProfit = instant,
        orderProfit = order,
        weeklyVolume = 1_000_000,
        sellers = sellers,
    )

    private fun craft(id: String, profit: Double, duration: Long = 0, cost: Double = 100.0) =
        CraftProfit.Craft(
            outputId = id,
            outputCount = 1.0,
            cost = cost,
            instantCost = cost,
            instantProfit = profit,
            orderProfit = profit,
            forgeProfit = profit,
            durationSeconds = duration,
            weeklyVolume = 1_000_000,
        )

    /** Stands in for the saved shop readings, which need the config and a data file. */
    private fun stock(perShop: Int): (NpcFlipSummary.Flip) -> Int = { perShop * it.sellers }

    /** [margin] is set through the price pair, since the Flip derives it rather than storing it. */
    private fun marketFlip(
        id: String,
        margin: Double,
        weekly: Long = 1_000_000,
        depth: Long = 10_000,
    ) = BazaarMarketSummary.Flip(
        productId = id,
        buyAt = 100.0,
        sellAt = 100.0 + margin,
        buyDepth = depth,
        sellDepth = depth,
        weeklyVolume = weekly,
        profitPerUnit = margin,
    )

    @Test
    fun `the Profit column ranks by a day's takings, so extra sellers count`() {
        // The bug this extraction exists to catch. Sorting by "Profit" used to compare per-unit
        // figures on the grounds that the daily multiplier was the same on every row - true only
        // where every item has one seller. 42 items are stocked by two or more shops, each holding
        // separate stock, so their daily total is a multiple of everyone else's.
        //
        // Here PAIR earns less per unit but is sold by three shops, so its day is worth 1920
        // against SOLO's 1280 - and the column shows exactly that total.
        val solo = flip("SOLO", instant = 1.0, order = 2.0, sellers = 1)
        val pair = flip("PAIR", instant = 0.5, order = 1.0, sellers = 3)

        val ranked = listOf(solo, pair)
            .sortedWith(BazaarSort.npcFlips(DataTable.Sort(BazaarSort.TOTAL), stock(640)))

        assertEquals(
            listOf("PAIR", "SOLO"),
            ranked.map { it.productId },
            "the row whose day is worth more must come first",
        )
    }

    @Test
    fun `with one seller each, the total ordering still follows the per-unit profit`() {
        // The case the old shortcut was right about, kept so the fix can't be "always multiply"
        // in a way that changes the ordinary answer.
        val rows = listOf(
            flip("SMALL", instant = 1.0, order = 2.0),
            flip("BIG", instant = 4.0, order = 9.0),
            flip("MIDDLE", instant = 2.0, order = 5.0),
        )

        val ranked = rows.sortedWith(BazaarSort.npcFlips(DataTable.Sort(BazaarSort.TOTAL), stock(640)))

        assertEquals(listOf("BIG", "MIDDLE", "SMALL"), ranked.map { it.productId })
    }

    @Test
    fun `the per-unit columns ignore shop stock entirely`() {
        // "Now" and "Offer" are per-unit figures, so a second shop must not promote a row there -
        // the column would then disagree with its own heading.
        val rows = listOf(
            flip("MANY_SHOPS", instant = 1.0, order = 1.0, sellers = 5),
            flip("ONE_SHOP", instant = 3.0, order = 3.0, sellers = 1),
        )

        val ranked = rows.sortedWith(BazaarSort.npcFlips(DataTable.Sort(BazaarSort.ORDER_PROFIT), stock(640)))

        assertEquals(listOf("ONE_SHOP", "MANY_SHOPS"), ranked.map { it.productId })
    }

    @Test
    fun `clicking a heading twice reverses it`() {
        val rows = listOf(
            flip("LOW", instant = 1.0, order = 1.0),
            flip("HIGH", instant = 9.0, order = 9.0),
        )

        val descending = DataTable.Sort(BazaarSort.ORDER_PROFIT)
        val ascending = descending.toggled(BazaarSort.ORDER_PROFIT)

        assertEquals(
            listOf("HIGH", "LOW"),
            rows.sortedWith(BazaarSort.npcFlips(descending, stock(640))).map { it.productId },
        )
        assertEquals(
            listOf("LOW", "HIGH"),
            rows.sortedWith(BazaarSort.npcFlips(ascending, stock(640))).map { it.productId },
        )
    }

    @Test
    fun `forge rows rank per hour, not by profit`() {
        // The measured pair: Tungsten Key makes 259k in 30 seconds (31.1M/h) against Gleaming
        // Crystal's 11.65M in six hours (1.94M/h). Ranked on profit the crystal wins by 45x;
        // ranked on the rate the key wins by 16x.
        val rows = listOf(
            craft("GLEAMING_CRYSTAL", 11_650_000.0, duration = 6 * 3600),
            craft("TUNGSTEN_KEY", 259_000.0, duration = 30),
        )

        val ranked = rows.sortedWith(BazaarSort.crafts(DataTable.Sort(BazaarSort.PER_HOUR)))

        assertEquals("TUNGSTEN_KEY", ranked.first().outputId)
    }

    @Test
    fun `sorting by cost is not sorting by profit`() {
        // Two separate columns, and an expensive recipe is not a profitable one: this ordering has
        // to follow what the ingredients cost, whatever the row earns.
        val rows = listOf(
            craft("CHEAP_RICH", profit = 90_000.0, cost = 10.0),
            craft("DEAR_POOR", profit = 5.0, cost = 4_000_000.0),
        )

        val ranked = rows.sortedWith(BazaarSort.crafts(DataTable.Sort(BazaarSort.COST)))

        assertEquals("DEAR_POOR", ranked.first().outputId)
    }

    @Test
    fun `flips can be ranked by weekly volume`() {
        // Depth and volume answer different questions - what the book holds now against how fast
        // it refills - so both headings sort. A deep book on something that trades twice a week is
        // a position you can enter and not leave.
        val rows = listOf(
            marketFlip("THIN", margin = 40.0, weekly = 12_000),
            marketFlip("LIQUID", margin = 3.0, weekly = 8_900_000),
            marketFlip("MIDDLE", margin = 12.0, weekly = 400_000),
        )

        val ranked = rows.sortedWith(BazaarSort.marketFlips(DataTable.Sort(BazaarSort.WEEKLY_VOLUME)))

        assertEquals(listOf("LIQUID", "MIDDLE", "THIN"), ranked.map { it.productId })
    }

    @Test
    fun `volume and depth are separate orderings`() {
        // The row with the deepest book is not the most traded one. Sorting by either key has to
        // produce its own answer, or one of the two headings is decoration.
        val shallowButBusy = marketFlip("BUSY", margin = 5.0, weekly = 9_000_000, depth = 800)
        val deepButQuiet = marketFlip("QUIET", margin = 5.0, weekly = 1_000, depth = 500_000)
        val rows = listOf(shallowButBusy, deepButQuiet)

        assertEquals(
            "BUSY",
            rows.sortedWith(BazaarSort.marketFlips(DataTable.Sort(BazaarSort.WEEKLY_VOLUME))).first().productId,
        )
        assertEquals(
            "QUIET",
            rows.sortedWith(BazaarSort.marketFlips(DataTable.Sort(BazaarSort.DEPTH))).first().productId,
        )
    }

    @Test
    fun `recipes can be ranked by the scarcest ingredient's volume`() {
        // A fine margin you can repeat all day beats a wide one you can run twice, and the volume
        // shown on a recipe row is the ingredient that caps how often it runs.
        val rows = listOf(
            craft("RARE", profit = 900_000.0).copy(weeklyVolume = 60_000),
            craft("COMMON", profit = 400.0).copy(weeklyVolume = 7_000_000),
        )

        val ranked = rows.sortedWith(BazaarSort.crafts(DataTable.Sort(BazaarSort.WEEKLY_VOLUME)))

        assertEquals("COMMON", ranked.first().outputId)
    }

    @Test
    fun `an unknown sort key falls back to the view's usual ordering`() {
        // A key with no comparator would otherwise leave rows in whatever order they arrived,
        // which reads as the sort having silently done nothing.
        val rows = listOf(
            craft("SMALL", profit = 10.0),
            craft("BIG", profit = 900.0),
        )

        val ranked = rows.sortedWith(BazaarSort.crafts(DataTable.Sort("nonsense")))

        assertEquals("BIG", ranked.first().outputId)
    }
}
