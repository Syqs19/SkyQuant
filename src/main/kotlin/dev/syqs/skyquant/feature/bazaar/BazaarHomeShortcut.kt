package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.gui.BazaarHomeScreen
import dev.syqs.skyquant.util.Hotkey
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Opens the bazaar overview on a key press while playing.
 *
 * The key is polled per tick rather than registered as a vanilla [net.minecraft.client.KeyMapping]:
 * the binding already lives in the mod's own config screen, and registering a second one in the
 * vanilla controls list would give the same action two places to be rebound, which quietly
 * disagree with each other.
 */
object BazaarHomeShortcut {

    private val config get() = SkyQuantConfigManager.config.bazaar

    private val hotkey = Hotkey(key = { config.homeKey }) { BazaarHomeScreen.open() }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            // Only while actually playing: with a screen open the key belongs to that screen,
            // and typing "b" into a sign or an anvil must not open anything.
            hotkey.tick(minecraft, enabled = minecraft.screen == null && minecraft.player != null)
        }
    }
}
