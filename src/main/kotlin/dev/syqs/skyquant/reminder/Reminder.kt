package dev.syqs.skyquant.reminder

import dev.syqs.skyquant.config.ReminderSettings
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Fires one kind of reminder: chat line, toast, title and sound, per its [settings].
 *
 * Owns the notifying so features only decide *when* to fire. Each reminder previously carried its
 * own copy of this, which is how the pickaxe one ended up with a configurable sound and title
 * duration that Ubik's Cube silently lacked.
 *
 * [defaultTitle], [defaultSubtitle] and [defaultChat] are what the player's custom text falls back
 * to when left blank. They're passed here rather than stored in the settings because Gson skips
 * constructors when loading, so a default written into the settings class wouldn't survive a
 * restart.
 */
class Reminder(
    private val defaultTitle: String,
    private val defaultSubtitle: String,
    private val defaultChat: String,
    private val settings: () -> ReminderSettings,
) {

    private val toastId = SystemToast.SystemToastId()

    /** Set while a title is showing, so it can be cleared at the configured duration. */
    private var titleClearAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Shows the reminder. [placeholders] are substituted into every text, letting a reminder
     * carry a value it only knows at that moment - the pickaxe's ability name, say.
     */
    fun fire(vararg placeholders: Pair<String, String>) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return

        val config = settings()
        val style = config.style

        fun resolve(custom: String, fallback: String): String =
            placeholders.fold(custom.ifBlank { fallback }) { text, (key, value) ->
                text.replace(key, value)
            }

        val title = resolve(config.text.title, defaultTitle)
        val subtitle = resolve(config.text.subtitle, defaultSubtitle)
        val chat = resolve(config.text.chat, defaultChat)

        if (style.chatMessage) {
            player.sendSystemMessage(
                Component.literal("[SkyQuant] ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(chat).withStyle(ChatFormatting.GREEN)),
            )
        }

        if (style.toast) {
            SystemToast.add(minecraft.toastManager, toastId, Component.literal(title), Component.literal(subtitle))
        }

        if (style.title) {
            minecraft.gui.resetTitleTimes()
            minecraft.gui.setSubtitle(Component.literal(subtitle).withStyle(ChatFormatting.GREEN))
            minecraft.gui.setTitle(Component.literal(title).withStyle(ChatFormatting.GOLD))
            // Gui has no public API for custom fade/stay durations, so the default title is
            // force-cleared early at the configured duration instead.
            titleClearAt = TimeSource.Monotonic.markNow() +
                config.presentation.titleDurationSeconds.toDouble().seconds
        }

        if (style.sound) {
            player.playSound(config.presentation.sound.event, 0.6f, config.presentation.pitch)
        }
    }

    /** Called every tick by [ReminderTicker] to clear a title once its duration has elapsed. */
    internal fun tick() {
        val clearAt = titleClearAt ?: return
        if (clearAt.elapsedNow() < Duration.ZERO) return

        titleClearAt = null
        Minecraft.getInstance().gui.clearTitles()
    }
}
