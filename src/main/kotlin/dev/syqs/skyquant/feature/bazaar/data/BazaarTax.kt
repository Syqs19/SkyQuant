package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.util.JsonFile

/**
 * The cut the bazaar takes on every sale, and what the player actually keeps.
 *
 * 1.25% by default, reduced by 0.125% per level of the Bazaar Flipper community upgrade, to a
 * floor of 1%. Small, but not nothing: on a 500k flip the difference between gross and net is
 * 6250 coins, and a terminal quoting gross figures is quietly overstating every row.
 *
 * The tax applies to bazaar sales only - selling to an NPC is untaxed, which is why the two NPC
 * views compute their profit differently.
 *
 * The level is learned from the Community Shop menu when the player opens it and remembered
 * afterwards, so in the normal case nothing has to be configured. The setting overrides that
 * for anyone who would rather just state it.
 */
object BazaarTax {

    /** Base rate before any upgrade, as a fraction. */
    const val BASE_RATE = 0.0125

    /** Each Bazaar Flipper level takes this much off the rate. */
    private const val REDUCTION_PER_LEVEL = 0.00125

    /** The upgrade caps out here; beyond it the rate would be wrong rather than generous. */
    const val MAX_LEVEL = 2

    private class Stored {
        /** The rate Hypixel stated, as a fraction. Null until the Community Shop has been read. */
        var detectedRate: Double? = null
    }

    private val file = JsonFile.of("bazaar_tax", { Stored() })

    @Volatile
    private var detectedRate: Double? = null

    /** Volatile alongside [detectedRate], for the reason given in [NpcDailyLimit]: the rate is
     * read while rows are drawn and written from the screen-event scan of the Community Shop. */
    @Volatile
    private var loaded = false

    /** The rate read from the Community Shop, or null if it has never been opened. */
    val knownRate: Double?
        get() {
            ensureLoaded()
            return detectedRate
        }

    /**
     * The rate in force, as a fraction. Prefers the player's explicit setting; falls back to
     * what was detected in-game; failing both, assumes no upgrade.
     *
     * Assuming *no* upgrade rather than a middling guess is deliberate: it understates profit
     * slightly for an upgraded player, which is the harmless direction to be wrong in. The
     * reverse would show trades as better than they are.
     */
    val rate: Float
        get() {
            val override = SkyQuantConfigManager.config.bazaar.taxOverride
            if (override != TaxOverride.AUTOMATIC) return rateForLevel(override.level)

            return knownRate?.toFloat() ?: BASE_RATE.toFloat()
        }

    /** True when the figure comes from the player rather than from a guess or a reading. */
    val isFromSetting: Boolean
        get() = SkyQuantConfigManager.config.bazaar.taxOverride != TaxOverride.AUTOMATIC

    fun rateForLevel(level: Int): Float =
        (BASE_RATE - REDUCTION_PER_LEVEL * level.coerceIn(0, MAX_LEVEL)).toFloat()

    /**
     * Records the rate read from the Community Shop. Ignored if unchanged, so this can be
     * called every frame the menu is open without rewriting the file each time.
     */
    @Synchronized
    fun recordDetectedRate(newRate: Double) {
        ensureLoaded()
        if (detectedRate != null && kotlin.math.abs(detectedRate!! - newRate) < 1e-9) return

        detectedRate = newRate
        file.save(Stored().apply { detectedRate = newRate })
        SkyQuantMod.LOGGER.info("Bazaar tax rate detected: {}%", newRate * 100)
    }

    /** Loaded once, with the flag set after the read - see [NpcDailyLimit.ensureLoaded]. */
    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        detectedRate = file.load().detectedRate
        loaded = true
    }

    /** Reset for tests, which must not inherit a rate left behind by another test. */
    internal fun forgetForTest() {
        detectedRate = null
        loaded = true
    }

    /** The player's answer to "what tax do you pay", with automatic as the default. */
    enum class TaxOverride(private val label: String, val level: Int) {
        AUTOMATIC("Detect automatically", -1),
        NONE("1.25% (no upgrade)", 0),
        LEVEL_1("1.125% (Bazaar Flipper I)", 1),
        LEVEL_2("1% (Bazaar Flipper II)", 2),
        ;

        override fun toString(): String = label
    }
}
