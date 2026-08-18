package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.DataCredits
import dev.syqs.skyquant.feature.bazaar.ForgeTracker
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
import dev.syqs.skyquant.feature.bazaar.data.ForgeLedger
import dev.syqs.skyquant.feature.bazaar.data.Liquidity
import dev.syqs.skyquant.feature.bazaar.data.NpcShopPrices
import dev.syqs.skyquant.feature.bazaar.data.WorkingCapital
import dev.syqs.skyquant.feature.bazaar.data.WorkingCapitalSummary
import dev.syqs.skyquant.feature.bazaar.data.WorkingGroup
import dev.syqs.skyquant.feature.bazaar.data.WorkingItem
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
        /**
         * What the player has working right now, rather than where the market's profit is.
         *
         * First, and the one the terminal opens on: it is the only view about the player's own
         * position rather than about prices, so it is what "how am I doing" opens to. Every other
         * tab ranks a market; this one adds up coins already committed.
         *
         * Its rule for what belongs is narrow on purpose - capital that is currently maturing.
         * Daily quests, commissions and powders were considered and rejected: they are things to
         * do rather than money in motion, and the tab list already shows them without opening
         * anything.
         */
        STATUS("Status", null),

        WATCH("Watchlist", null),
        FLIP("Flip", DataTable.Sort(BazaarSort.MARGIN)),

        /**
         * Named for what you do, not for where the item ends up: "NPC → BZ" reads as an
         * instruction, where a label like "NPC" left it ambiguous which way round the trade
         * went - the question that started this pair of views existing.
         */
        NPC_BUY("NPC → BZ", DataTable.Sort(BazaarSort.TOTAL)),
        // Sorted by the order profit rather than a daily total, which this tab has no column for:
        // with no shop stock to multiply by, the per-unit figure is the whole story.
        NPC_SELL("BZ → NPC", DataTable.Sort(BazaarSort.ORDER_PROFIT)),

        CRAFT("Craft", DataTable.Sort(BazaarSort.ORDER_PROFIT)),

        // Sorted per hour rather than by profit, and that is the whole reason it is its own tab:
        // forge durations run from 30 seconds to a week, so the two rankings disagree completely.
        FORGE("Forge", DataTable.Sort(BazaarSort.PER_HOUR)),
    }

    // Status: opening on "what have I got working" rather than on a market ranking, since that is
    // the question the terminal is opened with most often.
    private var tab = Tab.STATUS

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

    /** Status rows drawn this frame, keyed by which group they expand. */
    private val statusRows = mutableListOf<StatusRow>()

    private class StatusRow(val bounds: ScreenRectangle, val groupId: String)

    /** Item rows inside expanded groups, each opening that product's graph. */
    private val statusItemRows = mutableListOf<StatusItemRow>()

    private class StatusItemRow(val bounds: ScreenRectangle, val productId: String)

    /**
     * Which Status groups are open. Kept for the life of the screen, so opening the forge and
     * switching tabs to check a price doesn't collapse it on the way back.
     */
    private val expandedGroups = mutableSetOf<String>()

    /** Whether the opening row has been chosen yet - see the note in [drawStatus]. */
    private var statusExpansionInitialised = false

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
        statusRows.clear()
        statusItemRows.clear()
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
            Tab.STATUS -> drawStatus(graphics, contentLeft, contentTop, contentWidth, contentBottom, mouseX, mouseY)
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

        // Connection state, where a terminal puts it: the figures below are worthless if the
        // feed is stale, so whether it's live has to be visible without being asked for. The
        // filled/hollow dot carries that on its own, so it survives any theme.
        //
        // Three states, not two. This used to ask only whether any product had loaded, which stays
        // true forever once the first snapshot lands - so the indicator read LIVE through a
        // network outage while the prices beneath it aged. STALE is the case that matters: it is
        // the one where the numbers still look authoritative and are not.
        val loaded = BazaarLivePrices.productIds.isNotEmpty()
        val connected = loaded && BazaarLivePrices.isFresh()
        val status = when {
            connected -> "● LIVE"
            loaded -> "◐ STALE " + staleAge()
            else -> "○ CONNECTING"
        }
        val statusX = left + panel.width() - PADDING - font.width(status)
        graphics.text(
            font,
            Component.literal(status),
            statusX,
            top + 10,
            when {
                connected -> Palette.POSITIVE
                // Amber rather than grey: a stale feed is a fault to act on, where "connecting"
                // is the ordinary first few seconds of a session.
                loaded -> Palette.STALE
                else -> Palette.MUTED
            },
        )

        // The mod's name, centred on the panel rather than tucked into the left corner: with the
        // credit moved to the footer this strip carries two things, and a centred title reads as
        // the heading of the whole terminal instead of as the first of a row of labels.
        //
        // Centred on the panel, not on the space left over beside the status - so it stays put as
        // the status text changes between LIVE, STALE and CONNECTING, which are different widths.
        // Dropped only if it would actually collide, for the same reason the footer drops its own
        // right half: a title printing through the connection state costs the reading of both.
        val title = "SKYQUANT"
        val titleX = left + (panel.width() - font.width(title)) / 2
        if (titleX + font.width(title) + TITLE_GAP < statusX) {
            graphics.text(font, Component.literal(title), titleX, top + 10, Palette.NAME)
        }
    }

    /**
     * How far behind the feed has fallen, as "3m" or "45s".
     *
     * A bare "STALE" leaves the reader unable to judge whether to wait or to go and check their
     * connection; the age answers that without them having to ask.
     */
    private fun staleAge(): String {
        val age = BazaarLivePrices.snapshotAgeMillis() ?: return ""
        val seconds = age / 1000

        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
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

    /**
     * What the player has working right now, one line per source, expandable for the detail.
     *
     * A list rather than the quadrants first sketched, and the arithmetic is why: the five
     * sources fully expanded want 698px against the 250 this page has - 2.8 times over, with
     * minions alone larger than the whole page. Quadrants would have hit the same wall inside a
     * narrower box. Summarising by default costs 88px and leaves 162 to spend on whichever row
     * the player opens, which is enough for all seven forge slots at once.
     *
     * The total is the figure no other screen gives, and it is deliberately hard to trust
     * wrongly: it counts only what could actually be priced, and says so when a source could not
     * be read at all.
     */
    private fun drawStatus(
        graphics: GuiGraphicsExtractor,
        x: Int,
        top: Int,
        width: Int,
        bottom: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        // Records before reading, so a job seen for the first time has its cost captured at the
        // prices of the moment it started rather than at whatever they are when it finishes.
        ForgeTracker.recordIfReadable()

        val capital = WorkingCapitalSummary.build(
            forge = ForgeTracker.state,
            priceOf = { name ->
                // A forge output whose name doesn't map to a bazaar id comes back null and shows
                // as "–", which is the honest answer for an item nothing here can price.
                BazaarLivePrices.quoteFor(ProductName.idOf(name))?.let { quote ->
                    // topBid, not sellPrice: this is what the player will collect and then sell,
                    // and a sell offer fills at the standing bid. And net of the bazaar's cut,
                    // which the Craft and Forge pages already subtract - showing the gross here
                    // overstated the profit on screen by 12%, in the flattering direction.
                    val unit = quote.topBid.takeIf { it > 0 } ?: quote.sellPrice
                    val net = unit * (1 - BazaarTax.rate)

                    WorkingCapitalSummary.Priced(
                        value = net.toLong(),
                        thin = Liquidity.of(quote).isThin,
                    )
                }
            },
            remembered = ForgeLedger.jobs,
        )

        // Opens the actionable row the first time the page is drawn, not on every frame: after
        // that the player's own expanding and collapsing is what decides.
        if (!statusExpansionInitialised) {
            statusExpansionInitialised = true
            WorkingCapitalSummary.rowToExpand(capital)?.let { expandedGroups.add(it) }
        }

        val table = DataTable(BazaarColumns.status(width), x, width)
        var y = table.drawHeader(graphics, font, top)

        table.headerTooltipAt(font, top, mouseX, mouseY)?.let { pendingTooltip = it }

        for (group in capital.groups) {
            if (y + DataTable.ROW_HEIGHT > bottom) break

            val expanded = group.id in expandedGroups
            val hasDetail = group.count > 0

            val bounds = table.drawRow(
                graphics, font, y,
                listOf(
                    DataTable.Cell(
                        if (!hasDetail) " " else if (expanded) "▾" else "▸",
                        Palette.FAINT,
                    ),
                    DataTable.Cell(group.label, Palette.NAME),
                    statusStateCell(group),
                    moneyCell(group.cost, Palette.MUTED),
                    moneyCell(group.value, Palette.TEXT),
                    profitCell(group.profit, group.anyThin),
                    DataTable.Cell(nextEventOf(group) ?: "–", Palette.MUTED),
                ),
                mouseX, mouseY,
            )

            if (hasDetail) statusRows.add(StatusRow(bounds, group.id))
            y += DataTable.ROW_HEIGHT

            if (!expanded) continue

            for (item in group.items) {
                if (y + DataTable.ROW_HEIGHT > bottom) break
                y = drawStatusDetail(graphics, table, item, x, y, mouseX, mouseY)
            }
            // A blank line after an open group, so the next source doesn't read as another of its
            // items. Only when something followed, or the total gains a stray gap above it.
            if (group.items.isNotEmpty() && y + DataTable.ROW_HEIGHT <= bottom) y += DETAIL_GAP
        }

        drawWorkingTotal(graphics, capital, x, y, width, bottom)
    }

    /** One item inside an expanded group - indented, and quieter than the summary above it. */
    private fun drawStatusDetail(
        graphics: GuiGraphicsExtractor,
        table: DataTable,
        item: WorkingItem,
        x: Int,
        y: Int,
        mouseX: Int,
        mouseY: Int,
    ): Int {
        val ready = item.remaining == WorkingCapitalSummary.READY

        val bounds = table.drawRow(
            graphics, font, y,
            listOf(
                DataTable.Cell(" ", Palette.FAINT),
                DataTable.Cell("  ${item.name}", if (item.idle) Palette.FAINT else Palette.MUTED),
                // The liquidity warning sits in the state column, which is empty on detail rows.
                // Amber rather than red: it is a reason to check, not a verdict - a real demand
                // spike looks identical from here.
                if (item.thin) DataTable.Cell("THIN", Palette.STALE)
                else DataTable.Cell("", Palette.MUTED),
                moneyCell(item.cost, Palette.FAINT),
                moneyCell(item.value, Palette.MUTED),
                profitCell(item.profit, item.thin),
                // Something finished is the one thing on this page worth acting on now, so it is
                // the only detail cell that gets the positive colour.
                DataTable.Cell(
                    item.remaining ?: "–",
                    when {
                        ready -> Palette.POSITIVE
                        item.idle -> Palette.FAINT
                        else -> Palette.MUTED
                    },
                ),
            ),
            mouseX, mouseY,
        )

        // columnRight is already absolute, so the row's own x isn't added again.
        item.progress?.let {
            drawProgressBar(graphics, it, table.columnRight(NAME_COLUMN) - PROGRESS_GAP, y)
        }

        // Only rows that name a real item open anything: an empty slot has no graph to show.
        if (!item.idle) statusItemRows.add(StatusItemRow(bounds, ProductName.idOf(item.name)))

        return y + DataTable.ROW_HEIGHT
    }

    /**
     * A thin bar to the right of the item's name, showing how far through the job it is.
     *
     * Beside the name rather than under it. Underlining every row made the names read as links
     * and the bars as one continuous rule down the page - the progress was there but nobody would
     * see it as progress. Set to the right of the text it belongs to, vertically centred on the
     * row, it reads as a gauge.
     *
     * Still inside the name column rather than given a column of its own: it restates the "Next"
     * figure visually, so a fixed column would spend width on a duplicate.
     */
    private fun drawProgressBar(
        graphics: GuiGraphicsExtractor,
        progress: Double,
        nameRight: Int,
        y: Int,
    ) {
        val left = nameRight - PROGRESS_WIDTH
        val top = y + (DataTable.ROW_HEIGHT - PROGRESS_HEIGHT) / 2

        graphics.fill(left, top, left + PROGRESS_WIDTH, top + PROGRESS_HEIGHT, Palette.RULE)
        val filled = (PROGRESS_WIDTH * progress).toInt().coerceIn(0, PROGRESS_WIDTH)
        if (filled > 0) {
            graphics.fill(left, top, left + filled, top + PROGRESS_HEIGHT, Palette.ACCENT)
        }
    }

    /** A money figure, or a dash when it isn't known - never a zero standing in for unknown. */
    private fun moneyCell(amount: Long?, colour: Int): DataTable.Cell = DataTable.Cell(
        amount?.let { NumberFormats.price(it.toDouble()) } ?: "–",
        if (amount != null) colour else Palette.FAINT,
    )

    /**
     * Profit, signed and coloured by direction. Blank when either half of the sum is missing.
     *
     * A profit computed from a thin market is bracketed and drawn muted rather than green. The
     * figure is arithmetically correct and practically meaningless - it was a green "+8.5M" on an
     * item nobody was buying that made the forge look like a bargain - so it is shown without the
     * colour that invites acting on it.
     */
    private fun profitCell(profit: Long?, thin: Boolean = false): DataTable.Cell {
        if (profit == null) return DataTable.Cell("–", Palette.FAINT)

        val sign = if (profit >= 0) "+" else "-"
        val figure = sign + NumberFormats.price(kotlin.math.abs(profit).toDouble())

        if (thin) return DataTable.Cell("[$figure]", Palette.MUTED)

        return DataTable.Cell(
            figure,
            if (profit >= 0) Palette.POSITIVE else Palette.NEGATIVE,
        )
    }

    /** The state column: what this source is currently able to tell us. */
    private fun statusStateCell(group: WorkingGroup): DataTable.Cell = when (group.state) {
        // "tracked" against "estimated": the pair says where the figures come from rather than
        // how many there are. Reading the widget is first-hand; replaying the ledger from another
        // island is a calculation from a reading taken earlier, and the words separate the two
        // without needing a legend.
        WorkingGroup.State.READ ->
            if (group.count > 0) DataTable.Cell("${group.count} tracked", Palette.TEXT)
            else DataTable.Cell("idle", Palette.FAINT)

        WorkingGroup.State.REMEMBERED ->
            DataTable.Cell("${group.count} estimated", Palette.STALE)

        // Named for the fix rather than the fault: the widget being off is the usual cause, and
        // "unknown" alone leaves the player with nothing to do about it.
        WorkingGroup.State.UNKNOWN -> DataTable.Cell("enable /widgets", Palette.STALE)

        WorkingGroup.State.PLANNED -> DataTable.Cell("not yet read", Palette.FAINT)
    }

    /** The soonest thing to happen in a group, or null when nothing is pending. */
    private fun nextEventOf(group: WorkingGroup): String? {
        if (group.items.any { it.remaining == WorkingCapitalSummary.READY }) {
            return WorkingCapitalSummary.READY
        }
        // First rather than shortest: Hypixel writes the wait as text ("1h 25m", "29h"), and
        // ordering those properly means parsing a duration the widget never promised. The forge
        // lists its slots in order, so the first pending one is the honest answer.
        return group.items.firstOrNull { it.remaining != null }?.remaining
    }

    /**
     * The summed line under the list.
     *
     * Says "at least" whenever a source could not be read, because the figure is then a floor and
     * not a total - and a floor presented as a total is the kind of plausible wrong number this
     * project has decided is worse than a visibly missing one.
     */
    private fun drawWorkingTotal(
        graphics: GuiGraphicsExtractor,
        capital: WorkingCapital,
        x: Int,
        y: Int,
        width: Int,
        bottom: Int,
    ) {
        if (y + DataTable.ROW_HEIGHT * 2 > bottom) return

        val ruleY = y + 3
        graphics.fill(x, ruleY, x + width, ruleY + 1, Palette.RULE)

        val label = if (capital.partial) "TOTAL WORKING (at least)" else "TOTAL WORKING"
        graphics.text(font, Component.literal(label), x, ruleY + 6, Palette.HEADING)

        val total = capital.total?.let { NumberFormats.price(it.toDouble()) } ?: "–"
        graphics.text(
            font,
            Component.literal(total),
            x + width - font.width(total),
            ruleY + 6,
            if (capital.total != null) Palette.TEXT else Palette.FAINT,
        )
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

        val table = DataTable(BazaarColumns.watchlist(width, changeColumnTitle()), x, width)
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
        val flips = BazaarMarketSummary.bestFlips(LIST_ROWS).sortedWith(BazaarSort.marketFlips(sort))

        if (flips.isEmpty()) {
            drawEmptyState(graphics, x, top, listOf("Waiting for the first price snapshot…" to Palette.MUTED))
            return
        }

        val table = DataTable(BazaarColumns.flips(width), x, width)
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
            graphics, Tab.NPC_BUY, BazaarColumns.npcToBazaar(width), x, top, width, bottom, mouseX, mouseY,
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
            graphics, Tab.NPC_SELL, BazaarColumns.bazaarToNpc(width), x, top, width, bottom, mouseX, mouseY,
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
        val sorted = flips.sortedWith(BazaarSort.npcFlips(sort))

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
        graphics, Tab.CRAFT, BazaarColumns.crafts(width, forge = false),
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
        graphics, Tab.FORGE, BazaarColumns.crafts(width, forge = true),
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
        val sorted = crafts.sortedWith(BazaarSort.crafts(sort))

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
                    // The Forge page's left-hand figure is the mixed trade - ingredients bought
                    // outright, result sold on an offer - because that is what a recipe with a
                    // wait in it actually calls for. See [CraftProfit.Craft.forgeProfit]. On the
                    // Craft page the pair stays fast-against-patient, where it describes two
                    // trades somebody could really choose between.
                    val fastFigure = if (isForge) craft.forgeProfit else craft.instantProfit
                    add(pairedProfitCell(fastFigure, craft.orderProfit))
                    if (isForge) {
                        add(
                            pairedProfitCell(
                                craft.profitPerHour(fastFigure),
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
        // The bazaar equivalent of the auction warning above: what this makes barely trades, so
        // the profit beside it is arithmetic rather than money. A word rather than a colour, and
        // the same reason - colour never carries meaning alone here.
        if (craft.outputIsThin) {
            return DataTable.Cell.of(
                DataTable.Cell.Part(craftName(craft), Palette.MUTED),
                DataTable.Cell.Part(" THIN", Palette.STALE),
            )
        }

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
     * A day's takings both ways round: instant on the left of the slash, order on the right.
     *
     * Paired rather than given a column each because the two are read against each other - the
     * question is which route is worth the wait - and because two more numeric columns is more
     * than the panel has room for. One decimal throughout: "174.53k/307.28k" overflows the
     * column, and the second decimal is below the noise of a price that moves every minute.
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
     * Footer: where you are in the list on the left, where the data came from on the right.
     *
     * The keyboard hints that used to sit here are gone. They described what the table already
     * shows - a row highlights under the cursor, a heading takes a sort arrow when clicked - so
     * they cost a line of the panel to teach what one click teaches better. The tracked and pinned
     * counts went with them for the same reason: both are visible in the list they count.
     *
     * What stayed is what the table cannot show. The tax rate qualifies every profit figure above
     * it, and the credit is required wherever Coflnet's data is.
     */
    private fun drawStatusBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int) {
        // Scroll position first, since it changes as the player moves; the tax rate follows it and
        // only appears on the views whose figures are net of it.
        val left = listOfNotNull(
            scrollRange,
            taxNote().takeIf { tab == Tab.FLIP || tab == Tab.NPC_BUY },
        ).joinToString(" · ")

        // The attribution, which Coflnet's terms require to be stated wherever their data is
        // shown. Drawn first, and unconditionally: it moved here from the title bar and the
        // priority moved with it, so on a narrow panel the *left* side is what gives way. An
        // attribution that disappears is not one, where a scroll position that does is merely
        // missing.
        val credit = DataCredits.SHORT
        val creditX = x + width - font.width(credit)
        graphics.text(font, Component.literal(credit), creditX, y, Palette.FAINT)

        if (left.isNotEmpty() && x + font.width(left) + FOOTER_GAP < creditX) {
            graphics.text(font, Component.literal(left), x, y, Palette.FAINT)
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

        // Item rows first: they sit inside an expanded group, so testing the group's own row first
        // would swallow every click on the items it just revealed.
        for (row in statusItemRows) {
            if (!row.bounds.containsPoint(x, y)) continue

            minecraft.setScreen(BazaarGraphScreen(row.productId, this))
            return true
        }

        for (row in statusRows) {
            if (!row.bounds.containsPoint(x, y)) continue

            if (!expandedGroups.remove(row.groupId)) expandedGroups.add(row.groupId)
            return true
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
            // Neither sorts: the watchlist keeps the player's own order, and Status lists fixed
            // sources rather than a ranking - re-ordering "Forge, Minions, Auctions" by value
            // would move a row the player navigates to by position.
            Tab.STATUS -> null
            Tab.WATCH -> null
            Tab.FLIP -> BazaarColumns.flips(width)
            Tab.NPC_BUY -> BazaarColumns.npcToBazaar(width)
            Tab.NPC_SELL -> BazaarColumns.bazaarToNpc(width)
            Tab.CRAFT -> BazaarColumns.crafts(width, forge = false)
            Tab.FORGE -> BazaarColumns.crafts(width, forge = true)
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

        /**
         * The pin column's width, needed here as well as in [BazaarColumns] because the click
         * target for the pin toggle has to match the column it is drawn in. Read from there rather
         * than repeated, so the two cannot drift apart.
         */
        private const val PIN_COLUMN_WIDTH = BazaarColumns.PIN

        /** Blank line closing an expanded Status group, so its items don't run into the next one. */
        private const val DETAIL_GAP = 4

        /** Progress bar at the right of the name column - a gauge beside the name, not under it. */
        private const val PROGRESS_WIDTH = 40
        private const val PROGRESS_HEIGHT = 3

        /** Breathing room between the bar and the next column's figures. */
        private const val PROGRESS_GAP = 6

        /** Index of the name column in the Status table, which the progress bar sits inside. */
        private const val NAME_COLUMN = 1

        private const val TOOLTIP_MAX_WIDTH = 160

        /**
         * Rows fetched per ranking. Well past what fits on screen, since scrolling is what the
         * rest go to - the old limit was the number of visible rows, which is why there was
         * nothing to scroll to before.
         */
        private const val LIST_ROWS = 60

        /** Rows per wheel notch. Three is the usual step and keeps place easy to follow. */
        private const val SCROLL_STEP = 3

        /** Clear space kept between the footer's two halves before the hint is dropped. */
        private const val FOOTER_GAP = 12

        /** The same, for the title bar's three labels before the middle one is dropped. */
        private const val TITLE_GAP = 12
    }
}
