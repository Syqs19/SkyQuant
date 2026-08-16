package dev.syqs.skyquant.feature.bazaar.gui

import java.util.Locale
import kotlin.math.abs

/**
 * Compact number formatting shared by every bazaar screen.
 *
 * Central so the rules live in one place: prices are read across the chart, the terminal and the
 * overlay at once, and the same value formatted two ways reads as two different numbers.
 *
 * Every format here pins [Locale.ROOT]. Without it Java follows the system locale, which on an
 * Italian machine prints "1,40k" - and in a compact figure the comma is the thousands separator,
 * so that reads as one million four hundred thousand rather than one thousand four hundred.
 */
object NumberFormats {

    /** Coins, at a precision that keeps neighbouring values distinct. */
    fun price(value: Double): String = when {
        abs(value) >= 1_000_000 -> format("%.2fM", value / 1_000_000)
        abs(value) >= 1_000 -> format("%.2fk", value / 1_000)
        abs(value) >= 10 -> format("%.1f", value)
        else -> format("%.2f", value)
    }

    /**
     * Unit counts. Keeps a decimal above a thousand so 1,200 and 1,800 don't both print "1k"
     * while their bars are visibly different heights, which reads as the number being wrong.
     */
    fun volume(units: Long): String = when {
        units >= 1_000_000 -> format("%.2fM", units / 1_000_000.0)
        units >= 1_000 -> format("%.1fk", units / 1_000.0)
        else -> units.toString()
    }

    /**
     * Coins at one decimal instead of two, for figures shown two-to-a-column.
     *
     * The pair is what forces it: "174.53k / 307.28k" is half again as wide as the column can
     * hold, and at these magnitudes the second decimal is below the noise of a price that moves
     * every minute anyway.
     */
    fun priceCompact(value: Double): String = when {
        abs(value) >= 1_000_000 -> format("%.1fM", value / 1_000_000)
        abs(value) >= 1_000 -> format("%.1fk", value / 1_000)
        abs(value) >= 10 -> format("%.0f", value)
        else -> format("%.1f", value)
    }

    /** A percentage, unsigned - for spreads, where the sign carries no information. */
    fun percent(value: Double): String = format("%.1f%%", value)

    /**
     * A percentage that stays inside its column however large it gets.
     *
     * Order-to-order margins reach five and six figures - one live row read 1239587.9%, which
     * is sixty pixels in a forty-eight pixel column and printed straight through the heading
     * beside it. Precision is dropped as the number grows, since the difference between 4899%
     * and 4900% decides nothing.
     */
    fun percentCompact(value: Double): String = when {
        abs(value) >= 100_000 -> format("%.0fk%%", value / 1000)
        abs(value) >= 10_000 -> format("%.1fk%%", value / 1000)
        abs(value) >= 100 -> format("%.0f%%", value)
        else -> format("%.1f%%", value)
    }

    /**
     * A percentage with the decimals it needs and no more: 1.25% prints in full, 1% prints as
     * "1%" rather than "1.00%".
     *
     * The tax needs this where [percent] won't do. At one decimal 1.25% displays as "1.3%",
     * which is a figure the player can compare against what the game tells them - and finding
     * it disagrees is exactly the wrong impression for a number the mod is asking to be trusted
     * on.
     */
    fun exactPercent(value: Double): String {
        // Three decimals, not two: the Bazaar Flipper tiers land on 1.125%, which "%.2f" rounds
        // to 1.13% - the same class of mistake this function exists to avoid.
        val text = format("%.3f", value).trimEnd('0').trimEnd('.')
        return "$text%"
    }

    /**
     * A change, with sign and arrow. The arrow is there so direction survives without colour:
     * red/green is the pair colour-blind players can least tell apart.
     */
    fun change(percent: Double): String =
        format("%s%+.1f%%", if (percent >= 0) "▲" else "▼", percent)

    /**
     * Axis label with precision taken from [step], the gap between gridlines, rather than from
     * the size of the number: at one decimal an axis spanning 1262 to 1400 prints "1.3k" three
     * times over, which leaves it saying nothing.
     */
    fun axisPrice(value: Double, step: Double): String = when {
        abs(value) >= 1_000_000 -> format("%.${if (step >= 100_000) 1 else 2}fM", value / 1_000_000)
        abs(value) >= 1_000 -> format("%.${if (step >= 100) 1 else 2}fk", value / 1_000)
        step >= 10 -> format("%.0f", value)
        step >= 1 -> format("%.1f", value)
        else -> format("%.2f", value)
    }

    /**
     * How long a forge recipe occupies its slot.
     *
     * The repo's durations run from 30 seconds to a week, and the short end is not a rounding
     * error: 22 of the 120 forge recipes take 30 seconds, so a plain hours column would print
     * "0h" for the fastest recipes in the game - and 30 seconds at 31M an hour is the best trade
     * on the page. Days are spelled out past 24 hours because "72h" makes a reader do arithmetic
     * to find out it is three days.
     */
    fun duration(seconds: Long): String = when {
        seconds <= 0 -> "-"
        seconds < 60 -> format("%ds", seconds)
        seconds < 3600 -> format("%dm", seconds / 60)
        seconds < 24 * 3600 -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            // 4.5h exists in the data (4 of them), so the half hour has to survive.
            if (minutes == 0L) format("%dh", hours) else format("%dh%02d", hours, minutes)
        }
        else -> {
            val days = seconds / (24 * 3600)
            val hours = (seconds % (24 * 3600)) / 3600
            if (hours == 0L) format("%dd", days) else format("%dd%dh", days, hours)
        }
    }

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.ROOT, pattern, *args)
}
