package dev.syqs.skyquant.util

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pacing that keeps the mod inside Coflnet's published limits.
 *
 * Worth testing directly because the failure is invisible in play and expensive when it lands: an
 * IP that accumulates 500 rate-limit violations is blocked outright, and the player would only see
 * charts that stopped working for no stated reason.
 *
 * Every case drives the clock explicitly rather than sleeping - the windows are ten and sixty
 * seconds, and a test suite that waited them out would take minutes to say what these say at once.
 */
class CoflnetRateLimitTest {

    @BeforeTest
    fun clear() = CoflnetRateLimit.resetForTesting()

    @AfterTest
    fun clearAfter() = CoflnetRateLimit.resetForTesting()

    @Test
    fun `allows a burst up to the ten-second ceiling`() {
        repeat(24) { index ->
            assertTrue(CoflnetRateLimit.tryAcquire(now = 1000L), "request ${index + 1} should pass")
        }
    }

    @Test
    fun `refuses once the ten-second window is full`() {
        repeat(24) { CoflnetRateLimit.tryAcquire(now = 1000L) }

        assertFalse(
            CoflnetRateLimit.tryAcquire(now = 1000L),
            "the 25th request in the same instant must be refused",
        )
    }

    /**
     * The case the in-flight cap could not catch, and the reason this class exists: four
     * concurrent requests completing in 100ms each is forty requests in ten seconds. Spreading
     * them out must not make them acceptable while they are still inside the same window.
     */
    @Test
    fun `refuses a fast trickle that stays inside the ten-second window`() {
        var now = 0L
        var allowed = 0

        // Forty attempts at 100ms apart - i.e. four in flight, each taking 100ms - spanning 4s.
        repeat(40) {
            if (CoflnetRateLimit.tryAcquire(now)) allowed++
            now += 100
        }

        assertEquals(24, allowed, "no more than the short-window ceiling may get through")
    }

    @Test
    fun `lets the window slide so traffic resumes`() {
        repeat(24) { CoflnetRateLimit.tryAcquire(now = 1000L) }
        assertFalse(CoflnetRateLimit.tryAcquire(now = 1000L))

        // 10s after the burst, those requests no longer count against the short window.
        assertTrue(
            CoflnetRateLimit.tryAcquire(now = 11_001L),
            "requests older than ten seconds must stop blocking new ones",
        )
    }

    /**
     * Both windows apply at once, so obeying the short one is not enough on its own: a steady
     * pace that never breaches ten seconds can still exceed the minute.
     */
    @Test
    fun `enforces the per-minute ceiling across several short windows`() {
        var now = 0L
        var allowed = 0

        // One request every 200ms for a full minute: only 5 per second, so the short window is
        // never breached, but 300 attempts is far past the minute's ceiling.
        repeat(300) {
            if (CoflnetRateLimit.tryAcquire(now)) allowed++
            now += 200
        }

        assertEquals(80, allowed, "the per-minute ceiling must bind even when bursts do not")
    }

    @Test
    fun `honours a server-sent Retry-After`() {
        CoflnetRateLimit.backOff(retryAfterMillis = 5_000, now = 1_000L)

        assertFalse(CoflnetRateLimit.tryAcquire(now = 4_000L), "still inside the requested wait")
        assertTrue(CoflnetRateLimit.tryAcquire(now = 6_001L), "the wait has passed")
    }

    @Test
    fun `falls back to the short window when no Retry-After is sent`() {
        CoflnetRateLimit.backOff(retryAfterMillis = null, now = 0L)

        assertFalse(CoflnetRateLimit.tryAcquire(now = 9_000L))
        assertTrue(CoflnetRateLimit.tryAcquire(now = 10_001L))
    }

    /**
     * Two 429s can arrive together from requests that were already in flight. The second, with a
     * shorter delay, must not cut the first one's wait short - which is what a plain assignment
     * would do.
     */
    @Test
    fun `a shorter back-off never shortens a longer one already in force`() {
        CoflnetRateLimit.backOff(retryAfterMillis = 30_000, now = 0L)
        CoflnetRateLimit.backOff(retryAfterMillis = 1_000, now = 0L)

        assertFalse(
            CoflnetRateLimit.tryAcquire(now = 5_000L),
            "the longer wait must survive a later, shorter one",
        )
        assertTrue(CoflnetRateLimit.tryAcquire(now = 30_001L))
    }

    @Test
    fun `a back-off blocks every caller, not just the one that was refused`() {
        // The point of applying it globally: a 429 describes the IP, so asking about a different
        // item would ignore exactly what the server said.
        CoflnetRateLimit.backOff(retryAfterMillis = 5_000, now = 0L)

        assertFalse(CoflnetRateLimit.tryAcquire(now = 1_000L))
        assertTrue(CoflnetRateLimit.isPaused(now = 1_000L))
        assertFalse(CoflnetRateLimit.isPaused(now = 6_000L), "and stops being paused afterwards")
    }

    /**
     * The two must never be confused. A [CoflnetRateLimit.Deferred] is our own pacing declining to
     * send; an [HttpJson.RateLimited] is Coflnet refusing one we did send. Sharing a type put
     * "Failed: HTTP 429" on a chart over a request that had never left the machine, naming a
     * status code no server had returned.
     */
    @Test
    fun `a deferred request is not a server rate limit`() {
        val deferred: Throwable = CoflnetRateLimit.Deferred()

        assertTrue(deferred.deferred(), "our own pacing should report as deferred")
        assertEquals(null, deferred.rateLimit(), "and must not look like a server 429")
    }

    @Test
    fun `a server rate limit is not a deferral`() {
        val refused: Throwable = HttpJson.RateLimited(retryAfterMillis = 5_000)

        assertFalse(refused.deferred(), "a server refusal is not our own pacing")
        assertEquals(5_000, refused.rateLimit()?.retryAfterMillis)
    }

    /**
     * Futures completed exceptionally wrap the cause in a CompletionException, so both helpers
     * have to see through one layer - the bug this guards against is a deferral arriving wrapped
     * and being reported to the player as a failure.
     */
    @Test
    fun `both are recognised through a CompletionException wrapper`() {
        val wrappedDeferral: Throwable =
            java.util.concurrent.CompletionException(CoflnetRateLimit.Deferred())
        val wrappedRefusal: Throwable =
            java.util.concurrent.CompletionException(HttpJson.RateLimited(1_000))

        assertTrue(wrappedDeferral.deferred())
        assertFalse(wrappedDeferral.rateLimit() != null)

        assertFalse(wrappedRefusal.deferred())
        assertEquals(1_000, wrappedRefusal.rateLimit()?.retryAfterMillis)
    }

    @Test
    fun `an ordinary failure is neither`() {
        val other: Throwable = IllegalStateException("HTTP 500")

        assertFalse(other.deferred())
        assertEquals(null, other.rateLimit())
    }

    @Test
    fun `does not record refused requests against the budget`() {
        repeat(30) { CoflnetRateLimit.tryAcquire(now = 1000L) }

        // 24 allowed, 6 refused - the refusals never reached Coflnet, so counting them would make
        // the limiter throttle itself harder the more it throttles.
        assertEquals(24, CoflnetRateLimit.recentCountForTesting())
    }
}
