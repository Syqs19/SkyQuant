package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Reads the Skyblock item id Hypixel attaches to every item it hands out.
 *
 * It arrives in the `custom_data` component, which on current versions holds the id at the top
 * level (`{id:"ENCHANTED_DIAMOND"}`); older Hypixel builds nested it under `ExtraAttributes`, so
 * both shapes are accepted. Matching on the display name instead would break on reforges, stars
 * and colour codes, all of which change the visible text while the id stays put.
 */
object SkyblockItemId {

    private const val EXTRA_ATTRIBUTES = "ExtraAttributes"
    private const val ID = "id"

    /** Returns e.g. `ENCHANTED_DIAMOND`, or null for a plain vanilla item. */
    fun of(stack: ItemStack): String? {
        if (stack.isEmpty) return null

        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null

        val id = tag.getStringOr(ID, "")
            .ifBlank { tag.getCompoundOrEmpty(EXTRA_ATTRIBUTES).getStringOr(ID, "") }

        return id.ifBlank { null }
    }

    /**
     * Maps an item to the product id the bazaar trades it under.
     *
     * Most ids match directly. Enchanted books are the exception worth handling: they all share
     * the id `ENCHANTED_BOOK` and the actual product is the enchantment inside them, so they're
     * rejected rather than silently charting the wrong thing.
     */
    fun bazaarProductOf(stack: ItemStack): String? {
        val id = of(stack) ?: return null
        if (id == "ENCHANTED_BOOK") return null

        // Only filtered once the product list has actually loaded: checking against an empty
        // cache would reject every item during the first minute of play.
        val known = BazaarLivePrices.productIds
        if (known.isEmpty()) return id

        return id.takeIf { it in known }
    }
}
