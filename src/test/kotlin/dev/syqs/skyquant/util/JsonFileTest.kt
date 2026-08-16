package dev.syqs.skyquant.util

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every store in the mod reads and writes through this, so a fault here loses the watchlist, the
 * HUD layout and the Ubik timer at once.
 */
class JsonFileTest {

    class Data(var name: String = "", var count: Int = 0)

    private val written = mutableListOf<File>()

    private fun store(name: String): JsonFile<Data> =
        JsonFile.of("test_$name", { Data() }).also {
            written += File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_$name.json")
        }

    @AfterTest
    fun removeWrittenFiles() {
        written.forEach { it.delete() }
    }

    @Test
    fun `round-trips a value`() {
        val file = store("roundtrip")
        file.save(Data(name = "diamond", count = 7))

        val loaded = file.load()

        assertEquals("diamond", loaded.name)
        assertEquals(7, loaded.count)
    }

    @Test
    fun `returns the default when the file does not exist yet`() {
        assertEquals("", store("missing").load().name)
    }

    @Test
    fun `returns the default for an empty file instead of null`() {
        // The case runCatching alone doesn't cover: Gson returns null for empty input *without*
        // throwing, so a store guarding only with a try would hand back a null it declared
        // could never be null - and crash somewhere else entirely.
        val file = store("empty")
        File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_empty.json").apply {
            parentFile?.mkdirs()
            writeText("")
        }

        assertEquals("", file.load().name)
    }

    @Test
    fun `returns the default for truncated json`() {
        // What a crash mid-write actually leaves behind.
        val file = store("truncated")
        File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_truncated.json").apply {
            parentFile?.mkdirs()
            writeText("""{"name":"diam""")
        }

        assertEquals("", file.load().name)
    }

    @Test
    fun `each load returns a separate default`() {
        // Sharing one default instance between callers would let one store's edits appear in
        // another's, which is the kind of fault that looks like data corrupting itself.
        val file = store("separate")

        val first = file.load()
        first.name = "changed"

        assertEquals("", file.load().name)
    }

    @Test
    fun `delete removes the file`() {
        val file = store("delete")
        file.save(Data(name = "gone"))

        file.delete()

        assertTrue(File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_delete.json").exists().not())
    }

    /**
     * Saving through a temporary file, which is what keeps a crash mid-write from emptying a
     * store rather than merely interrupting it.
     *
     * The guards above prove a truncated file *loads* safely; these prove one is never *left*.
     * The distinction matters because "loads safely" means "returns the default", and the default
     * for a watchlist is no items - the store survives and the player's data is gone.
     */
    @Test
    fun `a save leaves no temporary file behind`() {
        val file = store("tmp_cleanup")
        file.save(Data(name = "kept", count = 3))

        val temporary = File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_tmp_cleanup.json.tmp")
        assertTrue(temporary.exists().not(), "the temporary file must be moved, not copied")
        assertEquals("kept", file.load().name)
    }

    @Test
    fun `a stale temporary file is ignored rather than read`() {
        // What a crash mid-write actually leaves: the real file intact and a half-written sibling.
        // The sibling must have no effect at all - the previous save is still the truth.
        val file = store("tmp_stale")
        file.save(Data(name = "committed", count = 9))

        File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_tmp_stale.json.tmp").apply {
            parentFile?.mkdirs()
            writeText("""{"name":"half-writ""")
        }
        written += File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_tmp_stale.json.tmp")

        assertEquals("committed", file.load().name)
        assertEquals(9, file.load().count)
    }

    @Test
    fun `a save that cannot be staged leaves the previous contents alone`() {
        // The property the whole change exists for, tested at the only point a unit test can reach
        // it: with the staging file blocked, the save fails *before* touching the real file, so
        // what is on disk is still the previous save rather than nothing.
        //
        // This is deliberately not a crash simulation - a test cannot halt the JVM mid-write. What
        // it does pin down is the ordering that makes a crash survivable: new content is committed
        // to a sibling first, and the live file is only ever replaced whole. A direct write has no
        // such ordering to test, which is why it passes this by writing straight through.
        val file = store("survives")
        file.save(Data(name = "original", count = 42))

        val blocker = File("config/${dev.syqs.skyquant.SkyQuantMod.MOD_ID}_test_survives.json.tmp")
        blocker.mkdirs()
        written += blocker

        try {
            // Swallowed and logged rather than thrown, as every other failure here is.
            file.save(Data(name = "replacement", count = 1))

            assertEquals("original", file.load().name, "a failed save must not destroy the old data")
            assertEquals(42, file.load().count)
        } finally {
            blocker.deleteRecursively()
        }
    }
}
