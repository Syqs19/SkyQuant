package dev.syqs.skyquant.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.syqs.skyquant.SkyQuantMod
import java.io.File

/**
 * A JSON file in the config folder that holds one piece of the mod's own state.
 *
 * Wraps the read/write pair every store was repeating: create the folder, catch and log failures,
 * and fall back to a default rather than propagating. That last part matters more than it looks -
 * Gson returns null for an empty or truncated file *without* throwing, so a store that only
 * guarded with runCatching would hand back a null it declared could never be null.
 *
 * Not for the settings themselves: those are MoulConfig's, which brings its own file handling
 * (see [dev.syqs.skyquant.config.SkyQuantConfigManager]).
 */
class JsonFile<T : Any>(
    name: String,
    private val type: java.lang.reflect.Type,
    /** Built fresh each time it's needed, so callers can't accidentally share one instance. */
    private val default: () -> T,
    pretty: Boolean = false,
) {

    private val gson: Gson = if (pretty) GsonBuilder().setPrettyPrinting().create() else Gson()
    private val file = File("config/${SkyQuantMod.MOD_ID}_$name.json")

    /** Reads the file, or returns the default if it's missing, empty or unreadable. */
    fun load(): T {
        if (!file.exists()) return default()

        return runCatching { gson.fromJson<T>(file.readText(), type) }
            .onFailure { SkyQuantMod.LOGGER.warn("Failed to read $file", it) }
            .getOrNull() ?: default()
    }

    fun save(value: T) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(value))
        }.onFailure { SkyQuantMod.LOGGER.warn("Failed to save $file", it) }
    }

    /** Removes the file, for state that becomes meaningless rather than reverting to a default. */
    fun delete() {
        runCatching { file.delete() }
            .onFailure { SkyQuantMod.LOGGER.warn("Failed to delete $file", it) }
    }

    companion object {
        /** Convenience for the common case, where the type is just the class itself. */
        inline fun <reified T : Any> of(
            name: String,
            noinline default: () -> T,
            pretty: Boolean = false,
        ): JsonFile<T> = JsonFile(name, object : TypeToken<T>() {}.type, default, pretty)
    }
}
