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

    private val ABBREVIATIONS = mapOf(
        "Enchanted" to "Ench",
        "Ultimate" to "Ult",
    )
}
