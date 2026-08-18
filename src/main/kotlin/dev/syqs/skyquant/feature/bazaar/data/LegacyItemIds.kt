package dev.syqs.skyquant.feature.bazaar.data

/**
 * Turns the 1.8.9 item ids the NEU repository still uses into ids Minecraft recognises today.
 *
 * The repo was written against 1.8.9 and never migrated, so it names items from before the
 * Flattening: `skull` for every head, `fireworks` for a rocket, and a family of ids that packed
 * several items into one, selecting between them with a damage value - `dye` 4 is lapis,
 * `red_flower` 3 an allium.
 *
 * **This table is deliberately small, and the measurement is the reason.** Of the repo's 8745
 * items, 8113 carry an `ItemModel`, which overrides the item's appearance outright and makes its
 * legacy id irrelevant to an icon; another 4888 are heads drawn from their skin. What is left -
 * items with no model, no skin, and an id that no longer resolves - is 206 entries, 164 of them
 * potions. The reference mods carry conversion tables several hundred lines long because they
 * rebuild the whole item for every entry in the game; this only has to reach a sprite, so it
 * covers what the data actually contains and nothing else.
 *
 * Ids that survived the Flattening unchanged - `paper`, `book`, `stick`, `clock`, `arrow` - are
 * absent on purpose. [modernise] passes anything it does not know through untouched, so listing
 * them would restate the default.
 */
object LegacyItemIds {

    /**
     * The modern id for a legacy [itemId]/[damage] pair.
     *
     * Unknown ids are returned as they came, lowercased and without a namespace. That is the
     * right default here: most legacy ids are still valid, and an id this table has never heard
     * of is far more likely to be one of those than a mistake worth substituting for.
     */
    fun modernise(itemId: String, damage: Int = 0): String {
        // Lowercased before the prefix is stripped, not after: `MINECRAFT:SKULL` keeps its
        // namespace the other way round, and an id carrying one matches nothing in either table -
        // so it would be returned unchanged and drawn as whatever `minecraft:skull` resolves to.
        val id = itemId.lowercase().removePrefix("minecraft:")

        DAMAGE_VARIANTS[id]?.let { variants ->
            return variants.getOrNull(damage) ?: variants.first()
        }

        return RENAMED[id] ?: id
    }

    /**
     * Ids that were renamed outright, damage playing no part.
     *
     * `skull` is the one that matters at any scale: 4927 items are heads. It resolves to
     * `player_head` regardless of damage, since the damage value chose between skeleton, zombie
     * and player variants and every SkyBlock item using it is a player head.
     */
    private val RENAMED = mapOf(
        "skull" to "player_head",
        "fireworks" to "firework_rocket",
        "firework_charge" to "firework_star",
        "banner" to "white_banner",
        "monster_egg" to "infested_stone",
        "mob_spawner" to "spawner",
        "record_cat" to "music_disc_cat",
        "deadbush" to "dead_bush",
        "noteblock" to "note_block",
        "boat" to "oak_boat",
        "stained_hardened_clay" to "white_terracotta",
        "web" to "cobweb",
        "reeds" to "sugar_cane",
        "melon_block" to "melon",
        "speckled_melon" to "glistering_melon_slice",
        "cactus_green" to "green_dye",
        "netherbrick" to "nether_brick",
        "golden_rail" to "powered_rail",
        "waterlily" to "lily_pad",
        "snow_layer" to "snow",
    )

    /**
     * The sixteen dyed colours in damage order, shared by wool, carpet and the glass families.
     *
     * Not the order `dye` uses: there white is 15 and black 0, the sequence running the other
     * way, which is why that family spells its own list out above instead of building on this.
     */
    private val COLOURS = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )

    /**
     * The ids that packed several items behind one name, listed in damage order.
     *
     * Only the families the repository actually uses are here. Index 0 doubles as the fallback
     * for a damage value past the end of a list, which is what a malformed entry would produce.
     */
    private val DAMAGE_VARIANTS = mapOf(
        "dye" to listOf(
            "ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye",
            "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye",
            "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal",
        ),
        "red_flower" to listOf(
            "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy",
        ),
        "yellow_flower" to listOf("dandelion"),
        "fish" to listOf("cod", "salmon", "tropical_fish", "pufferfish"),
        "cooked_fish" to listOf("cooked_cod", "cooked_salmon"),
        "coal" to listOf("coal", "charcoal"),
        "wool" to COLOURS.map { "${it}_wool" },
        "carpet" to COLOURS.map { "${it}_carpet" },
        "stained_glass" to COLOURS.map { "${it}_stained_glass" },
        "stained_glass_pane" to COLOURS.map { "${it}_stained_glass_pane" },
        "log" to listOf("oak_log", "spruce_log", "birch_log", "jungle_log"),
        "log2" to listOf("acacia_log", "dark_oak_log"),
        "leaves" to listOf("oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves"),
        "leaves2" to listOf("acacia_leaves", "dark_oak_leaves"),
        "sapling" to listOf(
            "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
            "acacia_sapling", "dark_oak_sapling",
        ),
        "stone" to listOf(
            "stone", "granite", "polished_granite", "diorite", "polished_diorite",
            "andesite", "polished_andesite",
        ),
        "stonebrick" to listOf(
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks",
        ),
        "sandstone" to listOf("sandstone", "chiseled_sandstone", "cut_sandstone"),
        "quartz_block" to listOf("quartz_block", "chiseled_quartz_block", "quartz_pillar"),
        "prismarine" to listOf("prismarine", "prismarine_bricks", "dark_prismarine"),
        "tallgrass" to listOf("dead_bush", "short_grass", "fern"),
        "double_plant" to listOf(
            "sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony",
        ),
    )
}
