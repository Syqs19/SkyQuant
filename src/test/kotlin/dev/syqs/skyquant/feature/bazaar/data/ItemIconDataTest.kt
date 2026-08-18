package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the three icon fields out of an NEU item file.
 *
 * As with [RecipeTest], every fixture is a real entry from the repository rather than an invented
 * one: the job of this parser is to agree with a format nobody here controls, and a hand-written
 * example only proves it agrees with itself. The heads matter most - they are 4888 of the repo's
 * 8745 items, and the first pattern written for them matched 858, which is the kind of failure
 * that reads on screen as "pets have no icon" rather than as something broken.
 */
class ItemIconDataTest {

    private fun parse(json: String) =
        ItemIconData.parse(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `reads a vanilla item that the server retextures`() {
        // TUNGSTEN_PLATE, verbatim. A sheet of paper wearing a Hypixel model - the case the
        // whole feature turns on, since without the model this draws as blank paper.
        val json = """
            {"itemid": "minecraft:paper", "damage": 0,
             "nbttag": "{ExtraAttributes:{id:\"TUNGSTEN_PLATE\"},HideFlags:254,
              ItemModel:\"hypixel_skyblock:item/glacite/tungsten/tungsten_plate\"}"}
        """.trimIndent()

        val icon = assertNotNull(parse(json))

        assertEquals("paper", icon.itemId)
        assertEquals("hypixel_skyblock:item/glacite/tungsten/tungsten_plate", icon.itemModel)
        assertNull(icon.skullTexture)
    }

    @Test
    fun `reads a damage-selected variant`() {
        // ENCHANTED_LAPIS_LAZULI: `dye` with damage 4. The damage is what separates lapis from
        // the fifteen other items sharing that id, so losing it draws an ink sac.
        val json = """
            {"itemid": "minecraft:dye", "damage": 4,
             "nbttag": "{ExtraAttributes:{id:\"ENCHANTED_LAPIS_LAZULI\"},
              ItemModel:\"minecraft:lapis_lazuli\"}"}
        """.trimIndent()

        val icon = assertNotNull(parse(json))

        assertEquals("dye", icon.itemId)
        assertEquals(4, icon.damage)
        assertEquals("minecraft:lapis_lazuli", icon.itemModel)
    }

    @Test
    fun `finds a head texture past the signature that precedes it`() {
        // SUPERIOR_DRAGON_HELMET, shortened but keeping the shape that broke the first attempt:
        // `Signature` sits between `SkullOwner` and `Value`, and in the real file it runs several
        // hundred characters. A pattern anchored on SkullOwner with a bounded gap gives up inside
        // it; this one anchors on the textures array instead.
        val json = """
            {"itemid": "minecraft:skull", "damage": 3,
             "nbttag": "{ExtraAttributes:{id:\"SUPERIOR_DRAGON_HELMET\"},
              ItemModel:\"minecraft:player_head\",
              SkullOwner:{Id:\"d4981ca4-723e-3237-8099-7c8f5637b3c3\",Properties:{textures:[0:{
              Name:\"textures\",Signature:\"dWsx7DJ2XBh0giihH4miVXlTR4CkHAXhalvWRXwZCzehMeJKHYqqX3IBhaY7LEAuGQhURhNm44elgmhknGfKmb3dP0RFVBizLb4rk8qJ05uFxG8DaOQkShjFEX0I2JxrhgIlbGs82N8z4Clp4ECVcniS64SwGsgqN0lZ8E0LzzIUwFlY8W0N29Qt8QdlkWvcpHtITFOBlsBHAAVA02oSUTgZneq24ORrioZjiFNnm7XM9QvuJ5FdLOSGXNsNdpxSUb9QfJDC93JZTzG2mhO7Vr40qCErvpjJXdtiBVOWwKtP1RaNunka4L81sxMbLiyyS097UvUggUr2hHFqbmqIpzJCh3NXgiVmwXlVj8gNsgw3tSYyHxwRIBBxwFs3nM\",
              Value:\"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTIzNCJ9fX0=\"}]}}}"}
        """.trimIndent()

        val icon = assertNotNull(parse(json))

        assertEquals("skull", icon.itemId)
        val texture = assertNotNull(icon.skullTexture)
        // The signature is base64 too, so the wrong anchor picks up a valid-looking string: this
        // asserts it read the payload, not the run of characters before it.
        assertEquals("eyJ0ZXh0dXJlcyI6", texture.take(16))
    }

    @Test
    fun `treats a missing model and skin as absent rather than empty`() {
        // Most of the 632 items with no model look like this. Both fields staying null is what
        // lets the drawing side fall back to the base item instead of asking for "".
        val json = """{"itemid": "minecraft:potion", "damage": 4, "nbttag": "{HideFlags:254}"}"""

        val icon = assertNotNull(parse(json))

        assertNull(icon.itemModel)
        assertNull(icon.skullTexture)
        assertEquals(4, icon.damage)
    }

    @Test
    fun `rejects an entry that names no item`() {
        // A file with no itemid describes nothing that can be drawn. Returning null here is what
        // keeps it out of the index entirely, rather than storing a blank that fails later.
        assertNull(parse("""{"displayname": "Â§aSomething", "nbttag": "{}"}"""))
        assertNull(parse("""{"itemid": "", "nbttag": "{}"}"""))
    }

    @Test
    fun `reads the display name the repository gives`() {
        // INK_SACK-3, verbatim apart from the shortened lore. The name is carried for exactly
        // this case: the bazaar trades it as INK_SACK:3, and no rule over that id produces
        // "Cocoa Beans".
        val json = """
            {"itemid": "minecraft:dye", "damage": 3, "displayname": "§fCocoa Beans",
             "nbttag": "{ExtraAttributes:{id:\"INK_SACK-3\"}}"}
        """.trimIndent()

        assertEquals("Cocoa Beans", assertNotNull(parse(json)).displayName)
    }

    @Test
    fun `marks an item that shimmers in game`() {
        // ENCHANTED_FEATHER carries `ench:[]` - an EMPTY list. Hypixel uses its presence alone to
        // make an item glint, so reading the contents would leave every Enchanted item flat.
        val enchanted = """
            {"itemid": "minecraft:feather",
             "nbttag": "{ExtraAttributes:{id:\"ENCHANTED_FEATHER\"},ench:[]}"}
        """.trimIndent()

        val plain = """
            {"itemid": "minecraft:feather", "nbttag": "{ExtraAttributes:{id:\"FEATHER\"}}"}
        """.trimIndent()

        assertTrue(assertNotNull(parse(enchanted)).enchanted)
        assertTrue(!assertNotNull(parse(plain)).enchanted)
    }

    @Test
    fun `defaults damage to zero when the field is absent or null`() {
        // Gson hands back JsonNull rather than null for a present-but-empty field, which is the
        // shape that turns into an exception if it is read as a number unguarded.
        assertEquals(0, assertNotNull(parse("""{"itemid": "minecraft:paper"}""")).damage)
        assertEquals(0, assertNotNull(parse("""{"itemid": "minecraft:paper", "damage": null}""")).damage)
    }
}
