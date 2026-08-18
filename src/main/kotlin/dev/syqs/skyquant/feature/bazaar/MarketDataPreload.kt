package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import dev.syqs.skyquant.feature.bazaar.data.ItemIconIndex
import dev.syqs.skyquant.feature.bazaar.data.NpcSellPrices
import dev.syqs.skyquant.feature.bazaar.data.NpcShopPrices
import dev.syqs.skyquant.feature.bazaar.data.RecipeIndex
import dev.syqs.skyquant.feature.bazaar.gui.ItemIcon
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Starts fetching market data once the player is in a world, rather than when they open the
 * terminal.
 *
 * Everything here was previously kicked off from the terminal's first frame, which meant the
 * player opened it and then waited: 1.5 MB of bazaar prices and, on a cold cache, 4.9 MB of item
 * catalogue, all beginning at the moment they wanted to read the figures. Starting on join spends
 * that time while they are walking around instead, so the terminal usually opens on data that has
 * already landed.
 *
 * The terminal still asks for all of this on its own frames. Every call here is idempotent and
 * self-throttling, so the two paths cannot fight: whichever runs first does the work and the other
 * returns immediately.
 *
 * Driven from the client tick rather than a join event, because that is the hook the rest of the
 * mod already uses - adding `fabric-networking-api-v1` for one callback would grow the dependency
 * list for no behaviour the tick can't provide.
 */
object MarketDataPreload {

    /**
     * Whether this world has already been warmed.
     *
     * Reset when the player leaves, so rejoining a server after a long session refreshes rather
     * than trusting figures from before. The bazaar snapshot expires on its own, but the caches
     * only re-check when asked.
     */
    private var warmed = false

    fun register() {
        // Built icons are derived from the index, so they have to go when it is replaced. Wired
        // here rather than in the mod's initialiser because it is a detail of how these two
        // stores relate, not something the rest of the mod has any reason to know about.
        ItemIconIndex.onReplaced = ItemIcon::clearCache

        // The other reason a built icon goes stale: Hypixel's textures arrive as a server pack,
        // so whether a model exists depends on what is mounted right now.
        ItemIcon.register()

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            val inWorld = minecraft.player != null

            if (!inWorld) {
                warmed = false
                return@register
            }

            if (warmed) return@register
            warmed = true

            SkyQuantMod.LOGGER.debug("In a world, warming market data")

            // The bazaar snapshot: what every screen and the HUD read from, and the only thing
            // here that expires - it keeps itself fresh on a 60s cycle once started.
            BazaarLivePrices.refreshIfStale()

            // Both read their disk cache first and only reach the network when it is missing or
            // stale, so on a warm cache this costs a file read and nothing more.
            NpcSellPrices.loadOnce()
            NpcShopPrices.refresh()

            // The recipe index behind the Craft and Forge pages, which also fills the item
            // icons on its way through the archive. Cached with an etag, so an unchanged NEU
            // repo answers in a few hundred bytes.
            RecipeIndex.refresh()
        }
    }
}
