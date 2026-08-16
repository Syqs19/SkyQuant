package dev.syqs.skyquant.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.syqs.skyquant.SkyQuantMod
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    /**
     * Writes [value], replacing the file only once the new content is safely on disk.
     *
     * Straight to the file - `writeText` - truncates it first and then writes, so the moment
     * between the two is a window where the file on disk is empty or half a JSON document. A crash
     * there leaves exactly the truncated file [load] has to defend against, and while that guard
     * works, its answer is the default: an empty watchlist, a forgotten HUD layout, a lost Ubik
     * timer. The store survives, the player's data doesn't.
     *
     * Writing to a sibling and moving it over is what removes the window entirely. A move on the
     * same filesystem swaps the directory entry rather than copying bytes, so the file is only ever
     * the old content or the new one, never something in between - and a crash mid-write leaves the
     * stray `.tmp`, which nothing reads.
     */
    fun save(value: T) {
        runCatching {
            file.parentFile?.mkdirs()

            val json = gson.toJson(value)
            val temporary = File("${file.path}.tmp")
            temporary.writeText(json)

            moveIntoPlace(temporary.toPath(), file.toPath())
        }.onFailure { SkyQuantMod.LOGGER.warn("Failed to save $file", it) }
    }

    /**
     * Replaces [destination] with [temporary], atomically where the filesystem allows it.
     *
     * `ATOMIC_MOVE` is the guarantee worth having and is not universally available: it throws
     * [java.nio.file.AtomicMoveNotSupportedException] across filesystems, and a config folder on a
     * network share or a mounted volume is a real setup rather than a hypothetical one. The
     * fallback is a plain replacing move, which reopens the crash window but is still no worse
     * than the direct write this replaced - and it happens only where the strong version cannot.
     */
    private fun moveIntoPlace(temporary: Path, destination: Path) {
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
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
