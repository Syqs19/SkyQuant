package dev.syqs.skyquant.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.syqs.skyquant.SkyQuantMod
import java.io.File

/**
 * Moves settings that changed shape between versions into their new place.
 *
 * Gson silently ignores keys it doesn't recognise, so without this a rename doesn't fail loudly -
 * it quietly resets that setting to its default, which looks to the player like the mod forgot
 * what they chose.
 *
 * Rewrites the file in place before it's read, so the rest of the mod only ever sees the current
 * shape and no code outside this file has to know an old one existed.
 */
object ConfigMigration {

    /**
     * The one migration so far: each reminder's `reminderStyle`, `customization` and `text`
     * blocks were merged into a single shared `ReminderSettings`, so every reminder gets the
     * same options rather than each growing its own set.
     */
    fun migrate(file: File) {
        if (!file.exists()) return

        val root = runCatching { JsonParser.parseString(file.readText()).asJsonObject }
            .onFailure { SkyQuantMod.LOGGER.warn("Could not parse config for migration", it) }
            .getOrNull() ?: return

        var changed = false

        changed = migrateReminder(root, "rift", "ubikCube", "notification") || changed
        changed = migrateReminder(root, "mining", "pickaxeAbility", "reminder") || changed

        if (!changed) return

        runCatching { file.writeText(root.toString()) }
            .onSuccess { SkyQuantMod.LOGGER.info("Migrated SkyQuant config to the shared reminder settings") }
            .onFailure { SkyQuantMod.LOGGER.warn("Failed to write migrated config", it) }
    }

    /**
     * Folds the three old blocks under [category].[feature] into one object named [target].
     * Returns whether anything was actually moved.
     */
    private fun migrateReminder(root: JsonObject, category: String, feature: String, target: String): Boolean {
        val settings = root.getAsJsonObject(category)?.getAsJsonObject(feature) ?: return false

        // Already migrated, or written by a fresh install.
        if (settings.has(target)) return false
        if (!settings.has("reminderStyle") && !settings.has("customization") && !settings.has("text")) return false

        val merged = JsonObject()

        settings.remove("reminderStyle")?.let { merged.add("style", it) }
        // "customization" only ever existed on the pickaxe; the cube had no sound settings at
        // all, and now inherits the defaults.
        settings.remove("customization")?.let { merged.add("presentation", it) }
        settings.remove("text")?.let { merged.add("text", it) }

        settings.add(target, merged)
        return true
    }
}
