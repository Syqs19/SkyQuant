package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trend store decides both the change column and the shape of the overlay's sparkline. Its
 * rules only show themselves over time, which is why the clock is passed in: waiting an hour to
 * find out whether the window trims correctly isn't a test anyone would run.
 */
class BazaarPriceTrendTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    @BeforeTest
    fun clearRecordedSamples() {
        BazaarPriceTrend.reset()
    }

    private fun quote(price: Double, id: String = "ENCHANTED_DIAMOND") = BazaarLivePrices.Quote(
        productId = id,
        buyPrice = price,
        sellPrice = price * 0.95,
        buyVolume = 0,
        sellVolume = 0,
        buyMovingWeek = 1_000_000,
        sellMovingWeek = 1_000_000,
    )

    @Test
    fun `says nothing until there is enough history to mean something`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)

        // One sample is a price, not a trend. Reporting 0% here would look like an answer.
        assertNull(BazaarPriceTrend.changePercentFor("ENCHANTED_DIAMOND"))
    }

    @Test
    fun `reports the move once the samples span long enough`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        BazaarPriceTrend.record(listOf(quote(110.0)), now = start + 5 * minute)

        assertEquals(10.0, BazaarPriceTrend.changePercentFor("ENCHANTED_DIAMOND")!!, 0.01)
    }

    @Test
    fun `reports a fall as a negative move`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        BazaarPriceTrend.record(listOf(quote(90.0)), now = start + 5 * minute)

        assertTrue(BazaarPriceTrend.changePercentFor("ENCHANTED_DIAMOND")!! < 0)
    }

    @Test
    fun `ignores repeat calls within the same minute`() {
        // The overlay ticks constantly; without this the window would fill with thousands of
        // copies of the same number and cover only seconds of real time.
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        repeat(50) { BazaarPriceTrend.record(listOf(quote(100.0)), now = start + it * 100L) }
        // A minute later a second sample is accepted, proving the throttle let exactly one
        // through rather than blocking everything.
        BazaarPriceTrend.record(listOf(quote(105.0)), now = start + minute)

        assertEquals(listOf(100.0, 105.0), BazaarPriceTrend.seriesFor("ENCHANTED_DIAMOND"))
    }

    @Test
    fun `drops samples older than the window`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        BazaarPriceTrend.record(listOf(quote(200.0)), now = start + 30 * minute)
        // Two hours on: the first two samples are now outside the one-hour window.
        BazaarPriceTrend.record(listOf(quote(300.0)), now = start + 120 * minute)

        val series = BazaarPriceTrend.seriesFor("ENCHANTED_DIAMOND")

        assertTrue(100.0 !in series, "a sample older than the window survived: $series")
    }

    @Test
    fun `series is empty until it has a shape to draw`() {
        // One point is not a curve; the sparkline has to skip it rather than draw a flat line
        // that would read as a price that didn't move.
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)

        assertTrue(BazaarPriceTrend.seriesFor("ENCHANTED_DIAMOND").isEmpty())
    }

    @Test
    fun `series keeps prices in the order they happened`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        BazaarPriceTrend.record(listOf(quote(120.0)), now = start + 5 * minute)
        BazaarPriceTrend.record(listOf(quote(110.0)), now = start + 10 * minute)

        assertEquals(listOf(100.0, 120.0, 110.0), BazaarPriceTrend.seriesFor("ENCHANTED_DIAMOND"))
    }

    @Test
    fun `matches product ids regardless of case`() {
        BazaarPriceTrend.record(listOf(quote(100.0)), now = start)
        BazaarPriceTrend.record(listOf(quote(110.0)), now = start + 5 * minute)

        assertEquals(
            BazaarPriceTrend.changePercentFor("ENCHANTED_DIAMOND"),
            BazaarPriceTrend.changePercentFor("enchanted_diamond"),
        )
    }

    @Test
    fun `knows nothing about a product it has never seen`() {
        assertNull(BazaarPriceTrend.changePercentFor("NEVER_SEEN"))
        assertTrue(BazaarPriceTrend.seriesFor("NEVER_SEEN").isEmpty())
    }

    @Test
    fun `a lower-case id from the API is still found by its upper-case key`() {
        // Every reader looks products up upper-cased, so recording under whatever case the
        // snapshot happened to use would file a product where nobody looks for it - a change
        // column that stays blank with no way to tell it apart from "not enough history yet".
        // The existing case test only exercises the *reading* side.
        BazaarPriceTrend.record(listOf(quote(100.0, id = "enchanted_diamond")), now = start)
        BazaarPriceTrend.record(listOf(quote(150.0, id = "enchanted_diamond")), now = start + 5 * minute)

        assertEquals(50.0, BazaarPriceTrend.changePercentFor("ENCHANTED_DIAMOND")!!, 1e-9)
    }

    @Test
    fun `history stays bounded however long the session runs`() {
        // This holds every product on the bazaar, not just the followed ones, and runs for as long
        // as the client does. Samples expiring out of the window is what keeps a day-long session
        // from growing without limit - the window is an hour at one sample a minute, so a product
        // can never hold more than about sixty.
        repeat(600) { BazaarPriceTrend.record(listOf(quote(100.0 + it)), now = start + it * minute) }

        val held = BazaarPriceTrend.sampleCountForTest("ENCHANTED_DIAMOND")

        assertTrue(held <= 61, "ten hours of recording left $held samples for one product")
        assertTrue(held > 1, "the window trimmed everything, leaving no trend to report")
    }
}
