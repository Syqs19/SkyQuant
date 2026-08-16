package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.SkyQuantMod
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stock readings have to survive a restart: a player who checked a shop this morning should not
 * have to walk back to it after relaunching the game.
 *
 * The bug these were written for saved every reading as `{"stock":{}}`. `Stored().apply { stock
 * = stock.toMutableMap() }` resolves *both* names to the receiver's own field, so the object was
 * handed its own empty map. It compiled without a warning, the figures looked right on screen
 * for the rest of the session, and only a restart revealed that nothing had been written.
 */
class NpcDailyLimitTest {

    private val file = File("config/${SkyQuantMod.MOD_ID}_npc_stock.json")

    @BeforeTest
    fun clearStore() {
        file.delete()
        NpcDailyLimit.forgetForTest()
    }

    @AfterTest
    fun removeStore() {
        file.delete()
        NpcDailyLimit.forgetForTest()
    }

    @Test
    fun `a reading is written to disk, not just held in memory`() {
        NpcDailyLimit.recordStock("GOLD_INGOT", 415)

        assertEquals(415, NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
    }

    @Test
    fun `a reading survives a restart`() {
        NpcDailyLimit.recordStock("RAW_FISH", 415)

        // What relaunching the game does: the in-memory copy is gone and everything has to
        // come back off disk.
        NpcDailyLimit.reloadFromDiskForTest()

        assertEquals(415, NpcDailyLimit.savedStockForTest("RAW_FISH"))
    }

    @Test
    fun `several readings accumulate rather than replacing each other`() {
        NpcDailyLimit.recordStock("GOLD_INGOT", 415)
        NpcDailyLimit.recordStock("IRON_INGOT", 200)
        NpcDailyLimit.recordStock("COAL", 640)

        assertEquals(415, NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
        assertEquals(200, NpcDailyLimit.savedStockForTest("IRON_INGOT"))
        assertEquals(640, NpcDailyLimit.savedStockForTest("COAL"))
    }

    @Test
    fun `a later reading replaces an earlier one for the same item`() {
        // Buying draws the stock down over the day, and the newest figure is the true one.
        NpcDailyLimit.recordStock("GOLD_INGOT", 640)
        NpcDailyLimit.recordStock("GOLD_INGOT", 415)

        assertEquals(415, NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
    }

    @Test
    fun `ids are stored in one case, so lookups match however they arrive`() {
        NpcDailyLimit.recordStock("gold_ingot", 415)

        assertEquals(415, NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
    }

    @Test
    fun `an item never read has nothing saved`() {
        NpcDailyLimit.recordStock("GOLD_INGOT", 415)

        assertNull(NpcDailyLimit.savedStockForTest("DIAMOND"))
    }

    @Test
    fun `yesterday's readings are dropped, because the shop has refilled`() {
        // Stock resets at 00:00 GMT. Keeping a reading past that would report "412 left" for a
        // shop back up to 640 - and worse than simply being wrong, it would carry the marker
        // saying the figure was read from the shop rather than assumed.
        NpcDailyLimit.recordStaleReadingForTest("GOLD_INGOT", 412)

        assertNull(NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
    }

    @Test
    fun `a reading made today is kept`() {
        // The other half of the rule: the reset must not throw away readings still valid.
        NpcDailyLimit.recordStock("GOLD_INGOT", 412)
        NpcDailyLimit.reloadFromDiskForTest()

        assertEquals(412, NpcDailyLimit.savedStockForTest("GOLD_INGOT"))
    }
}
