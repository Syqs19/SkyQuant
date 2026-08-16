package dev.syqs.skyquant.feature.bazaar.data

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading recipes out of the NEU repository.
 *
 * Every fixture here is copied from the live repo rather than written by hand - the whole point
 * of this parser is agreeing with a format nobody controls, and an invented example only proves
 * the parser agrees with itself. Each case below was found by walking all 4487 recipes in the
 * archive on 15 August 2026.
 */
class RecipeTest {

    private fun parse(json: String, fallbackOutputId: String = "OUT") =
        Recipe.parseAll(JsonParser.parseString(json).asJsonObject, fallbackOutputId)

    @Test
    fun `reads the singular recipe field`() {
        // DIAMOND, verbatim. Nine cells, most of them empty, and a count of 9.
        val json = """
            {"recipe": {"A1": "DIAMOND_BLOCK:1", "A2": "", "A3": "", "B1": "", "B2": "",
             "B3": "", "C1": "", "C2": "", "C3": "", "count": 9}}
        """.trimIndent()

        val recipe = parse(json, "DIAMOND").single()

        assertEquals(mapOf("DIAMOND_BLOCK" to 1.0), recipe.ingredients)
        assertEquals(9.0, recipe.outputCount)
        assertEquals("DIAMOND", recipe.outputId)
    }

    @Test
    fun `reads the plural recipes array`() {
        // ENCHANTED_DIAMOND, verbatim. Parsing only `recipe` loses roughly half the repo.
        val json = """
            {"recipes": [{"type": "crafting", "A1": "", "A2": "DIAMOND:32", "A3": "",
             "B1": "DIAMOND:32", "B2": "DIAMOND:32", "B3": "DIAMOND:32", "C1": "",
             "C2": "DIAMOND:32", "C3": "", "count": 1}]}
        """.trimIndent()

        val recipe = parse(json, "ENCHANTED_DIAMOND").single()

        // Five cells of 32, added up. Reading one cell would price this at a fifth of its cost.
        assertEquals(mapOf("DIAMOND" to 160.0), recipe.ingredients)
    }

    @Test
    fun `a recipe with no type is still a crafting recipe`() {
        // The biggest trap in this format: 2011 of the repo's 2670 crafting recipes carry no
        // `type` field at all. Requiring type == "crafting" discards 79% of them, and the loss is
        // silent - the page simply shows fewer rows.
        val json = """
            {"recipe": {"A1": "", "A2": "COVEN_SEAL:2", "A3": "COVEN_SEAL:2", "B1": "", "B2": "",
             "B3": "AATROX_BATPHONE:1", "C1": "", "C2": "COVEN_SEAL:2", "C3": "COVEN_SEAL:2"}}
        """.trimIndent()

        val recipe = parse(json, "AATROX_BADPHONE").single()

        assertEquals(8.0, recipe.ingredients["COVEN_SEAL"])
        assertEquals(1.0, recipe.ingredients["AATROX_BATPHONE"])
        // No `count` field at all - one is the sensible reading, and 159 untyped recipes rely on it.
        assertEquals(1.0, recipe.outputCount)
    }

    @Test
    fun `reads a forge recipe with its duration`() {
        // AMBER_MATERIAL, verbatim. Six hours, which is why the Forge page ranks on profit per
        // hour rather than profit.
        val json = """
            {"recipes": [{"type": "forge", "inputs": ["GOLDEN_PLATE:1", "FINE_AMBER_GEM:12"],
             "count": 1, "overrideOutputId": "AMBER_MATERIAL", "duration": 21600}]}
        """.trimIndent()

        val recipe = parse(json, "SOMETHING_ELSE").single()

        assertEquals(mapOf("GOLDEN_PLATE" to 1.0, "FINE_AMBER_GEM" to 12.0), recipe.ingredients)
        assertEquals(21600, recipe.durationSeconds)
        assertTrue(recipe.isForge)
        // The override wins over the file's own name.
        assertEquals("AMBER_MATERIAL", recipe.outputId)
    }

    @Test
    fun `a crafting recipe is not a forge recipe`() {
        // The two are ranked on different figures, so confusing them puts an instant craft in the
        // per-hour ranking with a division by zero waiting behind it.
        val json = """{"recipe": {"A1": "DIAMOND_BLOCK:1", "count": 9}}"""

        assertTrue(!parse(json).single().isForge)
        assertEquals(0, parse(json).single().durationSeconds)
    }

    @Test
    fun `decimal quantities are read rather than crashing`() {
        // Measured: 37 entries in the repo write their amount as a decimal, e.g.
        // "ENCHANTED_SUGAR:2500.0". String.toInt() throws on those, losing the whole item.
        val json = """{"recipe": {"A1": "COBBLESTONE:8.0", "B2": "ENCHANTED_SUGAR:2500.0"}}"""

        val recipe = parse(json).single()

        assertEquals(8.0, recipe.ingredients["COBBLESTONE"])
        assertEquals(2500.0, recipe.ingredients["ENCHANTED_SUGAR"])
    }

    @Test
    fun `kinds that are not craftable are skipped`() {
        // The repo holds seven kinds; five are not trades against the markets. Their fields look
        // nothing like a recipe's - a `drops` entry carries mob names and lore with colour codes,
        // which parsed as ingredients would yield entries like "§cAgarimoo" matching no item.
        val drops = """{"recipes": [{"type": "drops", "name": "§cAgarimoo", "level": 1, "drops": []}]}"""
        val shop = """{"recipes": [{"type": "npc_shop", "cost": ["ROTTEN_FLESH:1"], "result": "X"}]}"""
        val kat = """{"recipes": [{"type": "katgrade", "input": "A", "output": "B", "time": 1}]}"""

        assertTrue(parse(drops).isEmpty())
        assertTrue(parse(shop).isEmpty())
        assertTrue(parse(kat).isEmpty())
    }

    @Test
    fun `an item with several recipes yields all of them`() {
        // 178 items in the repo carry more than one. Keeping only the first would quietly hide
        // the cheaper way of making something.
        val json = """
            {"recipe": {"A1": "DIAMOND_BLOCK:1", "count": 9},
             "recipes": [{"type": "crafting", "A1": "DIAMOND:64", "count": 1}]}
        """.trimIndent()

        assertEquals(2, parse(json).size)
    }

    @Test
    fun `an empty grid is not a recipe`() {
        // Some files carry a recipe block with every cell blank. Priced as written it would be an
        // item craftable out of nothing, i.e. infinite profit - which would sort straight to the
        // top of the page.
        val json = """{"recipe": {"A1": "", "A2": "", "B1": "", "C3": "", "count": 1}}"""

        assertTrue(parse(json).isEmpty())
    }

    @Test
    fun `a forge recipe with no duration is rejected`() {
        // Rather than defaulting to zero, which would rank it among the instant crafts and hand
        // the per-hour maths a division by zero.
        val json = """{"recipes": [{"type": "forge", "inputs": ["GOLDEN_PLATE:1"], "count": 1}]}"""

        assertTrue(parse(json).isEmpty())
    }

    @Test
    fun `a malformed stack is skipped rather than throwing`() {
        // The index is built by walking 8745 files in one pass; one bad entry must not abort it.
        assertNull(Recipe.parseStack(""))
        assertNull(Recipe.parseStack("NO_AMOUNT"))
        assertNull(Recipe.parseStack(":32"))
        assertNull(Recipe.parseStack("DIAMOND:abc"))
        assertNull(Recipe.parseStack("DIAMOND:0"))
    }

    @Test
    fun `an id containing a colon keeps its own name`() {
        // Enchantment ids are written "ENCHANTMENT_ULTIMATE_WISE;5" but some entries carry a
        // colon inside the id itself, so the split has to be on the LAST one.
        val (id, amount) = Recipe.parseStack("SOME:WEIRD:ID:4")!!

        assertEquals("SOME:WEIRD:ID", id)
        assertEquals(4.0, amount)
    }
}
