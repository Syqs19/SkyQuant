package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.JsonObject

/**
 * One way of making an item, read from the NEU repository.
 *
 * Only the two kinds this mod prices are represented. A crafting recipe is an immediate trade -
 * buy the ingredients, craft, sell - while a forge recipe occupies a slot for a stated time, so
 * the two are ranked on different figures and [durationSeconds] is what separates them.
 */
data class Recipe(
    val outputId: String,
    /** How many come out. A few recipes state this as a decimal; see [parseAmount]. */
    val outputCount: Double,
    val ingredients: Map<String, Double>,
    /** Zero for crafting, which is instant. Forge recipes run from 30 seconds to a week. */
    val durationSeconds: Long = 0,
) {
    val isForge: Boolean get() = durationSeconds > 0

    companion object {

        /**
         * The nine grid cells of a crafting recipe, in the repo's own naming.
         *
         * Read as a set rather than iterated over the object's keys: a recipe carries other
         * fields alongside the grid (`count`, `overrideOutputId`), and an untyped one carries
         * nothing to distinguish them by.
         */
        private val GRID_CELLS = listOf(
            "A1", "A2", "A3",
            "B1", "B2", "B3",
            "C1", "C2", "C3",
        )

        /**
         * Reads every priceable recipe out of one item's JSON.
         *
         * **Both `recipe` and `recipes` must be read.** DIAMOND uses the first and
         * ENCHANTED_DIAMOND the second; parsing only one loses roughly half the items. 178 items
         * carry more than one recipe, which is why this returns a list rather than one recipe.
         *
         * [fallbackOutputId] is the item the file is named after, used when a recipe doesn't
         * state its own output. Measured across the repo: `overrideOutputId` is present on 511 of
         * 539 typed crafting recipes and on all 120 forge ones, so the fallback is what covers
         * the rest - including every untyped recipe, none of which carries it.
         */
        fun parseAll(itemJson: JsonObject, fallbackOutputId: String): List<Recipe> {
            val raw = buildList {
                itemJson.getAsJsonObject("recipe")?.let { add(it) }
                itemJson.getAsJsonArray("recipes")?.forEach { element ->
                    if (element.isJsonObject) add(element.asJsonObject)
                }
            }

            return raw.mapNotNull { parse(it, fallbackOutputId) }
        }

        /**
         * One recipe, or null when it isn't a kind this prices.
         *
         * The repo holds seven kinds and only two are trades a player can make with the markets:
         * `drops` describes a mob, `npc_shop` a purchase already covered by [NpcShopPrices],
         * `katgrade` a pet upgrade, `trade` an NPC exchange. Their fields are shaped nothing like
         * a recipe's - `drops` carries mob names and lore lines with colour codes in them - so
         * parsing them as ingredients produces entries like "§cAgarimoo" that match no item.
         */
        private fun parse(json: JsonObject, fallbackOutputId: String): Recipe? {
            // Absent means crafting. 2011 of the repo's 2670 crafting recipes carry no `type` at
            // all, so requiring it to equal "crafting" would discard 79% of them - the single
            // biggest trap in this format.
            val type = json.get("type")?.takeIf { !it.isJsonNull }?.asString

            return when (type) {
                null, "crafting" -> parseCrafting(json, fallbackOutputId)
                "forge" -> parseForge(json, fallbackOutputId)
                else -> null
            }
        }

        private fun parseCrafting(json: JsonObject, fallbackOutputId: String): Recipe? {
            val ingredients = mutableMapOf<String, Double>()

            for (cell in GRID_CELLS) {
                val entry = json.get(cell)?.takeIf { !it.isJsonNull }?.asString ?: continue
                val (id, amount) = parseStack(entry) ?: continue

                // The same ingredient repeats across cells and the counts add up: ENCHANTED_DIAMOND
                // is five cells of DIAMOND:32, i.e. 160 diamonds, not 32.
                ingredients.merge(id, amount, Double::plus)
            }

            if (ingredients.isEmpty()) return null

            return Recipe(
                outputId = outputIdOf(json, fallbackOutputId),
                outputCount = json.get("count")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0,
                ingredients = ingredients,
            )
        }

        private fun parseForge(json: JsonObject, fallbackOutputId: String): Recipe? {
            val inputs = json.getAsJsonArray("inputs") ?: return null
            val ingredients = mutableMapOf<String, Double>()

            for (element in inputs) {
                val (id, amount) = parseStack(element.asString) ?: continue
                ingredients.merge(id, amount, Double::plus)
            }

            if (ingredients.isEmpty()) return null

            // Guarded rather than assumed: a forge recipe with no duration would be ranked as an
            // instant craft, which is the one figure the Forge page exists to get right.
            val duration = json.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: return null
            if (duration <= 0) return null

            return Recipe(
                outputId = outputIdOf(json, fallbackOutputId),
                outputCount = json.get("count")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0,
                ingredients = ingredients,
                durationSeconds = duration,
            )
        }

        /**
         * The real output, which is not always the file the recipe sits in.
         *
         * AMBER_MATERIAL's forge recipe lives in its own file and names itself, but 511 crafting
         * recipes point somewhere else entirely - a recipe file for one item producing another.
         */
        private fun outputIdOf(json: JsonObject, fallback: String): String =
            json.get("overrideOutputId")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?: fallback

        /**
         * Splits `"DIAMOND:32"` into its id and its amount.
         *
         * Null for an empty cell, which is how the repo writes an unused slot, and for anything
         * with no amount attached - the `drops` and `npc_shop` kinds are filtered out before this
         * point, but a malformed entry should be skipped rather than crash the index build.
         */
        internal fun parseStack(entry: String): Pair<String, Double>? {
            if (entry.isBlank()) return null

            val separator = entry.lastIndexOf(':')
            if (separator <= 0) return null

            val id = entry.substring(0, separator)
            val amount = parseAmount(entry.substring(separator + 1)) ?: return null
            if (id.isBlank() || amount <= 0) return null

            return id to amount
        }

        /**
         * Reads a quantity, which the repo does not always write as an integer.
         *
         * Measured: 37 entries across the repo are written as decimals - "COBBLESTONE:8.0",
         * "ENCHANTED_SUGAR:2500.0". `toInt()` throws on those, so the whole item would be lost;
         * they are all whole numbers wearing a decimal point, but the parser must not depend on
         * that either.
         */
        private fun parseAmount(text: String): Double? = text.trim().toDoubleOrNull()
    }
}
