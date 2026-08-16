package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two markets report different things and the chart draws one shape, so this mapping is
 * where a mix-up would be silent: swap high and low and the chart still draws, just upside
 * down, with the tooltip labelling a floor as a ceiling.
 */
class PriceSeriesTest {

    private fun bazaar(buy: Double, sell: Double, buyVol: Long = 100, sellVol: Long = 50) =
        BazaarHistory.Point(timestamp = 1_000, buy = buy, sell = sell, buyVolume = buyVol, sellVolume = sellVol)

    private fun auction(min: Double, max: Double, avg: Double, volume: Long = 3) =
        AuctionHistory.Point(timestamp = 1_000, low = min, high = max, average = avg, volume = volume)

    @Test
    fun `bazaar buy is the high side and sell the low`() {
        // buyPrice is what you pay and is always the higher of the two - getting this backwards
        // inverts every chart without producing an error anywhere.
        val series = PriceSeries.ofBazaar(listOf(bazaar(buy = 1400.0, sell = 1280.0)))
        val point = series.points.single()

        assertEquals(1400.0, point.high, 1e-9)
        assertEquals(1280.0, point.low, 1e-9)
        assertEquals(1340.0, point.middle, 1e-9)
    }

    @Test
    fun `bazaar volume is the weaker side`() {
        // A flip has to buy and sell, so the side with less trade is what limits it. Summing
        // them would flatter an item that only moves in one direction.
        val series = PriceSeries.ofBazaar(listOf(bazaar(1400.0, 1280.0, buyVol = 900, sellVol = 40)))

        assertEquals(40, series.points.single().volume)
    }

    @Test
    fun `auction keeps min, max and the average between them`() {
        val series = PriceSeries.ofAuction(listOf(auction(min = 478_000_000.0, max = 1_220_000_000.0, avg = 939_166_332.0)))
        val point = series.points.single()

        assertEquals(478_000_000.0, point.low, 1e-9)
        assertEquals(1_220_000_000.0, point.high, 1e-9)
        // Coflnet's own mean, not recomputed: the midpoint of the extremes would be a different
        // number and would misreport an hour where most sales clustered at one end.
        assertEquals(939_166_332.0, point.middle, 1e-9)
    }

    @Test
    fun `the kind travels with the series`() {
        // The chart labels and the band both key off this; losing it would show auction data
        // under bazaar wording.
        assertEquals(PriceSeries.Kind.BAZAAR, PriceSeries.ofBazaar(listOf(bazaar(1.0, 0.5))).kind)
        assertEquals(PriceSeries.Kind.AUCTION, PriceSeries.ofAuction(listOf(auction(1.0, 2.0, 1.5))).kind)
    }

    @Test
    fun `a series needs two points to be drawable`() {
        // One point is a dot, not a line, and the chart's own maths divides by size - 1.
        assertTrue(PriceSeries.ofAuction(listOf(auction(1.0, 2.0, 1.5))).isEmpty)
        assertTrue(PriceSeries(PriceSeries.Kind.BAZAAR, emptyList()).isEmpty)
        assertFalse(PriceSeries.ofBazaar(listOf(bazaar(1.0, 0.5), bazaar(1.1, 0.6))).isEmpty)
    }

    @Test
    fun `an hour with one sale has no span, and that is not an error`() {
        // Divan's Drill sold three times in a whole day. A flat point is real data.
        val series = PriceSeries.ofAuction(listOf(auction(min = 500.0, max = 500.0, avg = 500.0, volume = 1)))
        val point = series.points.single()

        assertEquals(point.low, point.high, 1e-9)
        assertEquals(1, point.volume)
    }

    @Test
    fun `the summary spans the whole window, not just the last point`() {
        // The bug this guards: the side panel read only the final point, and a final hour with
        // one sale has that sale as its min, its max and its average alike - so High, Avg and
        // Low all showed 620.00M and the panel looked broken.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 310_000_000.0, max = 480_000_000.0, avg = 395_000_000.0, volume = 2),
                auction(min = 380_000_000.0, max = 580_000_000.0, avg = 453_000_000.0, volume = 3),
                auction(min = 620_000_000.0, max = 620_000_000.0, avg = 620_000_000.0, volume = 1),
            ),
        )

        val summary = series.summarize()!!

        assertEquals(310_000_000.0, summary.low, 1e-9)
        assertEquals(620_000_000.0, summary.high, 1e-9)
        // The last sale is still reported, separately - it just isn't allowed to stand in for
        // the window's extremes.
        assertEquals(620_000_000.0, summary.latest, 1e-9)
        assertEquals(6, summary.totalVolume)
    }

    @Test
    fun `the average is weighted by how many actually sold`() {
        // An hour with forty sales says more about the going rate than an hour with one. A plain
        // mean of the two hourly averages would give 550M; weighting gives 300M, which is what
        // nearly every buyer actually paid.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 300_000_000.0, max = 300_000_000.0, avg = 300_000_000.0, volume = 40),
                auction(min = 800_000_000.0, max = 800_000_000.0, avg = 800_000_000.0, volume = 1),
            ),
        )

        val summary = series.summarize()!!

        val plainMean = 550_000_000.0
        assertTrue(summary.average < plainMean, "a single outlier sale must not drag the average")
        assertEquals((300_000_000.0 * 40 + 800_000_000.0) / 41, summary.average, 1.0)
    }

    @Test
    fun `hours with no sales do not drag the average`() {
        // Coflnet emits rows for quiet hours carrying a stale price and zero volume. Weighting
        // is what keeps them out: a plain mean of the three averages here would be 366, a price
        // at which nothing ever changed hands.
        val traded = auction(min = 100.0, max = 100.0, avg = 100.0, volume = 5)
        val quiet = auction(min = 900.0, max = 900.0, avg = 900.0, volume = 0)

        val summary = PriceSeries.ofAuction(listOf(traded, quiet, traded)).summarize()!!

        assertEquals(100.0, summary.average, 1e-9)
        assertEquals(10, summary.totalVolume)
    }

    @Test
    fun `a quiet hour still counts as a high or a low`() {
        // Deliberately different from the average: an hour with no completed sales carries the
        // asking prices that were standing, and those are part of the range the item traded in
        // even though they shouldn't move the average. Excluding them from the extremes as well
        // would narrow the band to only the hours that happened to clear.
        val summary = PriceSeries.ofAuction(
            listOf(
                auction(min = 100.0, max = 150.0, avg = 120.0, volume = 5),
                auction(min = 90.0, max = 900.0, avg = 400.0, volume = 0),
            ),
        ).summarize()!!

        assertEquals(90.0, summary.low, 1e-9)
        assertEquals(900.0, summary.high, 1e-9)
        assertEquals(120.0, summary.average, 1e-9)
    }

    @Test
    fun `a window with no sales at all still reports a price`() {
        // Falling back to an unweighted mean rather than dividing by a zero total, which would
        // put NaN on the panel - a number that formats as "NaN" and looks like a crash.
        val summary = PriceSeries.ofAuction(
            listOf(
                auction(min = 100.0, max = 200.0, avg = 150.0, volume = 0),
                auction(min = 100.0, max = 300.0, avg = 250.0, volume = 0),
            ),
        ).summarize()!!

        assertEquals(200.0, summary.average, 1e-9)
        assertEquals(0, summary.totalVolume)
    }

    @Test
    fun `summarizing nothing gives nothing rather than zero`() {
        // Zero would be drawn as a real price of zero coins.
        assertEquals(null, PriceSeries(PriceSeries.Kind.AUCTION, emptyList()).summarize())
    }

    @Test
    fun `a mispriced listing does not become the base price`() {
        // Measured on the real API: a week of Titanium Drill contains one hour whose minimum is
        // 1.0M against a median of 336M. Following the raw minimum would drag the line - and the
        // chart's whole vertical scale - down to it for that hour.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 330_000_000.0, max = 450_000_000.0, avg = 380_000_000.0),
                auction(min = 1_000_000.0, max = 400_000_000.0, avg = 350_000_000.0),
                auction(min = 340_000_000.0, max = 470_000_000.0, avg = 390_000_000.0),
            ),
        )

        assertTrue(
            series.basePrices[1] > 100_000_000.0,
            "the 1M outlier was taken as a real base price: ${series.basePrices[1]}",
        )
        // The hour is corrected, not dropped - removing it would shift every later point along
        // the time axis, so a bad listing would distort *when* things happened.
        assertEquals(3, series.basePrices.size)
    }

    @Test
    fun `genuinely cheap hours are kept`() {
        // The guard has to be loose enough to leave real data alone. Hyperion's honest range runs
        // to 2.29x within a week, so anything tighter than half the median starts deleting the
        // cheap sales the base line exists to find.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 520_000_000.0, max = 1_190_000_000.0, avg = 800_000_000.0),
                auction(min = 430_000_000.0, max = 900_000_000.0, avg = 650_000_000.0),
                auction(min = 540_000_000.0, max = 1_200_000_000.0, avg = 820_000_000.0),
            ),
        )

        assertEquals(430_000_000.0, series.basePrices[1], 1e-9)
    }

    @Test
    fun `the bazaar has no base price, only its own low`() {
        // Every Enchanted Diamond is identical, so there is no "unmodified example" to find and
        // no outlier floor to apply - a cheap moment on the bazaar is just a cheap moment.
        val series = PriceSeries.ofBazaar(
            listOf(bazaar(buy = 1400.0, sell = 1280.0), bazaar(buy = 1410.0, sell = 1.0)),
        )

        assertEquals(1.0, series.basePrices[1], 1e-9)
        assertFalse(series.hasVariants)
        assertTrue(series.hasOrderBook)
    }

    @Test
    fun `a month of bazaar prices has neither variants nor an order book`() {
        // The month window feeds a bazaar item through the item-price endpoint, so it is a third
        // case: a fungible market whose data has no buy and sell sides. Getting this wrong draws
        // two curves from one series, or applies the auction outlier floor to the bazaar.
        val series = PriceSeries.ofBazaarDaily(listOf(auction(min = 1250.0, max = 1380.0, avg = 1315.0)))

        assertFalse(series.hasOrderBook, "there are no two sides in daily bazaar data")
        assertFalse(series.hasVariants, "bazaar goods are fungible")
    }

    @Test
    fun `the band and the line differ by market`() {
        val auctionSeries = PriceSeries.ofAuction(
            listOf(auction(min = 300.0, max = 900.0, avg = 400.0), auction(min = 310.0, max = 800.0, avg = 420.0)),
        )
        // At auction the line is the base and the band stops at the average, so the most
        // enchanted example that sold doesn't set the top of the chart.
        assertEquals(300.0, auctionSeries.linePrice(0), 1e-9)
        assertEquals(400.0, auctionSeries.bandTop(0), 1e-9)

        val dailySeries = PriceSeries.ofBazaarDaily(
            listOf(auction(min = 1250.0, max = 1380.0, avg = 1315.0), auction(min = 1260.0, max = 1424.0, avg = 1342.0)),
        )
        // On the bazaar the representative figure is the day's average and the band is the full
        // swing around it - putting the line on the daily minimum would report the single
        // cheapest moment of each day as if it were the price.
        assertEquals(1315.0, dailySeries.linePrice(0), 1e-9)
        assertEquals(1380.0, dailySeries.bandTop(0), 1e-9)
        assertEquals(1250.0, dailySeries.bandBottom(0), 1e-9)
    }

    @Test
    fun `a single sale does not set the chart's scale`() {
        // Measured on a real day of Titanium Drill: four hours of twenty-two had one sale, one of
        // them a well-enchanted example at 620M against a typical 335M. Scaling to it took the
        // axis from 134M tall to 301M and squashed every other hour into a strip.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 330_000_000.0, max = 400_000_000.0, avg = 360_000_000.0, volume = 5),
                auction(min = 620_000_000.0, max = 620_000_000.0, avg = 620_000_000.0, volume = 1),
                auction(min = 335_000_000.0, max = 420_000_000.0, avg = 370_000_000.0, volume = 4),
            ),
        )

        val bounds = series.scaleBounds()!!

        assertTrue(
            bounds.endInclusive < 500_000_000.0,
            "the one-sale hour set the top of the axis: ${bounds.endInclusive}",
        )
        // Still present in the data - it is drawn, just clipped, because a real sale that is
        // hidden misreports how the item traded.
        assertEquals(3, series.points.size)
    }

    @Test
    fun `a wide but well-traded hour still counts`() {
        // The rejected alternative was a ceiling at 1.5x the median, which clipped 21 of
        // Hyperion's 23 hours - there the spread is genuine. Only thin hours are excluded.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 480_000_000.0, max = 1_200_000_000.0, avg = 900_000_000.0, volume = 6),
                auction(min = 500_000_000.0, max = 1_100_000_000.0, avg = 850_000_000.0, volume = 5),
            ),
        )

        val bounds = series.scaleBounds()!!

        // The top follows the drawn line, which at auction is the base price - not the average,
        // which was the top of the band before the band was removed. Scaling to a level nothing
        // is drawn at would leave the curve sitting in the bottom half of an empty plot.
        assertEquals(500_000_000.0, bounds.endInclusive, 1e-9)
        assertEquals(480_000_000.0, bounds.start, 1e-9)
    }

    @Test
    fun `one busy hour among thin ones does not collapse the scale`() {
        // Divan's Drill, read off the live API on 15 August 2026 - the exact four points behind a
        // chart that drew no curve at all and printed "1355.00M" on all four axis labels.
        //
        // Only the 10:00 hour had more than one sale, so the top - which looks at those hours
        // only - saw a single 1355M point, while the bottom looks at every hour and found that
        // same 1355M. Both ends landed on one value and the plot had zero height.
        //
        // The fault was the asymmetry: the two ends were computed over different sets of points,
        // so a filtered maximum could sit at or below an unfiltered minimum.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 2_028_000_000.0, max = 2_028_000_000.0, avg = 2_028_000_000.0, volume = 1),
                auction(min = 1_355_000_000.0, max = 1_974_900_000.0, avg = 1_573_300_000.0, volume = 3),
                auction(min = 2_245_000_000.0, max = 2_245_000_000.0, avg = 2_245_000_000.0, volume = 1),
                auction(min = 1_888_888_800.0, max = 1_888_888_800.0, avg = 1_888_888_888.0, volume = 1),
            ),
        )

        val bounds = series.scaleBounds()!!

        assertTrue(
            bounds.endInclusive > bounds.start,
            "the axis collapsed to a single value: ${bounds.start} .. ${bounds.endInclusive}",
        )
    }

    @Test
    fun `an item that only ever sells once an hour still gets a scale`() {
        // Divan's Drill sold three times in a day. If every hour is thin then thin hours are all
        // the data there is, and excluding them would leave nothing to scale to.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 1_500_000_000.0, max = 1_500_000_000.0, avg = 1_500_000_000.0, volume = 1),
                auction(min = 1_600_000_000.0, max = 1_600_000_000.0, avg = 1_600_000_000.0, volume = 1),
            ),
        )

        val bounds = series.scaleBounds()!!

        assertTrue(bounds.endInclusive >= 1_500_000_000.0, "an all-thin item lost its scale")
        assertTrue(bounds.start <= bounds.endInclusive)
    }

    @Test
    fun `a genuinely flat window still gets a drawable axis`() {
        // Two hours at exactly the same price is real data, not an error - but a zero-height axis
        // draws nothing, so the line would vanish for the one item whose price never moved.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 500_000.0, max = 500_000.0, avg = 500_000.0, volume = 4),
                auction(min = 500_000.0, max = 500_000.0, avg = 500_000.0, volume = 5),
            ),
        )

        val bounds = series.scaleBounds()!!

        assertTrue(bounds.endInclusive > bounds.start, "a flat window left no room to draw in")
        // The padding stays small, so a flat stretch still reads as flat rather than as a swing.
        assertTrue(
            bounds.endInclusive - bounds.start < 500_000.0 * 0.05,
            "the padding invented a price swing: ${bounds.start} .. ${bounds.endInclusive}",
        )
    }

    @Test
    fun `the usual price is the median, not the cheapest hour`() {
        // The verdict compares the live price against this, so using the window minimum would
        // call almost everything expensive - and would drift lower the longer the window, so the
        // same item would look dearer on 7d than on 1d without anything having changed.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 300.0, max = 400.0, avg = 350.0, volume = 3),
                auction(min = 340.0, max = 450.0, avg = 380.0, volume = 4),
                auction(min = 350.0, max = 460.0, avg = 390.0, volume = 2),
            ),
        )

        val summary = series.summarize()!!

        assertEquals(340.0, summary.usual, 1e-9)
        assertEquals(300.0, summary.base, 1e-9)
    }

    @Test
    fun `quiet hours do not set the usual price`() {
        // A run of hours with no sales carries standing prices, and letting them into the median
        // would define "usual" from prices nobody paid.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 300.0, max = 400.0, avg = 350.0, volume = 5),
                auction(min = 900.0, max = 900.0, avg = 900.0, volume = 0),
                auction(min = 900.0, max = 900.0, avg = 900.0, volume = 0),
                auction(min = 310.0, max = 410.0, avg = 360.0, volume = 4),
            ),
        )

        assertEquals(305.0, series.summarize()!!.usual, 1e-9)
    }

    @Test
    fun `the band never inverts`() {
        // An hour whose average sits below its own base would draw a band with its top under its
        // bottom, which fills as a stripe in the wrong place rather than as nothing.
        val series = PriceSeries.ofAuction(listOf(auction(min = 500.0, max = 500.0, avg = 400.0)))

        assertTrue(series.bandTop(0) >= series.bandBottom(0))
    }

    @Test
    fun `repeated summaries agree, since the graph screen asks three times a frame`() {
        // The figures are cached now: the side panel, the verdict row and the change figure each
        // call summarize() on the same series, and recomputing was measured at 42us a time. What
        // caching must not do is change the answer, so the cheap guard is that it doesn't.
        val series = PriceSeries.ofAuction(
            listOf(
                auction(min = 300.0, max = 500.0, avg = 400.0, volume = 6),
                auction(min = 320.0, max = 520.0, avg = 420.0, volume = 3),
                auction(min = 280.0, max = 480.0, avg = 380.0, volume = 9),
            ),
        )

        val first = series.summarize()!!
        val second = series.summarize()!!

        assertEquals(first.low, second.low, 1e-9)
        assertEquals(first.high, second.high, 1e-9)
        assertEquals(first.average, second.average, 1e-9)
        assertEquals(first.usual, second.usual, 1e-9)
        assertEquals(first.changePercent, second.changePercent, 1e-9)
        assertEquals(first.totalVolume, second.totalVolume)
    }

    @Test
    fun `two series with the same shape summarise independently`() {
        // A cache held on the wrong scope - shared between instances rather than per series -
        // would answer one item's panel with another item's figures. The failure would look like
        // the chart and the panel disagreeing, which reads as a rendering fault rather than a
        // caching one.
        val cheap = PriceSeries.ofAuction(listOf(auction(min = 100.0, max = 200.0, avg = 150.0, volume = 4)))
        val dear = PriceSeries.ofAuction(listOf(auction(min = 900.0, max = 1_100.0, avg = 1_000.0, volume = 4)))

        assertEquals(150.0, cheap.summarize()!!.latest, 1e-9)
        assertEquals(1_000.0, dear.summarize()!!.latest, 1e-9)
        // Asked again in the other order, in case the first read populated something shared.
        assertEquals(1_000.0, dear.summarize()!!.latest, 1e-9)
        assertEquals(150.0, cheap.summarize()!!.latest, 1e-9)
    }
}
