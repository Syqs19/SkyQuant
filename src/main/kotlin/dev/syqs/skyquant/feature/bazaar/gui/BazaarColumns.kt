package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit

/**
 * The column layouts behind each terminal table.
 *
 * Pure description - a title, a width, what the heading explains on hover, and whether clicking it
 * sorts - so none of it needs a running game. It lived in [BazaarHomeScreen] and made up a quarter
 * of that file while having nothing to do with drawing.
 *
 * Every layout gives the name column whatever is left after the fixed ones, so the figures stay a
 * constant distance from the right edge at any window size, and a narrow panel truncates names
 * rather than folding a numeric column to nothing.
 */
object BazaarColumns {

    /**
     * Watchlist: the player's own list, with what each item costs and how it has moved.
     *
     * [changeTitle] is passed in because the change column is labelled with the window actually
     * recorded so far - history only covers the current session, so a fixed "15min" would misstate
     * what it measures during the first quarter of an hour.
     */
    fun watchlist(width: Int, changeTitle: String): List<DataTable.Column> {
        val fixed = PIN + PRICE * 2 + CHANGE + SPREAD

        return listOf(
            DataTable.Column("", PIN, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(60), numeric = false),
            DataTable.Column("Buy", PRICE, description = "What you pay to buy this item instantly."),
            DataTable.Column("Sell", PRICE, description = "What you receive selling it instantly."),
            DataTable.Column(
                changeTitle,
                CHANGE,
                description = "How the buy price moved over the session so far.",
            ),
            DataTable.Column(
                "Spread",
                SPREAD,
                description = "Gap between buy and sell, as a share of the buy price. " +
                    "The margin a flip has to work with.",
            ),
        )
    }

    /**
     * The order-to-order flip, which is the trade people actually make: place a buy order, wait,
     * place a sell offer, wait. Instant prices are gone from this view - crossing the spread twice
     * clears almost nothing, so ranking by it flattered items nobody could profit from.
     */
    fun flips(width: Int): List<DataTable.Column> {
        val fixed = PIN + PRICE * 3 + SPREAD + VOLUME * 2

        return listOf(
            DataTable.Column("", PIN, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(50), numeric = false),
            DataTable.Column(
                "Buy @",
                PRICE,
                description = "Where your buy order has to sit to compete: the cheapest price " +
                    "anyone is currently asking.",
            ),
            DataTable.Column(
                "Sell @",
                PRICE,
                description = "Where your sell offer fills: the best price anyone is currently bidding.",
            ),
            DataTable.Column(
                "Profit",
                PRICE,
                description = "Coins kept per unit after the bazaar's cut on the sale.",
                sortKey = BazaarSort.PROFIT,
            ),
            DataTable.Column(
                "Margin",
                SPREAD,
                description = "Profit against what it costs to get in. Ranks cheap and expensive " +
                    "items on equal terms.",
                sortKey = BazaarSort.MARGIN,
            ),
            DataTable.Column(
                "Depth",
                VOLUME,
                description = "How many units this flip could actually be done in, whichever " +
                    "side is thinner. Counts the whole order book within 1% of the best price, " +
                    "not just the units at it - so it answers \"how much can I trade\" rather " +
                    "than \"how much sits at the front of the queue\".",
                sortKey = BazaarSort.DEPTH,
            ),
            DataTable.Column(
                "Vol 7d",
                VOLUME,
                description = "Units traded this week. A wide margin on a thin market is a " +
                    "position you can't get out of - and unlike Depth, which is what the book " +
                    "holds right now, this says how quickly it refills.",
                sortKey = BazaarSort.WEEKLY_VOLUME,
            ),
        )
    }

    /**
     * The two recipe views.
     *
     * [forge] adds the per-hour figure and drops the margin. That is not a space compromise but a
     * reading of the trade: a percentage of the money put up says nothing about what a forge slot
     * is worth, since two recipes with the same margin can differ tenfold per hour. It also keeps
     * the name column wide enough - with every figure given a place the arithmetic left it 32px
     * short of the longest item name.
     */
    fun crafts(width: Int, forge: Boolean): List<DataTable.Column> {
        // Every figure column holds a pair, so the whole table reads on one convention: left of
        // the slash is the fast trade, right of it the patient one.
        val pairedColumns = if (forge) 3 else 2
        val fixed = PIN + VOLUME + PAIRED * pairedColumns + if (forge) 0 else MARGIN
        val nameWidth = (width - fixed).coerceAtLeast(MIN_NAME)

        // Repeated in each description rather than stated once: a tooltip is read on its own, and
        // a reader hovering "Profit" has no reason to have hovered "Cost" first.
        //
        // The two pages pair different trades, so they need different notes. On the Craft page the
        // pair is speed against patience, both ways through. On the Forge page the left figure
        // buys outright and *still* sells on an offer, because the recipe's own wait - a median of
        // six hours - makes the minutes a sell offer takes irrelevant, while a buy order that has
        // to fill delays the only scarce thing there is: the slot starting.
        val pairNote = if (forge) {
            " Left of the slash: ingredients bought outright so the slot starts now, result sold " +
                "on an offer. Right: a buy order for the ingredients too, cheaper but the forge " +
                "waits."
        } else {
            " Left of the slash: buy outright and sell into the bids, done in a minute. " +
                "Right: a buy order and a sell offer, cheaper and slower."
        }

        // The cost column is about the ingredients alone, so its pairing is the same on both
        // pages - outright against ordered - whatever the profit columns go on to do with them.
        val costNote = " Left of the slash: bought outright. Right: bought on orders."

        return buildList {
            add(DataTable.Column("", PIN, markerColumn = true))
            add(
                DataTable.Column(
                    "Item",
                    nameWidth,
                    numeric = false,
                    description = "What the recipe makes, and how many one craft yields. " +
                        "\"AH\" means the price comes from the auction house - the median of the " +
                        "four cheapest listings, an estimate of what yours would fetch rather " +
                        "than a standing bid. \"!\" beside it means the cheapest listing sits far " +
                        "below the others, so that market is thin.",
                ),
            )
            add(
                DataTable.Column(
                    "Cost",
                    PAIRED,
                    description = "What the ingredients cost." + costNote +
                        " For most items the two are identical, but cheap raw materials can be " +
                        "several times dearer bought outright - gravel is nineteen times.",
                    sortKey = BazaarSort.COST,
                ),
            )
            add(
                DataTable.Column(
                    "Profit",
                    PAIRED,
                    description = "Coins kept per craft, after the bazaar's cut on the sale." +
                        pairNote +
                        if (forge) {
                            " The gap between the two is what starting sooner costs you."
                        } else {
                            " Several recipes lose money the fast way and make it the slow way, " +
                                "which is why both are here."
                        },
                    sortKey = BazaarSort.ORDER_PROFIT,
                ),
            )
            if (forge) {
                add(
                    DataTable.Column(
                        "Per hour",
                        PAIRED,
                        description = "Profit spread over the time the slot is busy - the only " +
                            "fair way to rank a forge recipe, since durations run from 30 seconds " +
                            "to a week. A 30-second recipe making 259k beats a 6-hour one making " +
                            "11.65M." + pairNote,
                        sortKey = BazaarSort.PER_HOUR,
                    ),
                )
            } else {
                add(
                    DataTable.Column(
                        "Margin",
                        MARGIN,
                        description = "Profit against the money that trade puts up, so cheap and " +
                            "expensive recipes rank on equal terms." + pairNote,
                        sortKey = BazaarSort.MARGIN,
                    ),
                )
            }
            add(
                DataTable.Column(
                    "Vol 7d",
                    VOLUME,
                    description = "Weekly volume of the scarcest ingredient - the one that caps " +
                        "how often this can actually be run.",
                    sortKey = BazaarSort.WEEKLY_VOLUME,
                ),
            )
        }
    }

    /** Buying from an NPC shop and selling on the bazaar - the direction worth trading. */
    fun npcToBazaar(width: Int): List<DataTable.Column> = npcFlips(
        width,
        costTitle = "Cost",
        costDescription = "What the NPC charges per unit. A fixed shop price, not a market.",
        instantTitle = "Now",
        instantDescription = "Profit selling instantly into the bazaar, after tax. Certain, but smaller.",
        orderTitle = "Offer",
        orderDescription = "Profit from a sell offer, after tax. Larger, but only once someone buys.",
        // Buying from a shop: 640 units a day, and that cap is the whole shape of this trade.
        stockLimited = true,
    )

    /** Buying on the bazaar and selling to a shop. Untaxed, since the sale isn't a bazaar sale. */
    fun bazaarToNpc(width: Int): List<DataTable.Column> = npcFlips(
        width,
        costTitle = "Buy",
        costDescription = "What you pay buying instantly on the bazaar.",
        instantTitle = "Now",
        instantDescription = "Profit buying instantly and selling to the shop. No bazaar tax applies.",
        orderTitle = "Order",
        orderDescription = "Profit if a buy order fills at the lower price first. Cheaper, but you wait.",
        // Buying on the bazaar and selling to a shop: neither side has a daily stock.
        stockLimited = false,
    )

    /**
     * Shared shape for both NPC views: cost, then the two exits side by side. Laying them out as a
     * pair is the point - several rows lose money one way and make it the other, which no single
     * column could show.
     *
     * [stockLimited] is true only buying *from* an NPC. Selling to one has no cap, and the bazaar
     * has no daily stock at all. Showing the column on both tabs put a flat "640" on every row of
     * BZ → NPC, where it neither limited anything nor varied between rows: a column that reads the
     * same all the way down distinguishes nothing, and implied a cap that does not exist.
     */
    private fun npcFlips(
        width: Int,
        costTitle: String,
        costDescription: String,
        instantTitle: String,
        instantDescription: String,
        orderTitle: String,
        orderDescription: String,
        stockLimited: Boolean,
    ): List<DataTable.Column> {
        // Both the Profit total and the Stock column exist only where a daily cap does. Without
        // one the "total" is the per-unit profit multiplied by one, which is the Now and Order
        // columns printed a second time - "-45.5 / +949.3" followed by "-45/949".
        val extraWidth = if (stockLimited) NPC_TOTAL + NPC_STOCK else 0
        val fixed = PIN + NPC_PRICE * 3 + extraWidth

        return listOf(
            DataTable.Column("", PIN, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(50), numeric = false),
            DataTable.Column(costTitle, NPC_PRICE, description = costDescription),
            DataTable.Column(
                instantTitle,
                NPC_PRICE,
                description = instantDescription,
                sortKey = BazaarSort.INSTANT_PROFIT,
            ),
            DataTable.Column(
                orderTitle,
                NPC_PRICE,
                description = orderDescription,
                sortKey = BazaarSort.ORDER_PROFIT,
            ),
        ) + if (!stockLimited) {
            // Nothing to add: Now and Order already are the per-unit profits, and with no cap to
            // multiply by there is no third figure to report.
            emptyList()
        } else {
            listOf(
                // A day's takings, which only means something against a shop's daily stock.
                DataTable.Column(
                    "Profit",
                    NPC_TOTAL,
                    description = "A day's profit both ways round: $instantTitle then $orderTitle, " +
                        "each times the whole stock. The Stock column says how many units that is " +
                        "and where the number came from. " +
                        (
                            if (NpcDailyLimit.default > NpcDailyLimit.STANDARD) {
                                "Mayor Diaz's tenfold limit is switched on in the settings."
                            } else {
                                "Turn on Mayor Diaz in the settings while he is in office."
                            }
                            ),
                    sortKey = BazaarSort.TOTAL,
                ),
                // Replaces the weekly volume here, which matters less against a fixed shop price
                // than knowing how many units the total was actually built on.
                DataTable.Column(
                    "Stock",
                    NPC_STOCK,
                    numeric = false,
                    description = "Units per shop behind the total. ○ is the assumed daily limit; " +
                        "■ is what a shop's own stock line said, which is what is left today. " +
                        "×2 means two shops sell it, each with separate stock.",
                ),
            )
        }
    }

    // Fixed so figures stay in line across rows and between tabs.
    const val PIN = 12

    private const val PRICE = 54
    private const val CHANGE = 52
    private const val SPREAD = 48
    private const val VOLUME = 48

    /**
     * Holds two figures and the slash between them, e.g. "105.4k/98.9k".
     *
     * Derived from the worst case rather than the usual one: both halves compact, six characters
     * each at most, plus the separator.
     */
    private const val PAIRED = 78

    /** The same, for a pair of percentages - shorter, since they cap at four characters. */
    private const val MARGIN = 72

    /**
     * Floor for the name column, so a narrow window truncates names rather than folding the column
     * to nothing and leaving a row of anonymous figures.
     */
    private const val MIN_NAME = 90

    private const val NPC_PRICE = 46

    /** Wider than a price column: it carries two figures and a separator. */
    private const val NPC_TOTAL = 92

    /** Holds a marker, a count and an optional "×2". */
    private const val NPC_STOCK = 54
}
