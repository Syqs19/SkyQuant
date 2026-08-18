package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.util.JsonFile

/**
 * Remembers what each forge slot is making, what it cost to start, and when it finishes.
 *
 * Two problems, one store. The tab list widget only exists on the forge island, so walking away
 * loses sight of jobs that are still running - and the widget never says what a job cost, so
 * without recording it at the moment the slot starts, the figure is gone for good.
 *
 * The ingredient cost is captured **once, when the job first appears**, at that moment's prices.
 * Re-pricing it later would answer a different question: it would say what the ingredients are
 * worth now, not what committing them cost - and the profit line would then move for reasons that
 * have nothing to do with the job.
 */
object ForgeLedger {

    /** One slot's job, as it was when it started. */
    data class Job(
        val slot: Int,
        val item: String,
        /** Ingredient cost at the moment the job was first seen, or null if nothing priced it. */
        val cost: Long?,
        /** Wall-clock millis when the job is expected to finish. */
        val finishesAt: Long,
        /** How long the job runs in total, for drawing progress. Null when the recipe is unknown. */
        val totalMillis: Long?,
    )

    private class Stored {
        var jobs: MutableList<Job> = mutableListOf()

        /**
         * Which profile these jobs belong to, so another profile's forge is never shown.
         *
         * A player switching profile keeps the same account, and jobs are per profile - showing
         * one profile's forge on another would be a confidently wrong answer rather than a
         * missing one.
         */
        var profile: String? = null
    }

    private val file = JsonFile.of("forge_jobs", { Stored() })

    private val stored: Stored by lazy { file.load() }

    /** Jobs still running or waiting to be collected, newest reading first. */
    val jobs: List<Job> get() = stored.jobs.sortedBy { it.slot }

    /**
     * Records what the tab list currently shows, keeping costs already captured.
     *
     * Called only while the widget is readable. A slot that has gone quiet - the job collected,
     * or the slot emptied - is dropped, since the ledger is about what is in progress rather than
     * a history of what was made.
     */
    fun record(
        state: ForgeState,
        profile: String?,
        now: Long,
        costOf: (String) -> Long?,
        durationOf: (String) -> Long?,
    ) {
        // A profile change invalidates every job at once: they belong to the forge of a save the
        // player is no longer on.
        var changed = false
        if (profile != null && profile != stored.profile) {
            stored.jobs.clear()
            stored.profile = profile
            // Tracked separately from the job list: a player switching to a profile whose forge
            // happens to hold the same items would otherwise leave the new profile name unsaved,
            // and the next session would clear the jobs all over again.
            changed = true
        }

        val existing = stored.jobs.associateBy { it.slot }
        val updated = mutableListOf<Job>()

        for ((index, slot) in state.slots.withIndex()) {
            val number = index + 1
            val item = when (slot) {
                is ForgeSlot.Busy -> slot.item
                is ForgeSlot.Ready -> slot.item
                else -> continue
            }

            val previous = existing[number]?.takeIf { it.item == item }
            if (previous != null) {
                // Same job as last time: keep the cost and the finish time already worked out.
                // Re-deriving the finish time every read would let it drift by a minute each pass,
                // since the widget rounds down.
                updated += previous
                continue
            }

            val total = durationOf(item)
            val finishesAt = when (slot) {
                // Something already finished ends now; there is nothing left to wait for.
                is ForgeSlot.Ready -> now
                is ForgeSlot.Busy -> now + (parseRemaining(slot.remaining) ?: 0L)
                else -> now
            }
            updated += Job(number, item, costOf(item), finishesAt, total)
        }

        // Written only when the jobs actually changed. This is called from the Status page's draw,
        // so an unconditional save serialised the ledger and replaced the file on disk **sixty
        // times a second** for as long as the tab was open - pointless wear on an SSD, and a
        // visible stall on a config folder that lives on a network share.
        if (changed || updated != stored.jobs) {
            stored.jobs = updated
            file.save(stored)
        }
    }

    /** Forgets everything, for a profile switch or a player who wants a clean slate. */
    fun clear() {
        stored.jobs.clear()
        file.save(stored)
    }

    /**
     * Turns Hypixel's wording into milliseconds: "1h 25m", "16m", "29h".
     *
     * Only used to work out *when* a job ends, once, at the moment it is first seen. The widget
     * rounds down to the minute, so this is accurate to about a minute - which is why the page
     * shows a job as ready rather than counting down to a precise second.
     *
     * Returns null for anything unrecognised, so an unexpected wording leaves the job without a
     * usable finish time rather than inventing one.
     */
    fun parseRemaining(text: String): Long? {
        val matches = DURATION_PART.findAll(text).toList()
        if (matches.isEmpty()) return null

        var total = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            total += when (match.groupValues[2]) {
                "d" -> amount * 24 * 60 * 60_000L
                "h" -> amount * 60 * 60_000L
                "m" -> amount * 60_000L
                "s" -> amount * 1_000L
                else -> return null
            }
        }
        return total
    }

    /**
     * `29h`, `1h 25m`, `16m`.
     *
     * Days are included although the widget was confirmed in game never to use them - it reports
     * `29h` rather than `1d 5h`. Accepting `d` costs nothing and means a future change in Hypixel's
     * wording degrades into a correct reading rather than a null one.
     */
    private val DURATION_PART = Regex("""(\d+)\s*([dhms])""")
}
