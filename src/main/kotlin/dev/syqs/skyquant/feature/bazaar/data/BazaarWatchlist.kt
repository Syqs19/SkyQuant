package dev.syqs.skyquant.feature.bazaar.data

import dev.syqs.skyquant.util.JsonFile

/**
 * Products the player follows, and which of those stay on screen while playing.
 *
 * Kept in its own file rather than in the config: this is player data that grows over time, not
 * a setting, and mixing it in would clutter the settings screen with a list nobody edits there.
 */
object BazaarWatchlist {

    private class State {
        var tracked: MutableList<String> = mutableListOf()
        var pinned: MutableSet<String> = mutableSetOf()
    }

    private val store = JsonFile.of("watchlist", { State() })
    private val state: State = store.load()

    /** Followed products, in the order the player added them. */
    val tracked: List<String> get() = state.tracked

    /** Subset of [tracked] shown on the live overlay. */
    val pinned: Set<String> get() = state.pinned

    fun isTracked(productId: String) = productId.uppercase() in state.tracked

    fun isPinned(productId: String) = productId.uppercase() in state.pinned

    /** Adds the product if missing, removes it if already there. Returns true when now tracked. */
    fun toggleTracked(productId: String): Boolean {
        val id = productId.uppercase()

        val nowTracked = if (id in state.tracked) {
            state.tracked.remove(id)
            // Dropping it from the watchlist has to drop the pin too, or the overlay would keep
            // showing something the player no longer follows.
            state.pinned.remove(id)
            false
        } else {
            state.tracked.add(id)
            true
        }

        save()
        return nowTracked
    }

    /** Pins or unpins the product on the overlay. Pinning also starts tracking it. */
    fun togglePinned(productId: String): Boolean {
        val id = productId.uppercase()

        val nowPinned = if (id in state.pinned) {
            state.pinned.remove(id)
            false
        } else {
            if (id !in state.tracked) state.tracked.add(id)
            state.pinned.add(id)
            true
        }

        save()
        return nowPinned
    }

    private fun save() = store.save(state)
}
