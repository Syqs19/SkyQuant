package dev.syqs.skyquant.config

import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.gui.WideSliderOptionEditor
import dev.syqs.skyquant.feature.bazaar.BazaarGraphCommand
import dev.syqs.skyquant.gui.Palette
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import io.github.notenoughupdates.moulconfig.gui.GuiContext
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.io.File

/**
 * Loads/saves [SkyQuantConfig] to `config/skyquant.json` and builds the config screen.
 *
 * Uses MoulConfig's [ManagedConfig], which handles reading/writing the file and turning
 * the annotated fields in [SkyQuantConfig] into GUI widgets automatically.
 */
object SkyQuantConfigManager {

    /** Two letters, matching the SQ monogram, for a command typed mid-game with one hand. */
    private const val SHORT_COMMAND = "sq"

    private val file = File("config/${SkyQuantMod.MOD_ID}.json")

    private val managedConfig = ManagedConfig.create(
        file,
        SkyQuantConfig::class.java,
    ) {
        // Overrides the built-in slider editor: its number field is too narrow to show
        // decimals (see WideSliderComponent).
        customProcessor<ConfigEditorSlider> { option, annotation ->
            WideSliderOptionEditor(option, annotation.minValue, annotation.maxValue, annotation.minStep)
        }
    }

    val config: SkyQuantConfig get() = managedConfig.instance

    fun register() {
        // Files first, keys second, and in that order: the rename from Hyblock moves
        // config/hyblock.json to config/skyquant.json, and [ConfigMigration] below reads the file
        // at its new name. Swapped round, the first launch after the rename would migrate an
        // empty file and then load settings that had never been touched.
        LegacyConfigFiles.migrate()

        // Renamed keys are moved before the file is parsed, since Gson drops what it doesn't
        // recognise and the setting would come back as its default instead.
        ConfigMigration.migrate(file)

        // Read before anything can touch the settings. ManagedConfig starts on the defaults and
        // only loads when asked, so without this every setting silently reverted on restart -
        // and the first save then wrote the defaults back over the file.
        runCatching { managedConfig.reloadFromFile() }
            .onFailure { SkyQuantMod.LOGGER.warn("Failed to read SkyQuant config, using defaults", it) }

        applyTheme()

        // Backstop for anything that changes settings outside the config screen, or a screen
        // dismissed in a way that skips onClose.
        ClientLifecycleEvents.CLIENT_STOPPING.register { save() }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Registered twice rather than aliased through redirect(): Brigadier's redirect makes
            // the alias expect a *subcommand* before it will run, so `/sq` on its own would print
            // a usage error while only `/sq bazaar` worked - the opposite of what a shortcut is
            // for. Two full trees cost a few objects and behave identically.
            dispatcher.register(commandTree("skyquant"))
            dispatcher.register(commandTree(SHORT_COMMAND))
        }
    }

    /**
     * The command tree, built fresh per name.
     *
     * Not built once and reused: Brigadier nodes carry their parent, so registering the same
     * builder under two names entangles the two trees.
     */
    private fun commandTree(name: String) =
        literal<FabricClientCommandSource>(name)
            .executes {
                // Deferred to the next client tick: submitting a chat command closes the
                // chat screen right after this callback runs, which would immediately
                // overwrite/close the config screen if opened synchronously here.
                Minecraft.getInstance().execute {
                    Minecraft.getInstance().setScreen(buildScreen(null))
                }
                0
            }
            // Grafted here rather than registered on its own: Brigadier keeps only the
            // last registration of a literal, so a separate `/skyquant` would replace
            // this one instead of adding to it.
            .then(BazaarGraphCommand.bazaarBranch("bazaar"))

    /** Builds a fresh [Screen] wrapping the MoulConfig editor, for Mod Menu or the `/skyquant` command. */
    fun buildScreen(previousScreen: Screen?): Screen {
        val editor: MoulConfigEditor<SkyQuantConfig> = managedConfig.getEditor()
        return object : MoulConfigScreenComponent(
            Component.empty(),
            GuiContext(GuiElementComponent(editor)),
            previousScreen,
        ) {
            // MoulConfig edits the live instance but never writes it out on its own, so without
            // this every change is lost the moment the game closes.
            override fun onClose() {
                save()
                super.onClose()
            }
        }
    }

    /** Writes the current settings to `config/skyquant.json`. */
    fun save() {
        runCatching { managedConfig.saveToFile() }
            .onFailure { SkyQuantMod.LOGGER.warn("Failed to save SkyQuant config", it) }

        // The dropdown writes to the config instance and tells nobody, so the palette would keep
        // the old theme until the next restart without this.
        applyTheme()
    }

    /** Points the palette at the chosen theme, so every screen redraws in it. */
    fun applyTheme() {
        Palette.theme = config.about.theme.theme
    }
}
