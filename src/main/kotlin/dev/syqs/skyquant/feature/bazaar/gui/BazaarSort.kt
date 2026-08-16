package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.BazaarMarketSummary
import dev.syqs.skyquant.feature.bazaar.data.CraftProfit
import dev.syqs.skyquant.feature.bazaar.data.NpcFlipSummary
import dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit

/**
 * How each terminal table orders its rows.
 *
 * Lifted out of [BazaarHomeScreen] because none of it needs a running game: a comparator is a rule
 * about which of two rows comes first, and inside a `Screen` that rule could only ever be checked
 * by looking at the table. That is not a theoretical worry - see [npcFlips], where the ordering was
 * quietly wrong for the 42 items sold by more than one shop and nothing could have caught it.
 *
 * The keys live here too, so a column that offers to sort and a comparator that knows how cannot
 * drift apart: a typo is a compile error rather than a heading that does nothing when clicked.
 */
object BazaarSort {

    // Only the figures a view is actually ranked on get a key. A heading that offers to sort
    // implies ordering by it is a useful way to read the list.
    const val PROFIT = "profit"
    const val MARGIN = "margin"
    const val DEPTH = "depth"
    const val INSTANT_PROFIT = "instantProfit"
    const val ORDER_PROFIT = "orderProfit"
    const val TOTAL = "total"
    const val COST = "cost"
    const val PER_HOUR = "perHour"
    const val WEEKLY_VOLUME = "weeklyVolume"

    /** Applies the sort's direction. Descending is the default: the best row belongs at the top. */
    private fun <T> Comparator<T>.directed(sort: DataTable.Sort?): Comparator<T> =
        if (sort?.descending != false) reversed() else this

    /** Bazaar-to-bazaar flips, ranked on the margin unless another column was clicked. */
    fun marketFlips(sort: DataTable.Sort?): Comparator<BazaarMarketSummary.Flip> =
        when (sort?.key) {
            PROFIT -> compareBy<BazaarMarketSummary.Flip> { it.profitPerUnit }
            DEPTH -> compareBy { minOf(it.buyDepth, it.sellDepth) }
            // Weekly volume asks a different question from depth, which is why both are sortable:
            // depth is what the book can absorb this minute, volume is how often it refills. A
            // deep book on an item that trades twice a week is a position you can enter and not
            // leave.
            WEEKLY_VOLUME -> compareBy { it.weeklyVolume }
            else -> compareBy { it.marginPercent }
        }.directed(sort)

    fun crafts(sort: DataTable.Sort?): Comparator<CraftProfit.Craft> =
        when (sort?.key) {
            INSTANT_PROFIT -> compareBy<CraftProfit.Craft> { it.instantProfit }
            MARGIN -> compareBy { it.orderMargin }
            COST -> compareBy { it.cost }
            // Per hour and per craft are the same ordering when nothing has a duration, which is
            // why the Craft tab can share this comparator without a special case.
            PER_HOUR -> compareBy { it.profitPerHour(it.orderProfit) }
            // The scarcest ingredient's weekly volume, which is what caps how often a recipe can
            // actually be run - a fine margin you can repeat all day beats a wide one you can run
            // twice.
            WEEKLY_VOLUME -> compareBy { it.weeklyVolume }
            else -> compareBy { it.orderProfit }
        }.directed(sort)

    /**
     * NPC flips.
     *
     * [dailyLimitOf] is passed in rather than read from [NpcDailyLimit] so the ordering can be
     * exercised without the config and the saved shop readings the real one reaches for.
     */
    fun npcFlips(
        sort: DataTable.Sort?,
        dailyLimitOf: (NpcFlipSummary.Flip) -> Int = { NpcDailyLimit.forProduct(it.productId, it.sellers) },
    ): Comparator<NpcFlipSummary.Flip> =
        when (sort?.key) {
            INSTANT_PROFIT -> compareBy<NpcFlipSummary.Flip> { it.instantProfit }
            ORDER_PROFIT -> compareBy { it.orderProfit }
            // A day's takings, which is what the Profit column actually shows: the better per-unit
            // profit times the shop stock behind it.
            //
            // The multiplier used to be treated as constant across rows - "the same on every row,
            // so ranking by the total is ranking by the per-unit figure" - and that is true only
            // where every item has one seller. 42 items are stocked by two or more shops, each
            // holding separate stock, so their daily total is a multiple of everyone else's and
            // they sorted below rows they out-earn. The column shows the total; the ordering has
            // to be the total.
            TOTAL -> compareBy { maxOf(it.instantProfit, it.orderProfit) * dailyLimitOf(it) }
            else -> compareBy { maxOf(it.instantProfit, it.orderProfit) }
        }.directed(sort)
}
