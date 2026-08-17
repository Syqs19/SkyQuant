package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.gui.BazaarGraphScreen
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.util.Hotkey
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component

/**
 * Opens the price graph for whatever bazaar item the cursor is over, on a key press.
 *
 * Works in any container screen rather than only Hypixel's bazaar menu: the item id travels
 * with the item itself, so this covers the player's own inventory, storage and the auction
 * house for free, and doesn't break when Hypixel renames or reshapes a menu.
 */
object BazaarGraphShortcut {

    private const val HINT_BOTTOM_MARGIN = 12

    private val config get() = SkyQuantConfigManager.config.bazaar

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register

            ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
                if (event.key() != config.graphKey) return@register true

                val productId = hoveredProduct(screen) ?: return@register true
                // Hands the container back on close: the inventory or bazaar menu stays open
                // underneath instead of having to be reopened every time.
                Minecraft.getInstance().setScreen(BazaarGraphScreen(productId, screen))
                // Swallow the press so it doesn't also reach the screen underneath.
                false
            }

            // Confirms the hook is attached at all, once per screen. Without this a missing
            // button has two possible explanations that look identical from outside - the checks
            // rejected it, or this callback never runs - and only one of them leaves a trace.
            BazaarGraphButton.noteScreenOpened(screen)

            ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, _ ->
                // From the draw rather than from init: the container arrives empty and is filled a
                // moment later, so a survey taken on open would report nothing. It logs once per
                // menu and is off by default.
                BazaarGraphButton.surveyMenu(screen)
                drawHint(screen, graphics)
                BazaarGraphButton.render(screen, graphics, mouseX, mouseY)
            }

            // Intercepted before the screen sees it: a click that reaches the container is
            // relayed to Hypixel, and this is a menu where stray clicks spend coins.
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                !BazaarGraphButton.onClick(screen, event.x(), event.y())
            }
        }
    }

    /**
     * Footer hint over the container, shown only while pointing at something chartable: the
     * shortcut is otherwise invisible, and a permanent label would be noise on every other item.
     */
    private fun drawHint(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor) {
        if (!config.showShortcutHint) return
        if (hoveredProduct(screen) == null) return

        val minecraft = Minecraft.getInstance()
        val text = Component.literal("[${keyName()}] Price graph").withStyle(ChatFormatting.YELLOW)

        graphics.centeredText(
            minecraft.font,
            text,
            screen.width / 2,
            screen.height - HINT_BOTTOM_MARGIN,
            Palette.TEXT,
        )
    }

    private fun keyName(): String = Hotkey.nameOf(config.graphKey)

    /**
     * The Skyblock id under the cursor, whatever it is.
     *
     * `of`, not `bazaarProductOf`: the latter drops anything the bazaar doesn't trade, which
     * silently turned the key off over every weapon, tool and forge output - the items whose
     * price is hardest to know and most worth charting. The graph screen decides what it can
     * show; this only has to answer "is the cursor on a Skyblock item".
     */
    private fun hoveredProduct(screen: AbstractContainerScreen<*>): String? {
        val stack = screen.hoveredSlot?.item ?: return null
        return SkyblockItemId.of(stack)
    }

}
