package dev.syqs.skyquant.feature.bazaar.data

/**
 * Builds the Status page's rows out of whatever the game is currently telling us.
 *
 * Separate from the screen so it can be tested without a running Minecraft, which is the reason
 * the rest of this package is shaped the way it is.
 */
object WorkingCapitalSummary {

    /**
     * A price and how much the market behind it can be trusted.
     *
     * The two travel together on purpose. Looking the price up in one place and its liquidity in
     * another leaves room for a screen to show a figure without its warning, which is exactly the
     * failure this was built to fix.
     */
    data class Priced(val value: Long, val thin: Boolean = false)

    /** Sources the page lists, in the order they appear. Planned ones are listed too - see below. */
    private val PLANNED = listOf(
        "minion" to "Minions",
        "auctions" to "Auctions",
        "kat" to "Kat",
        "fann" to "Fann",
    )

    /**
     * The whole page.
     *
     * [forge] is passed in rather than read from a singleton so this can be exercised directly;
     * null means the tab list widget wasn't readable, which is *unknown* and never "empty".
     *
     * The sources that aren't built yet are still returned, marked [WorkingGroup.State.PLANNED].
     * Listing them is the honest option: a player whose minions are working would otherwise read
     * a total that silently excludes them and looks complete.
     */
    fun build(
        forge: ForgeState?,
        priceOf: (String) -> Priced? = { null },
        remembered: List<ForgeLedger.Job> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): WorkingCapital {
        val groups = mutableListOf(forgeGroup(forge, priceOf, remembered, now))
        PLANNED.mapTo(groups) { (id, label) ->
            WorkingGroup(id, label, WorkingGroup.State.PLANNED)
        }
        return WorkingCapital(groups)
    }

    /**
     * The forge, from the widget when it is readable and from the ledger when it isn't.
     *
     * The widget only exists on the forge island, so walking away would otherwise blank a forge
     * that is still running. The remembered jobs cover that: the items can't change while they
     * cook, so the only thing that has to be recomputed is the wait, which comes off the clock.
     */
    private fun forgeGroup(
        forge: ForgeState?,
        priceOf: (String) -> Priced?,
        remembered: List<ForgeLedger.Job>,
        now: Long,
    ): WorkingGroup {
        if (forge != null) return liveForge(forge, priceOf, remembered, now)
        if (remembered.isEmpty()) return WorkingGroup("forge", "Forge", WorkingGroup.State.UNKNOWN)

        val items = remembered.map { job ->
            // Looked up once: two calls could disagree if a snapshot landed between them, and
            // the whole point of Priced is that the figure and its warning stay together.
            val priced = priceOf(job.item)
            WorkingItem(
                name = job.item,
                value = priced?.value,
                thin = priced?.thin ?: false,
                remaining = countdown(job.finishesAt - now),
                cost = job.cost,
                progress = progressOf(job, now),
            )
        }
        // REMEMBERED rather than READ: every figure here is derived from a reading taken earlier,
        // and the page says so. A job collected while the player was away still shows until the
        // widget is seen again, which is a claim worth marking as second-hand.
        return WorkingGroup("forge", "Forge", WorkingGroup.State.REMEMBERED, items)
    }

    private fun liveForge(
        forge: ForgeState,
        priceOf: (String) -> Priced?,
        remembered: List<ForgeLedger.Job>,
        now: Long,
    ): WorkingGroup {
        val bySlot = remembered.associateBy { it.slot }

        val items = forge.slots.mapIndexedNotNull { index, slot ->
            val job = bySlot[index + 1]
            val priced = when (slot) {
                is ForgeSlot.Busy -> priceOf(slot.item)
                is ForgeSlot.Ready -> priceOf(slot.item)
                else -> null
            }
            when (slot) {
                is ForgeSlot.Busy -> WorkingItem(
                    name = slot.item,
                    value = priced?.value,
                    thin = priced?.thin ?: false,
                    remaining = slot.remaining,
                    cost = job?.takeIf { it.item == slot.item }?.cost,
                    progress = job?.takeIf { it.item == slot.item }?.let { progressOf(it, now) },
                )

                is ForgeSlot.Ready -> WorkingItem(
                    name = slot.item,
                    value = priced?.value,
                    thin = priced?.thin ?: false,
                    remaining = READY,
                    cost = job?.takeIf { it.item == slot.item }?.cost,
                    progress = 1.0,
                )

                // Listed rather than dropped, so idle capacity is visible: an empty slot is a
                // forge earning nothing, which is exactly what the page is for. Marked idle so it
                // is never counted as active or priced.
                is ForgeSlot.Empty -> WorkingItem("Empty", null, null, idle = true)
                is ForgeSlot.Locked -> WorkingItem("Locked", null, null, idle = true)

                // An unrecognised line says so rather than being guessed into a state.
                is ForgeSlot.Unknown -> WorkingItem(slot.text, null, null, idle = true)
            }
        }
        return WorkingGroup("forge", "Forge", WorkingGroup.State.READ, items)
    }

    /** How far through the job, or null when the recipe's length isn't known. */
    private fun progressOf(job: ForgeLedger.Job, now: Long): Double? {
        val total = job.totalMillis?.takeIf { it > 0 } ?: return null
        val elapsed = total - (job.finishesAt - now)
        return (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * The wait as text, worked out from the clock rather than read off the widget.
     *
     * Deliberately coarse - hours and minutes, no seconds - to match how Hypixel writes it. A
     * ticking second counter would imply a precision the original reading never had, since the
     * widget rounds down to the minute.
     */
    fun countdown(millis: Long): String {
        if (millis <= 0) return READY

        val minutes = millis / 60_000
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m"
            // Under a minute is still a wait, and "0m" reads as finished.
            else -> "<1m"
        }
    }

    /**
     * Which row to open when the page is first shown.
     *
     * The one with something finished, if anything is - that is the row the player can act on
     * without waiting. Otherwise the first row that has anything in it at all. Opening everything
     * would defeat the summary; opening nothing would make the page a list of numbers to click.
     */
    fun rowToExpand(capital: WorkingCapital): String? =
        capital.active.firstOrNull { group -> group.items.any { it.remaining == READY } }?.id
            ?: capital.active.firstOrNull()?.id

    /** Hypixel's own wording for a finished forge, kept verbatim so the page never re-phrases it. */
    const val READY = "Ready!"
}
