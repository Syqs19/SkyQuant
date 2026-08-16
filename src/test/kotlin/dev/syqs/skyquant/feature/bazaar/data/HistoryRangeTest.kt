package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which windows each market actually serves.
 *
 * These are not preferences, they are measurements against the live Coflnet API - the bazaar
 * answers 404 for a month, auctions answer 404 for an hour. Getting one wrong doesn't crash
 * anything: it lights a button that then loads an empty chart, or greys out a window whose data
 * exists. Both are invisible in code review and obvious only to a player.
 */
class HistoryRangeTest {

    @Test
    fun `auctions have no hourly data`() {
        // Measured: /api/item/price/{id}/history/hour -> HTTP 404.
        assertFalse(BazaarHistory.Range.HOUR.availableOn(PriceSeries.Kind.AUCTION))
        assertTrue(BazaarHistory.Range.HOUR.availableOn(PriceSeries.Kind.BAZAAR))
    }

    @Test
    fun `the month is available on both markets`() {
        // Coflnet's *bazaar* endpoint 404s for a month, but its item-price endpoint serves
        // bazaar products too - measured on ENCHANTED_DIAMOND, which returns 1250-1380 against a
        // live quote of 1289-1343. So the month is fetched through AuctionHistory either way.
        assertTrue(BazaarHistory.Range.MONTH.availableOn(PriceSeries.Kind.BAZAAR))
        assertTrue(BazaarHistory.Range.MONTH.availableOn(PriceSeries.Kind.AUCTION))
    }

    @Test
    fun `a month of bazaar data still offers the bazaar's own windows`() {
        // While BAZAAR_DAILY is on screen the item is still a bazaar item, so switching back to
        // 1h has to stay possible - treating that kind as an auction would grey out the very
        // button the player came from.
        assertTrue(BazaarHistory.Range.HOUR.availableOn(PriceSeries.Kind.BAZAAR_DAILY))
    }

    @Test
    fun `both markets serve the day and the week`() {
        for (range in listOf(BazaarHistory.Range.DAY, BazaarHistory.Range.WEEK)) {
            assertTrue(range.availableOn(PriceSeries.Kind.BAZAAR), "${range.label} on bazaar")
            assertTrue(range.availableOn(PriceSeries.Kind.AUCTION), "${range.label} at auction")
        }
    }

    @Test
    fun `every range is offered by at least one market`() {
        // A window neither market serves would be a permanently dead button.
        for (range in BazaarHistory.Range.entries) {
            assertTrue(
                range.onBazaar || range.onAuction,
                "${range.label} is offered by neither market",
            )
        }
    }

    @Test
    fun `the hour maps to no auction window rather than silently to the day`() {
        // The old behaviour served the hour with the day's data, leaving the 1h button lit above
        // a chart showing something else. Null is what lets the screen say so instead.
        assertNull(AuctionHistory.Range.forChartRange(BazaarHistory.Range.HOUR))
    }

    @Test
    fun `the other windows map to their auction counterpart`() {
        assertEquals(AuctionHistory.Range.DAY, AuctionHistory.Range.forChartRange(BazaarHistory.Range.DAY))
        assertEquals(AuctionHistory.Range.WEEK, AuctionHistory.Range.forChartRange(BazaarHistory.Range.WEEK))
        assertEquals(AuctionHistory.Range.MONTH, AuctionHistory.Range.forChartRange(BazaarHistory.Range.MONTH))
    }

    @Test
    fun `labels are distinct so no two buttons read the same`() {
        val labels = BazaarHistory.Range.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate range labels: $labels")
    }
}
