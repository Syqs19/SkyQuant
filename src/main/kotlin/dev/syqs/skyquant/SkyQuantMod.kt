package dev.syqs.skyquant

import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.BazaarGraphCommand
import dev.syqs.skyquant.feature.bazaar.BazaarGraphShortcut
import dev.syqs.skyquant.feature.bazaar.BazaarHomeShortcut
import dev.syqs.skyquant.feature.bazaar.BazaarOverlayRenderer
import dev.syqs.skyquant.feature.bazaar.BazaarTaxCalibration
import dev.syqs.skyquant.feature.bazaar.MarketDataPreload
import dev.syqs.skyquant.feature.pickaxe.PickaxeAbilityReminder
import dev.syqs.skyquant.feature.rift.UbikCubeReminder
import dev.syqs.skyquant.hud.HudRegistry
import dev.syqs.skyquant.reminder.ReminderTicker
import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SkyQuantMod : ModInitializer {
    const val MOD_ID = "skyquant"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    const val VERSION = /*$ mod_version*/ "0.1.0"
    const val MINECRAFT = /*$ minecraft*/ "26.1.2"

    override fun onInitialize() {
        //? if !release
        LOGGER.info("SkyQuant {} starting on Minecraft {}", VERSION, MINECRAFT)

        SkyQuantConfigManager.register()
        // Before anything registers an overlay, so the shutdown save is in place no matter which
        // route out of the game the player takes.
        HudRegistry.register()
        ReminderTicker.register()
        UbikCubeReminder.register()
        PickaxeAbilityReminder.register()
        BazaarGraphCommand.register()
        BazaarGraphShortcut.register()
        BazaarHomeShortcut.register()
        BazaarOverlayRenderer.register()
        BazaarTaxCalibration.register()
        // Last of the bazaar registrations: it only warms data the others read, so nothing here
        // depends on it having run.
        MarketDataPreload.register()
    }
}
