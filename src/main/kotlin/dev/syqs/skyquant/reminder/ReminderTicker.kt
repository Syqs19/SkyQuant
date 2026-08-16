package dev.syqs.skyquant.reminder

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Ticks every [Reminder] so titles clear at their configured duration.
 *
 * Central so a feature never has to remember to tick its own reminder - forgetting would leave
 * the title on screen for vanilla's full duration instead of the configured one, which is the
 * kind of omission that only shows up when someone happens to look.
 */
object ReminderTicker {

    private val reminders = mutableListOf<Reminder>()

    /** Builds a reminder and keeps it ticking. Features should create theirs through here. */
    fun create(
        defaultTitle: String,
        defaultSubtitle: String,
        defaultChat: String,
        settings: () -> dev.syqs.skyquant.config.ReminderSettings,
    ): Reminder = Reminder(defaultTitle, defaultSubtitle, defaultChat, settings).also { reminders.add(it) }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            for (reminder in reminders) reminder.tick()
        }
    }
}
