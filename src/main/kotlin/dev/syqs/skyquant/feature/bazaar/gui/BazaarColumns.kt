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
            DataTable.Column("Buy", PRICE, description = "What an instant buy costs."),
            DataTable.Column("Sell", PRICE, description = "What an instant sell pays."),
            DataTable.Column(
                changeTitle,
                CHANGE,
                description = "How the buy price moved this session.",
            ),
            DataTable.Column(
                "Spread",
                SPREAD,
                description = "Gap between buy and sell, as a share of the buy price.",
            ),
        )
    }

    /**
     * The order-to-order flip, which is the trade people actually make: place a buy order, wait,
     * place a sell offer, wait. Instant prices are gone from this view - crossing the spread twice
     * clears almost nothing, so ranking by it flattered items nobody could profit from.
     *
     * Two columns here measure liquidity and are easy to confuse. Depth is what the book holds
     * right now, counted across everything within 1% of the best price rather than the units
     * sitting exactly at it: the question a trader has is "how much can I move", not "how much is
     * at the front of the queue". Vol 7d is the other half - how fast the book refills - because a
     * wide margin on a thin market is a position that cannot be closed.
     */
    fun flips(width: Int): List<DataTable.Column> {
        val fixed = PIN + PRICE * 3 + SPREAD + VOLUME * 2

        return listOf(
            DataTable.Column("", PIN, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(50), numeric = false),
            DataTable.Column(
                "Buy @",
                PRICE,
                description = "What your buy order has to beat: the cheapest anyone is asking.",
            ),
            DataTable.Column(
                "Sell @",
                PRICE,
                description = "What your sell offer has to beat: the best anyone is bidding.",
            ),
            DataTable.Column(
                "Profit",
                PRICE,
                description = "Coins kept per unit, after tax.",
                sortKey = BazaarSort.PROFIT,
            ),
            DataTable.Column(
                "Margin",
                SPREAD,
                description = "Profit as a share of what you put in.",
                sortKey = BazaarSort.MARGIN,
            ),
            DataTable.Column(
                "Depth",
                VOLUME,
                description = "Units you can trade before moving the price. " +
                    "The thinner side of the book, within 1% of the best.",
                sortKey = BazaarSort.DEPTH,
            ),
            DataTable.Column(
                "Vol 7d",
                VOLUME,
                description = "Units traded this week. How fast the market refills.",
                sortKey = BazaarSort.WEEKLY_VOLUME,
            ),
        )
    }

    /**
     * The two recipe views.
     *
     * [forge] adds the per-hour figure and drops the margin. That is not a space compromise but a
     * reading of the trade: a percentage of the money put up says nothing about what a forge slot
     * is worth, since two recipes with the same margin can differ tenfold per hour - durations run
     * from 30 seconds to a week, and a 30-second recipe making 259k beats a 6-hour one making
     * 11.65M. It also keeps the name column wide enough - with every figure given a place the
     * arithmetic left it 32px short of the longest item name.
     *
     * Both pages carry the fast trade and the patient one side by side because several recipes
     * lose money one way and make it the other, which no single column could show. On Cost the two
     * are identical for most items, but cheap raw materials can be several times dearer bought
     * outright - gravel is nineteen times.
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
        // Named after the four bazaar buttons rather than described as fast and patient: the
        // reader is about to press one of them, and "instant buy" needs no translating.
        //
        // The two pages pair different trades, so they need different notes. On the Craft page the
        // pair is speed against patience, both ways through. On the Forge page the left figure
        // instant-buys but *still* sells on an offer, because the recipe's own wait - a median of
        // six hours - makes the minutes a sell offer takes irrelevant, while a buy order that has
        // to fill delays the only scarce thing there is: the slot starting.
        val pairNote = if (forge) {
            " Left: instant buy, sell offer. Right: buy order, sell offer."
        } else {
            " Left: instant buy, instant sell. Right: buy order, sell offer."
        }

        // The cost column is about the ingredients alone, so its pairing is the same on both
        // pages - outright against ordered - whatever the profit columns go on to do with them.
        val costNote = " Left: instant buy. Right: buy order."

        return buildList {
            add(DataTable.Column("", PIN, markerColumn = true))
            add(
                DataTable.Column(
                    "Item",
                    nameWidth,
                    numeric = false,
                    // "AH" marks an auction-house price: the median of the four cheapest listings,
                    // an estimate of what yours would fetch rather than a standing bid. "!" means
                    // the cheapest listing sits far below the others, so that market is thin.
                    description = "What the recipe makes, and how many per craft. " +
                        "\"AH\" sells on the auction house, \"!\" few listings.",
                ),
            )
            add(
                DataTable.Column(
                    "Cost",
                    PAIRED,
                    description = "What the ingredients cost." + costNote,
                    sortKey = BazaarSort.COST,
                ),
            )
            add(
                DataTable.Column(
                    "Profit",
                    PAIRED,
                    description = "Coins kept per craft, after tax." + pairNote,
                    sortKey = BazaarSort.ORDER_PROFIT,
                ),
            )
            if (forge) {
                add(
                    DataTable.Column(
                        "Per hour",
                        PAIRED,
                        description = "Profit per hour of forge time." + pairNote,
                        sortKey = BazaarSort.PER_HOUR,
                    ),
                )
            } else {
                add(
                    DataTable.Column(
                        "Margin",
                        MARGIN,
                        description = "Profit as a share of what you put in." + pairNote,
                        sortKey = BazaarSort.MARGIN,
                    ),
                )
            }
            add(
                DataTable.Column(
                    "Vol 7d",
                    VOLUME,
                    description = "Weekly volume of the scarcest ingredient - " +
                        "what caps how often you can run this.",
                    sortKey = BazaarSort.WEEKLY_VOLUME,
                ),
            )
        }
    }

    /** Buying from an NPC shop and selling on the bazaar - the direction worth trading. */
    fun npcToBazaar(width: Int): List<DataTable.Column> = npcFlips(
        width,
        costTitle = "Cost",
        costDescription = "What the NPC charges. A fixed price, not a market.",
        instantTitle = "Now",
        instantDescription = "Profit with an instant sell, after tax.",
        orderTitle = "Offer",
        orderDescription = "Profit with a sell offer, after tax. Fills once someone buys.",
        // Buying from a shop: 640 units a day, and that cap is the whole shape of this trade.
        stockLimited = true,
    )

    /** Buying on the bazaar and selling to a shop. Untaxed, since the sale isn't a bazaar sale. */
    fun bazaarToNpc(width: Int): List<DataTable.Column> = npcFlips(
        width,
        costTitle = "Buy",
        costDescription = "What an instant buy costs on the bazaar.",
        instantTitle = "Now",
        instantDescription = "Profit with an instant buy, then selling to the shop. No tax.",
        orderTitle = "Order",
        orderDescription = "Profit if a buy order fills first. Cheaper, but you wait for it.",
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
                    description = "A day's profit: $instantTitle then $orderTitle, each times " +
                        "the whole stock. " +
                        (
                            if (NpcDailyLimit.default > NpcDailyLimit.STANDARD) {
                                "Mayor Diaz's tenfold limit is on."
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
                    description = "Units per shop behind the total. " +
                        "○ assumed daily limit, ■ what the shop says is left, ×2 two shops.",
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
