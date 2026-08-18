package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.JsonObject
import dev.syqs.skyquant.util.stripFormatting

/**
 * What it takes to draw one item's icon and name it, read from the NEU repository.
 *
 * Four fields out of a file that holds thirty: the item whose model to start from, the texture
 * Hypixel's own resource pack draws over it, the skin a head resolves to, and what Hypixel calls
 * the thing. Stats, lore and recipes belong to other readers.
 *
 * Measured across the 8745 items in the repo, which is what set the shape of this class:
 *
 * - 8113 (92%) carry [itemModel]. Where it is present the legacy [itemId] no longer decides
 *   anything, because the model overrides the item's whole appearance.
 * - 4888 (55%) carry [skullTexture], the vast majority of them also carrying a model.
 * - 206 (2.4%) have neither and are named by an id that no longer exists, which is the only
 *   population [LegacyItemIds] has to cover - and 164 of those are potions.
 *
 * That last figure is why the legacy table here is twenty lines rather than the two hundred the
 * reference mods carry: they rebuild the full item for every entry in the game, where this only
 * has to reach a 16x16 sprite.
 */
data class ItemIconData(
    /** The 1.8.9 item id the repo names, e.g. `skull`, `dye`, `diamond_block`. Never null. */
    val itemId: String,
    /** The damage value that used to select a variant: `dye` 4 is lapis, `red_flower` 3 an allium. */
    val damage: Int = 0,
    /** e.g. `hypixel_skyblock:item/glacite/tungsten/tungsten_plate`, when the server textures it. */
    val itemModel: String? = null,
    /** The base64 texture property off a head's `SkullOwner`, which resolves to a skin. */
    val skullTexture: String? = null,
    /**
     * What Hypixel calls this item, with the colour codes stripped.
     *
     * Carried because ten bazaar products cannot be named from their id at all: the bazaar
     * trades `INK_SACK:3`, and deriving a name from that gives "Ink Sack:3" where the item is
     * Cocoa Beans. The repository already holds the right answer for every one of them, so it is
     * read here rather than kept as a hand-written table that would drift.
     */
    val displayName: String? = null,
    /**
     * Whether the item carries the enchantment shimmer in game.
     *
     * Read from the *presence* of the NBT's `ench` field, not from its contents: 1455 items
     * declare it and only 15 list an actual enchantment, because Hypixel uses the empty list
     * purely to make an item glint. Judging by contents would light up those 15 and leave every
     * Enchanted collection item flat, which is the whole population this is for.
     */
    val enchanted: Boolean = false,
) {

    companion object {

        /**
         * Reads one item file, or null when it names no item at all.
         *
         * The NBT is a 1.8.9 tag string rather than JSON, so the two fields buried in it are
         * matched out by pattern. Parsing it properly would mean carrying a legacy NBT parser to
         * reach two strings, where a miss here costs one icon rather than a wrong one: both
         * fields are optional, and an item without them still draws from its base id.
         */
        fun parse(itemJson: JsonObject): ItemIconData? {
            // Lowercased first, then stripped: the other order leaves `MINECRAFT:SKULL` with its
            // namespace attached. See the same note in [LegacyItemIds.modernise].
            val itemId = itemJson.string("itemid")
                ?.lowercase()
                ?.removePrefix("minecraft:")
                ?: return null

            if (itemId.isBlank()) return null

            val nbt = itemJson.string("nbttag").orEmpty()

            return ItemIconData(
                itemId = itemId,
                damage = itemJson.get("damage")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                itemModel = ITEM_MODEL.find(nbt)?.groupValues?.get(1),
                skullTexture = SKULL_TEXTURE.find(nbt)?.groupValues?.get(1),
                displayName = itemJson.string("displayname")?.stripFormatting()?.trim()?.ifBlank { null },
                enchanted = nbt.contains(ENCHANTMENT_FIELD),
            )
        }

        /**
         * `ItemModel:"hypixel_skyblock:item/..."` inside the legacy tag string.
         *
         * Anchored on the field name and the quotes rather than on what follows: the value is a
         * namespaced path on most items but plain `minecraft:diamond_block` on many, and a
         * pattern written around the Hypixel namespace would silently skip the vanilla half.
         */
        private val ITEM_MODEL = Regex("""ItemModel:"([^"]+)"""")

        /**
         * The skin blob out of `SkullOwner:{...Properties:{textures:[0:{...Value:"..."}]}}`.
         *
         * Anchored on `textures:[0:{` and not on `SkullOwner`, which is what makes it match at
         * all. Every entry names a `Signature` *before* its `Value`, and a signature runs several
         * hundred characters, so a pattern that starts at `SkullOwner` and allows a bounded gap
         * reaches the end of its window mid-signature and gives up. That first attempt found 858
         * of 4888 heads - it did not fail, it quietly answered for one in six, which on screen
         * would have read as "most pets have no icon" rather than as a bug.
         *
         * Verified against the whole repository: 4888 of 4888. Of those, 4834 decode to a
         * `textures.minecraft.net` URL as JSON; the remaining 54 spell the same payload as
         * unquoted NBT, which Minecraft's own profile parsing accepts and this pattern does not
         * need to distinguish.
         */
        private val SKULL_TEXTURE =
            Regex("""textures:\[0:\{.*?Value:"([A-Za-z0-9+/=]{32,})"""", RegexOption.DOT_MATCHES_ALL)

        /**
         * The marker that an item shimmers: `ench:[` anywhere in the tag.
         *
         * A plain substring rather than a pattern, since only its presence is being asked about.
         * Verified across the repository: 145 of the 160 `ENCHANTED_` collection items carry it,
         * the 15 that don't are book *bundles* rather than enchanted items, and nothing that
         * should stay flat - Hyperion, Tungsten Plate, plain Feather - has it.
         */
        private const val ENCHANTMENT_FIELD = "ench:["

        /** Gson hands back a JsonNull rather than null for a present-but-empty field. */
        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
