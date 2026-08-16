package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.feature.bazaar.data.BazaarHistory
import dev.syqs.skyquant.feature.bazaar.data.PriceSeries
import dev.syqs.skyquant.gui.Palette
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Draws the price curves, axes, volume band and hover readout inside a given rectangle.
 *
 * Split out of the screen because it needs none of what a screen is: no state, no lifecycle, no
 * input handling - only points and a box to draw them in. Keeping the two together left one file
 * doing loading, layout, input and six kinds of drawing at once, which is where a change is
 * hardest to make safely.
 */
class PriceChart(
    private val plot: ScreenRectangle,
    private val series: PriceSeries,
    private val range: BazaarHistory.Range,
    /** Cheapest live listing, drawn as a reference line. Null when there isn't one to show. */
    private val liveBin: Double? = null,
) {

    private val points: List<PriceSeries.Point> get() = series.points

    /**
     * What the two curves are called, which is not the same question on the two markets.
     *
     * On the bazaar they are the live sides of an order book - what you pay and what you get.
     * At auction they are the extremes of what completed sales went for in that hour. Labelling
     * the second pair "Buy" and "Sell" would invite reading the gap as a spread you could trade
     * against, when it is really a measure of how widely the item has been going for.
     */
    private val highLabel: String
        get() = if (series.hasOrderBook) "Buy" else "High"

    private val lowLabel: String
        get() = if (series.hasOrderBook) "Sell" else "Low"

    private val gapLabel: String
        get() = if (series.hasOrderBook) "Gap" else "Range"

    private val volumeLabel: String
        get() = if (series.kind == PriceSeries.Kind.AUCTION) "Sold" else "Vol"

    /**
     * Vertical band the curves occupy, inset so the line's own thickness isn't clipped at the
     * extremes and the volume bars have room underneath. Derived once because the curves, the
     * gridlines and the hover markers all have to agree on it.
     */
    private val curveTop: Int get() = plot.position().y() + CURVE_VERTICAL_PADDING

    private val curveHeight: Int
        get() = (plot.height() - CURVE_VERTICAL_PADDING * 2 - VOLUME_BAND_HEIGHT).coerceAtLeast(1)

    private val left: Int get() = plot.position().x()
    private val top: Int get() = plot.position().y()
    private val right: Int get() = left + plot.width()
    private val bottom: Int get() = top + plot.height()

    /** [hoverX] is the cursor's position when it's over the plot, or null when it isn't. */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, pose: Matrix3x2f, hoverX: Int?) {
        if (points.size < 2) return

        // Both curves share one scale so the visual gap between them is the real spread; scaling
        // them separately would make a wide spread look identical to a narrow one.
        //
        // At auction the scale is set by what is actually drawn - the base line and the band up
        // to the average - rather than by the raw extremes. Including the hourly maximum here
        // would let one heavily enchanted sale set the top of the axis and squash the line
        // everyone came to read into the bottom third of the plot.
        val bounds = series.scaleBounds()

        var lowestPoint = if (series.hasOrderBook || bounds == null) {
            points.minOf { minOf(it.high, it.low) }
        } else {
            bounds.start
        }

        var highestPoint = if (series.hasOrderBook || bounds == null) {
            points.maxOf { maxOf(it.high, it.low) }
        } else {
            bounds.endInclusive
        }

        // The live rule is only worth drawing if it lands on the chart, so the scale makes room
        // for it - but only within reach of the history, since an item listed at ten times what
        // it has ever sold for would otherwise flatten the entire curve to accommodate one
        // hopeful seller. Beyond that the rule is dropped and the panel reports the figure.
        liveBin?.takeIf { it > 0 }?.let { bin ->
            val span = (highestPoint - lowestPoint).coerceAtLeast(1.0)
            if (bin in (lowestPoint - span)..(highestPoint + span)) {
                lowestPoint = minOf(lowestPoint, bin)
                highestPoint = maxOf(highestPoint, bin)
            }
        }
        val span = (highestPoint - lowestPoint).takeIf { abs(it) > 1e-9 } ?: 1.0

        // Headroom above and below the extremes. Mapping the highest price exactly to the top of
        // the band clipped the tallest spike: the smoothing overshoots a sharp peak by design,
        // and the line's own thickness sits on top of that. Padding in pixels can't fix it
        // because the overshoot scales with how sharp the move is, so the room is taken here, in
        // the value range itself.
        val headroom = span * VERTICAL_HEADROOM
        val lowest = lowestPoint - headroom
        val priceRange = span + headroom * 2

        drawGrid(graphics, font, lowest, priceRange)
        drawTimeAxis(graphics, font)
        drawVolumeBars(graphics)
        // Only where the span is the point. On a month of bazaar prices it is the day's high-low
        // swing, which is real movement in one fungible good's price.
        //
        // Deliberately *not* drawn at auction any more. There it showed the room between a bare
        // item and a typical one, which is true and was still the wrong thing to draw: it filled
        // the plot with the very variation that makes an auction chart hard to read, competing
        // with the two lines that answer the actual question - what it usually costs, and what
        // it costs now. The premium is still reported in the hover tooltip, where it can be
        // asked for rather than being in the way.
        if (!series.hasOrderBook && !series.hasVariants) drawRangeBand(graphics, lowest, priceRange)
        drawCurves(graphics, font, pose, lowest, priceRange)
        drawBandLabels(graphics, font)
        drawLiveBinLine(graphics, font, lowest, priceRange)

        hoverX?.let { drawHover(graphics, font, it, lowest, priceRange) }
    }

    /**
     * The low-to-high span of each hour, as a faint column behind the curves.
     *
     * Auction-only, and the reason is what the two markets measure. A bazaar's two curves are
     * prices you can act on right now, so the gap between them is a cost. An hour of auction
     * sales is a scatter - three items might go for 480M, 900M and 1.2B - and drawing only the
     * average would state a precision the data doesn't have. The band is that scatter, shown
     * rather than averaged away.
     *
     * Drawn before the curves so they stay legible on top of it, and as plain fills rather than
     * a polygon: the split-phase renderer draws quads only, and one column per point is both
     * simpler and immune to the self-intersection that sinks a hand-built outline.
     */
    private fun drawRangeBand(graphics: GuiGraphicsExtractor, lowest: Double, priceRange: Double) {
        val columnWidth = (plot.width().toFloat() / points.size).coerceAtLeast(1f)

        for (index in points.indices) {
            val x = left + (plot.width().toFloat() * index / points.size).toInt()
            // Base to average, not minimum to maximum: the top of an hour's range is whichever
            // best-equipped example happened to sell, and letting it set the band made the chart
            // mostly a record of how well-kitted the luckiest seller was.
            // Clamped to the plot, because the axis is scaled to the hours backed by real
            // trading: a single-sale hour can sit above the top and must stop at the edge rather
            // than paint over the header. The bar is still drawn, so the reader sees the price
            // went off the top - it just no longer decides the scale for everything else.
            val topY = valueY(series.bandTop(index), lowest, priceRange).toInt().coerceIn(top, bottom)
            val baseY = valueY(series.bandBottom(index), lowest, priceRange).toInt().coerceIn(top, bottom)

            // An hour with a single sale has no span at all; a hairline keeps it visible as a
            // point that exists rather than letting it vanish.
            graphics.fill(
                x,
                topY,
                x + columnWidth.toInt().coerceAtLeast(1),
                maxOf(baseY, topY + 1).coerceAtMost(bottom),
                Palette.RANGE_BAND,
            )
        }
    }

    /**
     * Names the two shaded bands, in place, at the left edge where the reader starts.
     *
     * They were the chart's blind spot: the min-max columns and the volume bars are both large
     * blocks of colour that carry meaning, and neither said what it was. The only way to find
     * out was to hover a point - which you would only do if you already suspected there was
     * something to find. A chart that has to be interrogated before it can be read is not
     * finished.
     *
     * Drawn over the plot rather than in a legend beneath it because these label areas, not
     * series: a swatch in a footer would still leave the reader mapping colour to region.
     */
    private fun drawBandLabels(graphics: GuiGraphicsExtractor, font: Font) {
        // The volume strip is present on both markets, and "Sold" versus "Vol" is the same
        // distinction the tooltip already makes.
        val volumeText = if (series.kind == PriceSeries.Kind.AUCTION) "Sold" else "Volume"
        val volumeY = bottom - CURVE_VERTICAL_PADDING - VOLUME_BAND_HEIGHT + 2

        graphics.text(font, Component.literal(volumeText), left + BAND_LABEL_INSET, volumeY, Palette.FAINT)

        // The price band only needs naming where it isn't self-evident. Two curves labelled in
        // the side panel explain themselves; a field of translucent columns does not.
        // Only where a band is actually drawn - the auction chart no longer has one.
        if (series.hasOrderBook || series.hasVariants) return

        graphics.text(
            font,
            Component.literal("day range"),
            left + BAND_LABEL_INSET,
            curveTop + BAND_LABEL_INSET,
            Palette.FAINT,
        )
    }

    /**
     * The cheapest live listing, as a dashed rule across the plot.
     *
     * This is the one line on the chart that isn't history, and the dashes say so: a solid line
     * would read as another recorded series, when it is a single price that exists right now.
     * Its whole value is the comparison - a rule sitting below the curve means the item is going
     * for less than it has been, which is the question that brings anyone to a price chart.
     *
     * Drawn as short fills rather than a [LineRenderState] because it is horizontal and needs no
     * smoothing; the split-phase renderer draws quads, and a run of them is exactly a dash.
     */
    private fun drawLiveBinLine(
        graphics: GuiGraphicsExtractor,
        font: Font,
        lowest: Double,
        priceRange: Double,
    ) {
        val bin = liveBin ?: return

        val y = valueY(bin, lowest, priceRange).toInt()
        // Off-scale when the live price sits far outside the charted window - which happens, and
        // is worth not drawing rather than pinning to an edge where it would claim a level it
        // doesn't have. The side panel still reports the figure.
        if (y < curveTop || y > curveTop + curveHeight) return

        var x = left
        while (x < right) {
            graphics.fill(x, y, (x + DASH_LENGTH).coerceAtMost(right), y + 1, Palette.ACCENT)
            x += DASH_LENGTH + DASH_GAP
        }

        // Labelled in place: an unexplained rule across a chart is a puzzle, and the whole point
        // of it is being recognised at a glance.
        //
        // Anchored inside the plot rather than flush to its right edge - at the edge the text
        // began where the plot ended and ran on into the side panel, so the tail of the word sat
        // outside the chart it belongs to.
        val label = "BIN"
        val labelX = right - font.width(label) - BAND_LABEL_INSET
        graphics.fill(labelX - 2, y - 4, right - 1, y + 5, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(label), labelX, y - 3, Palette.ACCENT)
    }

    private fun drawCurves(
        graphics: GuiGraphicsExtractor,
        font: Font,
        pose: Matrix3x2f,
        lowest: Double,
        priceRange: Double,
    ) {
        val ceiling = lowest + priceRange

        fun project(value: Double, index: Int) = LineRenderState.Point(
            x = left + plot.width() * index.toFloat() / (points.size - 1),
            y = valueY(value.coerceAtMost(ceiling), lowest, priceRange).toFloat(),
        )

        // The hours the line skips entirely - see [offScaleIndices]. Two earlier attempts got
        // this wrong in opposite directions and both are worth remembering: clipping the finished
        // polyline cut the line into three pieces, because a single 620M point drags its two
        // neighbouring segments out of the plot too; capping the value kept the line whole but
        // drew a rounded peak rising to the ceiling and back, which reads as a price that climbed
        // to 400M - a movement that never happened. A visibly broken chart is a smaller fault
        // than a plausible false one.
        val skipped = offScaleIndices(points.indices.map { series.linePrice(it) }, ceiling).toSet()

        // At auction the line follows the cheapest example sold each hour, which is the closest
        // thing the data has to "an unmodified one of these". Auction items aren't fungible -
        // a drill with good reforges and enchantments is a different product wearing the same
        // name - so an average across all of them answers a question nobody asked. The spread
        // above the line is still visible as the band; it just no longer drives the line.
        val curves = if (!series.hasOrderBook) {
            listOf(
                CurveInterpolation.smooth(
                    points.indices.filterNot { it in skipped }.map { project(series.linePrice(it), it) },
                ) to if (series.hasVariants) Palette.SELL else Palette.BUY,
            )
        } else {
            listOf(
                // Sell first, so the buy line stays on top where the two cross.
                CurveInterpolation.smooth(points.mapIndexed { i, p -> project(p.low, i) }) to Palette.SELL,
                CurveInterpolation.smooth(points.mapIndexed { i, p -> project(p.high, i) }) to Palette.BUY,
            )
        }

        // Clipped to the plot before being handed over. A point can sit far outside the scale by
        // design - see CurveInterpolation.clipVertically - and nothing downstream bounds it, so
        // without this the line ran up the panel and across the tooltip and the side panel.
        val clipTop = plot.position().y().toFloat()
        val clipBottom = (plot.position().y() + plot.height()).toFloat()

        drawOffScaleMarkers(graphics, font, lowest, priceRange)

        for ((curve, color) in curves) {
            for (run in CurveInterpolation.clipVertically(curve, clipTop, clipBottom)) {
                graphics.guiRenderState.addGuiElement(
                    LineRenderState(pose, run, LINE_THICKNESS, color, plot, LineRenderState.boundsOf(run, LINE_THICKNESS)),
                )
            }
        }
    }

    /**
     * Marks the hours the line skips, as separate dots at the top of the plot.
     *
     * These are hours the curve deliberately does not visit. An hour whose only sale sits far
     * above everything around it is usually not the same product: auction items aren't fungible,
     * so a single well-enchanted Titanium Drill at 620M among ordinary ones at 340M is a
     * different thing wearing the same name. Joining it to its neighbours draws a price movement
     * that never happened, whichever way the join is drawn.
     *
     * Shown as a detached dot for the reason charting convention gives for it: a solid line means
     * measured, comparable data, and anything less certain is drawn *apart from* the line rather
     * than inside it. The dot keeps the fact on screen - it was a real sale - without letting it
     * describe the price of the item.
     *
     * Only the top edge is handled. Prices going off the bottom would need the same treatment, but
     * the floor in [PriceSeries.basePrices] already replaces absurdly low points with the median,
     * so nothing gets there - and a marker for a case that cannot occur is untested code.
     */
    private fun drawOffScaleMarkers(
        graphics: GuiGraphicsExtractor,
        font: Font,
        lowest: Double,
        priceRange: Double,
    ) {
        val ceiling = lowest + priceRange
        val excursions = offScaleIndices(
            points.indices.map { series.linePrice(it) },
            ceiling,
        )
        if (excursions.isEmpty()) return

        val color = if (series.hasVariants) Palette.SELL else Palette.BUY

        for (index in excursions) {
            val x = (left + plot.width() * index.toFloat() / (points.size - 1)).toInt()

            // A small square in the curve's own colour, sitting at the top of the plot where the
            // value would be if the axis reached it. Detached from the line on purpose: it marks
            // a sale, not a point the price passed through.
            graphics.fill(x - DOT_RADIUS, curveTop, x + DOT_RADIUS + 1, curveTop + DOT_RADIUS * 2 + 1, color)
        }

        // Labelled at the tallest excursion rather than in a corner of the plot. Parked top right
        // it read as belonging to whatever peak happened to be nearest that corner - the first
        // screenshot showed "620.00M" floating above a peak that was not the 620M one, so the
        // figure named the wrong moment while looking perfectly deliberate.
        //
        // One label rather than one per caret: several would overlap on a chart with a cluster of
        // them, and the reader's question is how far off the chart this goes, which the highest
        // one answers.
        val peakIndex = excursions.maxBy { series.linePrice(it) }
        // "▲" rather than "↑": the same glyph the trend figures already use, so it is known to
        // render in the game's font. An arrow that the font lacks draws as an empty box.
        val text = "▲ ${NumberFormats.price(series.linePrice(peakIndex))}"

        val labelWidth = font.width(text)
        val peakX = (left + plot.width() * peakIndex.toFloat() / (points.size - 1)).toInt()

        // Centred over its own caret, then pulled inside the plot at either edge - a peak in the
        // last few points would otherwise push the text into the side panel.
        val textX = (peakX - labelWidth / 2)
            .coerceIn(left + BAND_LABEL_INSET, left + plot.width() - labelWidth - BAND_LABEL_INSET)

        // Just below the dot, not level with it: sharing the row would have the text start inside
        // the very marker it explains.
        val textY = curveTop + DOT_RADIUS * 2 + 2

        graphics.fill(textX - 2, textY, textX + labelWidth + 1, textY + 9, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(text), textX, textY + 1, Palette.MUTED)
    }

    private fun valueY(value: Double, lowest: Double, priceRange: Double): Double =
        curveTop + curveHeight * (1.0 - (value - lowest) / priceRange)

    /**
     * Vertical readout line following the cursor, with the values at that moment.
     *
     * Without it the chart can only be read approximately: two curves over a time axis give no
     * way to line a point up with its timestamp by eye, which is the whole question being asked
     * of a price history.
     */
    private fun drawHover(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        lowest: Double,
        priceRange: Double,
    ) {
        if (mouseX < left || mouseX > right) return

        val index = (((mouseX - left).toFloat() / plot.width() * (points.size - 1)).toInt())
            .coerceIn(0, points.size - 1)
        val point = points[index]
        val snappedX = left + (plot.width().toFloat() * index / (points.size - 1)).toInt()

        graphics.fill(snappedX, top, snappedX + 1, bottom, Palette.HOVER_LINE)

        // Markers sit on what is drawn. At auction that is the base line and the top of the
        // band; putting one on the hourly maximum would place a dot in empty space above the
        // chart, since the maximum no longer sets the scale.
        val markers = when {
            series.hasOrderBook -> listOf(point.high to Palette.BUY, point.low to Palette.SELL)
            // One marker at auction, because there is one line: a second dot floating where the
            // band used to be would mark a level nothing is drawn at.
            series.hasVariants -> listOf(series.linePrice(index) to Palette.SELL)
            else -> listOf(series.bandTop(index) to Palette.BUY, series.linePrice(index) to Palette.SELL)
        }

        for ((value, color) in markers) {
            val y = valueY(value, lowest, priceRange).toInt()
            graphics.fill(snappedX - 2, y - 2, snappedX + 3, y + 3, color)
        }

        drawHoverTooltip(graphics, font, point, snappedX, index)
    }

    private fun drawHoverTooltip(
        graphics: GuiGraphicsExtractor,
        font: Font,
        point: PriceSeries.Point,
        anchorX: Int,
        index: Int,
    ) {
        // Each monthly point covers a whole day, so showing "00:00" beside it would state a
        // precision the figure doesn't have - those are a day's sales, not midnight's.
        val tooltipFormat = if (range == BazaarHistory.Range.MONTH) DATE_FORMAT else TOOLTIP_FORMAT
        val time = tooltipFormat.format(Instant.ofEpochMilli(point.timestamp).atZone(ZoneId.systemDefault()))

        // Label and value are drawn as separate columns rather than padded with spaces: the
        // game's font is proportional, so spaces line values up only by accident.
        val rows = if (series.hasVariants) {
            val base = series.basePrices[index]
            val premium = point.high - base
            val premiumPercent = if (abs(base) > 1e-9) premium / base * 100 else 0.0

            listOf(
                // Base first: it is what the line shows and what an unmodified item costs.
                Triple("Base", NumberFormats.price(base), Palette.SELL),
                Triple("Avg", NumberFormats.price(point.middle), Palette.TEXT),
                Triple("Top", NumberFormats.price(point.high), Palette.BUY),
                // Named for what it actually is: the extra that reforges and enchantments on the
                // best example sold that hour were worth. Calling it a spread, as the bazaar
                // tooltip does, would invite reading it as a margin you could trade against.
                Triple(
                    "Extras",
                    "${NumberFormats.price(premium)}  ${NumberFormats.percentCompact(premiumPercent)}",
                    Palette.MUTED,
                ),
                Triple(volumeLabel, NumberFormats.volume(point.volume), Palette.MUTED),
            )
        } else if (series.hasOrderBook) {
            val spread = point.high - point.low
            val spreadPercent = if (abs(point.high) > 1e-9) spread / point.high * 100 else 0.0

            listOf(
                Triple(highLabel, NumberFormats.price(point.high), Palette.BUY),
                Triple(lowLabel, NumberFormats.price(point.low), Palette.SELL),
                // The margin at that moment, the reason for looking at a past point at all.
                Triple(
                    gapLabel,
                    "${NumberFormats.price(spread)}  ${NumberFormats.percentCompact(spreadPercent)}",
                    Palette.TEXT,
                ),
                Triple(volumeLabel, NumberFormats.volume(point.volume), Palette.MUTED),
            )
        } else {
            // A day of bazaar prices: fungible goods, so no base example to name, but also no
            // order book, so the high-low gap is a day's swing rather than a spread anyone could
            // have traded. "Swing" rather than "Gap" for exactly that reason.
            val swing = point.high - point.low
            val swingPercent = if (abs(point.low) > 1e-9) swing / point.low * 100 else 0.0

            listOf(
                Triple("Avg", NumberFormats.price(point.middle), Palette.BUY),
                Triple("High", NumberFormats.price(point.high), Palette.TEXT),
                Triple("Low", NumberFormats.price(point.low), Palette.SELL),
                Triple(
                    "Swing",
                    "${NumberFormats.price(swing)}  ${NumberFormats.percentCompact(swingPercent)}",
                    Palette.MUTED,
                ),
                Triple(volumeLabel, NumberFormats.volume(point.volume), Palette.MUTED),
            )
        }

        val labelWidth = rows.maxOf { font.width(it.first) } + 6
        val width = maxOf(font.width(time), labelWidth + rows.maxOf { font.width(it.second) }) + 12
        val height = (rows.size + 1) * LINE_HEIGHT + 8
        // Flips to the left of the cursor when it would otherwise run past the plot edge.
        val x = if (anchorX + 8 + width <= right) anchorX + 8 else anchorX - 8 - width
        val y = top + 4

        graphics.fill(x, y, x + width, y + height, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(time), x + 6, y + 4, Palette.MUTED)

        for ((i, row) in rows.withIndex()) {
            val rowY = y + 4 + (i + 1) * LINE_HEIGHT
            graphics.text(font, Component.literal(row.first), x + 6, rowY, Palette.MUTED)
            graphics.text(font, Component.literal(row.second), x + 6 + labelWidth, rowY, row.third)
        }
    }

    /**
     * Traded volume as a strip of bars along the bottom, on its own scale.
     *
     * Volume runs into the millions of units while prices sit in the thousands of coins, so it
     * can't share the vertical axis - but it has to share the time axis, because the question
     * it answers is whether a price move happened on real trading or on a handful of orders.
     * A wide spread on a flat volume strip is an item you can buy into and then not get out of.
     */
    private fun drawVolumeBars(graphics: GuiGraphicsExtractor) {
        val peak = points.maxOf { it.volume }.takeIf { it > 0 } ?: return

        val bandBottom = bottom - CURVE_VERTICAL_PADDING
        val bandTop = bandBottom - VOLUME_BAND_HEIGHT

        // Rule between the two: the bars share the time axis but not the vertical one, and
        // without a divider a tall bar reaching up towards the sell curve reads as touching it.
        graphics.fill(left, bandTop, right, bandTop + 1, Palette.GRID)

        val barWidth = (plot.width().toFloat() / points.size).coerceAtLeast(1f)

        for ((index, point) in points.withIndex()) {
            val total = point.volume
            // Bars are kept just inside the band so the tallest one stops short of the rule
            // rather than merging with it.
            val height = ((VOLUME_BAND_HEIGHT - 2) * total.toDouble() / peak).toInt()
            if (height <= 0) continue

            val x = left + (plot.width().toFloat() * index / points.size).toInt()
            graphics.fill(x, bandBottom - height, x + barWidth.toInt().coerceAtLeast(1), bandBottom, Palette.VOLUME)
        }
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor, font: Font, lowest: Double, priceRange: Double) {
        for (step in 0..GRID_LINES) {
            val fraction = step.toFloat() / GRID_LINES
            val y = curveTop + (curveHeight * (1f - fraction)).toInt()

            graphics.fill(left, y, right, y + 1, Palette.GRID)

            val label = Component.literal(
                NumberFormats.axisPrice(lowest + priceRange * fraction, priceRange / GRID_LINES),
            )
            graphics.text(font, label, left - 4 - font.width(label), y - 4, Palette.MUTED)
        }

        graphics.fill(left, top, left + 1, bottom, Palette.AXIS)
        graphics.fill(left, bottom, right, bottom + 1, Palette.AXIS)
    }

    /** Time labels under the plot, spaced out so they never collide at this font size. */
    private fun drawTimeAxis(graphics: GuiGraphicsExtractor, font: Font) {
        val formatter = when (range) {
            BazaarHistory.Range.HOUR, BazaarHistory.Range.DAY -> TIME_FORMAT
            // A month of daily points spans several, so a clock time would repeat "00:00" the
            // whole way across and say nothing about where you are in the window.
            BazaarHistory.Range.WEEK, BazaarHistory.Range.MONTH -> DATE_FORMAT
        }

        val labelCount = timeLabelCount(right - left, points.size)

        for (step in 0..labelCount) {
            val fraction = step.toFloat() / labelCount

            val millis = timeLabelMillis(
                points.first().timestamp,
                points.last().timestamp,
                step,
                labelCount,
            )
            val text = formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

            val x = left + ((right - left) * fraction).toInt()
            // Ends are pulled inward so the first and last labels stay inside the panel.
            val drawX = (x - font.width(text) / 2).coerceIn(left, right - font.width(text))
            graphics.text(font, Component.literal(text), drawX, bottom + 4, Palette.MUTED)
        }
    }

    companion object {

        /**
         * Which points sit above [ceiling], i.e. which ones the plot cannot show.
         *
         * Separated from the drawing so the rule can be checked without a screen. The comparison
         * is deliberately strict: a point landing exactly on the ceiling is drawn at the top edge
         * and is not an excursion, so marking it would put a caret over a line that is perfectly
         * visible.
         */
        internal fun offScaleIndices(values: List<Double>, ceiling: Double): List<Int> {
            val above = values.indices.filter { values[it] > ceiling }

            // Skipping these hours only makes sense while they are the exception. If dropping them
            // would leave too little to draw, the line is better off keeping them - a chart that
            // silently renders nothing is the worst outcome of the three, and the axis is derived
            // from these same points, so this is a guard against a degenerate case rather than an
            // expected one.
            return if (values.size - above.size >= MIN_DRAWABLE_POINTS) above else emptyList()
        }

        /** Below this many points there is no curve left to draw, so nothing is skipped. */
        private const val MIN_DRAWABLE_POINTS = 2

        /**
         * How many gaps the time axis is divided into, given the room available and the data.
         *
         * Capped at `pointCount - 1` because an axis cannot name more moments than the series
         * contains. Without that cap a slow item drew repeats: a real day of Divan's Drill has
         * four sales, six labels were asked for, and the axis read
         * `02:00 02:00 12:00 12:00 18:00 20:00` - two duplicated pairs, which reads as a rendering
         * fault rather than as a thinly traded item.
         *
         * Internal so the arithmetic can be checked without a font or a screen.
         */
        internal fun timeLabelCount(plotWidth: Int, pointCount: Int): Int =
            (plotWidth / MIN_TIME_LABEL_SPACING)
                .coerceAtMost(pointCount - 1)
                .coerceIn(1, 5)

        /**
         * The moment sitting at label [step] of [labelCount], interpolated across the window.
         *
         * Interpolated rather than read off `points[index]`, which is what the axis did before.
         * The label's *position* has always been a fraction of the plot's width, so taking its
         * *text* from whichever point that fraction truncated to put the two out of step whenever
         * the points were unevenly spaced - and auction points are extremely uneven, since an
         * hour with no sales produces none. The label then named a time the curve above it wasn't
         * drawing, which is worse than a duplicate: it is an axis that misreports when.
         */
        internal fun timeLabelMillis(firstMillis: Long, lastMillis: Long, step: Int, labelCount: Int): Long {
            if (labelCount <= 0) return firstMillis
            val fraction = step.toDouble() / labelCount

            return firstMillis + ((lastMillis - firstMillis) * fraction).toLong()
        }

        private const val LINE_THICKNESS = 1.4f
        private const val GRID_LINES = 3
        private const val CURVE_VERTICAL_PADDING = 4

        /**
         * Share of the price span left empty above the highest point and below the lowest, so a
         * sharp spike has somewhere to overshoot into instead of being cut off at the edge.
         */
        private const val VERTICAL_HEADROOM = 0.06

        /** Height reserved at the bottom of the plot for the volume strip. */
        const val VOLUME_BAND_HEIGHT = 28

        private const val LINE_HEIGHT = 10

        /** Roughly the width of a timestamp plus breathing room, to decide how many fit. */
        private const val MIN_TIME_LABEL_SPACING = 70

        /** Gap between a band's edge and the label naming it. */
        private const val BAND_LABEL_INSET = 3

        /**
         * Half the width of the dot marking an hour the line skips.
         *
         * The label's placement is derived from it rather than from a second literal, so the two
         * cannot drift apart and start overlapping.
         */
        private const val DOT_RADIUS = 2

        /** Dash pattern for the live-price rule, in pixels. */
        private const val DASH_LENGTH = 4
        private const val DASH_GAP = 4

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
        private val TOOLTIP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    }
}
