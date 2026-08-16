package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the item catalogue is taken from disk rather than downloaded again.
 *
 * The endpoint sends 4.9 MB and **no etag**, so there is no cheap way to ask whether anything
 * changed - this interval is the entire policy, and getting it wrong either re-downloads 4.9 MB
 * every session or serves prices from a game patch ago.
 */
class NpcSellPricesTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a cache written moments ago is used as is`() {
        assertTrue(NpcSellPrices.isCacheFresh(fetchedAtMillis = 1_000, entryCount = 2400, now = 5_000))
    }

    @Test
    fun `a cache from within the day is still used`() {
        assertTrue(NpcSellPrices.isCacheFresh(fetchedAtMillis = 0, entryCount = 2400, now = day - 1))
    }

    @Test
    fun `a cache older than a day is refreshed`() {
        assertFalse(NpcSellPrices.isCacheFresh(fetchedAtMillis = 0, entryCount = 2400, now = day + 1))
    }

    /**
     * An empty cache is not a fresh one however recently it was written - otherwise a failed parse
     * saved moments ago would suppress the download that would fix it.
     */
    @Test
    fun `an empty cache is never fresh`() {
        assertFalse(NpcSellPrices.isCacheFresh(fetchedAtMillis = 1_000, entryCount = 0, now = 1_100))
    }

    /**
     * A timestamp in the future means the clock moved backwards - a machine correcting its time,
     * or a file copied from another. Treating it as fresh would pin the cache permanently, since
     * the age would stay negative and never reach the interval.
     */
    @Test
    fun `a future timestamp is treated as stale rather than fresh forever`() {
        assertFalse(
            NpcSellPrices.isCacheFresh(fetchedAtMillis = day * 10, entryCount = 2400, now = day),
        )
    }
}
