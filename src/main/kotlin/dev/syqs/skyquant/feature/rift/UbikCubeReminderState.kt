package dev.syqs.skyquant.feature.rift

import dev.syqs.skyquant.util.JsonFile

/**
 * Persists the Ubik's Cube ready timestamp to `config/skyquant_ubik_cube.json`, so it
 * survives client restarts instead of resetting every time [UbikCubeReminder] is reloaded.
 */
object UbikCubeReminderState {

    private data class State(val readyAtMillis: Long = 0)

    private val store = JsonFile.of("ubik_cube", { State() })

    /** Null when no cooldown is pending, which is also what an unreadable file falls back to. */
    fun load(): Long? = store.load().readyAtMillis.takeIf { it > 0 }

    fun save(readyAtMillis: Long) = store.save(State(readyAtMillis))

    /** Deleted rather than zeroed: once the cooldown has passed there's nothing to remember. */
    fun clear() = store.delete()
}
