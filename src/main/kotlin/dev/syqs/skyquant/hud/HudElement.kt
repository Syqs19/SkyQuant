package dev.syqs.skyquant.hud

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Something the mod draws over the game at a position and size the player controls.
 *
 * The interface exists so the editor doesn't have to know about each overlay individually: it
 * lays out and drags whatever is registered, and a new overlay becomes editable by implementing
 * this rather than by extending the editor.
 */
interface HudElement {

    /** Stable id, used as the key its position is saved under. Never shown to the player. */
    val id: String

    /** Name shown in the editor, so a panel being dragged can be identified while it's moved. */
    val displayName: String

    /** Unscaled size. The editor applies the scale itself when measuring and hit-testing. */
    fun width(font: Font): Int

    fun height(font: Font): Int

    /**
     * Draws at [x], [y] in unscaled coordinates - the caller has already applied the scale, so
     * implementations lay out at their natural size and ignore it entirely.
     */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int)

    /** Whether this belongs on screen right now, outside the editor. */
    fun shouldRender(minecraft: Minecraft): Boolean

    /**
     * Whether the editor should show it even when [shouldRender] says no.
     *
     * An overlay with nothing to show still needs to be placeable, otherwise its position can
     * only be set at the exact moment it happens to have content.
     */
    fun showInEditor(): Boolean = true
}
