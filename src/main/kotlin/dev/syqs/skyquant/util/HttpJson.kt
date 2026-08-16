package dev.syqs.skyquant.util

import com.google.gson.Gson
import dev.syqs.skyquant.SkyQuantMod
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Fetches and parses JSON over HTTP, off the render thread.
 *
 * Uses the JDK's own client so the mod doesn't bundle an HTTP library: the volume here is a
 * handful of requests per minute against two public APIs, which needs nothing fancier.
 */
object HttpJson {

    private val gson = Gson()

    /**
     * The server asked us to slow down.
     *
     * A distinct type rather than a status code inside the generic failure because callers have to
     * tell it apart from "this item has nothing": both arrive as a failed request, and treating a
     * 429 as an empty answer caches "no listings" for a perfectly tradeable item - poisoning the
     * cache for as long as the failure backoff lasts, on the exact items the player was looking at.
     *
     * [retryAfterMillis] carries the server's own `Retry-After` when it sends one, which Coflnet's
     * terms require clients to respect. Null means it didn't say, and the caller should fall back
     * to its own pacing.
     */
    class RateLimited(val retryAfterMillis: Long?) : RuntimeException("rate limited")

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Requests [url] and parses the body as [T].
     *
     * The returned future completes on an HTTP worker thread, never on the render thread, so
     * whatever touches Minecraft afterwards has to hop back via `Minecraft.getInstance().execute`.
     * Failures (network down, non-2xx, malformed JSON) complete exceptionally rather than
     * returning null, so callers can tell "no data yet" apart from "this request failed".
     */
    fun <T> get(url: String, type: Class<T>): CompletableFuture<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "${SkyQuantMod.MOD_ID}/${SkyQuantMod.VERSION}")
            .header("Accept", "application/json")
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                if (response.statusCode() == TOO_MANY_REQUESTS) {
                    throw RateLimited(retryAfterMillis(response))
                }
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("HTTP ${response.statusCode()} for $url")
                }
                gson.fromJson(response.body(), type)
                    ?: throw IllegalStateException("Empty JSON body for $url")
            }
    }

    /**
     * The `Retry-After` header in milliseconds, or null if absent or unparseable.
     *
     * Only the delay-seconds form is read. The header may also carry an HTTP date, which is
     * allowed but not what Coflnet sends; a date would parse as null here and leave the caller on
     * its own pacing, which is the safe direction to be wrong in.
     */
    private fun retryAfterMillis(response: HttpResponse<*>): Long? =
        response.headers().firstValue("retry-after")
            .orElse(null)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.times(1000)

    private const val TOO_MANY_REQUESTS = 429
}

/**
 * The [HttpJson.RateLimited] behind a failure, or null if it was something else.
 *
 * Exists because `whenComplete` hands callers a `CompletionException` wrapping the real cause,
 * while a failure raised inside a `thenApply` chain may arrive unwrapped - so a plain
 * `as? RateLimited` catches one shape and silently misses the other. Every Coflnet caller needs
 * this test, and each writing its own is four chances to write the version that misses.
 */
fun Throwable.rateLimit(): HttpJson.RateLimited? =
    this as? HttpJson.RateLimited ?: cause as? HttpJson.RateLimited
