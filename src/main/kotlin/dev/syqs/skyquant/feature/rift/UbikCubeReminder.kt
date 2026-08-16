package dev.syqs.skyquant.feature.rift

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.reminder.ReminderTicker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Notifies the player when Ubik's Cube is available again in the Rift.
 *
 * The timer starts the moment a Split or Steal match ends ("Your opponent earned
 * ... Motes in this match!"), using the known fixed 2-hour cooldown - no need to
 * reclick the cube to find out when it'll be ready. If the player later does
 * reclick it while still on cooldown, Hypixel's exact residual-time message
 * ("You need to wait 1h 23m 45s before you can play again.") overrides the
 * estimate with the server's real value, in case the two ever drift apart.
 *
 * The target time is persisted to disk ([UbikCubeReminderState]) so it survives
 * client restarts, and is re-checked on login in case it already elapsed while
 * the client was closed.
 */
object UbikCubeReminder {

    private val reminder = ReminderTicker.create(
        defaultTitle = "Ubik's Cube",
        defaultSubtitle = "Available again in the Rift!",
        defaultChat = "Ubik's Cube is available again in the Rift!",
    ) { SkyQuantConfigManager.config.rift.ubikCube.notification }

    private val matchEndRegex = Regex("""Your opponent earned [\d,]+ Motes in this match!""")
    private val cooldownRegex = Regex("""You need to wait (?<duration>.+?) before you can play again\.""")
    private val durationPartRegex = Regex("""(\d+)\s*([hms])""")
    private val cooldown = 2.hours

    private var readyAt: Long? = UbikCubeReminderState.load()
    private var reminderPending = readyAt != null

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) onGameMessage(message)
        }

        ClientTickEvents.END_CLIENT_TICK.register { checkReminder() }
    }

    private fun onGameMessage(message: Component) {
        if (!SkyQuantConfigManager.config.rift.ubikCube.reminder) return

        if (matchEndRegex.containsMatchIn(message.string)) {
            setReadyAt(System.currentTimeMillis() + cooldown.inWholeMilliseconds)
            SkyQuantMod.LOGGER.info("Ubik's Cube: match ended, next use in {}", cooldown)
            return
        }

        val match = cooldownRegex.find(message.string) ?: return
        val duration = parseDuration(match.groups["duration"]!!.value) ?: return

        setReadyAt(System.currentTimeMillis() + duration.inWholeMilliseconds)
        SkyQuantMod.LOGGER.info("Ubik's Cube: resynced from cooldown message, next use in {}", duration)
    }

    private fun setReadyAt(atMillis: Long) {
        readyAt = atMillis
        reminderPending = true
        UbikCubeReminderState.save(atMillis)
    }

    private fun checkReminder() {
        if (!reminderPending) return
        val target = readyAt ?: return
        // Waits for the player to exist: firing during loading would notify into nothing.
        Minecraft.getInstance().player ?: return

        if (System.currentTimeMillis() >= target) {
            reminderPending = false
            UbikCubeReminderState.clear()
            if (SkyQuantConfigManager.config.rift.ubikCube.reminder) reminder.fire()
        }
    }

    private fun parseDuration(text: String): Duration? {
        var total = Duration.ZERO
        var found = false

        for (part in durationPartRegex.findAll(text)) {
            found = true
            val value = part.groupValues[1].toLong()
            total += when (part.groupValues[2]) {
                "h" -> value.hours
                "m" -> value.minutes
                "s" -> value.seconds
                else -> Duration.ZERO
            }
        }

        return total.takeIf { found }
    }
}
