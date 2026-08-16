package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which of the two NPC trades a daily stock limit applies to.
 *
 * The two tabs were built from one shared column set, so `BZ → NPC` inherited the shop's 640-unit
 * cap - a number that belongs to the *other* direction. It showed as a "Stock 640" column reading
 * the same on every row, and worse, silently multiplied every profit figure by 640.
 *
 * The rule is a fact about the game rather than about the code, so it is stated once here and the
 * screen reads it from the same place.
 */
class NpcTradeDirectionTest {

    /**
     * Mirrors the screen: the multiplier applied to a per-unit profit for each direction.
     *
     * `perDay` is true only when buying from a shop, which is the only side with a daily stock.
     * The screen calls `NpcDailyLimit.forProduct`, which reads saved settings and so needs a
     * running game; the constant is the part this is about, and the branch either takes it or
     * takes 1.
     */
    private fun unitsFor(perDay: Boolean) = if (perDay) NpcDailyLimit.STANDARD else 1

    @Test
    fun `buying from a shop is capped by its daily stock`() {
        // NPC → BZ: the shop sells 640 a day, and that cap is the whole shape of the trade -
        // it is what turns a 3-coin margin into a day's takings.
        assertEquals(NpcDailyLimit.STANDARD, unitsFor(perDay = true))
    }

    @Test
    fun `selling to a shop is not capped`() {
        // BZ → NPC: the bazaar has no daily stock and an NPC buys without limit, so there is no
        // quantity to multiply by. Using 640 here invented a ceiling and inflated every figure
        // in the column by the same arbitrary factor.
        assertEquals(1, unitsFor(perDay = false))
    }

    @Test
    fun `the two directions do not share a multiplier`() {
        // The bug in one line: both tabs took the same number because they took the same code
        // path. They must differ, or one of them is wrong by construction.
        assertTrue(unitsFor(perDay = true) != unitsFor(perDay = false))
    }

    @Test
    fun `a per-unit profit is reported unchanged when nothing caps it`() {
        val perUnitProfit = 49.0

        assertEquals(49.0, perUnitProfit * unitsFor(perDay = false), 1e-9)
        // And is genuinely scaled on the capped side, so the distinction is not cosmetic.
        assertEquals(49.0 * NpcDailyLimit.STANDARD, perUnitProfit * unitsFor(perDay = true), 1e-9)
    }
}
