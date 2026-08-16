package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tax is small enough to look ignorable and large enough to matter: on a 500k flip it is
 * 6250 coins. Every profit figure on two screens is computed through this, so an error here is
 * an error everywhere at once.
 */
class BazaarTaxTest {

    @Test
    fun `base rate with no upgrade`() {
        assertEquals(0.0125f, BazaarTax.rateForLevel(0), 1e-6f)
    }

    @Test
    fun `each level takes off an eighth of a percent`() {
        assertEquals(0.01125f, BazaarTax.rateForLevel(1), 1e-6f)
        assertEquals(0.01f, BazaarTax.rateForLevel(2), 1e-6f)
    }

    @Test
    fun `the reduction stops at the maximum level`() {
        // Past the cap the rate would keep falling and eventually go negative, turning the tax
        // into a bonus.
        assertEquals(BazaarTax.rateForLevel(BazaarTax.MAX_LEVEL), BazaarTax.rateForLevel(99), 1e-6f)
    }

    @Test
    fun `a negative level is treated as no upgrade`() {
        assertEquals(0.0125f, BazaarTax.rateForLevel(-1), 1e-6f)
    }

    @Test
    fun `the override enum states the rate it stands for`() {
        // The labels in the dropdown quote percentages, so they have to match what the levels
        // actually produce, or the setting would lie about its own effect.
        assertEquals(0.0125f, BazaarTax.rateForLevel(BazaarTax.TaxOverride.NONE.level), 1e-6f)
        assertEquals(0.01125f, BazaarTax.rateForLevel(BazaarTax.TaxOverride.LEVEL_1.level), 1e-6f)
        assertEquals(0.01f, BazaarTax.rateForLevel(BazaarTax.TaxOverride.LEVEL_2.level), 1e-6f)
    }
}
