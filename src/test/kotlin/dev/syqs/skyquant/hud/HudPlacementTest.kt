package dev.syqs.skyquant.hud

import com.google.gson.reflect.TypeToken
import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.util.JsonFile
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The persistence behind the HUD layout.
 *
 * [HudRegistry] itself can't be exercised here - it holds registered [HudElement]s, and that
 * interface takes `Minecraft` and `Font`, neither of which loads outside the game. What *is*
 * testable is the part the layout actually depends on: that a [HudRegistry.Placement] survives
 * being written and read back.
 *
 * Worth pinning down because the failure is silent and total. Placements are only ever written by
 * dragging, so a store that round-trips wrong doesn't corrupt one field visibly - it hands back
 * defaults, and the player's whole layout is simply gone with nothing to explain it.
 */
class HudPlacementTest {

    private val file = File("config/${SkyQuantMod.MOD_ID}_test_hud.json")

    private fun store() = JsonFile<MutableMap<String, HudRegistry.Placement>>(
        name = "test_hud",
        type = object : TypeToken<MutableMap<String, HudRegistry.Placement>>() {}.type,
        default = { mutableMapOf() },
        pretty = true,
    )

    @AfterTest
    fun removeWrittenFile() {
        file.delete()
    }

    @Test
    fun `a placement survives being written and read back`() {
        val saved = mutableMapOf(
            "bazaar_prices" to HudRegistry.Placement(x = 0.42f, y = 0.75f, scale = 1.6f),
        )

        store().save(saved)
        val loaded = store().load()

        val placement = loaded["bazaar_prices"]
        assertEquals(0.42f, placement?.x)
        assertEquals(0.75f, placement?.y)
        // The one most easily lost: scale is set by the scroll wheel rather than by dragging, so
        // it is written on a different path from x and y.
        assertEquals(1.6f, placement?.scale)
    }

    @Test
    fun `several overlays keep their own positions`() {
        // The registry is keyed by element id, so two overlays sharing one entry - or one
        // overwriting the other - would place them both in the same corner.
        val saved = mutableMapOf(
            "bazaar_prices" to HudRegistry.Placement(x = 0.1f, y = 0.2f, scale = 1f),
            "another_overlay" to HudRegistry.Placement(x = 0.8f, y = 0.9f, scale = 2f),
        )

        store().save(saved)
        val loaded = store().load()

        assertEquals(2, loaded.size)
        assertEquals(0.1f, loaded["bazaar_prices"]?.x)
        assertEquals(0.8f, loaded["another_overlay"]?.x)
        assertEquals(2f, loaded["another_overlay"]?.scale)
    }

    @Test
    fun `an unreadable file falls back to no placements rather than failing`() {
        // What a crash mid-write leaves behind. Empty is the right answer: every overlay returns
        // to its default corner, which is recoverable by dragging - where a thrown exception
        // during registration would take the whole mod down with it.
        file.parentFile?.mkdirs()
        file.writeText("""{"bazaar_prices":{"x":0.4""")

        assertEquals(0, store().load().size)
    }
}
