package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.DataCredits
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.feature.bazaar.ProductName
import dev.syqs.skyquant.feature.bazaar.data.AuctionBin
import dev.syqs.skyquant.feature.bazaar.data.AuctionHistory
import dev.syqs.skyquant.feature.bazaar.data.BazaarHistory
import dev.syqs.skyquant.feature.bazaar.data.PriceSeries
import dev.syqs.skyquant.feature.bazaar.data.PriceVerdict
import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import dev.syqs.skyquant.feature.bazaar.data.BazaarWatchlist
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.util.deferred
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f

/**
 * Price history for one bazaar product, drawn as buy and sell curves over time.
 *
 * Both sides are shown because the gap between them is the margin a flip has to work with:
 * a single averaged line would hide exactly the number that decides whether a trade is worth
 * making. History comes from Coflnet; the live Hypixel snapshot supplies the current figures
 * in the side panel, which update faster than the recorded history does.
 */
class BazaarGraphScreen(
    private val productId: String,
    /**
     * Screen to return to on close, so opening a graph from an inventory doesn't dismiss it.
     * Restoring it is enough because [Minecraft.setScreen] only detaches the old screen without
     * calling `onClose`, which is what would tell the server the container is gone.
     */
    private val previousScreen: Screen? = null,
) : Screen(Component.literal("Bazaar Graph")) {

    /**
     * [WAITING] is distinct from [FAILED] on purpose: it means our own pacing held the request
     * back before it was sent, which clears itself within seconds. Reporting that as a failure -
     * and as "HTTP 429", a status code no server had returned - described the mod's own throttle
     * as an error from Coflnet.
     */
    private enum class State { LOADING, WAITING, READY, EMPTY, FAILED }

    private var panel: ScreenRectangle = ScreenRectangle.empty()
    private var plot: ScreenRectangle = ScreenRectangle.empty()

    private var range = BazaarHistory.Range.DAY
    private var state = State.LOADING
    /**
     * The bazaar points, kept as their own type because the live sample appended to them and the
     * header's change figure both need the two sides separately. Empty for an auction item.
     */
    private var points: List<BazaarHistory.Point> = emptyList()

    /** What the chart draws, from whichever market had data. */
    private var series: PriceSeries = PriceSeries(PriceSeries.Kind.BAZAAR, emptyList())
    private var failureMessage: String? = null

    /** Guards against a slow response for a range the user has already switched away from. */
    private var pendingRange: BazaarHistory.Range? = null

    /**
     * The window to ask for again once the request budget frees up, or null when nothing is
     * waiting. Set only by [handleFailure] on a deferred request.
     */
    private var retryRange: BazaarHistory.Range? = null
    private var retryRangeAtMillis = 0L

    /** Cursor position while it's over the plot, driving the readout line. */
    private var hoverX: Int? = null

    /** Whether the last entry in [points] is our own live sample rather than Coflnet's. */
    private var livePointAppended = false

    override fun init() {
        val panelWidth = (width - PANEL_MARGIN * 2).coerceAtMost(MAX_PANEL_WIDTH)
        val panelHeight = (height - PANEL_MARGIN * 2).coerceAtMost(MAX_PANEL_HEIGHT)
        panel = ScreenRectangle(
            (width - panelWidth) / 2,
            (height - panelHeight) / 2,
            panelWidth,
            panelHeight,
        )

        plot = ScreenRectangle(
            panel.position().x() + PRICE_LABEL_WIDTH,
            panel.position().y() + HEADER_HEIGHT,
            (panelWidth - PRICE_LABEL_WIDTH - SIDE_PANEL_WIDTH).coerceAtLeast(1),
            (panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT).coerceAtLeast(1),
        )

        if (series.points.isEmpty()) load(range)
    }

    /** Screen-space box of the range button for [option], in the top right of the header. */
    private fun rangeButtonBounds(option: BazaarHistory.Range): ScreenRectangle {
        val index = BazaarHistory.Range.entries.indexOf(option)
        val totalWidth = BazaarHistory.Range.entries.size * (RANGE_BUTTON_WIDTH + 4) - 4
        val startX = panel.position().x() + panel.width() - totalWidth - 12

        return ScreenRectangle(
            startX + index * (RANGE_BUTTON_WIDTH + 4),
            panel.position().y() + 6,
            RANGE_BUTTON_WIDTH,
            RANGE_BUTTON_HEIGHT,
        )
    }

    /**
     * Track and pin buttons, in the footer under the side panel.
     *
     * Placed away from the range buttons deliberately: those change what the chart shows, while
     * these change saved state, and a misclick between the two groups has very different costs.
     */
    private fun trackButtonBounds(): ScreenRectangle = ScreenRectangle(
        plot.position().x() + plot.width() + SIDE_PANEL_GAP,
        panel.position().y() + panel.height() - FOOTER_HEIGHT - 4,
        TRACK_BUTTON_WIDTH,
        RANGE_BUTTON_HEIGHT,
    )

    private fun pinButtonBounds(): ScreenRectangle {
        val track = trackButtonBounds()
        return ScreenRectangle(
            track.position().x(),
            track.position().y() - RANGE_BUTTON_HEIGHT - 4,
            TRACK_BUTTON_WIDTH,
            RANGE_BUTTON_HEIGHT,
        )
    }

    private fun drawTrackButtons(graphics: GuiGraphicsExtractor, pose: Matrix3x2f, mouseX: Int, mouseY: Int) {
        val tracked = BazaarWatchlist.isTracked(productId)
        val pinned = BazaarWatchlist.isPinned(productId)

        // Pin only appears once the item is tracked: pinning implies tracking, and offering both
        // at once invites the player to wonder how they differ before they've used either.
        val buttons = buildList {
            add(Triple(trackButtonBounds(), if (tracked) "✓ Tracked" else "+ Track", tracked))
            if (tracked) add(Triple(pinButtonBounds(), if (pinned) "◆ Pinned" else "◇ Pin", pinned))
        }

        for ((box, label, active) in buttons) {
            val hovered = box.containsPoint(mouseX, mouseY)

            graphics.guiRenderState.addGuiElement(
                RoundedRectRenderState(
                    pose,
                    box.position().x().toFloat(),
                    box.position().y().toFloat(),
                    (box.position().x() + box.width()).toFloat(),
                    (box.position().y() + box.height()).toFloat(),
                    BUTTON_CORNER_RADIUS,
                    when {
                        active -> Palette.BUTTON_ACTIVE
                        hovered -> Palette.BUTTON_HOVER
                        else -> Palette.BUTTON
                    },
                    null,
                ),
            )

            graphics.centeredText(
                font,
                Component.literal(label),
                box.position().x() + box.width() / 2,
                box.position().y() + (box.height() - 8) / 2,
                if (active) Palette.TEXT else Palette.MUTED,
            )
        }
    }

    /**
     * Drawn rather than built from vanilla [Button] widgets: those carry their own texture and
     * shading, which reads as a stray piece of the game UI sitting on top of the panel.
     */
    private fun drawRangeButtons(graphics: GuiGraphicsExtractor, pose: Matrix3x2f, mouseX: Int, mouseY: Int) {
        for (option in BazaarHistory.Range.entries) {
            val box = rangeButtonBounds(option)
            val selected = option == range
            val available = isAvailable(option)
            val hovered = available && box.containsPoint(mouseX, mouseY)

            graphics.guiRenderState.addGuiElement(
                RoundedRectRenderState(
                    pose,
                    box.position().x().toFloat(),
                    box.position().y().toFloat(),
                    (box.position().x() + box.width()).toFloat(),
                    (box.position().y() + box.height()).toFloat(),
                    BUTTON_CORNER_RADIUS,
                    when {
                        // Kept in place rather than hidden when unavailable: the buttons would
                        // otherwise shift sideways between a bazaar item and an auction one, so
                        // the same window would sit under a different button each time.
                        !available -> Palette.BUTTON_DISABLED
                        selected -> Palette.BUTTON_ACTIVE
                        hovered -> Palette.BUTTON_HOVER
                        else -> Palette.BUTTON
                    },
                    null,
                ),
            )

            graphics.centeredText(
                font,
                Component.literal(option.label),
                box.position().x() + box.width() / 2,
                box.position().y() + (box.height() - 8) / 2,
                when {
                    !available -> Palette.FAINT
                    selected -> Palette.TEXT
                    else -> Palette.MUTED
                },
            )
        }

        drawUnavailableHint(graphics, mouseX, mouseY)
    }

    /**
     * Whether the market currently charted records [option] at all.
     *
     * Both windows are offered while loading, since which market answers isn't known yet -
     * greying a button out and then re-enabling it a moment later would be worse than leaving it
     * lit for that moment.
     */
    private fun isAvailable(option: BazaarHistory.Range): Boolean =
        state == State.LOADING || state == State.WAITING || option.availableOn(series.kind)

    /**
     * Says why a greyed-out range can't be picked, on hover.
     *
     * A disabled control that gives no reason invites the reader to assume the mod is broken.
     * The reason here is a property of the market, not of the mod: auctions complete too rarely
     * for an hour of them to be a series, and Coflnet doesn't keep a month of bazaar prices.
     */
    private fun drawUnavailableHint(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val hovered = BazaarHistory.Range.entries.firstOrNull {
            !isAvailable(it) && rangeButtonBounds(it).containsPoint(mouseX, mouseY)
        } ?: return

        val text = "No hourly data for auctions"

        val box = rangeButtonBounds(hovered)
        val width = font.width(text) + 8
        // Right-aligned to the button so a long hint stays inside the panel instead of running
        // off the edge, and below it so it doesn't cover the row of buttons itself.
        val x = (box.position().x() + box.width() - width)
            .coerceAtLeast(panel.position().x() + PANEL_PADDING)
        val y = box.position().y() + box.height() + 3

        graphics.fill(x, y, x + width, y + LINE_HEIGHT + 2, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(text), x + 4, y + 3, Palette.MUTED)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 0) {
            val x = event.x().toInt()
            val y = event.y().toInt()

            for (option in BazaarHistory.Range.entries) {
                if (rangeButtonBounds(option).containsPoint(x, y)) {
                    // Swallowed rather than ignored when unavailable: the click landed on the
                    // button, so letting it fall through would close the screen behind it.
                    if (isAvailable(option)) selectRange(option)
                    return true
                }
            }

            if (trackButtonBounds().containsPoint(x, y)) {
                BazaarWatchlist.toggleTracked(productId)
                return true
            }

            if (BazaarWatchlist.isTracked(productId) && pinButtonBounds().containsPoint(x, y)) {
                BazaarWatchlist.togglePinned(productId)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun selectRange(option: BazaarHistory.Range) {
        if (option == range) return
        range = option
        load(option)
    }

    /**
     * Re-asks for a window that our own pacing held back, once the budget should have freed up.
     *
     * Driven from the render pass because that is what already runs every frame here; the delay
     * does the throttling, not the call site. Only ever set for a deferred request, so this cannot
     * spin on a genuine failure.
     */
    private fun retryIfDue() {
        val pending = retryRange ?: return
        if (System.currentTimeMillis() < retryRangeAtMillis) return

        retryRange = null
        load(pending)
    }

    /**
     * Handles a failed fetch, telling a deferred request apart from a real fault.
     *
     * Returns true when it was ours to wait on, in which case the window is queued for another
     * attempt and nothing is reported: the player asked for a chart, and a chart a moment later is
     * the answer to that - not a red error they can do nothing about.
     */
    private fun handleFailure(error: Throwable, option: BazaarHistory.Range, what: String): Boolean {
        if (error.deferred()) {
            state = State.WAITING
            failureMessage = null
            retryRangeAtMillis = System.currentTimeMillis() + RETRY_DELAY_MILLIS
            retryRange = option
            return true
        }

        SkyQuantMod.LOGGER.warn("$what fetch failed for $productId", error)
        state = State.FAILED
        failureMessage = error.cause?.message ?: error.message
        return false
    }

    private fun load(option: BazaarHistory.Range) {
        state = State.LOADING
        failureMessage = null
        retryRange = null
        pendingRange = option
        // The fresh series is all Coflnet's again, so the next live sample has to be appended
        // rather than overwrite a real recorded point.
        livePointAppended = false

        // The month comes from the item-price endpoint on both markets - Coflnet's bazaar
        // endpoint 404s for it. Which market the item belongs to still matters, because it
        // decides how the figures are read, so it is settled here from the live product list
        // rather than guessed from whether a request happened to succeed.
        if (option == BazaarHistory.Range.MONTH) {
            loadMonth(BazaarLivePrices.quoteFor(productId) != null)
            return
        }

        BazaarHistory.fetch(productId, option).whenComplete { result, error ->
            // Back to the render thread: touching screen state from an HTTP worker would race
            // with the frame being drawn.
            Minecraft.getInstance().execute {
                if (pendingRange != option) return@execute

                if (error != null) {
                    handleFailure(error, option, "Bazaar history")
                    return@execute
                }

                if (result.size >= 2) {
                    points = result
                    series = PriceSeries.ofBazaar(result)
                    state = State.READY
                    return@execute
                }

                // Nothing on the bazaar. That is the normal answer for weapons, tools and forge
                // outputs rather than an error, and those are exactly the items whose price is
                // hardest to know - so ask the auction house before giving up.
                loadAuction(option)
            }
        }
    }

    /**
     * The month window, which both markets serve through the item-price endpoint.
     *
     * [onBazaar] decides only how the result is labelled and drawn, not where it comes from: a
     * month of Enchanted Diamond and a month of Hyperion arrive in the same shape, but only one
     * of them has a meaningful "cheapest unmodified example".
     */
    private fun loadMonth(onBazaar: Boolean) {
        AuctionHistory.fetch(productId, AuctionHistory.Range.MONTH).whenComplete { result, error ->
            Minecraft.getInstance().execute {
                if (pendingRange != BazaarHistory.Range.MONTH) return@execute

                if (error != null) {
                    handleFailure(error, BazaarHistory.Range.MONTH, "Monthly history")
                    return@execute
                }

                // Emptied because this list is the bazaar's two-sided history, which a month of
                // daily points is not. Leaving the previous window's points here would let the
                // live sample be appended to a series it doesn't belong to.
                points = emptyList()
                series = if (onBazaar) {
                    PriceSeries.ofBazaarDaily(result)
                } else {
                    PriceSeries.ofAuction(result)
                }
                state = if (result.size < 2) State.EMPTY else State.READY
            }
        }
    }

    /**
     * Falls back to auction history, which covers everything the bazaar doesn't trade.
     *
     * The hour window has no auction counterpart, and that now ends the attempt rather than
     * quietly substituting the day's data: doing so left the `1h` button lit above a chart that
     * was showing something else, which is a worse answer than an honest empty one.
     */
    private fun loadAuction(option: BazaarHistory.Range) {
        val auctionRange = AuctionHistory.Range.forChartRange(option)

        if (auctionRange == null) {
            // Reached when the hour was asked for and the bazaar had nothing, so this is an
            // auction item being asked for the one window auctions don't record. Switch to the
            // day rather than showing a dead end the player has to work out how to leave.
            //
            // The kind is set first so the range buttons grey `1h` out immediately; leaving it as
            // BAZAAR would flash the button as available for a frame.
            points = emptyList()
            series = PriceSeries(PriceSeries.Kind.AUCTION, emptyList())
            range = BazaarHistory.Range.DAY
            pendingRange = BazaarHistory.Range.DAY

            // Straight to the auction fetch rather than back through `load`, which would ask the
            // bazaar for the day first - and we have just learned this item isn't on the bazaar.
            // Terminates because DAY always maps to a real auction window, so this can't recur.
            loadAuction(BazaarHistory.Range.DAY)
            return
        }

        AuctionHistory.fetch(productId, auctionRange).whenComplete { result, error ->
            Minecraft.getInstance().execute {
                if (pendingRange != option) return@execute

                if (error != null) {
                    handleFailure(error, option, "Auction history")
                    return@execute
                }

                points = emptyList()
                series = PriceSeries.ofAuction(result)
                state = if (result.size < 2) State.EMPTY else State.READY
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        BazaarLivePrices.refreshIfStale()
        retryIfDue()
        appendLivePoint()
        hoverX = if (plot.containsPoint(mouseX, mouseY)) mouseX else null
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val panelLeft = panel.position().x()
        val panelTop = panel.position().y()
        val left = plot.position().x()
        val top = plot.position().y()
        val right = left + plot.width()
        val bottom = top + plot.height()

        val pose = Matrix3x2f(graphics.pose())

        // Border as a slightly larger plate behind the panel, matching the terminal's treatment.
        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                (panelLeft - BORDER_WIDTH).toFloat(), (panelTop - BORDER_WIDTH).toFloat(),
                (panelLeft + panel.width() + BORDER_WIDTH).toFloat(),
                (panelTop + panel.height() + BORDER_WIDTH).toFloat(),
                // Outer radius grows with the plate, or the two curves run at different offsets
                // and the border thins to nothing along a corner.
                PANEL_CORNER_RADIUS + BORDER_WIDTH, Palette.BORDER, null,
                PANEL_CORNER_CUT + BORDER_WIDTH,
                softEdges = false,
            ),
        )
        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                panelLeft.toFloat(), panelTop.toFloat(),
                (panelLeft + panel.width()).toFloat(), (panelTop + panel.height()).toFloat(),
                PANEL_CORNER_RADIUS, Palette.BACKGROUND, null,
                PANEL_CORNER_CUT,
            ),
        )
        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                PLOT_CORNER_RADIUS, Palette.SURFACE, null,
            ),
        )

        // Left-aligned rather than centred, so a long item name grows away from the range
        // buttons in the top right instead of colliding with them - and cut where they begin,
        // since a name like "Enchanted Lapis Lazuli Block" otherwise runs straight into them.
        val titleSpace = rangeButtonBounds(BazaarHistory.Range.entries.first()).position().x() - panelLeft - 20
        graphics.text(
            font,
            Component.literal(font.plainSubstrByWidth(ProductName.of(productId), titleSpace)),
            panelLeft + 12,
            panelTop + 10,
            Palette.TEXT,
        )

        drawRangeButtons(graphics, pose, mouseX, mouseY)
        drawTrackButtons(graphics, pose, mouseX, mouseY)
        drawFooter(graphics, panelLeft, panelTop)

        when (state) {
            State.LOADING -> centerMessage(graphics, "Loading…", Palette.MUTED)
            // Muted, and worded as the wait it is. This is the mod spacing its own requests, so
            // it resolves on its own in a second or two - nothing for the player to act on.
            State.WAITING -> centerMessage(graphics, "Waiting for data…", Palette.MUTED)
            State.FAILED -> centerMessage(graphics, failureMessage?.let { "Failed: $it" } ?: "Request failed", Palette.NEGATIVE)
            // Reached only after both markets came back empty, so it can say so plainly rather
            // than leaving the player wondering whether the mod failed or the item has no price.
            State.EMPTY -> centerMessage(graphics, "Not traded on the bazaar or the auction house", Palette.MUTED)
            State.READY -> PriceChart(plot, series, range, liveBin())
                .draw(graphics, font, pose, hoverX)
        }

        drawSidePanel(graphics, right, top)
    }

    /**
     * Extends the history with Hypixel's live snapshot so the curve reaches the present.
     *
     * Coflnet records on a fixed interval - five minutes on the day range - so its newest point
     * can be well behind the price shown in the side panel, leaving the chart looking stale.
     * The appended point is replaced rather than stacked once it's itself older than the
     * interval, which keeps the series evenly spaced instead of growing a dense tail.
     */
    private fun appendLivePoint() {
        if (state != State.READY) return

        val quote = BazaarLivePrices.quoteFor(productId) ?: return
        val last = points.lastOrNull() ?: return
        val now = System.currentTimeMillis()

        val live = BazaarHistory.Point(
            timestamp = now,
            buy = quote.buyPrice,
            sell = quote.sellPrice,
            buyVolume = quote.buyVolume,
            sellVolume = quote.sellVolume,
        )

        points = if (livePointAppended && points.size > 1) {
            points.dropLast(1) + live
        } else {
            // Nothing to add while Coflnet's own last sample is still recent.
            if (now - last.timestamp < range.sampleIntervalMillis) return
            livePointAppended = true
            points + live
        }

        // The chart draws the series, not this list, so the new point has to reach it too -
        // without this the live sample was recorded and then never shown.
        series = PriceSeries.ofBazaar(points)
    }

    /**
     * The footer line: where the data came from on the left, where Escape leads on the right.
     *
     * The back hint appears only when Escape returns to a screen of this mod's own: coming from a
     * Hypixel container it hands back a menu the player opened themselves and already expects,
     * while coming from the terminal it's a step in a path they'd otherwise have to guess at.
     * The credit has no such condition - see [DataCredits].
     */
    private fun drawFooter(graphics: GuiGraphicsExtractor, panelLeft: Int, panelTop: Int) {
        // The credit is drawn first and unconditionally: every chart on this screen is drawn from
        // Coflnet's history, and their terms require saying so wherever it is shown. It takes the
        // left edge because the hint below is right-aligned, so the two never meet.
        val creditY = panelTop + panel.height() - 12
        graphics.text(
            font,
            Component.literal(DataCredits.SHORT),
            panelLeft + PANEL_PADDING,
            creditY,
            Palette.FAINT,
        )

        if (previousScreen !is BazaarHomeScreen) return

        // Right-aligned under the side panel and on its own line below the time labels: sharing
        // either the left edge or the baseline with them left the two crowding each other.
        val text = "esc → terminal"
        graphics.text(
            font,
            Component.literal(text),
            panelLeft + panel.width() - PANEL_PADDING - font.width(text),
            creditY,
            Palette.FAINT,
        )
    }

    /**
     * The side panel for an auction item, which has no live quote to report.
     *
     * Describes the whole charted window rather than its last point. Reading only the final hour
     * was the panel's worst fault: a slow item often sells once an hour, and one sale is its own
     * minimum, maximum and average, so all three rows showed the same number - which reads as a
     * broken panel rather than as a thin market.
     *
     * Ordered high, average, low so the rows fall in the same order as the values do on the
     * chart beside them. Reading downwards then means reading down the price axis.
     */
    private fun drawAuctionSidePanel(entry: (String, String, Int, Int?) -> Unit) {
        val summary = series.summarize()
        if (summary == null) {
            entry("Buy now", "…", Palette.MUTED, Palette.SELL)
            entry("Avg ${range.label}", "…", Palette.MUTED, null)
            return
        }

        // Cut from six rows to four, plus a verdict. What went were "Top" and "Low": they say
        // what reforges and enchantments are worth, which is a real thing to know and the wrong
        // answer to "should I buy this". Four prices with no ranking between them was what made
        // this screen harder to read than the bazaar's, not the number of pixels on it.
        drawBinRows(entry)

        // What one normally costs, directly under what one costs now, because the whole verdict
        // is the comparison between those two lines.
        entry("Usual ${range.label}", NumberFormats.price(summary.usual), Palette.TEXT, null)

        // Kept because it qualifies everything above it: a verdict drawn from three sales is a
        // coincidence, and only this row says which one you are looking at.
        entry("Sold ${range.label}", NumberFormats.volume(summary.totalVolume), Palette.MUTED, null)

        if (state != State.READY) return

        // "Trend", not the bare window label. This and the verdict above are both percentages
        // and they routinely disagree - a Titanium Drill priced normally today (+4.7% on its
        // usual) sat inside a month that fell 25.9%. Both are true and they answer different
        // questions; unlabelled, side by side, they simply looked like a contradiction.
        entry(
            "Trend ${range.label}",
            NumberFormats.change(summary.changePercent),
            if (summary.changePercent >= 0) Palette.POSITIVE else Palette.NEGATIVE,
            null,
        )
    }

    /**
     * The one-line answer: `Price  Cheap  -17.4%`.
     *
     * One row rather than the three it took before. The earlier version stacked a percentage, a
     * phrase and the words "vs usual", which read as a legend for the chart - three lines of
     * explanation for a single fact, sitting where the panel's own headings sit.
     *
     * The word carries the meaning and the colour reinforces it, never the other way round: a
     * reader who can't separate the two colours still reads "Expensive".
     */
    private fun drawVerdict(graphics: GuiGraphicsExtractor, x: Int, right: Int, y: Int): Boolean {
        val bin = AuctionBin.quoteFor(productId)?.lowest
        val usual = series.summarize()?.usual

        val difference = PriceVerdict.differencePercent(bin, usual)
        // Nothing listed, or nothing sold: no comparison to make, and no row taken for one.
        val verdict = PriceVerdict.of(difference) ?: return false

        val (word, color) = when (verdict) {
            PriceVerdict.CHEAP -> "Cheap" to Palette.POSITIVE
            PriceVerdict.DEAR -> "Expensive" to Palette.NEGATIVE
            PriceVerdict.TYPICAL -> "Normal" to Palette.TEXT
        }

        // Drawn here rather than through `entry` because both halves take the verdict's colour,
        // where every other row has a grey label naming a coloured figure. The word takes the
        // label column and the percentage the value column, so the row still lines up with the
        // figures beneath it.
        //
        // No "Price" prefix: "Expensive" beside a percentage already says what it is, and the
        // prefix cost 30px - enough to push the longest word off a 104px column, losing the
        // label in exactly the case where the answer was least ordinary.
        val percent = NumberFormats.percentCompact(difference!!)
        graphics.text(font, Component.literal(word), x, y, color)
        graphics.text(font, Component.literal(percent), right - font.width(percent), y, color)
        return true
    }

    /**
     * The live cheapest listing, at the top of the panel where the eye lands first.
     *
     * It goes first because it is the only figure on the screen that can be acted on: everything
     * else describes what has already happened. History says whether the price is fair; this says
     * what it is.
     */
    private fun drawBinRows(entry: (String, String, Int, Int?) -> Unit) {
        AuctionBin.refreshIfStale(productId)
        val bin = AuctionBin.quoteFor(productId)

        if (bin == null) {
            // Distinguished from "still loading" by the chart beside it having drawn: an item
            // with sales but no listings is sold out, which is worth knowing before waiting.
            entry("Buy now", if (state == State.READY) "none listed" else "…", Palette.MUTED, Palette.SELL)
            return
        }

        // The marker warns that this price is one listing rather than a market, which is exactly
        // what isSoleListing means - it used to key off isLone, which is false for a sole listing
        // by design, so the riskiest case was the one that drew no warning. Colour alone never
        // carries meaning here, so it is a glyph, and it sits with the value it qualifies.
        val price = NumberFormats.price(bin.lowest)
        entry("Buy now", if (bin.isSoleListing) "$price ⚠" else price, Palette.SELL, Palette.SELL)

        // Named rather than left to the glyph: "⚠" says something is off, this says what, and the
        // two together are what let the player judge whether 1.3B is a price or one seller's hope.
        if (bin.isSoleListing) {
            entry("", "only one listed", Palette.MUTED, null)
            return
        }

        // Only shown when it changes the reading. When the two listings are within a rounding
        // error of each other - nine of twelve sampled items sit inside 1% - a second row saying
        // the same number is a row that costs attention and returns nothing.
        if (bin.isLone) {
            entry("2nd BIN", NumberFormats.price(bin.secondLowest), Palette.MUTED, null)
        }
    }

    /**
     * The cheapest live listing, for the chart to mark - auction items only.
     *
     * Null on the bazaar, where the side panel already carries a live buy and sell price and a
     * third line would be one more thing to disentangle rather than one more thing to know.
     */
    private fun liveBin(): Double? {
        if (!series.hasVariants) return null
        return AuctionBin.quoteFor(productId)?.lowest
    }

    private fun centerMessage(graphics: GuiGraphicsExtractor, text: String, color: Int) {
        graphics.centeredText(
            font,
            Component.literal(text),
            plot.position().x() + plot.width() / 2,
            plot.position().y() + plot.height() / 2 - 4,
            color,
        )
    }


    /**
     * Current figures beside the chart: label left, value right-aligned against the panel edge.
     *
     * Stacking the value under its label, as this did before, cost two rows per figure and left
     * the numbers ragged. Side by side they share a row and the digits line up in a column, the
     * same arrangement the terminal's tables use - and the whole block then takes half the
     * height, which is what keeps it clear of the buttons at the bottom.
     */
    private fun drawSidePanel(graphics: GuiGraphicsExtractor, plotRight: Int, plotTop: Int) {
        val x = plotRight + SIDE_PANEL_GAP
        val right = panel.position().x() + panel.width() - PANEL_PADDING
        var y = plotTop

        /**
         * [swatch] draws a short dash of that colour before the label, tying the row to its
         * curve. This is what replaces the old legend: it says the same thing, but from the
         * margin where it can't cover the data it's describing.
         */
        fun entry(
            label: String,
            value: String,
            valueColor: Int,
            swatch: Int? = null,
        ) {
            // Labels are grey because they name a figure rather than being one. The verdict row
            // is the exception and draws itself rather than going through here, since the word
            // there *is* the answer and takes its verdict's colour.
            val labelColor = Palette.MUTED
            val labelX = if (swatch == null) x else x + SWATCH_WIDTH + 4
            val valueX = right - font.width(value)

            if (valueX > labelX + font.width(label) + LABEL_VALUE_GAP) {
                swatch?.let { graphics.fill(x, y + 3, x + SWATCH_WIDTH, y + 4, it) }
                graphics.text(font, Component.literal(label), labelX, y, labelColor)
                graphics.text(font, Component.literal(value), valueX, y, valueColor)
                y += SIDE_PANEL_ROW_HEIGHT
                return
            }

            // Too tight for one line, so the label takes its own above the value rather than
            // being dropped. Dropping it is what produced a panel of anonymous numbers - and did
            // so invisibly, since the rows that happened to fit still looked correct. A row that
            // costs an extra line is a far smaller fault than a figure nobody can name.
            swatch?.let { graphics.fill(x, y + 3, x + SWATCH_WIDTH, y + 4, it) }
            graphics.text(font, Component.literal(label), labelX, y, labelColor)
            y += SIDE_PANEL_ROW_HEIGHT

            graphics.text(font, Component.literal(value), right - font.width(value), y, valueColor)
            y += SIDE_PANEL_ROW_HEIGHT
        }

        // An auction item has no live quote and never will: the panel describes what the chart
        // is showing instead, led by a verdict on whether the current asking price is fair.
        if (series.kind == PriceSeries.Kind.AUCTION) {
            // A blank line after it, but only if it drew: with nothing listed there is no verdict
            // and no gap should be left where one would have been.
            if (drawVerdict(graphics, x, right, y)) y += SIDE_PANEL_ROW_HEIGHT * 2
            drawAuctionSidePanel(::entry)
            return
        }

        val quote = BazaarLivePrices.quoteFor(productId)
        if (quote == null) {
            entry("Buy", "…", Palette.MUTED, Palette.BUY)
            entry("Sell", "…", Palette.MUTED, Palette.SELL)
            entry("Spread", "…", Palette.MUTED)
            return
        }

        entry("Buy", NumberFormats.price(quote.buyPrice), Palette.BUY, Palette.BUY)
        entry("Sell", NumberFormats.price(quote.sellPrice), Palette.SELL, Palette.SELL)

        // Both figures: the percentage says whether the margin is worth taking at all, the coin
        // amount says what one flipped unit actually earns. Neither answers alone.
        entry("Spread", NumberFormats.price(quote.spread), Palette.TEXT)
        entry("", NumberFormats.percent(quote.spreadPercent), Palette.MUTED)

        // Liquidity check: a tempting spread on an item nobody trades leaves you holding it.
        entry("Vol 7d", NumberFormats.volume(quote.weeklyVolume), Palette.TEXT)

        if (state != State.READY) return

        // Taken from the buy side where the two-sided history exists, and from the series
        // otherwise - the month window is a bazaar item drawn from daily min/max/avg points, so
        // this list is empty for it and the figure would silently disappear.
        val changePercent = if (points.isNotEmpty()) {
            val first = points.first().buy
            if (abs(first) > 1e-9) (points.last().buy - first) / first * 100 else 0.0
        } else {
            series.summarize()?.changePercent ?: return
        }

        // Labelled with the window it covers: "Change" alone leaves the reader guessing whether
        // it means the last hour or the whole chart.
        entry(
            range.label,
            NumberFormats.change(changePercent),
            if (changePercent >= 0) Palette.POSITIVE else Palette.NEGATIVE,
        )
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
        // Colours come from Palette; the chart's own drawing constants live in PriceChart.
        private const val PANEL_CORNER_RADIUS = 6f
        private const val PLOT_CORNER_RADIUS = 3f

        /** Same hairline and chamfer as the terminal, so the two screens read as one product. */
        private const val BORDER_WIDTH = 1
        private const val PANEL_CORNER_CUT = 14f

        // Sizes and spacing follow the common 8px grid (4px for spacing inside a component),
        // so gaps stay visually consistent instead of being tuned one by one.
        // Widened along with the side panel, so the chart itself keeps the width it had before
        // the figures beside it were given room to sit on one line.
        // Widened by exactly what the side panel gained, so the plot keeps the width it had.
        private const val MAX_PANEL_WIDTH = 568
        // Tall enough for the volume strip plus the five side-panel entries.
        private const val MAX_PANEL_HEIGHT = 296
        private const val PANEL_MARGIN = 24
        private const val HEADER_HEIGHT = 32
        // Two lines of text plus margins: the time labels sit just under the plot and the
        // escape hint below them, which at 24 left the two overlapping by a couple of pixels.
        private const val FOOTER_HEIGHT = 30

        /**
         * How long to wait before re-asking for a window our own pacing deferred.
         *
         * A little over a second: long enough that the ten-second budget window has moved on and
         * the retry isn't simply refused again, short enough that the chart appears while the
         * player is still looking at it.
         */
        private const val RETRY_DELAY_MILLIS = 1_200L
        // Wide enough for a full price like "1400" now that axis labels aren't abbreviated.
        private const val PRICE_LABEL_WIDTH = 48

        /**
         * Wide enough for the *worst* label and value together, not the average one.
         *
         * At 96 this left 72px of usable column, and an auction row needs 82-90px ("Buy now" +
         * "1300.00M", "Usual 30d" + "1574.5M"). Since [drawSidePanel] drops the label rather
         * than let it collide with its value, every price row silently lost its name - which is
         * why the panel showed two bare figures with nothing to say what they were, while the
         * shorter rows ("Sold 30d", "30d") kept theirs and looked fine.
         *
         * 128 gives 104px of column, which fits every combination the auction and bazaar panels
         * can produce - including "Usual 30d" beside a four-figure price. The panel width grew by
         * the same 32 so the plot is unchanged.
         *
         * 120 was tried first and rejected by the test: it fits the label pairs under a
         * per-character estimate of the real font but not under the pessimistic one the test
         * uses, and a panel that fits only if every glyph happens to be narrow is a panel that
         * loses its labels again on the next item with a wide name.
         */
        private const val SIDE_PANEL_WIDTH = 128
        private const val SIDE_PANEL_GAP = 12
        private const val SIDE_PANEL_ROW_HEIGHT = 11

        /** Breathing room between the rightmost figures and the panel edge. */
        private const val PANEL_PADDING = 12

        /** One line of text, for sizing the hover hint under a disabled range button. */
        private const val LINE_HEIGHT = 10

        /** Minimum gap before a label is dropped in favour of its value. */
        private const val LABEL_VALUE_GAP = 4

        /** Colour dash marking which curve a side panel row belongs to. */
        private const val SWATCH_WIDTH = 6
        private const val RANGE_BUTTON_WIDTH = 30
        private const val RANGE_BUTTON_HEIGHT = 14

        /** Spans the side panel's usable width, so the buttons align with the figures above. */
        private const val TRACK_BUTTON_WIDTH = SIDE_PANEL_WIDTH - SIDE_PANEL_GAP - PANEL_PADDING
        private const val BUTTON_CORNER_RADIUS = 3f
    }
}
