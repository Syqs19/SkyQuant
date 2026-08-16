package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.feature.bazaar.data.NpcDailyLimit
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents

/**
 * Reads the stock line off an NPC shop entry.
 *
 * Shop tooltips carry `Stock: 640 remaining`, which is better than the number the mod was
 * assuming in two ways: it is the real figure for *this* item rather than a limit taken to
 * apply to everything, and it is what is left today rather than the daily maximum - so a total
 * built on it describes what can actually still be bought.
 *
 * What it reads is fed to [NpcDailyLimit], which keeps the documented default for items the
 * player has not opened a shop for.
 */
object NpcStockReader {

    private const val STOCK_LABEL = "stock"
    private const val REMAINING = "remaining"

    /** Scans an open shop and records the stock of every entry it can read. */
    fun scan(screen: AbstractContainerScreen<*>) {
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue

            // `of`, not `bazaarProductOf`: the latter drops any id missing from the bazaar
            // product list, and a shop's window is full of those - pickaxes, torches, the
            // filler panes. Reading a stock line for something the bazaar doesn't trade costs
            // nothing, and the flip pages only ever look up ids they already have a quote for.
            val productId = SkyblockItemId.of(stack) ?: continue

            val lore = stack.get(DataComponents.LORE)
                ?.lines()
                ?.map { it.string }
                ?: continue

            stockFrom(lore)?.let { NpcDailyLimit.recordStock(productId, it) }
        }
    }

    /**
     * The remaining stock stated by an entry's lore, or null if it doesn't state one.
     *
     * Requires both the label and the word "remaining": shop tooltips also carry a cost line
     * and a collection count, and a parser matching bare digits would pick up whichever came
     * first.
     */
    fun stockFrom(lore: List<String>): Int? {
        val lines = lore.map { strip(it).lowercase() }

        for ((index, line) in lines.withIndex()) {
            // The label and the figure are usually on separate lines - the real tooltip reads
            // "Stock" then "640 remaining" - so the number is looked for on this line and the
            // next, rather than on this one alone.
            val hasLabel = line.contains(STOCK_LABEL)
            if (!hasLabel) continue

            val candidates = listOfNotNull(
                line.substringAfter(STOCK_LABEL),
                lines.getOrNull(index + 1),
            )

            for (candidate in candidates) {
                if (!candidate.contains(REMAINING)) continue

                val stock = candidate
                    .filter { it.isDigit() || it == ',' }
                    .replace(",", "")
                    .toIntOrNull()
                    ?: continue

                // Sold out is not a figure worth multiplying by: it would zero a row for an
                // item that is perfectly tradeable again tomorrow.
                if (stock > 0) return stock
            }
        }
        return null
    }

    /** Menu text carries `§` formatting inline, which would break every match above. */
    private fun strip(text: String): String = text.replace(FORMATTING, "")

    private val FORMATTING = Regex("§[0-9a-fk-orA-FK-OR]")
}
