package dev.syqs.skyquant.hud

import com.google.gson.reflect.TypeToken
import dev.syqs.skyquant.util.JsonFile
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents

/**
 * Every overlay the mod can draw, with where and how large the player put it.
 *
 * Positions live here rather than in the config because they're written by dragging rather than
 * by editing a field: keeping them in the settings file would mean the settings screen listing
 * coordinate pairs nobody edits by hand.
 */
object HudRegistry {

    // Pretty-printed: unlike the other stores this one is plausibly opened by hand, to copy a
    // layout between installs or undo a drag that went wrong.
    private val store = JsonFile<MutableMap<String, Placement>>(
        name = "hud",
        type = object : TypeToken<MutableMap<String, Placement>>() {}.type,
        default = { mutableMapOf() },
        pretty = true,
    )

    /**
     * Placement of one overlay. [x] and [y] are fractions of the screen so a panel keeps its
     * place when the window is resized or the GUI scale changes; pixels would drift.
     */
    class Placement(
        var x: Float = 0.01f,
        var y: Float = 0.35f,
        var scale: Float = 1f,
    )

    private val elements = mutableListOf<HudElement>()
    private val placements: MutableMap<String, Placement> = store.load()

    /** Registered overlays, in registration order. */
    val all: List<HudElement> get() = elements

    /**
     * Writes placements on shutdown, so a layout survives a route out of the game that never
     * reaches the editor's own save.
     *
     * [store] and [setScale] only touch memory, and [HudEditorScreen.onClose] was the single
     * caller of [save] - so a crash, an Alt+F4, or quitting with the editor still open lost every
     * drag since the last clean close. The same backstop the settings already have (see
     * [dev.syqs.skyquant.config.SkyQuantConfigManager.register]), for the same reason: the state
     * worth persisting is written by hand, and a hand-made layout is expensive to redo.
     */
    fun register() {
        ClientLifecycleEvents.CLIENT_STOPPING.register { save() }
    }

    fun register(element: HudElement) {
        elements.add(element)
    }

    fun placementOf(element: HudElement): Placement =
        placements.getOrPut(element.id) { Placement() }

    /**
     * Top-left corner in screen pixels, clamped so a panel can't end up entirely off screen -
     * which would otherwise strand it somewhere the player can't grab it back from.
     */
    fun originOf(element: HudElement, screenWidth: Int, screenHeight: Int, font: net.minecraft.client.gui.Font): Pair<Int, Int> {
        val placement = placementOf(element)
        val width = (element.width(font) * placement.scale).toInt()
        val height = (element.height(font) * placement.scale).toInt()

        val x = (screenWidth * placement.x).toInt().coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val y = (screenHeight * placement.y).toInt().coerceIn(0, (screenHeight - height).coerceAtLeast(0))

        return x to y
    }

    fun store(element: HudElement, x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        val placement = placementOf(element)
        placement.x = x.toFloat() / screenWidth
        placement.y = y.toFloat() / screenHeight
    }

    fun setScale(element: HudElement, scale: Float) {
        placementOf(element).scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun save() = store.save(placements)

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f

    /** Step per notch of the scroll wheel - fine enough to land on a size that feels right. */
    const val SCALE_STEP = 0.1f
}
