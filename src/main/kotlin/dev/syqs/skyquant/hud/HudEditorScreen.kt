package dev.syqs.skyquant.hud

import dev.syqs.skyquant.gui.Palette
import kotlin.math.abs
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Places every registered overlay in one pass: drag to move, scroll to resize.
 *
 * One screen for all of them rather than a button per overlay, because the thing being decided
 * is how they sit *together* - two panels that each look well placed in isolation can still
 * overlap, and that's only visible with both on screen at once.
 *
 * Written by hand because MoulConfig has no draggable-position editor; its only list control
 * reorders entries, and a position typed in as two numbers asks the player to guess where 0.35
 * lands on their screen.
 */
class HudEditorScreen(
    private val previousScreen: Screen?,
) : Screen(Component.literal("HUD Editor")) {

    private var dragged: HudElement? = null
    private var grabX = 0
    private var grabY = 0

    /** Element under the cursor this frame, for the scroll wheel and the hover outline. */
    private var hovered: HudElement? = null

    private val editable: List<HudElement>
        get() = HudRegistry.all.filter { it.showInEditor() }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        hovered = null

        for (element in editable) {
            val placement = HudRegistry.placementOf(element)
            val width = (element.width(font) * placement.scale).toInt()
            val height = (element.height(font) * placement.scale).toInt()

            val (x, y) = if (element === dragged) {
                val dragX = (mouseX - grabX).coerceIn(0, (this.width - width).coerceAtLeast(0))
                val dragY = (mouseY - grabY).coerceIn(0, (this.height - height).coerceAtLeast(0))
                HudRegistry.store(element, dragX, dragY, this.width, this.height)
                dragX to dragY
            } else {
                HudRegistry.originOf(element, this.width, this.height, font)
            }

            val isHovered = mouseX in x..(x + width) && mouseY in y..(y + height)
            if (isHovered && dragged == null) hovered = element

            drawOutline(graphics, x, y, width, height, element === dragged || isHovered)
            drawScaled(graphics, element, x, y, placement.scale)

            if (element === dragged) drawGuides(graphics, x, y, width, height)
            if (element === dragged || isHovered) drawBadge(graphics, element, placement.scale, x, y)
        }

        drawInstructions(graphics)
    }

    /**
     * Applies the placement's scale around the panel's own corner, so growing it expands away
     * from where it sits rather than dragging it towards the screen origin.
     */
    private fun drawScaled(graphics: GuiGraphicsExtractor, element: HudElement, x: Int, y: Int, scale: Float) {
        if (scale == 1f) {
            element.draw(graphics, font, x, y)
            return
        }

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        // Drawn at the origin because the translation above already put us at the corner.
        element.draw(graphics, font, 0, 0)
        pose.popMatrix()
    }

    private fun drawOutline(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, active: Boolean) {
        val color = if (active) Palette.ACCENT else Palette.OUTLINE

        graphics.fill(x - 1, y - 1, x + width + 1, y, color)
        graphics.fill(x - 1, y + height, x + width + 1, y + height + 1, color)
        graphics.fill(x - 1, y, x, y + height, color)
        graphics.fill(x + width, y, x + width + 1, y + height, color)
    }

    /** Name and current size, so the player can see what they're resizing while they do it. */
    private fun drawBadge(graphics: GuiGraphicsExtractor, element: HudElement, scale: Float, x: Int, y: Int) {
        val text = "${element.displayName}  ${(scale * 100).roundToInt()}%"
        // Above the panel, unless it's at the very top of the screen, where it goes below.
        val badgeY = if (y >= 12) y - 11 else y + 2

        graphics.fill(x, badgeY - 2, x + font.width(text) + 6, badgeY + 9, Palette.OVERLAY_BACKGROUND)
        graphics.text(font, Component.literal(text), x + 3, badgeY, Palette.TEXT)
    }

    /** Centre lines, shown only when the panel is nearly centred - the one alignment hard to eyeball. */
    private fun drawGuides(graphics: GuiGraphicsExtractor, x: Int, y: Int, panelWidth: Int, panelHeight: Int) {
        if (abs(x + panelWidth / 2 - width / 2) <= SNAP_DISTANCE) {
            graphics.fill(width / 2, 0, width / 2 + 1, height, Palette.GUIDE)
        }
        if (abs(y + panelHeight / 2 - height / 2) <= SNAP_DISTANCE) {
            graphics.fill(0, height / 2, width, height / 2 + 1, Palette.GUIDE)
        }
    }

    private fun drawInstructions(graphics: GuiGraphicsExtractor) {
        if (editable.isEmpty()) {
            graphics.centeredText(
                font,
                Component.literal("No overlays are enabled."),
                width / 2,
                height / 2,
                Palette.MUTED,
            )
            return
        }

        graphics.centeredText(
            font,
            Component.literal("Drag to move · Scroll to resize · R resets · Esc saves"),
            width / 2,
            height - 22,
            Palette.MUTED,
        )
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)

        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()

        // Reversed so the topmost panel wins when two overlap, matching what the player sees.
        for (element in editable.reversed()) {
            val placement = HudRegistry.placementOf(element)
            val (x, y) = HudRegistry.originOf(element, width, height, font)
            val panelWidth = (element.width(font) * placement.scale).toInt()
            val panelHeight = (element.height(font) * placement.scale).toInt()

            if (mouseX in x..(x + panelWidth) && mouseY in y..(y + panelHeight)) {
                dragged = element
                grabX = mouseX - x
                grabY = mouseY - y
                return true
            }
        }

        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragged = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val element = hovered ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

        val placement = HudRegistry.placementOf(element)
        HudRegistry.setScale(element, placement.scale + (scrollY * HudRegistry.SCALE_STEP).toFloat())
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // Reset for whatever is under the cursor: a panel scaled or dragged off somewhere odd
        // is otherwise fiddly to bring back by hand.
        if (event.key() == RESET_KEY) {
            hovered?.let { HudRegistry.setScale(it, 1f) }
            return true
        }

        return super.keyPressed(event)
    }

    override fun onClose() {
        HudRegistry.save()
        minecraft.setScreen(previousScreen)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        /** Opens the editor over whatever is currently open. */
        fun open() {
            val minecraft = Minecraft.getInstance()
            minecraft.setScreen(HudEditorScreen(minecraft.screen))
        }


        private const val SNAP_DISTANCE = 6
        private const val RESET_KEY = org.lwjgl.glfw.GLFW.GLFW_KEY_R
    }
}
