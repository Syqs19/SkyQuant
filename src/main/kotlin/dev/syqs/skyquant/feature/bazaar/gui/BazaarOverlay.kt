package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.config.SkyQuantConfig.OverlayVisibility
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.ProductName
import dev.syqs.skyquant.feature.bazaar.data.AuctionBin
import dev.syqs.skyquant.feature.bazaar.data.AuctionLivePrices
import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import dev.syqs.skyquant.feature.bazaar.data.BazaarPriceTrend
import dev.syqs.skyquant.feature.bazaar.data.BazaarWatchlist
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.hud.HudElement
import dev.syqs.skyquant.hud.HudRegistry
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f

/**
 * Live prices for pinned items, drawn over the game.
 *
 * Each row carries four things that answer different questions: the price now, the shape of how
 * it got there, how far it has moved, and how wide the margin is. A price alone says nothing
 * about whether now is a good moment - which is the only reason to have this on screen at all
 * rather than opening the terminal when curious.
 */
object BazaarOverlay : HudElement {

    private val config get() = SkyQuantConfigManager.config.bazaar.overlay

    override val id = "bazaar_prices"
    override val displayName = "Bazaar Prices"

    /** Runtime toggle from the hotkey, kept out of the config so it doesn't persist. */
    var hidden = false

    /**
     * Whether the overlay belongs on screen right now.
     *
     * [fromScreen] distinguishes the two hooks that call this: the HUD element runs even while a
     * screen is open, and the screen hook runs on top of it, so without the distinction anything
     * visible in a container would be drawn twice - once dimmed by the screen's backdrop and
     * once over it, which reads as a rendering fault.
     */
    override fun shouldRender(minecraft: Minecraft): Boolean = shouldRender(minecraft, fromScreen = false)

    /**
     * Placeable whenever it's switched on, even with nothing pinned: otherwise its position
     * could only be set at the exact moment an item happened to be pinned. Switched off it's
     * left out, since a panel that will never appear is only clutter in the editor.
     */
    override fun showInEditor(): Boolean = config.enabled

    fun shouldRender(minecraft: Minecraft, fromScreen: Boolean = false): Boolean {
        if (!config.enabled || hidden) return false
        if (BazaarWatchlist.pinned.isEmpty()) return false

        val screen = minecraft.screen
        val inContainer = screen is AbstractContainerScreen<*>

        // With a screen open only the screen hook may draw; with none open only the HUD hook can.
        if (fromScreen != (screen != null)) return false

        return when (config.visibility) {
            // Still hidden behind a full-screen menu: a price ticker over the pause screen or
            // the map is in the way of whatever the player opened it for.
            OverlayVisibility.ALWAYS -> screen == null || inContainer
            OverlayVisibility.HIDE_IN_SCREENS -> screen == null
            OverlayVisibility.INVENTORY_ONLY -> inContainer
        }
    }

    override fun width(font: Font): Int {
        // Falls back to a representative name when nothing is pinned, so the editor still has a
        // panel of a believable size to place rather than a sliver.
        val names = BazaarWatchlist.pinned.maxOfOrNull { font.width(ProductName.short(it)) }
            ?: font.width(PLACEHOLDER_NAME)
        var width = PADDING * 2 + names + GAP + PRICE_WIDTH + GAP + CHANGE_WIDTH

        if (config.showSparkline) width += Sparkline.WIDTH + GAP
        if (config.showSpreadBar) width += SPREAD_BAR_WIDTH + GAP

        return width
    }

    override fun height(font: Font): Int =
        HEADER_HEIGHT + BazaarWatchlist.pinned.size.coerceAtLeast(1) * ROW_HEIGHT + PADDING

    fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int) {
        val placement = HudRegistry.placementOf(this)
        val (x, y) = HudRegistry.originOf(this, screenWidth, screenHeight, font)

        if (placement.scale == 1f) {
            draw(graphics, font, x, y)
            return
        }

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(placement.scale, placement.scale)
        draw(graphics, font, 0, 0)
        pose.popMatrix()
    }

    /**
     * Draws the panel at an explicit position, so the editor can preview it under the cursor
     * rather than at the saved location.
     */
    override fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val pinned = BazaarWatchlist.pinned.toList()

        if (pinned.isEmpty()) {
            drawPlaceholder(graphics, font, x, y)
            return
        }

        val pose = Matrix3x2f(graphics.pose())
        val width = width(font)
        val height = height(font)

        // A panel rather than plain text with a shadow: this sits over a world that ranges from
        // the black of a cavern to snow in full daylight, and text alone survives one or the
        // other but never both.
        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                x.toFloat(), y.toFloat(),
                (x + width).toFloat(), (y + height).toFloat(),
                CORNER_RADIUS, Palette.BACKGROUND, null,
            ),
        )

        drawHeader(graphics, font, x, y, width)

        for ((index, productId) in pinned.withIndex()) {
            drawRow(graphics, font, pose, productId, x, y + HEADER_HEIGHT + index * ROW_HEIGHT, width)
        }
    }

    /**
     * Product on the overlay row under the cursor, or null.
     *
     * Only usable while a screen is open, since the cursor is captured otherwise - which is why
     * this is offered rather than handled here: only the caller knows whether there's a cursor.
     */
    fun productAt(mouseX: Int, mouseY: Int, screenWidth: Int, screenHeight: Int, font: Font): String? {
        val pinned = BazaarWatchlist.pinned.toList()
        if (pinned.isEmpty()) return null

        val scale = HudRegistry.placementOf(this).scale
        val (x, y) = HudRegistry.originOf(this, screenWidth, screenHeight, font)

        // Back into the panel's own coordinates, so the row maths doesn't have to know the scale.
        val localX = (mouseX - x) / scale
        val localY = (mouseY - y) / scale

        if (localX < 0 || localX > width(font)) return null

        val row = ((localY - HEADER_HEIGHT) / ROW_HEIGHT).toInt()
        return pinned.getOrNull(row.takeIf { localY >= HEADER_HEIGHT } ?: -1)
    }

    /** Stand-in shown in the editor when nothing is pinned, so the panel can still be placed. */
    private fun drawPlaceholder(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val pose = Matrix3x2f(graphics.pose())
        val width = width(font)

        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                pose,
                x.toFloat(), y.toFloat(),
                (x + width).toFloat(), (y + height(font)).toFloat(),
                CORNER_RADIUS, Palette.BACKGROUND, null,
            ),
        )

        drawHeader(graphics, font, x, y, width)
        graphics.text(font, Component.literal("Pin an item"), x + PADDING, y + HEADER_HEIGHT + 2, Palette.FAINT)
    }

    /**
     * A thin title strip, which is what makes the panel read as one object rather than as text
     * that happens to be stacked. The rule under it doubles as the boundary the eye uses to
     * find the first row.
     */
    private fun drawHeader(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int) {
        // Same layered depth as the terminal screens: the strip sits one step lighter than the
        // panel, so the heading reads as a band rather than as a first row of data.
        graphics.fill(x, y, x + width, y + HEADER_HEIGHT - 4, Palette.SURFACE)

        graphics.text(font, Component.literal("BAZAAR"), x + PADDING, y + 5, Palette.HEADING)

        // Doubles as a staleness check: a feed that stopped updating is worse than no feed,
        // because the numbers still look authoritative. The hollow dot says it without colour.
        val stale = BazaarLivePrices.productIds.isEmpty()
        if (stale) {
            val text = "○ offline"
            graphics.text(font, Component.literal(text), x + width - PADDING - font.width(text), y + 5, Palette.STALE)
        }

        graphics.fill(x + PADDING, y + HEADER_HEIGHT - 4, x + width - PADDING, y + HEADER_HEIGHT - 3, Palette.RULE)
    }

    private fun drawRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        pose: Matrix3x2f,
        productId: String,
        x: Int,
        y: Int,
        width: Int,
    ) {
        val quote = BazaarLivePrices.quoteFor(productId)

        // Items the bazaar doesn't trade are looked up at auction instead. Without this they sat
        // at "…" permanently: the bazaar snapshot can never contain a drill or a sword, so the
        // row looked like it was loading rather than like it was asking the wrong market.
        val auction = if (quote == null) {
            AuctionLivePrices.refreshIfStale(productId)
            AuctionBin.refreshIfStale(productId)
            AuctionLivePrices.quoteFor(productId)
        } else {
            null
        }

        // The cheapest listing, which is what the row should show. The history summary beside it
        // reports the last hour's *average sale* - a different figure and a worse one for a HUD:
        // it is what someone paid, including well-enchanted examples, not what one costs. Titanium
        // Drill read 391.50M against a 334.00M listing, and Hyperion was out by 142%.
        val bin = if (quote == null) AuctionBin.quoteFor(productId) else null

        val textY = y + 2

        graphics.text(font, Component.literal(ProductName.short(productId)), x + PADDING, textY, Palette.NAME)

        // Laid out from the right edge inward so the columns line up down the panel regardless
        // of how long the names are.
        var right = x + width - PADDING

        if (config.showSpreadBar && quote != null) {
            drawSpreadBar(graphics, right - SPREAD_BAR_WIDTH, y + 3, quote.spreadPercent)
            right -= SPREAD_BAR_WIDTH + GAP
        } else if (config.showSpreadBar && auction != null) {
            // An auction item has no spread to draw - there are no standing orders, only past
            // sales. The column is left empty rather than filled with something that would be
            // read as a spread of zero.
            right -= SPREAD_BAR_WIDTH + GAP
        }

        val change = BazaarPriceTrend.changePercentFor(productId) ?: auction?.changePercent
        val changeText = change?.let { NumberFormats.change(it) } ?: "–"
        graphics.text(
            font,
            Component.literal(changeText),
            right - font.width(changeText),
            textY,
            change?.let { if (it >= 0) Palette.POSITIVE else Palette.NEGATIVE } ?: Palette.FAINT,
        )
        right -= CHANGE_WIDTH + GAP

        val priceText = when {
            quote != null -> NumberFormats.price(quote.buyPrice)
            // Cheapest listing first; the last sale only stands in while that is still loading or
            // when nothing is listed, so the row shows a price rather than nothing.
            bin != null -> NumberFormats.price(bin.lowest)
            auction != null -> NumberFormats.price(auction.price)
            else -> "…"
        }
        graphics.text(font, Component.literal(priceText), right - font.width(priceText), textY, Palette.TEXT)

        // Marks the price as an auction figure rather than a live bazaar one. They are not the
        // same kind of number - one is what you can pay right now, the other what someone paid
        // in the last hour - and a column of prices that mixes them silently invites comparing
        // them as if they were.
        if (auction != null) {
            val mark = "⌂"
            graphics.text(
                font,
                Component.literal(mark),
                right - font.width(priceText) - font.width(mark) - 2,
                textY,
                Palette.FAINT,
            )
        }

        right -= PRICE_WIDTH + GAP

        if (config.showSparkline) {
            // Coloured by direction, so the shape and the sign agree instead of the eye having
            // to check the percentage to know which way it went.
            //
            // Auction items have no sparkline: the trend buffer is filled from the bazaar
            // snapshot, so it stays empty for them and Sparkline draws nothing. That is the
            // right outcome, and it is left explicit here so a future reader doesn't take the
            // blank column for a fault and "fix" it with a flat line.
            val series = BazaarPriceTrend.seriesFor(productId)
            Sparkline.draw(
                graphics, pose, series,
                right - Sparkline.WIDTH, y + 1,
                Sparkline.WIDTH, Sparkline.HEIGHT,
                if ((change ?: 0.0) >= 0) Palette.POSITIVE else Palette.NEGATIVE,
            )
        }
    }

    /**
     * Spread as a short bar: a shape is read faster than a number, and this is the figure a
     * player glances at repeatedly rather than reads once.
     *
     * Scaled against a ceiling instead of the widest spread on screen, so the same item always
     * looks the same width - a relative scale would have bars jump around as items are pinned
     * and unpinned, which reads as the spreads themselves changing.
     */
    private fun drawSpreadBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, spreadPercent: Double) {
        val fraction = (abs(spreadPercent) / SPREAD_BAR_CEILING).coerceIn(0.0, 1.0)
        val filled = (SPREAD_BAR_WIDTH * fraction).toInt()

        graphics.fill(x, y, x + SPREAD_BAR_WIDTH, y + SPREAD_BAR_HEIGHT, Palette.TRACK)
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + SPREAD_BAR_HEIGHT, Palette.BUY)
        }
    }

    private const val PADDING = 6
    private const val GAP = 5
    private const val ROW_HEIGHT = 11
    private const val HEADER_HEIGHT = 18
    private const val CORNER_RADIUS = 4f

    private const val PRICE_WIDTH = 34
    private const val CHANGE_WIDTH = 38
    private const val SPREAD_BAR_WIDTH = 16
    private const val SPREAD_BAR_HEIGHT = 4

    /** A spread this wide fills the bar; past it the exact figure stops mattering. */
    private const val SPREAD_BAR_CEILING = 15.0

    /** Stands in for a real name when sizing an empty panel in the editor. */
    private const val PLACEHOLDER_NAME = "Ench Diamond Block"

    // Darker and more opaque than the screens: this sits over moving scenery rather than a
    // dimmed backdrop, so it needs more separation to stay readable.
}
