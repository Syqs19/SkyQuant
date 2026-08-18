package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Status page's model: what the player has in progress, and what it adds up to.
 *
 * The figure this page exists for is the total, and the tests that matter most are the ones about
 * when it must refuse to give one. A total that silently treats "couldn't read it" as zero is
 * worse than no total, because it reads as complete.
 */
class WorkingCapitalTest {

    /** Survey 1's forge, all seven slots working on the same item. */
    private val busyForge = ForgeState(
        List(7) { ForgeSlot.Busy("Tungsten Plate", "1h 25m") },
    )

    private val prices = mapOf("Tungsten Plate" to 1_200_000L, "Refined Umber" to 840_000L)

    private fun priceOf(name: String): WorkingCapitalSummary.Priced? =
        prices[name]?.let { WorkingCapitalSummary.Priced(it) }

    @Test
    fun `sums what is in the forge`() {
        val capital = WorkingCapitalSummary.build(busyForge, ::priceOf)
        val forge = capital.groups.first { it.id == "forge" }

        assertEquals(7, forge.count)
        assertEquals(8_400_000L, forge.value)
        assertEquals(8_400_000L, capital.total)
    }

    @Test
    fun `an unreadable widget is unknown, and the page says so`() {
        // The widget being off must not read as an empty forge. The page shows the row as unknown
        // and flags the total as partial, rather than reporting 0 to someone whose forge is full.
        val capital = WorkingCapitalSummary.build(forge = null, priceOf = ::priceOf)
        val forge = capital.groups.first { it.id == "forge" }

        assertEquals(WorkingGroup.State.UNKNOWN, forge.state)
        assertNull(forge.value)
        assertTrue(capital.partial)
    }

    @Test
    fun `an unpriceable item does not count as zero`() {
        // A forge output nothing can price must leave the total unknown for that item rather than
        // adding nothing to it, or the page understates what the player has working.
        val forge = ForgeState(listOf(ForgeSlot.Busy("Mystery Widget", "3h")))
        val capital = WorkingCapitalSummary.build(forge, priceOf = { null })

        assertNull(capital.groups.first { it.id == "forge" }.value)
        assertNull(capital.total)
    }

    @Test
    fun `a priced item still counts when a sibling cannot be priced`() {
        // Mixed case: one known, one not. The known one must still contribute - refusing the whole
        // sum because of one unknown would throw away a figure the player can use.
        val forge = ForgeState(
            listOf(
                ForgeSlot.Busy("Tungsten Plate", "1h 25m"),
                ForgeSlot.Busy("Mystery Widget", "3h"),
            ),
        )
        val capital = WorkingCapitalSummary.build(forge, ::priceOf)

        assertEquals(1_200_000L, capital.total)
    }

    @Test
    fun `empty and locked slots are listed but never counted as active`() {
        // Both: they are shown, so idle capacity is visible, and they must not inflate the count
        // - a forge with one job running and six free is "1 active", not "7 active".
        val forge = ForgeState(
            listOf(
                ForgeSlot.Busy("Tungsten Plate", "1h 25m"),
                ForgeSlot.Empty,
                ForgeSlot.Locked,
            ),
        )
        val group = WorkingCapitalSummary.build(forge, ::priceOf).groups.first { it.id == "forge" }

        assertEquals(1, group.count)
        assertEquals(3, group.items.size, "idle slots are shown, just not counted")
        assertEquals(1_200_000L, group.value, "an idle slot must not be priced")
    }

    @Test
    fun `a job away from the forge is shown from what was recorded`() {
        // The case the ledger exists for: the widget only appears on the forge island, so without
        // this a player checking from the Hub sees nothing at all.
        val now = 1_000_000L
        val remembered = listOf(
            ForgeLedger.Job(
                slot = 1,
                item = "Tungsten Plate",
                cost = 9_200_000L,
                finishesAt = now + 16 * 60_000L,
                totalMillis = 60 * 60_000L,
            ),
        )
        val capital = WorkingCapitalSummary.build(null, ::priceOf, remembered, now)
        val group = capital.groups.first { it.id == "forge" }

        assertEquals(WorkingGroup.State.REMEMBERED, group.state)
        assertEquals("16m", group.items.single().remaining)
    }

    @Test
    fun `a remembered job that has run out reads as ready, not as a stale countdown`() {
        // The whole reason the finish time is stored rather than the remaining text: replaying a
        // saved "16m" an hour later would state something plainly false.
        val now = 1_000_000L
        val finished = listOf(
            ForgeLedger.Job(1, "Tungsten Plate", 9_200_000L, now - 60_000L, 60 * 60_000L),
        )
        val group = WorkingCapitalSummary.build(null, ::priceOf, finished, now)
            .groups.first { it.id == "forge" }

        assertEquals(WorkingCapitalSummary.READY, group.items.single().remaining)
    }

    @Test
    fun `profit is value minus what the job cost when it started`() {
        val now = 1_000_000L
        val remembered = listOf(
            ForgeLedger.Job(1, "Tungsten Plate", 900_000L, now + 60_000L, 60 * 60_000L),
        )
        val group = WorkingCapitalSummary.build(null, ::priceOf, remembered, now)
            .groups.first { it.id == "forge" }

        assertEquals(300_000L, group.items.single().profit)
        assertEquals(300_000L, group.profit)
    }

    @Test
    fun `an unknown cost leaves profit unknown rather than counting the sale as pure gain`() {
        // The most flattering possible error, on the screen where it would be believed: with no
        // cost, "profit" would be the entire sale price.
        val now = 1_000_000L
        val noCost = listOf(ForgeLedger.Job(1, "Tungsten Plate", null, now + 60_000L, null))
        val group = WorkingCapitalSummary.build(null, ::priceOf, noCost, now)
            .groups.first { it.id == "forge" }

        assertNull(group.items.single().profit)
        assertNull(group.profit)
    }

    @Test
    fun `the live widget wins over what was remembered`() {
        // Standing at the forge, the widget is the truth: a job collected and restarted with a
        // different item must not keep showing the old one from the ledger.
        val now = 1_000_000L
        val stale = listOf(ForgeLedger.Job(1, "Refined Umber", 500_000L, now + 60_000L, null))
        val live = ForgeState(listOf(ForgeSlot.Busy("Tungsten Plate", "16m")))

        val group = WorkingCapitalSummary.build(live, ::priceOf, stale, now)
            .groups.first { it.id == "forge" }

        assertEquals("Tungsten Plate", group.items.single().name)
        assertEquals(WorkingGroup.State.READ, group.state)
        assertNull(group.items.single().cost, "a different item's cost must not be reused")
    }

    @Test
    fun `countdown is written the way Hypixel writes it`() {
        assertEquals("1h 25m", WorkingCapitalSummary.countdown(85 * 60_000L))
        assertEquals("16m", WorkingCapitalSummary.countdown(16 * 60_000L))
        assertEquals(WorkingCapitalSummary.READY, WorkingCapitalSummary.countdown(0))
        // Under a minute is still a wait; "0m" would read as finished.
        assertEquals("<1m", WorkingCapitalSummary.countdown(30_000L))
    }

    @Test
    fun `sources that are not built yet are listed rather than hidden`() {
        // Omitting them would let the page imply the player has nothing in minions or auctions,
        // when the truth is the mod cannot see them yet.
        val capital = WorkingCapitalSummary.build(busyForge, ::priceOf)
        val ids = capital.groups.map { it.id }

        assertEquals(listOf("forge", "minion", "auctions", "kat", "fann"), ids)
        assertTrue(capital.partial, "planned sources must mark the total as partial")
    }

    @Test
    fun `an idle forge that was read is not partial`() {
        // Read successfully and genuinely empty is a different claim from unreadable, and the
        // forge row must not be the thing that flags the total - only the planned rows should.
        val idle = ForgeState(List(7) { ForgeSlot.Empty })
        val forge = WorkingCapitalSummary.build(idle, ::priceOf).groups.first { it.id == "forge" }

        assertEquals(WorkingGroup.State.READ, forge.state)
        assertEquals(0, forge.count)
    }

    @Test
    fun `opens the row with something ready to collect`() {
        val forge = ForgeState(
            listOf(
                ForgeSlot.Busy("Tungsten Plate", "1h 25m"),
                ForgeSlot.Ready("Refined Umber"),
            ),
        )
        val capital = WorkingCapitalSummary.build(forge, ::priceOf)

        assertEquals("forge", WorkingCapitalSummary.rowToExpand(capital))
    }

    @Test
    fun `opens nothing when nothing is in progress`() {
        // Every row empty or planned: expanding one would show an empty list, which is noise.
        val idle = ForgeState(List(7) { ForgeSlot.Empty })

        assertNull(WorkingCapitalSummary.rowToExpand(WorkingCapitalSummary.build(idle, ::priceOf)))
    }

    @Test
    fun `active skips sources that were never read`() {
        val capital = WorkingCapitalSummary.build(forge = null, priceOf = ::priceOf)

        assertTrue(capital.active.isEmpty())
        assertFalse(capital.groups.isEmpty(), "the rows are still listed, just not active")
    }
}
