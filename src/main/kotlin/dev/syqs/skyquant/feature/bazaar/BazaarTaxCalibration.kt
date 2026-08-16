package dev.syqs.skyquant.feature.bazaar

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Watches for the Community Shop and learns the player's Bazaar Flipper level from it.
 *
 * Reading a menu the player opens anyway, rather than asking for an API key: the key route
 * means visiting a website and pasting a token into the config, which is a lot of ceremony for
 * a figure worth at most 0.25%.
 *
 * Every container is scanned rather than only ones whose title matches. Hypixel renames menus
 * between updates, and a title check that goes stale fails silently - it simply stops finding
 * anything - while scanning for the perk item itself costs a pass over 54 slots on the frames a
 * menu happens to be open.
 */
object BazaarTaxCalibration {

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register

            // Per frame rather than once on open: Hypixel sends the container empty and fills
            // it a moment later, so a single read on open would nearly always see nothing.
            ScreenEvents.afterExtract(screen).register { _, _, _, _, _ ->
                BazaarFlipperDetector.scan(screen)
                // Shop entries state how much of that item is left today, which is a better
                // figure than the documented daily limit for anything the player has looked at.
                NpcStockReader.scan(screen)
            }
        }
    }
}
