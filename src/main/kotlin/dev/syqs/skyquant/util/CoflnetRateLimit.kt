package dev.syqs.skyquant.util

import java.util.ArrayDeque

/**
 * Paces every request the mod makes to Coflnet, so four callers sharing one IP stay inside one
 * budget.
 *
 * Coflnet publishes **30 requests per 10 seconds and 100 per minute, per IP**, and the two windows
 * apply at the same time: a burst of 31 inside ten seconds is refused even with the per-minute
 * count nowhere near 100. Both are tracked here because either one can be the binding constraint -
 * a steady trickle hits the minute window, a page opening hits the ten-second one.
 *
 * **Why a cap on concurrent requests was not enough.** [AuctionSellPrice] already limited itself to
 * four in flight, which sounds like pacing but isn't: four requests each completing in 100ms is
 * forty requests in ten seconds, over the limit, and the faster the player's connection the worse
 * it gets. Concurrency bounds how many are open at once; only a count over time bounds the rate.
 *
 * The stake is higher than a failed request. An IP that accumulates 500 rate-limit violations is
 * blocked automatically, so getting this wrong costs the player access to Coflnet entirely - and
 * they would have no idea why their charts stopped working.
 *
 * Deliberately conservative against the published figures. The ceilings below leave room for the
 * requests already in flight when a window fills, and for a clock that isn't the server's.
 */
object CoflnetRateLimit {

    /**
     * Raised when a request is held back by our own pacing, before it reaches the network.
     *
     * Deliberately *not* [HttpJson.RateLimited], which means "the server refused us". This one
     * means "we chose to wait", and the two must not read the same to a player: the first is a
     * fault worth reporting, the second is the mod working correctly and clears itself within
     * seconds. Sharing one type put "Failed: HTTP 429" on a chart over a request that had never
     * been sent - naming a status code no server had returned.
     */
    class Deferred : RuntimeException("waiting for the request budget")

    /** Against a published 30. The margin absorbs requests already open when the window fills. */
    private const val MAX_PER_SHORT_WINDOW = 24
    private const val SHORT_WINDOW_MILLIS = 10_000L

    /** Against a published 100. */
    private const val MAX_PER_LONG_WINDOW = 80
    private const val LONG_WINDOW_MILLIS = 60_000L

    /** Timestamps of recent requests, oldest first. Bounded by [MAX_PER_LONG_WINDOW]. */
    private val recent = ArrayDeque<Long>()

    /**
     * When a 429 told us to wait, the moment we may resume. Zero when not paused.
     *
     * A server-sent `Retry-After` outranks our own counting: it is the only figure that reflects
     * what the server has actually seen, including requests from anything else sharing the IP.
     */
    @Volatile
    private var pausedUntilMillis = 0L

    /**
     * Claims a slot for one request, returning false if there is no room right now.
     *
     * Callers must treat false as "try again later" rather than "this item has no data" - the two
     * are indistinguishable at the call site otherwise, and conflating them is what caches an
     * empty answer for a tradeable item.
     *
     * Synchronised because callers arrive from both the render thread and HTTP completion threads,
     * and the check and the record have to be one step: two threads reading "23 used" at once
     * would each conclude there was room.
     */
    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        if (now < pausedUntilMillis) return false

        // Only the long window needs pruning: it is the wider of the two, so anything outside it
        // is outside both, and the short window is counted within what remains.
        while (recent.isNotEmpty() && now - recent.peekFirst() >= LONG_WINDOW_MILLIS) {
            recent.pollFirst()
        }

        if (recent.size >= MAX_PER_LONG_WINDOW) return false

        val inShortWindow = recent.count { now - it < SHORT_WINDOW_MILLIS }
        if (inShortWindow >= MAX_PER_SHORT_WINDOW) return false

        recent.addLast(now)
        return true
    }

    /**
     * Records that the server refused a request, and holds every caller back until [retryAfterMillis]
     * has passed.
     *
     * Applied globally rather than per item: a 429 is a statement about the IP, so continuing to
     * ask about *other* items would be ignoring exactly what was said. Respecting `Retry-After` is
     * a condition of Coflnet's terms, not only good manners.
     *
     * The fallback matters as much as the header. With no `Retry-After` we know only that we were
     * over some limit, and the short window is the one a burst breaches, so waiting it out clears
     * the likely cause.
     */
    @Synchronized
    fun backOff(retryAfterMillis: Long?, now: Long = System.currentTimeMillis()) {
        val wait = retryAfterMillis ?: SHORT_WINDOW_MILLIS
        val until = now + wait

        // Never shortens an existing pause: two 429s arriving together must not have the second,
        // with a smaller Retry-After, cancel the first one's wait.
        if (until > pausedUntilMillis) pausedUntilMillis = until
    }

    /**
     * True while a 429 is still being waited out. Lets a screen say why figures aren't arriving.
     *
     * Takes the clock as a parameter for the same reason [tryAcquire] does: the tests drive time
     * explicitly, and a version reading `currentTimeMillis` directly can only be asserted against
     * a back-off set in the real present.
     */
    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = now < pausedUntilMillis

    /** Requests recorded in the last minute. Exists so the pacing can be asserted in tests. */
    @Synchronized
    fun recentCountForTesting(): Int = recent.size

    /** Clears all state, so one test's traffic doesn't pace the next one's. */
    @Synchronized
    fun resetForTesting() {
        recent.clear()
        pausedUntilMillis = 0
    }
}

/**
 * True when this failure is our own pacing holding a request back, rather than anything going
 * wrong.
 *
 * Unwraps `CompletionException` for the same reason [dev.syqs.skyquant.util.rateLimit] does: the
 * wrapper is added or not depending on where in the future chain the failure was raised.
 */
fun Throwable.deferred(): Boolean =
    this is CoflnetRateLimit.Deferred || cause is CoflnetRateLimit.Deferred
