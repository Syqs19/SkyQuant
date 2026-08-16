package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.gui.BazaarGraphScreen
import dev.syqs.skyquant.feature.bazaar.gui.BazaarOverlay
import dev.syqs.skyquant.hud.HudRegistry
import dev.syqs.skyquant.util.Hotkey
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/** Draws the pinned-price overlay over the game, and over container screens when configured. */
object BazaarOverlayRenderer {

    private val config get() = SkyQuantConfigManager.config.bazaar.overlay

    private val toggleHotkey = Hotkey(key = { config.toggleKey }) {
        BazaarOverlay.hidden = !BazaarOverlay.hidden
    }

    fun register() {
        HudRegistry.register(BazaarOverlay)

        // Drawn after the whole vanilla HUD so it sits above the hotbar rather than under it,
        // which is where an element added at the front ends up.
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(SkyQuantMod.MOD_ID, "bazaar_overlay"),
            HudElement { graphics, _ ->
                val minecraft = Minecraft.getInstance()
                if (!BazaarOverlay.shouldRender(minecraft)) return@HudElement

                BazaarOverlay.render(
                    graphics,
                    minecraft.font,
                    minecraft.window.guiScaledWidth,
                    minecraft.window.guiScaledHeight,
                )
            },
        )

        // Container screens draw over the HUD, so showing the overlay there needs a second hook
        // on the screen itself - the HUD element alone would be painted over.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                val minecraft = Minecraft.getInstance()
                if (minecraft.screen !== screen) return@register
                if (!BazaarOverlay.shouldRender(minecraft, fromScreen = true)) return@register

                BazaarOverlay.render(graphics, minecraft.font, screen.width, screen.height)
            }

            // A click on a row opens that item's chart. Intercepted before the screen sees it,
            // since a click reaching a Hypixel container is relayed to the server, and these
            // are menus where a stray click spends coins.
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                val minecraft = Minecraft.getInstance()
                if (!BazaarOverlay.shouldRender(minecraft, fromScreen = true)) return@register true

                val product = BazaarOverlay.productAt(
                    event.x().toInt(),
                    event.y().toInt(),
                    screen.width,
                    screen.height,
                    minecraft.font,
                ) ?: return@register true

                minecraft.setScreen(BazaarGraphScreen(product, screen))
                false
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            // Only while playing, so the key stays usable for typing in any open screen.
            toggleHotkey.tick(minecraft, enabled = minecraft.screen == null)
        }
    }
}
