package dev.syqs.skyquant.feature.bazaar.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Timestamp parsing shipped broken once: `Instant.parse` rejected every value Coflnet sends, so
 * all points were dropped and the chart came up empty with no error anywhere. A silent failure
 * like that is the strongest argument for testing this at all.
 */
class BazaarHistoryTest {

    @Test
    fun `parses the zoneless format Coflnet actually sends`() {
        val millis = BazaarHistory.parseTimestamp("2026-08-14T18:40:21.956")

        assertEquals(Instant.parse("2026-08-14T18:40:21.956Z").toEpochMilli(), millis)
    }

    @Test
    fun `parses without fractional seconds`() {
        val millis = BazaarHistory.parseTimestamp("2026-08-14T18:40:21")

        assertEquals(Instant.parse("2026-08-14T18:40:21Z").toEpochMilli(), millis)
    }

    @Test
    fun `still parses a value that does carry an offset`() {
        // Kept as a fallback in case the API ever starts sending one; without it, a format
        // change would empty the chart again.
        val millis = BazaarHistory.parseTimestamp("2026-08-14T18:40:21Z")

        assertEquals(Instant.parse("2026-08-14T18:40:21Z").toEpochMilli(), millis)
    }

    @Test
    fun `returns null on unusable input rather than throwing`() {
        // A single bad point should drop that point, not take down the whole fetch.
        assertNull(BazaarHistory.parseTimestamp(null))
        assertNull(BazaarHistory.parseTimestamp(""))
        assertNull(BazaarHistory.parseTimestamp("   "))
        assertNull(BazaarHistory.parseTimestamp("yesterday"))
    }

    @Test
    fun `sample intervals match how often each range is recorded`() {
        // These drive when the live price is appended to the chart; if one were wrong the
        // curve would either stop short of now or grow a dense tail of duplicates.
        assertEquals(60_000, BazaarHistory.Range.HOUR.sampleIntervalMillis)
        assertEquals(5 * 60_000, BazaarHistory.Range.DAY.sampleIntervalMillis)
        assertEquals(2 * 60 * 60_000, BazaarHistory.Range.WEEK.sampleIntervalMillis)
    }
}
