package dev.syqs.skyquant.feature.bazaar.data

/**
 * What the player has tied up in something that finishes later, gathered in one place.
 *
 * The Status page answers "how much do I have in play, and when does the next thing come out".
 * Every source here is the same shape of thing: coins already spent, an item that becomes
 * available at a known time, and a value it will be worth. A forge slot, a Kat upgrade and a
 * listed auction differ in where the figure is read from, not in what it means.
 *
 * Deliberately **not** a place for everything the player might want to see. Daily quests,
 * commissions and powders were considered and left out: they are things to do, not money in
 * motion, and the tab list already shows them for free. The rule that keeps this page from
 * becoming a dashboard is that a row has to be capital that is currently maturing.
 */

/** One thing being worked on: a forge slot, a pet at Kat, a listed auction. */
data class WorkingItem(
    val name: String,
    /** What it is expected to be worth when it comes out, or null when nothing prices it. */
    val value: Long?,
    /** The wait, as text. Hypixel's own wording while readable, recomputed from the clock after. */
    val remaining: String?,
    /**
     * What the ingredients cost when the job started, or null when it wasn't priced.
     *
     * Captured once and never re-priced: it is what committing to this job cost, not what those
     * ingredients are worth today.
     */
    val cost: Long? = null,
    /** How far along, 0..1, or null when the recipe's total duration isn't known. */
    val progress: Double? = null,
    /** True for a slot that holds nothing - shown so idle capacity is visible, but never priced. */
    val idle: Boolean = false,
    /**
     * True when [value] comes from a market too thin for the price to mean much.
     *
     * Carried on the item rather than worked out by the screen so the warning travels with the
     * figure it qualifies: a value and a "this value is unreliable" that can drift apart would
     * eventually show one without the other.
     */
    val thin: Boolean = false,
) {
    /**
     * Value minus cost, or null when either half is missing.
     *
     * Null rather than treating an unknown cost as zero, which would present the whole sale price
     * as profit - the most flattering possible error, on the screen where it would be believed.
     */
    val profit: Long? get() = if (value != null && cost != null) value - cost else null
}

/**
 * One source of working capital - the forge, Kat, minions - as the page shows it.
 *
 * [items] is the detail behind the summary line, shown only while the row is expanded. A source
 * that isn't implemented yet is still listed, with [state] saying why it has nothing to report:
 * a row reading "—" is honest, where omitting the row would suggest the player has nothing there.
 */
data class WorkingGroup(
    val id: String,
    val label: String,
    val state: State,
    val items: List<WorkingItem> = emptyList(),
) {

    enum class State {
        /** Read successfully; [items] is what is in progress (possibly nothing). */
        READ,

        /**
         * Shown from what was recorded earlier, because the game isn't reporting it right now.
         *
         * Its own state rather than passing for READ: the items are as solid as when they were
         * read - a forge job can't change what it is making - but the wait is derived from the
         * clock and a job collected in the meantime would still be listed. The page marks it so
         * the reader knows which figures are first-hand.
         */
        REMEMBERED,

        /** Readable in principle, but the game isn't telling us right now - e.g. widget off. */
        UNKNOWN,

        /** Not built yet. Listed so the page doesn't imply the source doesn't exist. */
        PLANNED,
        ;

        /** Whether this state carries real items, however they were obtained. */
        val hasContent: Boolean get() = this == READ || this == REMEMBERED
    }

    /** Total value tied up here, or null when nothing in it could be priced. */
    val value: Long? get() = items.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.sum()

    /** What starting everything here cost, or null when none of it was priced. */
    val cost: Long? get() = items.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * Value minus cost across the group, counting only jobs where **both** are known.
     *
     * Summing [value] and [cost] separately would mix jobs: a priced sale whose cost is unknown
     * would inflate the profit by its whole value. Pairing them per job means the figure is
     * always a real profit for a real subset.
     */
    val profit: Long? get() = items.mapNotNull { it.profit }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * How many things are actually in progress - idle slots are listed but are not capital.
     *
     * This is the figure the summary line leads with, so counting an empty forge slot here would
     * read as "7 active" on a forge doing nothing.
     */
    val count: Int get() = items.count { !it.idle }

    /** True when anything here is priced off a market too thin to trust the figure. */
    val anyThin: Boolean get() = items.any { it.thin }
}

/**
 * Everything in progress, across every source.
 *
 * The total is the figure no other screen in the mod gives: what the player has working right
 * now, added up. It sums only what could be priced, since a source that can't be valued must not
 * quietly count as zero - that would read as "nothing there" rather than "not known".
 */
data class WorkingCapital(val groups: List<WorkingGroup>) {

    /** Sum of every priced item, or null when nothing at all could be priced. */
    val total: Long?
        get() = groups.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.sum()

    /** Profit across every source, on the jobs where both value and cost are known. */
    val totalProfit: Long?
        get() = groups.mapNotNull { it.profit }.takeIf { it.isNotEmpty() }?.sum()

    /** True when any source could not be read, so the total is a floor rather than a figure. */
    val partial: Boolean
        get() = groups.any { it.state != WorkingGroup.State.READ }

    /**
     * Groups worth opening first: the ones that actually have something in them.
     *
     * Remembered counts as having something - a forge still running is exactly what the player
     * wants opened when they check from another island, which is the case this was built for.
     */
    val active: List<WorkingGroup>
        get() = groups.filter { it.state.hasContent && it.count > 0 }
}
