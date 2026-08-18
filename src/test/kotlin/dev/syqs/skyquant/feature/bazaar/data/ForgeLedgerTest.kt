package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Turning Hypixel's wording for a wait into a finish time.
 *
 * This is the part of the ledger that can be wrong without looking wrong: a duration parsed as
 * null or as the wrong unit still draws a plausible row, and the error only shows up as a job
 * that finishes at the wrong moment - hours later, on a different island.
 */
class ForgeLedgerTest {

    @Test
    fun `reads the wordings the widget was seen using`() {
        // All three observed in game: minutes alone, hours with minutes, and hours past a day.
        assertEquals(16 * 60_000L, ForgeLedger.parseRemaining("16m"))
        assertEquals(85 * 60_000L, ForgeLedger.parseRemaining("1h 25m"))
        assertEquals(29 * 60 * 60_000L, ForgeLedger.parseRemaining("29h"))
    }

    @Test
    fun `hours past a day stay hours rather than rolling over`() {
        // Confirmed in game: the widget writes 29h, never "1d 5h". Reading 29h as anything less
        // would show a job as ready while it still had five hours to run.
        val twentyNine = ForgeLedger.parseRemaining("29h")!!
        val oneDayFive = 24 * 60 * 60_000L + 5 * 60 * 60_000L

        assertEquals(oneDayFive, twentyNine)
    }

    @Test
    fun `accepts days even though the widget was never seen using them`() {
        // Cheap insurance: if Hypixel ever switches wording, this degrades to a correct reading
        // rather than to null, which would silently drop the job's finish time.
        assertEquals(24 * 60 * 60_000L + 60 * 60_000L, ForgeLedger.parseRemaining("1d 1h"))
    }

    @Test
    fun `an unrecognised wording gives no time rather than a made-up one`() {
        assertNull(ForgeLedger.parseRemaining("Ready!"))
        assertNull(ForgeLedger.parseRemaining("soon"))
        assertNull(ForgeLedger.parseRemaining(""))
    }

    @Test
    fun `an unchanged forge is not written to disk again`() {
        // Recorded from the Status page's draw, so an unconditional save rewrote the file sixty
        // times a second while the tab was open. The guard is that the job list is unchanged, and
        // this is what proves the comparison actually holds - Job is a data class precisely so
        // two readings of the same forge compare equal.
        val first = ForgeLedger.Job(1, "Tungsten Plate", 9_200_000L, 5_000_000L, 60_000L)
        val second = ForgeLedger.Job(1, "Tungsten Plate", 9_200_000L, 5_000_000L, 60_000L)

        assertEquals(first, second)
        assertEquals(listOf(first), listOf(second))
    }

    @Test
    fun `a job differing only in its finish time is a change`() {
        // The guard must not be so loose that a restarted slot goes unrecorded.
        val before = ForgeLedger.Job(1, "Tungsten Plate", 9_200_000L, 5_000_000L, 60_000L)
        val after = before.copy(finishesAt = 9_000_000L)

        assertNotEquals(before, after)
    }

    @Test
    fun `a wait with seconds is read to the second`() {
        // Short forge recipes run in well under a minute - the shortest in the repo is 30 seconds
        // - so the seconds unit is a real case rather than a defensive one.
        assertEquals(30_000L, ForgeLedger.parseRemaining("30s"))
        assertEquals(90_000L, ForgeLedger.parseRemaining("1m 30s"))
    }
}
