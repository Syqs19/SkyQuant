package dev.syqs.skyquant

/**
 * Who the mod's data comes from, and the wording used to say so.
 *
 * Kept in one place because the same credit has to appear in more than one spot - the terminal's
 * title bar, the graph screen, the mod's own description - and three copies of a string that is
 * a licence condition is three chances for one of them to drift.
 *
 * These are not decorative. Coflnet's terms of use require attribution wherever their data is
 * shown ("please attribute us as the data source"), and the NEU repository is MIT, which requires
 * the copyright notice to travel with any redistribution - and the Craft and Forge pages are
 * built entirely from it. Removing these lines would put the mod in breach of both.
 */
object DataCredits {

    /**
     * The one-line form, for a title bar where a full sentence would not fit.
     *
     * Names the two third-party sources rather than all four: Hypixel is the game itself, which
     * nobody reads as an outside credit, while Coflnet and NEU are the ones whose terms ask for
     * the mention.
     */
    const val SHORT = "data · coflnet + neu"

    /**
     * Where a player can go to check the figures for themselves, as Coflnet's terms ask a credit
     * to point.
     *
     * Not rendered in-game - a Minecraft screen has nothing to click a URL with - but stated in
     * full in `fabric.mod.json`, which Mod Menu shows and which travels with the jar. That is the
     * long-form attribution; [SHORT] is what appears over the data itself.
     */
    const val COFLNET_URL = "https://sky.coflnet.com/data"
}
