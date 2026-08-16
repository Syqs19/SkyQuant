package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.annotations.SerializedName
import dev.syqs.skyquant.util.CoflnetRateLimit
import dev.syqs.skyquant.util.HttpJson
import dev.syqs.skyquant.util.rateLimit
import java.util.concurrent.CompletableFuture

/**
 * Historical auction prices from Coflnet, for the items the bazaar doesn't trade.
 *
 * The bazaar covers materials; weapons, armour and forge outputs are sold at auction instead,
 * and until now the price graph simply did nothing on them. This is the same source as
 * [BazaarHistory] but a different endpoint, and it reports a different *kind* of figure:
 * completed sales grouped by hour, rather than the two live sides of an order book.
 *
 * That difference matters when reading a chart drawn from it. There is no spread, because there
 * are no standing orders - only what people actually paid. And the points are far sparser: a
 * heavily traded bazaar item has one a minute, while Divan's Drill sold three times in a day.
 */
object AuctionHistory {

    private const val BASE_URL = "https://sky.coflnet.com/api/item/price"

    /**
     * How far back a chart looks.
     *
     * [BazaarHistory.Range.HOUR] has no counterpart here: the endpoint answers 404 for it, which
     * is honest rather than broken - auctions don't complete often enough for an hour of them to
     * be a series. [MONTH] goes the other way and has no bazaar counterpart.
     *
     * There is also a `full` window, back to 2021. It is deliberately not offered: its newest
     * point was days old when measured, so it would draw a chart that looks current and isn't.
     */
    enum class Range(val path: String, val label: String) {
        DAY("day", "1d"),
        WEEK("week", "7d"),
        MONTH("month", "30d"),
        ;

        companion object {
            /**
             * The auction window to serve a chart request with, or null when there is none.
             *
             * Null is the honest answer for the hour: previously it was quietly served the day's
             * data, so the `1h` button stayed lit while showing something else entirely.
             */
            fun forChartRange(range: BazaarHistory.Range): Range? = when (range) {
                BazaarHistory.Range.HOUR -> null
                BazaarHistory.Range.DAY -> DAY
                BazaarHistory.Range.WEEK -> WEEK
                BazaarHistory.Range.MONTH -> MONTH
            }
        }
    }

    /**
     * One hour of completed sales.
     *
     * [low] and [high] bracket what the item went for; [average] is the middle of them weighted
     * by nothing in particular - Coflnet's own mean. [volume] is how many actually sold, which
     * is what says whether the other three mean anything.
     */
    data class Point(
        val timestamp: Long,
        val low: Double,
        val high: Double,
        val average: Double,
        val volume: Long,
    )

    private class RawPoint {
        /**
         * Named `time` here, where the bazaar endpoint calls the same thing `timestamp`. Reusing
         * the bazaar's class would have parsed every point as having no time at all, and a
         * series with no timestamps draws as nothing.
         */
        @SerializedName("time")
        var time: String? = null

        @SerializedName("min")
        var min: Double = 0.0

        @SerializedName("max")
        var max: Double = 0.0

        @SerializedName("avg")
        var avg: Double = 0.0

        @SerializedName("volume")
        var volume: Long = 0
    }

    /**
     * Fetches [range] of auction history for [itemId], oldest point first.
     *
     * Points with no usable time or no price are dropped, as in [BazaarHistory]: a gap left in
     * would be drawn as a plunge to zero.
     */
    fun fetch(itemId: String, range: Range): CompletableFuture<List<Point>> {
        val url = "$BASE_URL/${itemId.uppercase()}/history/${range.path}"

        // Shares the budget with every other Coflnet caller - see [BazaarHistory.fetch], which
        // does the same for the same reason.
        if (!CoflnetRateLimit.tryAcquire()) {
            return CompletableFuture.failedFuture(CoflnetRateLimit.Deferred())
        }

        return HttpJson.get(url, Array<RawPoint>::class.java)
            .whenComplete { _, error ->
                error?.rateLimit()?.let { CoflnetRateLimit.backOff(it.retryAfterMillis) }
            }
            .thenApply { raw ->
                raw.mapNotNull { point ->
                    // Shared with the bazaar parser: Coflnet sends timestamps with no zone
                    // designator, which Instant.parse rejects outright.
                    val millis = BazaarHistory.parseTimestamp(point.time) ?: return@mapNotNull null
                    if (point.avg <= 0.0 && point.min <= 0.0) return@mapNotNull null

                    Point(
                        timestamp = millis,
                        low = point.min,
                        high = point.max,
                        average = point.avg,
                        volume = point.volume,
                    )
                }.sortedBy { it.timestamp }
            }
    }
}
