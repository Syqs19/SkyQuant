package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.feature.bazaar.data.ForgeJobPricing
import dev.syqs.skyquant.feature.bazaar.data.ForgeLedger
import dev.syqs.skyquant.feature.bazaar.data.ForgeState
import dev.syqs.skyquant.util.stripFormatting
import net.minecraft.client.Minecraft

/**
 * Keeps the last readable forge state, read from Hypixel's `Forges:` tab list widget.
 *
 * Read on demand rather than ticked: the widget only changes as slots finish, and the Status page
 * is the only thing that asks. A tick would re-parse a hundred tab list entries every frame to
 * answer a question nobody was asking.
 */
object ForgeTracker {

    /**
     * The forge as the tab list currently describes it, or null when it can't be read.
     *
     * Null is *unknown*, never "nothing forging" - the widget is configured per island and can be
     * switched off entirely, in which case the section disappears from the tab list rather than
     * appearing empty. The two are indistinguishable from the outside, which is exactly why this
     * must not resolve them into a cheerful "idle".
     */
    /**
     * The forge as the tab list currently describes it, cached for a moment.
     *
     * Sorting and mapping a hundred tab list entries and parsing the result is not free, and the
     * Status page asks twice per frame - once to record and once to draw. The widget changes as
     * slots finish, which is minutes apart, so a fraction of a second of staleness costs nothing
     * and saves ~120 parses a second while the tab is open.
     */
    val state: ForgeState?
        get() {
            val now = System.currentTimeMillis()
            if (now - cachedAt < CACHE_MILLIS) return cached

            cached = ForgeState.parse(tabListLines())
            cachedAt = now
            return cached
        }

    private var cached: ForgeState? = null
    private var cachedAt = 0L

    /** Long enough to collapse a frame's repeated reads, short enough to feel live. */
    private const val CACHE_MILLIS = 500L

    /**
     * Writes what the widget currently shows into the ledger, if it can be read at all.
     *
     * Called before the Status page reads, so a job appearing for the first time has its
     * ingredient cost captured at the prices of that moment. Doing it later - when the job
     * finishes, say - would price the ingredients at whatever they cost then, which answers a
     * different question and makes the profit line move on its own.
     *
     * Does nothing when the widget isn't visible, which is what lets the remembered jobs survive
     * a trip to another island rather than being overwritten with an empty reading.
     */
    fun recordIfReadable() {
        val current = state ?: return

        ForgeLedger.record(
            state = current,
            profile = profileName(),
            now = System.currentTimeMillis(),
            costOf = { ForgeJobPricing.ingredientCost(it) },
            durationOf = { ForgeJobPricing.durationMillis(it) },
        )
    }

    /**
     * The SkyBlock profile the player is on, from the tab list's `Profile:` line.
     *
     * Used to drop remembered jobs when the player switches profile: the forge is per profile, so
     * showing one profile's jobs on another would be a confidently wrong answer. Null when the
     * line isn't there, which leaves the existing jobs alone rather than clearing them on a
     * reading that simply failed.
     */
    private fun profileName(): String? = tabListLines()
        .map { it.stripFormatting().trim() }
        .firstOrNull { it.startsWith(PROFILE_PREFIX) }
        ?.removePrefix(PROFILE_PREFIX)
        ?.trim()

    private const val PROFILE_PREFIX = "Profile:"

    /**
     * The tab list as plain strings, in the order the game itself lists them.
     *
     * Sorted the way the vanilla overlay sorts, since Hypixel lays its widgets out as runs of
     * consecutive entries - in any other order the `Forges:` header and its slots would be
     * scattered and the parser would find a header with nothing under it.
     */
    private fun tabListLines(): List<String> {
        val connection = Minecraft.getInstance().connection ?: return emptyList()

        return connection.listedOnlinePlayers
            .sortedWith(compareBy({ it.tabListOrder }, { it.profile.name }))
            .mapNotNull { it.tabListDisplayName?.string }
    }
}
