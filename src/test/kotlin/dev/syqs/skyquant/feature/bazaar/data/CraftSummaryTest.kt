package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the two pages choose to show, and in what order.
 *
 * The ranking is the page: a player reads the top few rows and acts on them, so a comparator
 * that sorts on the wrong figure recommends the wrong trade while looking entirely normal.
 */
class CraftSummaryTest {

    private fun craft(
        id: String,
        profit: Double,
        duration: Long = 0,
        volume: Long = 1_000_000,
        cost: Double = 100.0,
    ) = CraftProfit.Craft(
        outputId = id,
        outputCount = 1.0,
        cost = cost,
        instantCost = cost,
        instantProfit = profit,
        orderProfit = profit,
        durationSeconds = duration,
        weeklyVolume = volume,
    )

    private fun recipe(id: String, duration: Long = 0) =
        Recipe(id, 1.0, mapOf("IN" to 1.0), duration)

    @Test
    fun `forge rows rank per hour, which reverses the profit ranking`() {
        // The measured pair from the plan: Tungsten Key makes 259k in 30 seconds (31.1M/h),
        // Gleaming Crystal 11.65M in 6 hours (1.94M/h). Ranked on profit the crystal wins by a
        // factor of 45; ranked on the rate the key wins by 16. Only one of those is the advice a
        // player should act on.
        val key = recipe("TUNGSTEN_KEY", duration = 30)
        val crystal = recipe("GLEAMING_CRYSTAL", duration = 6 * 3600)

        val priced = mapOf(
            "TUNGSTEN_KEY" to craft("TUNGSTEN_KEY", 259_000.0, duration = 30),
            "GLEAMING_CRYSTAL" to craft("GLEAMING_CRYSTAL", 11_650_000.0, duration = 6 * 3600),
        )

        val rows = CraftSummary.forges(listOf(crystal, key)) { priced[it.outputId] }

        assertEquals("TUNGSTEN_KEY", rows.first().outputId)
    }

    @Test
    fun `crafts rank on the order profit`() {
        val recipes = listOf(recipe("SMALL"), recipe("BIG"))
        val priced = mapOf(
            "SMALL" to craft("SMALL", 100.0),
            "BIG" to craft("BIG", 5_000.0),
        )

        val rows = CraftSummary.crafts(recipes) { priced[it.outputId] }

        assertEquals("BIG", rows.first().outputId)
    }

    @Test
    fun `an illiquid ingredient keeps the row off the craft page`() {
        // A wonderful margin on something nobody trades is an order that never fills. The floor
        // applies to the scarcest ingredient, since that is the side that has to be bought.
        val recipes = listOf(recipe("THIN"), recipe("LIQUID"))
        val priced = mapOf(
            "THIN" to craft("THIN", 900_000.0, volume = 12),
            "LIQUID" to craft("LIQUID", 500.0, volume = 5_000_000),
        )

        val rows = CraftSummary.crafts(recipes) { priced[it.outputId] }

        assertEquals(listOf("LIQUID"), rows.map { it.outputId })
    }

    @Test
    fun `the forge page has no volume floor`() {
        // Forge outputs are slow, expensive items trading in tens rather than tens of thousands,
        // so the crafting threshold would empty the page - and a forge recipe you can run twice a
        // day is still worth running.
        val rows = CraftSummary.forges(listOf(recipe("SLOW", duration = 3600))) {
            craft("SLOW", 5_000_000.0, duration = 3600, volume = 30)
        }

        assertEquals(1, rows.size)
    }

    @Test
    fun `losing recipes are left off both pages`() {
        // Most recipes lose money. Showing them all would bury the handful worth acting on -
        // and the pages exist to answer "what is worth making", not "what exists".
        val recipes = listOf(recipe("LOSS"))
        val priced = mapOf("LOSS" to craft("LOSS", -400.0))

        assertTrue(CraftSummary.crafts(recipes) { priced[it.outputId] }.isEmpty())
        assertTrue(CraftSummary.forges(recipes) { priced[it.outputId] }.isEmpty())
    }

    @Test
    fun `an unpriceable recipe is skipped rather than dropped from the world`() {
        // Roughly 300 of 2528 crafting recipes price entirely on the bazaar; the rest involve
        // items no market trades. That must thin the page, not break it.
        val recipes = listOf(recipe("PRICED"), recipe("UNPRICEABLE"))

        val rows = CraftSummary.crafts(recipes) {
            if (it.outputId == "PRICED") craft("PRICED", 700.0) else null
        }

        assertEquals(listOf("PRICED"), rows.map { it.outputId })
    }

    /**
     * The bar that decides how long the Craft page takes to settle.
     *
     * Crafting has 502 unpriced auction outputs against the forge's 33, and asking about all of
     * them at the shared budget's pace is what made one page take seconds and the other minutes.
     * These pin down when the asking stops.
     */
    @Test
    fun `nothing is ruled out while the page is still filling`() {
        val ranked = (1..5).map { craft("R$it", profit = 1_000_000.0) }

        assertEquals(
            0.0,
            CraftSummary.profitCeiling(ranked),
            "a half-empty page must keep every candidate in play",
        )
    }

    /**
     * The version of this that would have changed nothing: only ~30 recipes clear their costs in a
     * typical market, so a bar that waits for 100 rows never engages. It has to key off what fits
     * on screen instead.
     */
    @Test
    fun `the bar engages once the visible rows are filled, not the whole page`() {
        val ranked = (1..25).map { craft("R$it", profit = (26 - it) * 1_000_000.0) }

        // 20 visible rows, so the bar is the 20th row's profit: 6M.
        assertEquals(6_000_000.0, CraftSummary.profitCeiling(ranked))
    }

    @Test
    fun `the bar is the weakest visible row, so better candidates still get asked about`() {
        val ranked = (1..20).map { craft("R$it", profit = (21 - it) * 500_000.0) }
        val bar = CraftSummary.profitCeiling(ranked)

        // The weakest visible row makes 500k, so a recipe whose ingredients cost 2M could still
        // beat it and must not be ruled out; one costing 100k cannot and should be.
        assertTrue(2_000_000.0 >= bar, "a dearer candidate stays in play")
        assertTrue(100_000.0 < bar, "a cheap one cannot reach the page")
    }
}
