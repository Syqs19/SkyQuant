package dev.syqs.skyquant.config

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rename from Hyblock to SkyQuant, seen from the disk.
 *
 * Worth testing because the failure is silent and total: every config file is named after the mod
 * id, so getting this wrong doesn't crash - it just shows a returning player a fresh install, with
 * their theme, HUD layout, watchlist and pins apparently gone.
 *
 * Each test drives a disposable folder through [LegacyConfigFiles.migrate]'s parameter, rather
 * than the real `config/`.
 */
class LegacyConfigFilesTest {

    private lateinit var configDir: File

    @BeforeTest
    fun setUp() {
        configDir = File.createTempFile("skyquant-config", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        configDir.deleteRecursively()
    }

    private fun write(name: String, contents: String) =
        File(configDir, name).apply { writeText(contents) }

    private fun read(name: String) = File(configDir, name).takeIf { it.exists() }?.readText()

    @Test
    fun `carries the main settings file across`() {
        write("hyblock.json", """{"theme":"midnight"}""")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("""{"theme":"midnight"}""", read("skyquant.json"))
        assertFalse(File(configDir, "hyblock.json").exists(), "the old file should be gone")
    }

    /**
     * The settings file is only one of several - the watchlist, the HUD layout and the caches all
     * follow the same naming rule, and a migration that moved just `hyblock.json` would look like
     * it worked while losing the player's tracked items.
     */
    @Test
    fun `carries every suffixed file across`() {
        write("hyblock.json", "settings")
        write("hyblock_watchlist.json", "watchlist")
        write("hyblock_hud.json", "hud")
        write("hyblock_recipes.json", "recipes")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("settings", read("skyquant.json"))
        assertEquals("watchlist", read("skyquant_watchlist.json"))
        assertEquals("hud", read("skyquant_hud.json"))
        assertEquals("recipes", read("skyquant_recipes.json"))
    }

    /**
     * Arises when someone launches the old build once more after updating: both names exist, and
     * the SkyQuant one is the newer. Overwriting it would discard settings changed since.
     */
    @Test
    fun `never overwrites an existing SkyQuant file`() {
        write("hyblock.json", "old")
        write("skyquant.json", "current")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("current", read("skyquant.json"), "the newer file must survive")
        assertTrue(File(configDir, "hyblock.json").exists(), "and the legacy copy is left alone")
    }

    @Test
    fun `does nothing on a fresh install`() {
        LegacyConfigFiles.migrate(configDir)

        assertEquals(0, configDir.listFiles()?.size, "no files should be created")
    }

    @Test
    fun `is safe to run again once the rename has happened`() {
        write("skyquant.json", "settings")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("settings", read("skyquant.json"))
        assertEquals(1, configDir.listFiles()?.size)
    }

    /**
     * Only the leading id is replaced. Rewriting every occurrence would turn a file whose suffix
     * happens to repeat the old name into the wrong thing.
     */
    @Test
    fun `replaces only the leading id`() {
        write("hyblock_hyblock_notes.json", "contents")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("contents", read("skyquant_hyblock_notes.json"))
    }

    @Test
    fun `leaves unrelated files untouched`() {
        write("someothermod.json", "theirs")
        write("hyblock.json", "ours")

        LegacyConfigFiles.migrate(configDir)

        assertEquals("theirs", read("someothermod.json"))
        assertEquals("ours", read("skyquant.json"))
    }
}
