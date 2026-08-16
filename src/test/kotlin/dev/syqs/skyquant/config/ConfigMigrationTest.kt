package dev.syqs.skyquant.config

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Migration is the one place where a mistake silently destroys settings the player chose, so it
 * gets the closest tests: Gson drops keys it doesn't recognise without complaining, which is
 * failure that looks exactly like success.
 */
class ConfigMigrationTest {

    private val temp: File = Files.createTempDirectory("skyquant-migration").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun fileWith(contents: String): File =
        File(temp, "config-${System.nanoTime()}.json").apply { writeText(contents) }

    @Test
    fun `moves the old reminder blocks under one settings object`() {
        val file = fileWith(
            """
            {
              "rift": {
                "ubikCube": {
                  "reminder": true,
                  "reminderStyle": { "chatMessage": true, "toast": false, "title": true },
                  "text": { "title": "mio titolo" }
                }
              }
            }
            """.trimIndent(),
        )

        ConfigMigration.migrate(file)

        val ubik = JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonObject("rift").getAsJsonObject("ubikCube")

        val notification = ubik.getAsJsonObject("notification")
        assertFalse(ubik.has("reminderStyle"), "old key left behind")

        // The player's actual choices have to survive, not just the shape.
        assertFalse(notification.getAsJsonObject("style").get("toast").asBoolean)
        assertTrue(notification.getAsJsonObject("style").get("title").asBoolean)
        assertEquals("mio titolo", notification.getAsJsonObject("text").get("title").asString)
    }

    @Test
    fun `moves the pickaxe sound settings into presentation`() {
        val file = fileWith(
            """
            {
              "mining": {
                "pickaxeAbility": {
                  "reminderStyle": { "sound": true },
                  "customization": { "sound": "EXPERIENCE_ORB", "pitch": 1.4 }
                }
              }
            }
            """.trimIndent(),
        )

        ConfigMigration.migrate(file)

        val presentation = JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonObject("mining").getAsJsonObject("pickaxeAbility")
            .getAsJsonObject("reminder").getAsJsonObject("presentation")

        assertEquals(1.4, presentation.get("pitch").asDouble, 1e-9)
    }

    @Test
    fun `running twice changes nothing the second time`() {
        // Migration runs on every startup, so a second pass must be a no-op rather than nesting
        // the already-migrated block inside another one.
        val file = fileWith(
            """{"rift":{"ubikCube":{"reminderStyle":{"toast":false}}}}""",
        )

        ConfigMigration.migrate(file)
        val afterFirst = file.readText()
        ConfigMigration.migrate(file)

        assertEquals(afterFirst, file.readText())
    }

    @Test
    fun `leaves an already-migrated file alone`() {
        val current = """{"rift":{"ubikCube":{"notification":{"style":{"toast":true}}}}}"""
        val file = fileWith(current)

        ConfigMigration.migrate(file)

        assertEquals(current, file.readText())
    }

    @Test
    fun `survives a corrupt file instead of throwing`() {
        // A crash mid-save leaves truncated JSON; the mod has to start anyway.
        val file = fileWith("{ this is not json")

        ConfigMigration.migrate(file)

        assertEquals("{ this is not json", file.readText())
    }

    @Test
    fun `does nothing when there is no file yet`() {
        val missing = File(temp, "absent.json")

        ConfigMigration.migrate(missing)

        assertFalse(missing.exists(), "migration created a file out of nothing")
    }
}
