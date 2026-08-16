package dev.syqs.skyquant.config

import dev.syqs.skyquant.SkyQuantMod
import java.io.File

/**
 * Carries settings across the rename from Hyblock to SkyQuant.
 *
 * Every file the mod writes is named after [SkyQuantMod.MOD_ID] - `config/hyblock.json` for the
 * settings, `config/hyblock_watchlist.json` and friends for the rest. Changing the id points all
 * of them at names that don't exist yet, so without this a player who updates finds their theme,
 * their HUD layout, their tracked items and their pinned rows all silently back at the defaults.
 * Nothing is lost from disk, but everything looks lost, which amounts to the same thing.
 *
 * Runs once before anything reads a config file, and does nothing at all on a fresh install or on
 * any later launch - the old names are gone after the first pass.
 */
object LegacyConfigFiles {

    private const val LEGACY_ID = "hyblock"

    /**
     * Renames every `hyblock*.json` to its `skyquant*.json` counterpart.
     *
     * Deliberately never overwrites: if a SkyQuant file already exists, it is the newer of the two
     * and the legacy copy is left alone rather than clobbering settings the player has since
     * changed. That case arises when someone runs the old build once more after updating.
     *
     * [configDir] defaults to the folder the game uses and is a parameter only so tests can point
     * it somewhere disposable - `File("config")` resolves against the working directory fixed when
     * the JVM started, which a test cannot move afterwards.
     */
    fun migrate(configDir: File = File("config")) {
        if (!configDir.isDirectory) return

        // listFiles returns null if the directory vanishes between the check and the call.
        val legacy = configDir.listFiles { file ->
            file.isFile && file.name.startsWith(LEGACY_ID) && file.name.endsWith(".json")
        } ?: return

        if (legacy.isEmpty()) return

        var moved = 0

        for (file in legacy) {
            // Only the leading id is replaced: "hyblock_watchlist.json" becomes
            // "skyquant_watchlist.json", and a hypothetical "hyblock_hyblock.json" keeps its
            // second word rather than having every occurrence rewritten.
            val target = File(configDir, SkyQuantMod.MOD_ID + file.name.removePrefix(LEGACY_ID))

            if (target.exists()) {
                SkyQuantMod.LOGGER.info("Keeping existing {} rather than overwriting it", target.name)
                continue
            }

            if (file.renameTo(target)) {
                moved++
            } else {
                // Worth a warning rather than silence: the player is about to see defaults, and
                // this line is the only thing that explains why.
                SkyQuantMod.LOGGER.warn("Could not carry {} over to {}", file.name, target.name)
            }
        }

        if (moved > 0) {
            SkyQuantMod.LOGGER.info("Carried {} config file(s) over from Hyblock to SkyQuant", moved)
        }
    }
}
