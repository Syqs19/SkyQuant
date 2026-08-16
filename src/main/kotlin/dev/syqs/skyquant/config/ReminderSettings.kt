package dev.syqs.skyquant.config

import com.google.gson.annotations.Expose
import dev.syqs.skyquant.feature.pickaxe.ReminderSound
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import net.minecraft.client.Minecraft

/**
 * The settings every reminder has: which notifications to show, how they sound, and what they
 * say.
 *
 * One class rather than a copy per feature. Reminders differ in *when* they fire, which is
 * feature-specific, but not in *how* they notify - so a new reminder declares one field of this
 * type and inherits the whole settings tree, instead of duplicating four classes that then drift
 * apart as one gets an option the others quietly lack.
 */
class ReminderSettings {

    @Expose
    @Accordion
    @ConfigOption(name = "Style", desc = "Which notifications to show.")
    @JvmField
    var style: Style = Style()

    @Expose
    @Accordion
    @ConfigOption(name = "Sound & Title", desc = "Fine-tune the sound and how long the title stays.")
    @JvmField
    var presentation: Presentation = Presentation()

    @Expose
    @Accordion
    @ConfigOption(name = "Custom Text", desc = "Write your own wording. Leave a field blank for the default.")
    @JvmField
    var text: Text = Text()

    class Style {
        @Expose
        @ConfigOption(name = "Chat Message", desc = "Show a message in chat.")
        @ConfigEditorBoolean
        @JvmField
        var chatMessage: Boolean = true

        @Expose
        @ConfigOption(name = "Toast Popup", desc = "Show a toast popup in the top right corner.")
        @ConfigEditorBoolean
        @JvmField
        var toast: Boolean = true

        @Expose
        @ConfigOption(name = "Title", desc = "Show a large title in the center of the screen.")
        @ConfigEditorBoolean
        @JvmField
        var title: Boolean = false

        @Expose
        @ConfigOption(name = "Sound", desc = "Play a sound.")
        @ConfigEditorBoolean
        @JvmField
        var sound: Boolean = true
    }

    class Presentation {
        @Expose
        @ConfigOption(name = "Sound", desc = "Which sound to play.")
        @ConfigEditorDropdown
        @JvmField
        var sound: ReminderSound = ReminderSound.EXPERIENCE_ORB

        @Expose
        @ConfigOption(name = "Pitch", desc = "Pitch of the sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2.0f, minStep = 0.05f)
        @JvmField
        var pitch: Float = 1.4f

        // Not @Expose: a button is an action, not a stored setting.
        @ConfigOption(name = "Test Sound", desc = "Play the selected sound at the selected pitch.")
        @ConfigEditorButton(buttonText = "Test")
        @JvmField
        var testSound: Runnable = Runnable { playTestSound() }

        @Expose
        @ConfigOption(name = "Title Duration", desc = "How long the title stays on screen, in seconds.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 5.0f, minStep = 0.1f)
        @JvmField
        var titleDurationSeconds: Float = 1.5f

        /** Previews this block's own sound, so the button matches the sliders beside it. */
        private fun playTestSound() {
            Minecraft.getInstance().player?.playSound(sound.event, 0.6f, pitch)
        }
    }

    /**
     * Wording overrides. Blank means "use the reminder's own default", so clearing a field undoes
     * a custom message rather than leaving an empty title on screen.
     *
     * The defaults deliberately aren't stored here: Gson builds this without calling the
     * constructor when loading the file, so anything set in a constructor would be lost on
     * restart. Each reminder passes its own defaults when it fires.
     */
    class Text {
        @Expose
        @ConfigOption(name = "Title", desc = "Large text on screen.")
        @ConfigEditorText
        @JvmField
        var title: String = ""

        @Expose
        @ConfigOption(name = "Subtitle", desc = "Smaller line under the title.")
        @ConfigEditorText
        @JvmField
        var subtitle: String = ""

        @Expose
        @ConfigOption(name = "Chat Message", desc = "Line sent to chat.")
        @ConfigEditorText
        @JvmField
        var chat: String = ""
    }
}
