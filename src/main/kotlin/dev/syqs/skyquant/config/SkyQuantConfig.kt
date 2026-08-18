package dev.syqs.skyquant.config

import com.google.gson.annotations.Expose
import dev.syqs.skyquant.feature.bazaar.data.BazaarTax
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.hud.HudEditorScreen
import dev.syqs.skyquant.feature.pickaxe.ReminderSound
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Accordion
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.common.text.StructuredText
import org.lwjgl.glfw.GLFW

class SkyQuantConfig : Config() {
    override fun getTitle(): StructuredText = StructuredText.of("§bSkyQuant")

    // Declared first so MoulConfig (which always opens on the first-declared category, it has
    // no dedicated landing screen) opens here instead of on an arbitrary feature category.
    @Expose
    @Category(name = "About", desc = "Information about SkyQuant.")
    @JvmField
    var about: AboutCategory = AboutCategory()

    class AboutCategory {
        @Expose
        @ConfigOption(
            name = "Theme",
            desc = "Colours used by every SkyQuant screen and overlay.\n" +
                "§7Red-Green§r covers ~99% of colour blindness; §7Blue-Yellow§r covers the rarer kind.",
        )
        @ConfigEditorDropdown
        @JvmField
        var theme: ThemeChoice = ThemeChoice.DARK

        @ConfigOption(
            name = "HUD Position",
            desc = "Move and resize every overlay at once: drag to move, scroll to resize.",
        )
        @ConfigEditorButton(buttonText = "Edit")
        @JvmField
        var editHud: Runnable = Runnable { HudEditorScreen.open() }
    }

    /**
     * Named for what the player is choosing rather than for the colours involved, so the list
     * still makes sense to someone who can't see the difference the names describe.
     */
    enum class ThemeChoice(private val label: String, val theme: Palette.Theme) {
        DARK("Dark", Palette.Theme.DARK),
        RED_GREEN("Red-Green Friendly", Palette.Theme.RED_GREEN),
        BLUE_YELLOW("Blue-Yellow Friendly", Palette.Theme.BLUE_YELLOW),
        HIGH_CONTRAST("High Contrast", Palette.Theme.HIGH_CONTRAST),
        ;

        override fun toString(): String = label
    }

    @Expose
    @Category(name = "Rift", desc = "Features for the Rift area.")
    @JvmField
    var rift: RiftCategory = RiftCategory()

    @Expose
    @Category(name = "Mining", desc = "Features for mining.")
    @JvmField
    var mining: MiningCategory = MiningCategory()

    @Expose
    @Category(name = "Bazaar", desc = "Price graphs and bazaar tools.")
    @JvmField
    var bazaar: BazaarCategory = BazaarCategory()

    class BazaarCategory {
        @Expose
        @ConfigOption(
            name = "Graph Shortcut",
            desc = "Key that opens the price graph for the item under the cursor, in any inventory.",
        )
        @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_G)
        @JvmField
        var graphKey: Int = GLFW.GLFW_KEY_G

        @Expose
        @ConfigOption(
            name = "Shortcut Hint",
            desc = "Show a reminder of the shortcut key while pointing at a bazaar item.",
        )
        @ConfigEditorBoolean
        @JvmField
        var showShortcutHint: Boolean = true

        @Expose
        @ConfigOption(
            name = "Log Price Graph Checks",
            desc = "Write to the log why the Price graph button did not appear. " +
                "Only useful when reporting that it is missing.",
        )
        @ConfigEditorBoolean
        @JvmField
        var logGraphButtonChecks: Boolean = false

        @Expose
        @ConfigOption(
            name = "Log Menu Contents",
            desc = "Write every open menu's items and lore to the log, once per menu. " +
                "For working out how to recognise a Hypixel screen.",
        )
        @ConfigEditorBoolean
        @JvmField
        var logMenuSurvey: Boolean = false

        @Expose
        @ConfigOption(
            name = "Log Tab List On F9",
            desc = "Write the whole tab list to the log when F9 is pressed. " +
                "For working out how to read a Hypixel widget.",
        )
        @ConfigEditorBoolean
        @JvmField
        var logTabListSurvey: Boolean = false

        @Expose
        @ConfigOption(
            name = "Bazaar Home",
            desc = "Key that opens the market terminal with your watchlist. Also /skyquant.",
        )
        @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_B)
        @JvmField
        var homeKey: Int = GLFW.GLFW_KEY_B

        @Expose
        @ConfigOption(
            name = "Bazaar Tax",
            desc = "The cut taken off every sale, which your Bazaar Flipper level lowers.\n" +
                "§7Detect automatically§r reads it from the Community Shop next time you open it, " +
                "and assumes 1.25% until then.",
        )
        @ConfigEditorDropdown
        @JvmField
        var taxOverride: BazaarTax.TaxOverride = BazaarTax.TaxOverride.AUTOMATIC

        @Expose
        @ConfigOption(
            name = "Mayor Diaz Elected",
            desc = "Diaz's §7Shopping Spree§r raises daily NPC shop limits tenfold, from 640 to 6,400.\n" +
                "§7Turn this on while he is in office so the NPC totals match what you can buy.",
        )
        @ConfigEditorBoolean
        @JvmField
        var shoppingSpree: Boolean = false

        @Expose
        @Accordion
        @ConfigOption(name = "Price Overlay", desc = "Live prices for the items you pinned.")
        @JvmField
        var overlay: OverlayCategory = OverlayCategory()
    }

    class OverlayCategory {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show pinned items on screen while playing.")
        @ConfigEditorBoolean
        @JvmField
        var enabled: Boolean = true

        @Expose
        @ConfigOption(name = "When To Show", desc = "Where the overlay stays visible.")
        @ConfigEditorDropdown
        @JvmField
        var visibility: OverlayVisibility = OverlayVisibility.HIDE_IN_SCREENS

        @Expose
        @ConfigOption(
            name = "Toggle Key",
            desc = "Hides or shows the overlay on the spot. Works with any visibility mode.",
        )
        @ConfigEditorKeybind(defaultKey = GLFW.GLFW_KEY_UNKNOWN)
        @JvmField
        var toggleKey: Int = GLFW.GLFW_KEY_UNKNOWN

        @Expose
        @ConfigOption(name = "Trend Line", desc = "Draw a small price curve beside each item.")
        @ConfigEditorBoolean
        @JvmField
        var showSparkline: Boolean = true

        @Expose
        @ConfigOption(name = "Spread Bar", desc = "Draw a bar showing how wide each spread is.")
        @ConfigEditorBoolean
        @JvmField
        var showSpreadBar: Boolean = true

        @Expose
        @ConfigOption(name = "Item Icons", desc = "Draw each item's icon beside its name.")
        @ConfigEditorBoolean
        @JvmField
        var showIcons: Boolean = true

        // Position and scale live in HudRegistry rather than here: they're set by dragging in
        // the HUD editor, and a coordinate pair in the settings list is not something anyone
        // edits by hand.
    }

    enum class OverlayVisibility(private val label: String) {
        ALWAYS("Always"),
        HIDE_IN_SCREENS("Only while playing"),
        INVENTORY_ONLY("Only with a menu open"),
        ;

        override fun toString(): String = label
    }

    class MiningCategory {
        @Expose
        @Category(name = "Pickaxe Ability", desc = "Reminder for your pickaxe's ability cooldown.")
        @JvmField
        var pickaxeAbility: PickaxeCategory = PickaxeCategory()
    }

    class PickaxeCategory {
        @Expose
        @ConfigOption(
            name = "Ability Reminder",
            desc = "Notifies you when your held pickaxe's ability is off cooldown again.",
        )
        @ConfigEditorBoolean
        @JvmField
        var abilityReminder: Boolean = true

        @Expose
        @Accordion
        @ConfigOption(
            name = "Notification",
            desc = "How to notify you. Use {ability} in the text for the pickaxe's ability name.",
        )
        @JvmField
        var reminder: ReminderSettings = ReminderSettings()
    }

    class RiftCategory {
        @Expose
        @Category(name = "Ubik's Cube", desc = "Reminder for Ubik's Cube in the Rift.")
        @JvmField
        var ubikCube: UbikCubeCategory = UbikCubeCategory()
    }

    class UbikCubeCategory {
        @Expose
        @ConfigOption(
            name = "Reminder",
            desc = "Notifies you when Ubik's Cube is available again after Split or Steal.",
        )
        @ConfigEditorBoolean
        @JvmField
        var reminder: Boolean = true

        @Expose
        @Accordion
        @ConfigOption(name = "Notification", desc = "How to notify you when it's ready.")
        @JvmField
        var notification: ReminderSettings = ReminderSettings()
    }
}
