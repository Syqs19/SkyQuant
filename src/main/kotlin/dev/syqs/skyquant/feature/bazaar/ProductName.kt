package dev.syqs.skyquant.feature.bazaar

/**
 * Turns a bazaar product id into something readable.
 *
 * Shared because all three bazaar screens need it and were each carrying their own copy - which
 * is how the overlay ended up with an abbreviation rule the others silently lacked.
 */
object ProductName {

    /** ENCHANTED_DIAMOND_BLOCK -> Enchanted Diamond Block */
    fun of(productId: String): String = productId
        .split('_')
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

    /**
     * As [of], but with the long prefixes shortened - "Enchanted" alone is nine characters and
     * fronts most of the bazaar, so on a narrow panel it pushes out the part that actually
     * distinguishes one row from another.
     */
    fun short(productId: String): String {
        val full = of(productId)

        return ABBREVIATIONS.entries.fold(full) { name, (long, brief) ->
            name.replace(long, brief)
        }
    }

    /**
     * The other direction: "Tungsten Plate" -> TUNGSTEN_PLATE.
     *
     * Needed because Hypixel's tab list names what is being forged the way a player reads it,
     * while every price here is keyed by id. Deriving the id is enough for forge outputs, whose
     * names are plain words, and it avoids carrying a second name table that would drift from the
     * first.
     *
     * It cannot be right for every item in the game - anything whose display name differs from
     * its id by more than case and spaces will not round-trip - so callers must treat a miss as
     * "not priced" rather than assuming the lookup always works. That is why this returns the id
     * rather than a price: the caller looks it up and gets null when it isn't a real product.
     */
    fun idOf(displayName: String): String = displayName
        .trim()
        .uppercase()
        .replace(' ', '_')

    private val ABBREVIATIONS = mapOf(
        "Enchanted" to "Ench",
        "Ultimate" to "Ult",
    )
}
