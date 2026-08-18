package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.util.Hotkey
import dev.syqs.skyquant.util.escapeNonAscii
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Dumps the whole tab list to the log on a key press, for working out how to read a widget.
 *
 * The counterpart to [BazaarGraphButton.surveyMenu], and here for the same reason: the forge
 * state is meant to be read out of Hypixel's `Forges:` widget, and the formats that reading
 * assumes are currently written down as expectations rather than observations. Guessing at
 * Hypixel's wording is what cost five attempts on the price graph button, so this settles the
 * wording before a parser is written against it.
 *
 * On a key rather than automatically: a tab list of a hundred players is far too much to write
 * every time it changes, and the useful moment is one the player picks - standing on the forge
 * island with slots running.
 */
object TabListSurvey {

    /** F9: unbound in vanilla, and not one of the mod's own keys. */
    private const val SURVEY_KEY = GLFW.GLFW_KEY_F9

    private val hotkey = Hotkey(key = { SURVEY_KEY }) { dump() }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            hotkey.tick(
                minecraft,
                // Only while playing and only when asked for: with a screen open the key belongs
                // to that screen. The tab list itself needs no screen - it is read from the
                // connection, not from what is drawn - so the player doesn't have to hold Tab.
                enabled = SkyQuantConfigManager.config.bazaar.logTabListSurvey &&
                    minecraft.screen == null &&
                    minecraft.player != null,
            )
        }
    }

    private fun dump() {
        val connection = Minecraft.getInstance().connection ?: return

        // Ordered the way the game orders the tab list itself - by `tabListOrder`, then by name.
        // Hypixel builds its widgets out of consecutive entries, so an arbitrary order would
        // scatter the very block this exists to read.
        val entries = connection.listedOnlinePlayers
            .sortedWith(compareBy({ it.tabListOrder }, { it.profile.name }))

        SkyQuantMod.LOGGER.info("=== SkyQuant tab list survey === {} entries", entries.size)

        for ((index, info) in entries.withIndex()) {
            // The widget text lives in the display name; the profile name is the account behind
            // the slot, which for a widget line is a placeholder rather than a player.
            val shown = info.tabListDisplayName?.string
            SkyQuantMod.LOGGER.info(
                "  [{}] order={} profile='{}' shown='{}' escaped='{}'",
                index,
                info.tabListOrder,
                info.profile.name,
                shown,
                shown?.escapeNonAscii(),
            )
        }

        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal(
                "§e[SkyQuant] §7tab list: §f${entries.size} entries §7written to the log",
            ),
        )
    }
}
