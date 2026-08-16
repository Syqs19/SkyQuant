package dev.syqs.skyquant.feature.pickaxe

import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.reminder.ReminderTicker
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component

/**
 * Notifies the player when their held pickaxe's ability is off cooldown again.
 *
 * Hypixel sends its own spontaneous chat message once the cooldown actually ends
 * ("<Ability> is now available!"), so this just listens for it directly instead of
 * estimating/tracking the cooldown duration client-side. To avoid false positives from
 * that generic-looking message (Hypixel could plausibly reuse similar wording elsewhere,
 * e.g. minions/pets), it's only armed after a genuine pickaxe ability use is observed.
 */
object PickaxeAbilityReminder {

    private val useMessageRegex = Regex("""You used your .+? Pickaxe Ability!""")
    private val availableRegex = Regex("""(?<name>.+?) is now available!""")

    private var armed = false

    private val reminder = ReminderTicker.create(
        defaultTitle = ABILITY_PLACEHOLDER,
        defaultSubtitle = "Ability is off cooldown!",
        defaultChat = "$ABILITY_PLACEHOLDER is off cooldown!",
    ) { SkyQuantConfigManager.config.mining.pickaxeAbility.reminder }

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) onGameMessage(message)
        }
    }

    private fun onGameMessage(message: Component) {
        if (!SkyQuantConfigManager.config.mining.pickaxeAbility.abilityReminder) {
            armed = false
            return
        }

        if (useMessageRegex.containsMatchIn(message.string)) {
            armed = true
            return
        }

        if (!armed) return
        val match = availableRegex.find(message.string) ?: return
        armed = false
        reminder.fire(ABILITY_PLACEHOLDER to match.groups["name"]!!.value)
    }

    /** Stands in for the held pickaxe's ability name in any of the custom texts. */
    private const val ABILITY_PLACEHOLDER = "{ability}"
}
