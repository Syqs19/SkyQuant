package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.HttpJson
import dev.syqs.skyquant.util.rateLimit
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

/**
 * Historical bazaar prices from Coflnet, which has been recording them since shortly after the
 * bazaar API launched. Hypixel itself only exposes the current snapshot, so past prices have to
 * come from a third party that stores them.
 */
object BazaarHistory {

    private const val BASE_URL = "https://sky.coflnet.com/api/bazaar"

    /**
     * How far back a chart looks, and how coarse the returned points are.
     *
     * [sampleIntervalMillis] is how often Coflnet records at that range - used to tell whether
     * its newest point is recent enough, or whether the live price should extend the series.
     *
     * The two markets do not offer the same windows, and the difference is not an oversight on
     * Coflnet's part - it follows from what each records. The bazaar is sampled continuously, so
     * an hour of it is a real series; auctions complete a handful of times a day, so an hour is
     * usually nothing at all and the endpoint answers 404. That asymmetry is why the flags below
     * are per-market rather than one "supported" boolean. Measured against the live API rather
     * than assumed:
     *
     *   /api/bazaar/{id}/history/:      hour 200, day 200, week 200, month 404, full 404
     *   /api/item/price/{id}/history/:  hour 404, day 200, week 200, month 200, full 200 (stale)
     *
     * Note the second row applies to bazaar products as well - the item-price endpoint is not
     * auction-only. That is what lets the month window exist on both markets despite the bazaar
     * endpoint's 404, and [MONTH] documents the trade-off it carries.
     */
    enum class Range(
        val path: String,
        val label: String,
        val sampleIntervalMillis: Long,
        /** Whether Coflnet's bazaar endpoint serves this window. */
        val onBazaar: Boolean = true,
        /** Whether its auction endpoint does. */
        val onAuction: Boolean = true,
    ) {
        HOUR("hour", "1h", 60_000, onAuction = false),
        DAY("day", "1d", 5 * 60_000),
        WEEK("week", "7d", 2 * 60 * 60_000),

        /**
         * One point per day rather than per hour, on both markets.
         *
         * Coflnet's *bazaar* endpoint has no month window - it answers 404 - but its item-price
         * endpoint does, and it serves bazaar products too: asked for ENCHANTED_DIAMOND it
         * returns 1250-1380, against a live quote of 1289-1343. So a month of bazaar history is
         * fetched through [AuctionHistory] regardless of which market the item trades on.
         *
         * The cost is that those points carry min/max/avg instead of buy/sell, so the month view
         * draws one curve where the shorter windows draw two. That suits the window: a spread is
         * a decision about right now, and nobody reads thirty days to decide whether to place an
         * order this minute.
         */
        MONTH("month", "30d", 24 * 60 * 60_000),
        ;

        /**
         * [PriceSeries.Kind.BAZAAR_DAILY] counts as the bazaar here: it is what the month window
         * produces *for* a bazaar item, so the buttons must keep offering the bazaar's windows
         * while it is on screen - otherwise picking 30d would grey out the 1h you came from.
         */
        fun availableOn(kind: PriceSeries.Kind): Boolean =
            if (kind == PriceSeries.Kind.AUCTION) onAuction else onBazaar

        /**
         * Why this window isn't offered for [kind], or null when it is.
         *
         * Lives here rather than in the screen because this enum is what already knows the answer.
         * The screen printed one fixed sentence - "No hourly data for auctions" - for whichever
         * button was greyed out, which is right for the hour at auction and wrong for anything
         * else the flags can rule out. A hint that explains the wrong thing is worse than none:
         * it invites the reader to trust it.
         */
        fun unavailableReason(kind: PriceSeries.Kind): String? = when {
            availableOn(kind) -> null
            kind == PriceSeries.Kind.AUCTION ->
                "Auctions don't sell often enough for an hourly chart"
            else -> "No $label of bazaar history is recorded"
        }
    }

    /** One recorded moment. Prices mirror the live API: [buy] is what you'd pay. */
    data class Point(
        val timestamp: Long,
        val buy: Double,
        val sell: Double,
        val buyVolume: Long,
        val sellVolume: Long,
    )

    private class RawPoint {
        @SerializedName("timestamp")
        var timestamp: String? = null

        @SerializedName("buy")
        var buy: Double = 0.0

        @SerializedName("sell")
        var sell: Double = 0.0

        @SerializedName("buyVolume")
        var buyVolume: Long = 0

        @SerializedName("sellVolume")
        var sellVolume: Long = 0
    }

    /**
     * Fetches [range] of history for [productId], oldest point first.
     *
     * Points missing a usable timestamp or with no price on either side are dropped: a gap in
     * the series would otherwise be drawn as a plunge to zero.
     */
    fun fetch(productId: String, range: Range): CompletableFuture<List<Point>> {
        val url = "$BASE_URL/${productId.uppercase()}/history/${range.path}"

        // One chart view is one request, so this rarely finds the budget spent - but it shares an
        // IP with the Craft and Forge pages, which can be mid-sweep when a chart is opened. Failing
        // here surfaces as "couldn't load", which is honest and recoverable by reopening; slipping
        // past the limit instead spends one of the 500 violations that get an IP blocked outright.
        if (!CoflnetRateLimit.tryAcquire()) {
            return CompletableFuture.failedFuture(CoflnetRateLimit.Deferred())
        }

        return HttpJson.get(url, Array<RawPoint>::class.java)
            .whenComplete { _, error ->
                error?.rateLimit()?.let { CoflnetRateLimit.backOff(it.retryAfterMillis) }
            }
            .thenApply { raw ->
                raw.mapNotNull { point ->
                    val millis = parseTimestamp(point.timestamp) ?: return@mapNotNull null
                    if (point.buy <= 0.0 && point.sell <= 0.0) return@mapNotNull null

                    Point(millis, point.buy, point.sell, point.buyVolume, point.sellVolume)
                }.sortedBy { it.timestamp }
            }
    }

    /**
     * Coflnet sends timestamps with no zone designator ("2026-08-14T18:40:21.956"), which
     * [Instant.parse] rejects outright - parsed as UTC via [LocalDateTime] instead, with
     * [Instant] kept as a fallback in case the format ever gains an offset.
     */
    internal fun parseTimestamp(text: String?): Long? {
        if (text.isNullOrBlank()) return null

        runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .onSuccess { return it }

        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
