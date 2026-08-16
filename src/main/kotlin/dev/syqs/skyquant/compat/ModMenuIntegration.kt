package dev.syqs.skyquant.compat

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.syqs.skyquant.config.SkyQuantConfigManager
import net.minecraft.client.gui.screens.Screen

/**
 * Optional integration: adds a "Config" button to SkyQuant's entry in Mod Menu's mod list.
 * Only used if the player has Mod Menu installed (see the "modmenu" entrypoint in
 * fabric.mod.json) - Mod Menu is not a hard dependency of this mod.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory<Screen> { previousScreen -> SkyQuantConfigManager.buildScreen(previousScreen) }
    }
}
