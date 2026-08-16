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
}
