package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.DataCredits
import dev.syqs.skyquant.feature.bazaar.ProductName
import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import dev.syqs.skyquant.feature.bazaar.data.BazaarMarketSummary
import dev.syqs.skyquant.feature.bazaar.data.BazaarPriceTrend
import dev.syqs.skyquant.feature.bazaar.data.BazaarTax
import dev.syqs.skyquant.feature.bazaar.data.BazaarWatchlist
import dev.syqs.skyquant.feature.bazaar.data.CraftProfit
import dev.syqs.skyquant.feature.bazaar.data.AuctionSellPrice
import dev.syqs.skyquant.feature.bazaar.data.CraftSummary
import dev.syqs.skyquant.feature.bazaar.data.RecipeIndex
import dev.syqs.skyquant.feature.bazaar.data.NpcFlipSummary
import dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit
import dev.syqs.skyquant.feature.bazaar.data.NpcSellPrices
import dev.syqs.skyquant.feature.bazaar.data.NpcShopPrices
import dev.syqs.skyquant.gui.Palette
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f

/**
 * Terminal home: the player's tracked items, or a ranking of the market, one view at a time.
 *
 * Tabbed rather than showing both at once. A terminal handles growing complexity by splitting it
 * across many small focused views reached by name, not by packing every figure onto one screen -
 * side by side, each half gets too little width to label its columns, and every view added later
 * makes the crowding worse. One view at a time gets the full width, so its columns can be
 * headed and aligned, and a new tab costs a word in the bar rather than a redesign.
 */
class BazaarHomeScreen(
    private val previousScreen: Screen? = null,
) : Screen(Component.literal("SkyQuant Terminal")) {

    /**
     * [FLIP] was called "Market" while it was the only ranking here. Named for the trade it
     * describes now that there are several: what it ranks is the spread, which is the figure a
     * bazaar-to-bazaar flip lives on, and that only became ambiguous once NPC flips arrived.
     */
    private enum class Tab(val label: String, val defaultSort: DataTable.Sort?) {
        WATCH("Watchlist", null),
        FLIP("Flip", DataTable.Sort(SORT_MARGIN)),

        /**
         * Named for what you do, not for where the item ends up: "NPC → BZ" reads as an
         * instruction, where a label like "NPC" left it ambiguous which way round the trade
         * went - the question that started this pair of views existing.
         */
        NPC_BUY("NPC → BZ", DataTable.Sort(SORT_TOTAL)),
        // Sorted by the order profit rather than a daily total, which this tab has no column for:
        // with no shop stock to multiply by, the per-unit figure is the whole story.
        NPC_SELL("BZ → NPC", DataTable.Sort(SORT_ORDER_PROFIT)),

        CRAFT("Craft", DataTable.Sort(SORT_ORDER_PROFIT)),

        // Sorted per hour rather than by profit, and that is the whole reason it is its own tab:
        // forge durations run from 30 seconds to a week, so the two rankings disagree completely.
        FORGE("Forge", DataTable.Sort(SORT_PER_HOUR)),
    }

    private var tab = Tab.WATCH

    private var panel: ScreenRectangle = ScreenRectangle.empty()

    /**
     * Scroll offset and sort kept per tab rather than once for the screen: they describe a
     * particular table, so sharing them would scroll the watchlist because the NPC list was
     * scrolled, and leave a sort key set that the other tab has no column for.
     */
    private val scrollByTab = mutableMapOf<Tab, Int>()
    private val sortByTab = mutableMapOf<Tab, DataTable.Sort>()

    /** Rows drawn this frame, with what each opens - so clicks don't recompute the layout. */
    private val rows = mutableListOf<Row>()

    private class Row(val bounds: ScreenRectangle, val productId: String, val pinToggle: ScreenRectangle?)

    /** Header explanation to draw last, so it sits above the rows instead of behind them. */
    private var pendingTooltip: Pair<String, String>? = null

    /** Where the current tab's header was drawn, so a click can be tested against it. */
    private var headerY = 0

    /**
     * How far past the visible window the current tab's list runs. Written while drawing, since
     * that is where the row count and the space for them are both known.
     */
    private var maxScroll = 0

    /** "12-25 of 60" for the footer, or null when the whole list is on screen. */
    private var scrollRange: String? = null

    private val scroll: Int get() = scrollByTab[tab] ?: 0

    private fun sortOf(tab: Tab): DataTable.Sort? = sortByTab[tab] ?: tab.defaultSort

    override fun init() {
        val panelWidth = (width - PANEL_MARGIN * 2).coerceAtMost(MAX_PANEL_WIDTH)
        val panelHeight = (height - PANEL_MARGIN * 2).coerceAtMost(MAX_PANEL_HEIGHT)

        panel = ScreenRectangle(
            (width - panelWidth) / 2,
            (height - panelHeight) / 2,
            panelWidth,
            panelHeight,
        )
    }

    private fun tabBounds(option: Tab): ScreenRectangle {
        val index = Tab.entries.indexOf(option)
        return ScreenRectangle(
            panel.position().x() + PADDING + index * (TAB_WIDTH + 4),
            panel.position().y() + TAB_TOP,
            TAB_WIDTH,
            TAB_HEIGHT,
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        BazaarLivePrices.refreshIfStale()
        // Normally already done by MarketDataPreload on joining a world, so these return
        // immediately. Kept as the backstop for the cases the preload can't cover - a terminal
        // opened before the warm-up finished, or a fetch that failed and left nothing loaded.
        NpcSellPrices.loadOnce()
        NpcShopPrices.refresh()
        RecipeIndex.refresh()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        rows.clear()
        pendingTooltip = null

        val pose = Matrix3x2f(graphics.pose())
        val left = panel.position().x()
        val top = panel.position().y()

        // Border first, as a slightly larger plate behind the panel: the fill then covers all
        // but a hairline of it. Cheaper than four edge quads, and the chamfer and the corners
        // stay in agreement for free, since both shapes are built by the same code.
        //
        // The outer radius has to grow with the plate. Keeping the inner radius here left the
        // two curves running at different offsets, so along a corner the gap narrowed to 1/√2
        // of a pixel and the border faded out exactly where the eye follows it.
        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                (left - BORDER_WIDTH).toFloat(), (top - BORDER_WIDTH).toFloat(),
                (left + panel.width() + BORDER_WIDTH).toFloat(),
                (top + panel.height() + BORDER_WIDTH).toFloat(),
                // Both grow with the plate. Worked through on the chamfer's actual geometry: it
                // pulls the right edge back by (cut - distance from the bottom), and the border's
                // bottom sits BORDER_WIDTH lower, so growing the cut by the same amount is what
                // keeps the diagonal exactly BORDER_WIDTH outside the panel's all the way along.
                // Leaving the cut unchanged makes the border twice as thick there instead.
                PANEL_CORNER_RADIUS + BORDER_WIDTH, Palette.BORDER, null,
                PANEL_CORNER_CUT + BORDER_WIDTH,
                softEdges = false,
            ),
        )

        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                left.toFloat(), top.toFloat(),
                (left + panel.width()).toFloat(), (top + panel.height()).toFloat(),
                PANEL_CORNER_RADIUS, Palette.BACKGROUND, null,
                PANEL_CORNER_CUT,
            ),
        )

        drawTitleBar(graphics, left, top)
        drawTabs(graphics, pose, mouseX, mouseY)

        val contentTop = top + CONTENT_TOP
        val contentBottom = top + panel.height() - FOOTER_HEIGHT
        val contentLeft = left + PADDING
        val contentWidth = panel.width() - PADDING * 2

        headerY = contentTop

        when (tab) {
            Tab.WATCH -> drawWatchlist(graphics, pose, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
            Tab.FLIP -> drawFlips(graphics, pose, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
            Tab.NPC_BUY -> drawNpcToBazaar(graphics, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
            Tab.NPC_SELL -> drawBazaarToNpc(graphics, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
            Tab.CRAFT -> drawCrafts(graphics, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
            Tab.FORGE -> drawForges(graphics, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
        }

        drawStatusBar(graphics, contentLeft, top + panel.height() - FOOTER_HEIGHT + 4, contentWidth)

        // Last, so it covers the table rather than being covered by it.
        pendingTooltip?.let { (title, description) -> drawTooltip(graphics, title, description, mouseX, mouseY) }
    }

    private fun drawTitleBar(graphics: GuiGraphicsExtractor, left: Int, top: Int) {
        // The title strip sits a step lighter than the panel. Depth here comes from stacked
        // surfaces rather than a drawn frame: the eye reads the change in lightness as a layer,
        // and every border avoided is geometry the split-phase renderer doesn't have to carry.
        graphics.fill(left, top, left + panel.width(), top + TITLE_BAR_HEIGHT, Palette.SURFACE)
        graphics.fill(
            left, top + TITLE_BAR_HEIGHT, left + panel.width(), top + TITLE_BAR_HEIGHT + 1,
            Palette.RULE,
        )

        graphics.text(font, Component.literal("SKYQUANT"), left + PADDING, top + 10, Palette.NAME)

        // Connection state, where a terminal puts it: the figures below are worthless if the
        // feed is stale, so whether it's live has to be visible without being asked for. The
        // filled/hollow dot carries that on its own, so it survives any theme.
        val connected = BazaarLivePrices.productIds.isNotEmpty()
        val status = if (connected) "● LIVE" else "○ CONNECTING"
        val statusX = left + panel.width() - PADDING - font.width(status)
        graphics.text(
            font,
            Component.literal(status),
            statusX,
            top + 10,
            if (connected) Palette.POSITIVE else Palette.MUTED,
        )

        // Where the figures come from, which Coflnet's terms require to be stated wherever their
        // data is shown. It sits here rather than in the footer because the footer's hints are
        // dropped on a narrow panel, and an attribution that disappears is not one.
        //
        // Centred between the two labels, and dropped only if it would collide with either -
        // same rule as the footer, since a credit printing through the connection state would
        // cost the reading of both.
        val credit = DataCredits.SHORT
        val creditX = left + (panel.width() - font.width(credit)) / 2
        val clearOfTitle = creditX > left + PADDING + font.width("SKYQUANT") + TITLE_GAP
        val clearOfStatus = creditX + font.width(credit) + TITLE_GAP < statusX
        if (clearOfTitle && clearOfStatus) {
            graphics.text(font, Component.literal(credit), creditX, top + 10, Palette.FAINT)
        }
    }

    private fun drawTabs(graphics: GuiGraphicsExtractor, pose: Matrix3x2f, mouseX: Int, mouseY: Int) {
        val left = panel.position().x()
        val barTop = panel.position().y() + TAB_TOP

        // The tab strip continues the title bar's surface down to the rule beneath it. Without
        // it a hover highlight was the only thing painted at this height, so it read as a loose
        // grey block floating over the panel rather than as a tab lighting up.
        graphics.fill(left, barTop, left + panel.width(), barTop + TAB_HEIGHT, Palette.SURFACE)
        graphics.fill(left, barTop + TAB_HEIGHT, left + panel.width(), barTop + TAB_HEIGHT + 1, Palette.RULE)

        for (option in Tab.entries) {
            val box = tabBounds(option)
            val selected = option == tab
            val hovered = box.containsPoint(mouseX, mouseY)

            // Only the hovered tab gets a fill; the selected one is marked by the underline
            // below. A filled pill for both made "where I am" and "where the cursor is" look
            // like the same kind of thing.
            if (hovered && !selected) {
                graphics.fill(
                    box.position().x(), box.position().y(),
                    box.position().x() + box.width(), box.position().y() + box.height(),
                    Palette.ROW_HOVER,
                )
            }

            graphics.centeredText(
                font,
                Component.literal(option.label),
                box.position().x() + box.width() / 2,
                box.position().y() + (box.height() - 8) / 2,
                if (selected) Palette.TEXT else Palette.MUTED,
            )

            // Accent rule under the active tab, drawn last so it sits over the strip's own rule.
            if (selected) {
                graphics.fill(
                    box.position().x(), box.position().y() + box.height() - TAB_UNDERLINE_HEIGHT,
                    box.position().x() + box.width(), box.position().y() + box.height(),
                    Palette.ACCENT,
                )
            }
        }
    }

    private fun drawWatchlist(
        graphics: GuiGraphicsExtractor,
        pose: Matrix3x2f,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val tracked = BazaarWatchlist.tracked

        if (tracked.isEmpty()) {
            drawEmptyState(
                graphics,
                x,
                top,
                listOf(
                    "No items tracked." to Palette.MUTED,
                    "" to Palette.MUTED,
                    "Open any item's price graph and press Track" to Palette.FAINT,
                    "to follow it here. Hold G over an item in any" to Palette.FAINT,
                    "inventory to open its graph." to Palette.FAINT,
                ),
            )
            return
        }

        val table = DataTable(watchColumns(width), x, width)
        var y = table.drawHeader(graphics, font, top)

        table.headerTooltipAt(font, top, mouseX, mouseY)?.let { pendingTooltip = it }

        val visible = visibleRows(top, bottom)
        maxScroll = (tracked.size - visible).coerceAtLeast(0)

        // Left in the player's own order rather than sorted: this is their list, and having it
        // reshuffle itself as prices move would make a familiar row hard to find again.
        for (productId in tracked.drop(scroll).take(visible)) {
            val quote = BazaarLivePrices.quoteFor(productId)
            val change = BazaarPriceTrend.changePercentFor(productId)
            val pinned = BazaarWatchlist.isPinned(productId)

            val bounds = table.drawRow(
                graphics, font, y,
                listOf(
                    DataTable.Cell(if (pinned) "◆" else "◇", if (pinned) Palette.ACCENT else Palette.FAINT),
                    DataTable.Cell(ProductName.of(productId), Palette.NAME),
                    DataTable.Cell(quote?.let { NumberFormats.price(it.buyPrice) } ?: "…", Palette.TEXT),
                    DataTable.Cell(quote?.let { NumberFormats.price(it.sellPrice) } ?: "…", Palette.MUTED),
                    DataTable.Cell(
                        change?.let { NumberFormats.change(it) } ?: "–",
                        change?.let { if (it >= 0) Palette.POSITIVE else Palette.NEGATIVE } ?: Palette.FAINT,
                    ),
                    DataTable.Cell(quote?.let { NumberFormats.percent(it.spreadPercent) } ?: "–", Palette.MUTED),
                ),
                mouseX, mouseY,
            )

            // Pin toggle covers only its own column, so clicking anywhere else opens the graph.
            val pinBounds = ScreenRectangle(x, y, PIN_COLUMN_WIDTH, DataTable.ROW_HEIGHT)
            rows.add(Row(bounds, productId, pinBounds))

            y += DataTable.ROW_HEIGHT
        }

        recordScrollRange(tracked.size, visible)
    }

    private fun drawFlips(
        graphics: GuiGraphicsExtractor,
        pose: Matrix3x2f,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val sort = sortOf(Tab.FLIP)
        val flips = BazaarMarketSummary.bestFlips(LIST_ROWS).sortedWith(marketComparator(sort))

        if (flips.isEmpty()) {
            drawEmptyState(graphics, x, top, listOf("Waiting for the first price snapshot…" to Palette.MUTED))
            return
        }

        val table = DataTable(flipColumns(width), x, width)
        var y = table.drawHeader(graphics, font, top, sort, mouseX, mouseY)

        table.headerTooltipAt(font, top, mouseX, mouseY)?.let { pendingTooltip = it }

        val visible = visibleRows(top, bottom)
        maxScroll = (flips.size - visible).coerceAtLeast(0)

        for (flip in flips.drop(scroll).take(visible)) {
            val bounds = table.drawRow(
                graphics, font, y,
                listOf(
                    DataTable.Cell(if (BazaarWatchlist.isTracked(flip.productId)) "◆" else "", Palette.ACCENT),
                    DataTable.Cell(ProductName.short(flip.productId), Palette.NAME),
                    DataTable.Cell(NumberFormats.price(flip.buyAt), Palette.TEXT),
                    DataTable.Cell(NumberFormats.price(flip.sellAt), Palette.TEXT),
                    DataTable.Cell("▲ " + NumberFormats.price(flip.profitPerUnit), Palette.POSITIVE),
                    DataTable.Cell(NumberFormats.percentCompact(flip.marginPercent), Palette.POSITIVE),
                    // The shallower side, since that is what caps how much of this is real.
                    DataTable.Cell(
                        NumberFormats.volume(minOf(flip.buyDepth, flip.sellDepth)),
                        Palette.MUTED,
                    ),
                    DataTable.Cell(NumberFormats.volume(flip.weeklyVolume), Palette.MUTED),
                ),
                mouseX, mouseY,
            )

            rows.add(Row(bounds, flip.productId, null))
            y += DataTable.ROW_HEIGHT
        }

        recordScrollRange(flips.size, visible)
    }

    /**
     * Items to buy on the bazaar and sell to an NPC shop.
     *
     * The buy figure shown is the *order* price, not the instant-buy one, because that is the
     * trade this ranks - instant-buying and reselling to a shop is worth well under 1% on even
     * the best item, since bots close that gap continuously.
     */
    /**
     * Buy from an NPC shop, sell on the bazaar - the direction worth trading. Measured on live
     * data it reached +2762% against under 1% the other way round.
     */
    private fun drawNpcToBazaar(
        graphics: GuiGraphicsExtractor,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (!NpcShopPrices.isLoaded) {
            drawEmptyState(
                graphics, x, top,
                listOf(
                    "Loading NPC shop prices…" to Palette.MUTED,
                    "" to Palette.MUTED,
                    "First run downloads the item database." to Palette.FAINT,
                    "It is cached afterwards." to Palette.FAINT,
                ),
            )
            return
        }

        drawFlipTable(
            graphics, Tab.NPC_BUY, npcBuyColumns(width), x, top, width, bottom, mouseX, mouseY,
            flips = NpcFlipSummary.npcToBazaar(LIST_ROWS),
            emptyMessage = listOf(
                "Nothing worth buying from a shop right now." to Palette.MUTED,
                "" to Palette.MUTED,
                "This list holds items the bazaar pays more for" to Palette.FAINT,
                "than an NPC charges." to Palette.FAINT,
            ),
        )
    }

    /** Buy on the bazaar, sell to an NPC shop. Untaxed, since the sale isn't a bazaar sale. */
    private fun drawBazaarToNpc(
        graphics: GuiGraphicsExtractor,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (!NpcSellPrices.isLoaded) {
            drawEmptyState(graphics, x, top, listOf("Loading NPC prices…" to Palette.MUTED))
            return
        }

        drawFlipTable(
            graphics, Tab.NPC_SELL, npcSellColumns(width), x, top, width, bottom, mouseX, mouseY,
            flips = NpcFlipSummary.bazaarToNpc(LIST_ROWS),
            emptyMessage = listOf(
                "Nothing worth selling to an NPC right now." to Palette.MUTED,
                "" to Palette.MUTED,
                "Shops rarely pay more than players do." to Palette.FAINT,
            ),
        )
    }

    /**
     * Both NPC views, which differ only in their column headings and where their figures come
     * from. Shared so the two can't drift apart in layout or in how a row responds.
     */
    private fun drawFlipTable(
        graphics: GuiGraphicsExtractor,
        tab: Tab,
        columns: List<DataTable.Column>,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
        flips: List<NpcFlipSummary.Flip>,
        emptyMessage: List<Pair<String, Int>>,
    ) {
        if (flips.isEmpty()) {
            drawEmptyState(graphics, x, top, emptyMessage)
            return
        }

        val sort = sortOf(tab)
        val sorted = flips.sortedWith(flipComparator(sort))

        val table = DataTable(columns, x, width)
        var y = table.drawHeader(graphics, font, top, sort, mouseX, mouseY)

        table.headerTooltipAt(font, top, mouseX, mouseY)?.let { pendingTooltip = it }

        val visible = visibleRows(top, bottom)
        maxScroll = (sorted.size - visible).coerceAtLeast(0)

        // Driven by the columns rather than fixed at seven: the BZ → NPC tab carries neither the
        // daily total nor the Stock column, and a row with more cells than there are columns
        // silently shifts every figure after it under the wrong heading.
        val hasDailyTotal = columns.any { it.title == "Profit" }

        for (flip in sorted.drop(scroll).take(visible)) {
            val bounds = table.drawRow(
                graphics, font, y,
                buildList {
                    add(DataTable.Cell(if (BazaarWatchlist.isTracked(flip.productId)) "◆" else "", Palette.ACCENT))
                    add(DataTable.Cell(ProductName.short(flip.productId), Palette.NAME))
                    add(DataTable.Cell(NumberFormats.price(flip.cost), Palette.TEXT))
                    add(profitCell(flip.instantProfit))
                    add(profitCell(flip.orderProfit))
                    if (hasDailyTotal) {
                        add(totalCell(flip))
                        add(stockCell(flip))
                    }
                },
                mouseX, mouseY,
            )

            rows.add(Row(bounds, flip.productId, null))
            y += DataTable.ROW_HEIGHT
        }

        recordScrollRange(sorted.size, visible)
    }

    /**
     * Buy the ingredients, craft, sell the result - ranked on what one craft clears.
     *
     * One level deep: an ingredient costs what the market charges for it, never what it would
     * cost to craft *that*. It matches the action a player actually takes, and the nested version
     * needs cycle handling before it can say anything this doesn't.
     */
    private fun drawCrafts(
        graphics: GuiGraphicsExtractor,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) = drawCraftTable(
        graphics, Tab.CRAFT, craftColumns(width, forge = false),
        x, top, width, bottom, mouseX, mouseY,
        crafts = CraftSummary.crafts(),
        emptyMessage = recipeEmptyMessage(),
    )

    /**
     * The same trade with a forge slot occupied for a stated time, ranked per hour.
     *
     * Per hour is the whole point of separating it from Craft: durations run from 30 seconds to
     * a week, so ranking on profit puts a week-long recipe above a thirty-second one earning ten
     * times as much over the same day.
     */
    private fun drawForges(
        graphics: GuiGraphicsExtractor,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) = drawCraftTable(
        graphics, Tab.FORGE, craftColumns(width, forge = true),
        x, top, width, bottom, mouseX, mouseY,
        crafts = CraftSummary.forges(),
        emptyMessage = recipeEmptyMessage(),
    )

    /**
     * Says which of the two things a page is waiting for, rather than a bare "nothing here".
     *
     * The recipes and the prices arrive independently and an empty page means one of them is
     * missing - the recipe index downloads once and is then cached for good, while prices
     * refresh every minute.
     */
    private fun recipeEmptyMessage(): List<Pair<String, Int>> = when {
        !RecipeIndex.isLoaded -> listOf(
            "Loading recipes…" to Palette.MUTED,
            "The recipe list downloads once, then stays on disk." to Palette.FAINT,
        )

        BazaarLivePrices.allQuotes().isEmpty() ->
            listOf("Waiting for the first price snapshot…" to Palette.MUTED)

        // Still pricing outputs at auction. Saying "nothing worth making" here was wrong twice
        // over: it isn't known yet, and it reads as a finished answer, so a page that was about
        // to fill looked like a page with nothing on it.
        AuctionSellPrice.isBusy -> listOf(
            "Pricing recipes at the auction house…" to Palette.MUTED,
            "Rows appear as prices arrive, dearest recipes first." to Palette.FAINT,
        )

        else -> listOf(
            "Nothing worth making right now." to Palette.MUTED,
            "Every recipe here costs more than the result sells for." to Palette.FAINT,
        )
    }

    /**
     * Both recipe views, which differ only in their duration column and what they rank on.
     *
     * Shared for the same reason the two NPC views are: two tables built separately drift apart
     * in layout, and a row whose cells don't match its columns silently files every figure after
     * it under the wrong heading.
     */
    private fun drawCraftTable(
        graphics: GuiGraphicsExtractor,
        tab: Tab,
        columns: List<DataTable.Column>,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
        crafts: List<CraftProfit.Craft>,
        emptyMessage: List<Pair<String, Int>>,
    ) {
        if (crafts.isEmpty()) {
            drawEmptyState(graphics, x, top, emptyMessage)
            return
        }

        val sort = sortOf(tab)
        val sorted = crafts.sortedWith(craftComparator(sort))

        val table = DataTable(columns, x, width)
        var y = table.drawHeader(graphics, font, top, sort, mouseX, mouseY)

        table.headerTooltipAt(font, top, mouseX, mouseY)?.let { pendingTooltip = it }

        val visible = visibleRows(top, bottom)
        maxScroll = (sorted.size - visible).coerceAtLeast(0)

        val isForge = tab == Tab.FORGE

        for (craft in sorted.drop(scroll).take(visible)) {
            val bounds = table.drawRow(
                graphics, font, y,
                buildList {
                    add(DataTable.Cell(if (BazaarWatchlist.isTracked(craft.outputId)) "◆" else "", Palette.ACCENT))
                    add(craftNameCell(craft))
                    add(pairedCostCell(craft))
                    add(pairedProfitCell(craft.instantProfit, craft.orderProfit))
                    if (isForge) {
                        add(
                            pairedProfitCell(
                                craft.profitPerHour(craft.instantProfit),
                                craft.profitPerHour(craft.orderProfit),
                            ),
                        )
                    }
                    // Margin is a crafting question. On the Forge page the ranking is the rate,
                    // and a percentage of the money put up says nothing about a slot's worth -
                    // two recipes with the same margin can differ tenfold per hour.
                    if (!isForge) add(pairedMarginCell(craft))
                    add(DataTable.Cell(NumberFormats.volume(craft.weeklyVolume), Palette.MUTED))
                },
                mouseX, mouseY,
            )

            rows.add(Row(bounds, craft.outputId, null))
            y += DataTable.ROW_HEIGHT
        }

        recordScrollRange(sorted.size, visible)
    }

    /**
     * The two ways of buying the ingredients, in one column: outright on the left, on orders on
     * the right.
     *
     * Paired rather than given a column each because the pair is the point - the gap between them
     * is what haste costs. For most items there is no gap at all (the median across 622 liquid
     * products is 0%), but the tail falls on exactly the cheap raw materials recipes use by the
     * hundred: GRAVEL is 19x dearer bought outright.
     *
     * Neutral colours: neither number is good or bad on its own, they are two prices for the same
     * goods. The colour belongs on the profit, which is where a sign actually means something.
     */
    private fun pairedCostCell(craft: CraftProfit.Craft): DataTable.Cell = DataTable.Cell.of(
        DataTable.Cell.Part(NumberFormats.priceCompact(craft.instantCost), Palette.MUTED),
        DataTable.Cell.Part("/", Palette.FAINT),
        DataTable.Cell.Part(NumberFormats.priceCompact(craft.cost), Palette.TEXT),
    )

    /**
     * Fast trade and patient trade side by side, each coloured by its own sign.
     *
     * Colouring the cell by the better of the two is the mistake this avoids: it once put a loss
     * in the same green as the gain beside it, and here that would be routine rather than rare -
     * 8 of the 29 rows the Craft page shows lose money the fast way while making it the slow way.
     * That contrast is the single most useful thing on the row, so it must not be flattened.
     */
    private fun pairedProfitCell(instant: Double, order: Double): DataTable.Cell = DataTable.Cell.of(
        DataTable.Cell.Part(NumberFormats.priceCompact(instant), ProfitColor.of(instant)),
        DataTable.Cell.Part("/", Palette.FAINT),
        DataTable.Cell.Part(NumberFormats.priceCompact(order), ProfitColor.of(order)),
    )

    /**
     * Both margins, each against the money its own trade actually puts up.
     *
     * `percentCompact` rather than `percent`: a craft margin reaches four figures - the plan
     * measured Packed Ice at 6467% - and at one decimal that runs straight through the column
     * beside it.
     */
    private fun pairedMarginCell(craft: CraftProfit.Craft): DataTable.Cell = DataTable.Cell.of(
        DataTable.Cell.Part(
            NumberFormats.percentCompact(craft.instantMargin),
            ProfitColor.of(craft.instantProfit),
        ),
        DataTable.Cell.Part("/", Palette.FAINT),
        DataTable.Cell.Part(
            NumberFormats.percentCompact(craft.orderMargin),
            ProfitColor.of(craft.orderProfit),
        ),
    )

    /**
     * The item's name, marked when its price came from the auction house rather than the bazaar.
     *
     * The two are not the same kind of figure and the row should say so. A bazaar price is what
     * an order book will pay this minute; an auction price is the median of the four cheapest
     * listings, which is an estimate of what yours would fetch - thinner evidence, and it sells
     * only when somebody comes along.
     *
     * The warning sign is a second, stronger claim: the cheapest listing sits far below the rest,
     * so the market underneath the estimate is thin enough for one seller's mistake to show in
     * it. Divan's Drill's lowest was 31% of its median when this was written. A glyph rather than
     * a colour, per the house rule that colour never carries meaning alone.
     */
    private fun craftNameCell(craft: CraftProfit.Craft): DataTable.Cell {
        if (!craft.fromAuction) return DataTable.Cell(craftName(craft), Palette.NAME)

        return DataTable.Cell.of(
            DataTable.Cell.Part(craftName(craft), Palette.NAME),
            DataTable.Cell.Part(" AH", Palette.FAINT),
            DataTable.Cell.Part(if (craft.hasOutlierListing) "!" else "", Palette.NEGATIVE),
        )
    }

    /**
     * The item's name, with how many one craft yields when that isn't one.
     *
     * DIAMOND's recipe makes nine at a time, and the profit column is for the whole craft rather
     * than per unit - without the count the two figures look inconsistent with the price beside
     * them.
     */
    private fun craftName(craft: CraftProfit.Craft): String {
        val name = ProductName.short(craft.outputId)
        val count = craft.outputCount

        return if (count > 1.0) "$name ×${NumberFormats.volume(count.toLong())}" else name
    }

    /**
     * A profit figure with an arrow for its sign.
     *
     * The arrow is not decoration: several rows here lose money one way and make it the other,
     * and that difference has to survive a theme where the two colours are hard to tell apart.
     */
    private fun profitCell(profit: Double): DataTable.Cell = DataTable.Cell(
        ProfitColor.arrow(profit) + " " + NumberFormats.price(kotlin.math.abs(profit)),
        ProfitColor.of(profit),
    )

    /**
     * A day's takings both ways round, in one column: instant on the left of the slash, order
     * on the right.
     *
     * Paired rather than given a column each because the two are read against each other - the
     * question is which route is worth the wait - and because two more numeric columns is more
     * than the panel has room for. One decimal throughout: "174.53k/307.28k" overflows the
     * column, and the second decimal is below the noise of a price that moves every minute.
     *
     * Coloured by the better of the two, since that is what the row is ranked on, and left
     * neutral where nothing is positive.
     */
    /**
     * A day's takings both ways round: instant on the left of the slash, order on the right.
     *
     * Each half is coloured by its own sign. Colouring the cell by the better of the two put a
     * loss in the same green as the gain next to it - Minnow Bait read "-2.6k/92.5k" entirely
     * in green, which says the opposite of what the minus sign says.
     */
    private fun totalCell(flip: NpcFlipSummary.Flip): DataTable.Cell {
        // Only ever drawn on the tab that buys from a shop, where this cap is real. Selling to an
        // NPC has none - the bazaar sells as much as its order book holds and an NPC buys without
        // limit - so that tab drops this column rather than multiplying by an invented ceiling.
        val limit = NpcDailyLimit.forProduct(flip.productId, flip.sellers)

        return DataTable.Cell.of(
            totalPart(flip.instantProfit * limit),
            DataTable.Cell.Part("/", Palette.FAINT),
            totalPart(flip.orderProfit * limit),
        )
    }

    private fun totalPart(total: Double) = DataTable.Cell.Part(
        NumberFormats.priceCompact(total),
        ProfitColor.of(total),
    )

    /**
     * How many units the row's total is built on, and where that number came from.
     *
     * Its own column because the total is otherwise unfalsifiable: the default and a shop's
     * reading are both usually 640, so a figure computed from either looks identical, and there
     * is no way to tell whether reading the shop did anything at all. Here a dot marks an
     * assumed figure and a filled square one that was read, with the seller count when an item
     * is stocked by more than one shop.
     */
    private fun stockCell(flip: NpcFlipSummary.Flip): DataTable.Cell {
        val known = NpcDailyLimit.isKnown(flip.productId)
        val perShop = NpcDailyLimit.forProduct(flip.productId)

        val shops = if (flip.sellers > 1) "×${flip.sellers}" else ""
        val marker = if (known) "■" else "○"

        return DataTable.Cell(
            "$marker${NumberFormats.volume(perShop.toLong())}$shops",
            if (known) Palette.TEXT else Palette.FAINT,
        )
    }

    /** How many rows fit between the header and the footer. */
    private fun visibleRows(top: Int, bottom: Int): Int =
        ((bottom - (top + DataTable.HEADER_HEIGHT)) / DataTable.ROW_HEIGHT).coerceAtLeast(1)

    /**
     * Records the range on screen, for the footer to report. Not drawn here: at the bottom of
     * the table it landed on the same line as the footer hints, which are also right-aligned,
     * and the two printed straight through each other.
     */
    private fun recordScrollRange(total: Int, visible: Int) {
        scrollRange = if (total <= visible) null else "${scroll + 1}-${minOf(scroll + visible, total)} of $total"
    }

    private fun marketComparator(sort: DataTable.Sort?): Comparator<BazaarMarketSummary.Flip> {
        val by = when (sort?.key) {
            SORT_PROFIT -> compareBy<BazaarMarketSummary.Flip> { it.profitPerUnit }
            SORT_DEPTH -> compareBy { minOf(it.buyDepth, it.sellDepth) }
            else -> compareBy { it.marginPercent }
        }
        return if (sort?.descending != false) by.reversed() else by
    }

    /**
     * Columns for the two recipe views.
     *
     * [forge] adds the duration and the per-hour figure, and drops the instant exit. That is not
     * a space compromise but a reading of the trade: a recipe you waited six hours for is not one
     * you then sell at the standing bid. It also keeps the name column wide enough - with every
     * figure given a place the arithmetic left it 32px short of the longest item name.
     */
    private fun craftColumns(width: Int, forge: Boolean): List<DataTable.Column> {
        // Every figure column holds a pair, so the whole table reads on one convention: left of
        // the slash is the fast trade, right of it the patient one.
        val pairedColumns = if (forge) 3 else 2
        val fixed = PIN_COLUMN_WIDTH + VOLUME_COLUMN_WIDTH + PAIRED_COLUMN_WIDTH * pairedColumns +
            if (forge) 0 else MARGIN_COLUMN_WIDTH
        val nameWidth = (width - fixed).coerceAtLeast(MIN_NAME_COLUMN_WIDTH)

        // Repeated in each description rather than stated once: a tooltip is read on its own,
        // and a reader hovering "Profit" has no reason to have hovered "Cost" first.
        val pairNote = " Left of the slash: buy outright and sell into the bids, done in a minute. " +
            "Right: a buy order and a sell offer, cheaper and slower."

        return buildList {
            add(DataTable.Column("", PIN_COLUMN_WIDTH, markerColumn = true))
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
                    PAIRED_COLUMN_WIDTH,
                    description = "What the ingredients cost." + pairNote +
                        " For most items the two are identical, but cheap raw materials can be " +
                        "several times dearer bought outright - gravel is nineteen times.",
                    sortKey = SORT_COST,
                ),
            )
            add(
                DataTable.Column(
                    "Profit",
                    PAIRED_COLUMN_WIDTH,
                    description = "Coins kept per craft, after the bazaar's cut on the sale." +
                        pairNote + " Several recipes lose money the fast way and make it the slow " +
                        "way, which is why both are here.",
                    sortKey = SORT_ORDER_PROFIT,
                ),
            )
            if (forge) {
                add(
                    DataTable.Column(
                        "Per hour",
                        PAIRED_COLUMN_WIDTH,
                        description = "Profit spread over the time the slot is busy - the only " +
                            "fair way to rank a forge recipe, since durations run from 30 seconds " +
                            "to a week. A 30-second recipe making 259k beats a 6-hour one making " +
                            "11.65M." + pairNote,
                        sortKey = SORT_PER_HOUR,
                    ),
                )
            } else {
                add(
                    DataTable.Column(
                        "Margin",
                        MARGIN_COLUMN_WIDTH,
                        description = "Profit against the money that trade puts up, so cheap and " +
                            "expensive recipes rank on equal terms." + pairNote,
                        sortKey = SORT_MARGIN,
                    ),
                )
            }
            add(
                DataTable.Column(
                    "Vol 7d",
                    VOLUME_COLUMN_WIDTH,
                    description = "Weekly volume of the scarcest ingredient - the one that caps " +
                        "how often this can actually be run.",
                ),
            )
        }
    }

    private fun craftComparator(sort: DataTable.Sort?): Comparator<CraftProfit.Craft> {
        val by = when (sort?.key) {
            SORT_INSTANT_PROFIT -> compareBy<CraftProfit.Craft> { it.instantProfit }
            SORT_MARGIN -> compareBy { it.orderMargin }
            SORT_COST -> compareBy { it.cost }
            // Per hour and per craft are the same ordering when nothing has a duration, which is
            // why the Craft tab can share this comparator without a special case.
            SORT_PER_HOUR -> compareBy { it.profitPerHour(it.orderProfit) }
            else -> compareBy { it.orderProfit }
        }
        return if (sort?.descending != false) by.reversed() else by
    }

    private fun flipComparator(sort: DataTable.Sort?): Comparator<NpcFlipSummary.Flip> {
        val by = when (sort?.key) {
            SORT_INSTANT_PROFIT -> compareBy<NpcFlipSummary.Flip> { it.instantProfit }
            SORT_ORDER_PROFIT -> compareBy { it.orderProfit }
            // The daily limit is the same multiplier on every row, so ranking by the total is
            // ranking by the better per-unit profit - no need to multiply just to compare.
            else -> compareBy { maxOf(it.instantProfit, it.orderProfit) }
        }
        return if (sort?.descending != false) by.reversed() else by
    }

    /**
     * Column widths are derived from the panel so the name column absorbs whatever is left over,
     * keeping the figures a fixed distance from the right edge at any window size.
     */
    private fun watchColumns(width: Int): List<DataTable.Column> {
        val fixed = PIN_COLUMN_WIDTH + PRICE_COLUMN_WIDTH * 2 + CHANGE_COLUMN_WIDTH + SPREAD_COLUMN_WIDTH
        return listOf(
            DataTable.Column("", PIN_COLUMN_WIDTH, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(60), numeric = false),
            DataTable.Column("Buy", PRICE_COLUMN_WIDTH, description = "What you pay to buy this item instantly."),
            DataTable.Column("Sell", PRICE_COLUMN_WIDTH, description = "What you receive selling it instantly."),
            DataTable.Column(
                changeColumnTitle(),
                CHANGE_COLUMN_WIDTH,
                description = "How the buy price moved over the session so far.",
            ),
            DataTable.Column(
                "Spread",
                SPREAD_COLUMN_WIDTH,
                description = "Gap between buy and sell, as a share of the buy price. The margin a flip has to work with.",
            ),
        )
    }

    /**
     * The order-to-order flip, which is the trade people actually make: place a buy order, wait,
     * place a sell offer, wait. Instant prices are gone from this view - crossing the spread
     * twice clears almost nothing, so ranking by it flattered items nobody could profit from.
     */
    private fun flipColumns(width: Int): List<DataTable.Column> {
        val fixed = PIN_COLUMN_WIDTH + PRICE_COLUMN_WIDTH * 3 + SPREAD_COLUMN_WIDTH + VOLUME_COLUMN_WIDTH * 2
        return listOf(
            DataTable.Column("", PIN_COLUMN_WIDTH, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(50), numeric = false),
            DataTable.Column(
                "Buy @",
                PRICE_COLUMN_WIDTH,
                description = "Where your buy order has to sit to compete: the cheapest price " +
                    "anyone is currently asking.",
            ),
            DataTable.Column(
                "Sell @",
                PRICE_COLUMN_WIDTH,
                description = "Where your sell offer fills: the best price anyone is currently bidding.",
            ),
            DataTable.Column(
                "Profit",
                PRICE_COLUMN_WIDTH,
                description = "Coins kept per unit after the bazaar's cut on the sale.",
                sortKey = SORT_PROFIT,
            ),
            DataTable.Column(
                "Margin",
                SPREAD_COLUMN_WIDTH,
                description = "Profit against what it costs to get in. Ranks cheap and expensive " +
                    "items on equal terms.",
                sortKey = SORT_MARGIN,
            ),
            DataTable.Column(
                "Depth",
                VOLUME_COLUMN_WIDTH,
                description = "Units queued at those two prices, whichever side is thinner. " +
                    "Buy more than this and you move the price against yourself.",
                sortKey = SORT_DEPTH,
            ),
            DataTable.Column(
                "Vol 7d",
                VOLUME_COLUMN_WIDTH,
                description = "Units traded this week. A wide margin on a thin market is a " +
                    "position you can't get out of.",
            ),
        )
    }

    /**
     * Shared shape for both NPC views: cost, then the two exits side by side, each with its
     * profit and its margin. Laying them out as a pair is the point - several rows lose money
     * one way and make it the other, which no single column could show.
     */
    private fun flipTableColumns(
        width: Int,
        costTitle: String,
        costDescription: String,
        instantTitle: String,
        instantDescription: String,
        orderTitle: String,
        orderDescription: String,
        /**
         * Whether a shop's daily stock limits the trade.
         *
         * True only buying *from* an NPC. Selling to one has no cap, and the bazaar has no daily
         * stock at all - you can buy as much as the order book holds. Showing the column on both
         * tabs put a flat "640" on every row of BZ → NPC, where it neither limited anything nor
         * varied between rows: a column that reads the same all the way down distinguishes
         * nothing, and implied a cap that does not exist.
         */
        stockLimited: Boolean,
    ): List<DataTable.Column> {
        // Both the Profit total and the Stock column exist only where a daily cap does. Without
        // one the "total" is the per-unit profit multiplied by one, which is the Now and Order
        // columns printed a second time - "-45.5 / +949.3" followed by "-45/949".
        val extraWidth = if (stockLimited) NPC_TOTAL_WIDTH + NPC_STOCK_WIDTH else 0
        val fixed = PIN_COLUMN_WIDTH + NPC_PRICE_WIDTH * 3 + extraWidth

        return listOf(
            DataTable.Column("", PIN_COLUMN_WIDTH, numeric = false, markerColumn = true),
            DataTable.Column("Item", (width - fixed).coerceAtLeast(50), numeric = false),
            DataTable.Column(costTitle, NPC_PRICE_WIDTH, description = costDescription),
            DataTable.Column(
                instantTitle,
                NPC_PRICE_WIDTH,
                description = instantDescription,
                sortKey = SORT_INSTANT_PROFIT,
            ),
            DataTable.Column(
                orderTitle,
                NPC_PRICE_WIDTH,
                description = orderDescription,
                sortKey = SORT_ORDER_PROFIT,
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
                    NPC_TOTAL_WIDTH,
                    description = "A day's profit both ways round: $instantTitle then $orderTitle, " +
                        "each times the whole stock. The Stock column says how many units that is " +
                        "and where the number came from. " +
                        (if (NpcDailyLimit.default > NpcDailyLimit.STANDARD) {
                            "Mayor Diaz's tenfold limit is switched on in the settings."
                        } else {
                            "Turn on Mayor Diaz in the settings while he is in office."
                        }),
                    sortKey = SORT_TOTAL,
                ),
                // Replaces the weekly volume here, which matters less against a fixed shop price
                // than knowing how many units the total was actually built on.
                DataTable.Column(
                    "Stock",
                    NPC_STOCK_WIDTH,
                    numeric = false,
                    description = "Units per shop behind the total. ○ is the assumed daily limit; " +
                        "■ is what a shop's own stock line said, which is what is left today. " +
                        "×2 means two shops sell it, each with separate stock.",
                ),
            )
        }
    }

    private fun npcBuyColumns(width: Int): List<DataTable.Column> = flipTableColumns(
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

    private fun npcSellColumns(width: Int): List<DataTable.Column> = flipTableColumns(
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
     * Labelled with the window actually recorded rather than a fixed "15min": history only
     * covers the current session, so early on the figure spans far less than the full window
     * and a fixed label would misstate what it measures.
     */
    private fun changeColumnTitle(): String {
        val span = BazaarWatchlist.tracked.maxOfOrNull { BazaarPriceTrend.spanMillis(it) } ?: 0
        if (span <= 0) return "Change"

        val minutes = (span / 60_000).coerceAtLeast(1)
        return "${minutes}m"
    }

    private fun drawEmptyState(graphics: GuiGraphicsExtractor, x: Int, top: Int, lines: List<Pair<String, Int>>) {
        for ((index, line) in lines.withIndex()) {
            graphics.text(font, Component.literal(line.first), x, top + index * LINE_HEIGHT, line.second)
        }
    }

    /**
     * Footer with counts and the keys that work here - the terminal convention of keeping
     * available actions on screen rather than leaving them to be discovered.
     */
    private fun drawStatusBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int) {
        // Which rows are on screen goes on the left, ahead of the collection counts: it changes
        // as the player scrolls, so it is the live figure of the three.
        val left = listOfNotNull(
            scrollRange,
            "${BazaarWatchlist.tracked.size} tracked",
            "${BazaarWatchlist.pinned.size} pinned",
        ).joinToString(" · ")

        graphics.text(font, Component.literal(left), x, y, Palette.FAINT)

        val hints = when (tab) {
            Tab.WATCH -> "row → graph · ◇ → pin"
            // The rate is stated on every view whose figures are net of it, with where it came
            // from: a net figure whose tax is invisible is one the player cannot check.
            // "assumed" is the prompt to go and fix it, and goes away once calibrated.
            Tab.FLIP, Tab.NPC_BUY -> taxNote() + " · heading → sort"
            else -> "row → graph · heading → sort"
        }

        // Dropped rather than allowed to overlap. Both halves are right- and left-anchored to
        // the same strip, so on a narrow panel they print through each other - which is exactly
        // what the earlier version did - and a hint is worth less than a figure.
        val hintX = x + width - font.width(hints)
        if (hintX > x + font.width(left) + FOOTER_GAP) {
            graphics.text(font, Component.literal(hints), hintX, y, Palette.FAINT)
        }
    }

    /**
     * How the tax used in this view's figures was arrived at.
     *
     * Says "assumed" while nothing is known, since that is the state the player can do something
     * about - open the Community Shop once, or set it in the config - and the one where the
     * figures are least likely to match what they actually get.
     */
    private fun taxNote(): String {
        val percent = NumberFormats.exactPercent(BazaarTax.rate * 100.0)
        return when {
            BazaarTax.isFromSetting -> "tax $percent (set)"
            BazaarTax.knownRate != null -> "tax $percent"
            else -> "tax $percent assumed"
        }
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor, title: String, description: String, mouseX: Int, mouseY: Int) {
        val wrapped = font.split(Component.literal(description), TOOLTIP_MAX_WIDTH)
        val bodyWidth = wrapped.maxOfOrNull { font.width(it) } ?: 0
        val boxWidth = maxOf(font.width(title), bodyWidth) + 12
        val boxHeight = (wrapped.size + 1) * LINE_HEIGHT + 8

        val x = (mouseX + 8).coerceAtMost(panel.position().x() + panel.width() - boxWidth)
        val y = mouseY + 10

        graphics.fill(x, y, x + boxWidth, y + boxHeight, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(title.uppercase()), x + 6, y + 4, Palette.TEXT)

        for ((index, line) in wrapped.withIndex()) {
            graphics.text(font, line, x + 6, y + 4 + (index + 1) * LINE_HEIGHT, Palette.MUTED)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)

        val x = event.x().toInt()
        val y = event.y().toInt()

        for (option in Tab.entries) {
            if (tabBounds(option).containsPoint(x, y)) {
                tab = option
                return true
            }
        }

        // Before the rows, since the header sits above them and a click there means "sort",
        // never "open this item".
        columnsFor(tab)?.let { columns ->
            DataTable(columns, panel.position().x() + PADDING, panel.width() - PADDING * 2)
                .sortKeyAt(headerY, x, y)
                ?.let { key ->
                    sortByTab[tab] = sortOf(tab)?.toggled(key) ?: DataTable.Sort(key)
                    // Back to the top: after re-sorting, the rows under the cursor are not the
                    // ones that were there a moment ago, and staying at row 40 of a list that
                    // just reordered shows an arbitrary slice of it.
                    scrollByTab[tab] = 0
                    return true
                }
        }

        for (row in rows) {
            if (!row.bounds.containsPoint(x, y)) continue

            if (row.pinToggle != null && row.pinToggle.containsPoint(x, y)) {
                BazaarWatchlist.togglePinned(row.productId)
            } else {
                // Carries this screen as the one to return to, so closing the graph comes back
                // here rather than dropping the player into the world.
                minecraft.setScreen(BazaarGraphScreen(row.productId, this))
            }
            return true
        }

        return super.mouseClicked(event, doubleClick)
    }

    /** The sortable columns of whichever tab is open, or null for one that isn't sortable. */
    private fun columnsFor(tab: Tab): List<DataTable.Column>? {
        val width = panel.width() - PADDING * 2
        return when (tab) {
            Tab.WATCH -> null
            Tab.FLIP -> flipColumns(width)
            Tab.NPC_BUY -> npcBuyColumns(width)
            Tab.NPC_SELL -> npcSellColumns(width)
            Tab.CRAFT -> craftColumns(width, forge = false)
            Tab.FORGE -> craftColumns(width, forge = true)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

        // Wheel up (positive) moves toward the start of the list, matching every other list the
        // player uses. Clamped against the count measured while drawing, so the last page can't
        // be scrolled past into empty space.
        val next = (scroll - scrollY.toInt() * SCROLL_STEP).coerceIn(0, maxScroll)
        scrollByTab[tab] = next
        return true
    }

    override fun onClose() {
        if (previousScreen != null) {
            minecraft.setScreen(previousScreen)
        } else {
            super.onClose()
        }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        /** Opens the terminal, keeping whatever is currently open to return to. */
        fun open() {
            val minecraft = Minecraft.getInstance()
            minecraft.setScreen(BazaarHomeScreen(minecraft.screen))
        }

        // Shared with BazaarGraphScreen so the screens read as one tool.


        private const val MAX_PANEL_WIDTH = 504
        private const val MAX_PANEL_HEIGHT = 296
        private const val PANEL_MARGIN = 24
        private const val PANEL_CORNER_RADIUS = 6f
        private const val PADDING = 12

        /** Hairline around the panel: one pixel, so it frames without boxing the layout in. */
        private const val BORDER_WIDTH = 1

        /** Diagonal cut on the bottom-right corner - the mod's one flourish, used once. */
        private const val PANEL_CORNER_CUT = 14f

        private const val TAB_TOP = 26
        private const val TAB_WIDTH = 60
        private const val TAB_HEIGHT = 14

        /** Thickness of the accent rule marking the active tab. */
        private const val TAB_UNDERLINE_HEIGHT = 2

        /**
         * The title strip ends exactly where the tabs begin. Derived rather than written out, so
         * moving the tabs can't leave a seam or an overlap behind.
         */
        private const val TITLE_BAR_HEIGHT = TAB_TOP

        /** Below the tab bar, with a gap so the table doesn't crowd it. */
        private const val CONTENT_TOP = 50
        private const val FOOTER_HEIGHT = 18
        private const val LINE_HEIGHT = 10

        // Fixed so figures stay in line across rows and between the two tabs.
        private const val PIN_COLUMN_WIDTH = 12

        /**
         * Holds two figures and the slash between them, e.g. "105.4k/98.9k".
         *
         * Derived from the worst case rather than the usual one: both halves compact, six
         * characters each at most, plus the separator.
         */
        private const val PAIRED_COLUMN_WIDTH = 78

        /** The same, for a pair of percentages - shorter, since they cap at four characters. */
        private const val MARGIN_COLUMN_WIDTH = 72

        /**
         * Floor for the name column, so a narrow window truncates names rather than folding the
         * column to nothing and leaving a row of anonymous figures.
         */
        private const val MIN_NAME_COLUMN_WIDTH = 90
        private const val PRICE_COLUMN_WIDTH = 54
        private const val CHANGE_COLUMN_WIDTH = 52
        private const val SPREAD_COLUMN_WIDTH = 48
        private const val VOLUME_COLUMN_WIDTH = 48

        private const val TOOLTIP_MAX_WIDTH = 160

        /**
         * Rows fetched per ranking. Well past what fits on screen, since scrolling is what the
         * rest go to - the old limit was the number of visible rows, which is why there was
         * nothing to scroll to before.
         */
        private const val LIST_ROWS = 60

        /** Rows per wheel notch. Three is the usual step and keeps place easy to follow. */
        private const val SCROLL_STEP = 3

        // Sort keys. Shared between the column definitions and the comparators, so a typo is a
        // compile error rather than a column that silently does nothing when clicked. Only the
        // figures a view is actually ranked on get one - a heading that offers to sort implies
        // that ordering by it is a useful way to read the list.
        private const val SORT_PROFIT = "profit"
        private const val SORT_MARGIN = "margin"
        private const val SORT_DEPTH = "depth"
        private const val SORT_INSTANT_PROFIT = "instantProfit"
        private const val SORT_ORDER_PROFIT = "orderProfit"
        private const val SORT_TOTAL = "total"
        private const val SORT_COST = "cost"
        private const val SORT_PER_HOUR = "perHour"

        private const val NPC_PRICE_WIDTH = 46

        /** Wider than a price column: it carries two figures and a separator. */
        private const val NPC_TOTAL_WIDTH = 92

        /** Holds a marker, a count and an optional "×2". */
        private const val NPC_STOCK_WIDTH = 54

        /** Clear space kept between the footer's two halves before the hint is dropped. */
        private const val FOOTER_GAP = 12

        /** The same, for the title bar's three labels before the middle one is dropped. */
        private const val TITLE_GAP = 12
    }
}
