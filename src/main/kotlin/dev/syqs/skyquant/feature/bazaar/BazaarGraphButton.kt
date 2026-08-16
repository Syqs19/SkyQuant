package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.gui.BazaarGraphScreen
import dev.syqs.skyquant.feature.bazaar.gui.RoundedRectRenderState
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.util.stripFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3x2f

/**
 * "Price graph" button beside Hypixel's product pages.
 *
 * Hypixel puts the item a page is about in the middle slot of the top row, so that's what the
 * button charts - the page's subject rather than whatever the cursor happens to be near.
 *
 * It sits outside the container window instead of in one of the filler glass panes: those are
 * real slots, and a click on them is still sent to the server. Keeping the button off the grid
 * removes any chance of a stray interaction in a menu where the other clicks spend coins.
 */
object BazaarGraphButton {

    /** Middle of the top row in a 9-wide container - Hypixel's slot for the current subject. */
    private const val SUBJECT_SLOT = 13

    /** Lore phrases Hypixel puts on entries that lead somewhere else. */
    private val NAVIGATION_HINTS = listOf("Click to view", "Click to open", "Click to browse")

    /**
     * Menus this button must never appear in, matched on their title.
     *
     * A blocklist, having tried the opposite and been wrong. Requiring "bazaar" in the title
     * looked like the principled fix for the button showing up in The Forge, and it silenced the
     * button everywhere - because Hypixel titles a bazaar product page after the *item*
     * ("Enchanted Diamond"), not after the bazaar. The rule was never verified against a real
     * title before being relied on, and the failure was invisible: no button, no error.
     *
     * The forge is the actual exception and is named as one. Slot 13 there holds the item being
     * smithed - Refined Umber, a genuine bazaar product whose lore reads "Currently making"
     * rather than "Click to view" - so it clears every check that looks at the item alone.
     */
    private val EXCLUDED_TITLES = listOf("forge", "quick forge")

    private const val WIDTH = 76
    private const val HEIGHT = 18
    private const val GAP = 6

    private const val CORNER_RADIUS = 3f

    /** Accent stripe down the left edge, marking the button as the mod's own. */
    private const val ACCENT_EDGE_WIDTH = 2

    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        productOf(screen) ?: return

        val box = bounds(screen)
        val hovered = box.containsPoint(mouseX, mouseY)

        val left = box.position().x()
        val top = box.position().y()

        // Unlike every other surface the mod draws, this one sits over the world: a player can
        // open a bazaar page in a snowfield or in Deep Caverns. A translucent panel that reads
        // fine against a dark cave washes out completely against snow, so an opaque plate goes
        // down first and the translucent fill sits on top of it.
        graphics.fill(left, top, left + box.width(), top + box.height(), Palette.OVERLAY_BACKGROUND)

        graphics.guiRenderState.addGuiElement(
            RoundedRectRenderState(
                Matrix3x2f(graphics.pose()),
                left.toFloat(),
                top.toFloat(),
                (left + box.width()).toFloat(),
                (top + box.height()).toFloat(),
                CORNER_RADIUS,
                if (hovered) Palette.FOREIGN_BUTTON_HOVER else Palette.FOREIGN_BUTTON,
                null,
            ),
        )

        // Accent rule down the leading edge: marks the button as the mod's rather than part of
        // Hypixel's menu, and gives the eye an edge to find it by against a busy scene.
        graphics.fill(left, top, left + ACCENT_EDGE_WIDTH, top + box.height(), Palette.ACCENT)

        graphics.centeredText(
            Minecraft.getInstance().font,
            Component.literal("Price graph"),
            left + box.width() / 2,
            top + (box.height() - 8) / 2,
            if (hovered) Palette.ACCENT else Palette.TEXT,
        )
    }

    /** Opens the graph if the click landed on the button. Returns true when it was handled. */
    fun onClick(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double): Boolean {
        val productId = productOf(screen) ?: return false
        if (!bounds(screen).containsPoint(mouseX.toInt(), mouseY.toInt())) return false

        Minecraft.getInstance().setScreen(BazaarGraphScreen(productId, screen))
        return true
    }

    /**
     * Product the open page is about, or null if this isn't a product page.
     *
     * Slot 13 holds a sellable item on category lists too, so its presence proves nothing. The
     * distinction is in the lore: on a list every entry is a link and says so ("Click to view
     * details!"), while on a product page the item is the subject and carries no such prompt.
     * That reflects what the screen does rather than how its slots happen to be arranged, which
     * is why it holds up across Hypixel's several menu layouts.
     *
     * Read every frame rather than cached on open: Hypixel sends the container empty and fills
     * it a moment later, so anything sampled once at init would always come back blank.
     */
    private fun productOf(screen: AbstractContainerScreen<*>): String? {
        if (!screenAllowsButton(screen)) return diagnose(screen, "menu is on the exclusion list")

        val slots = screen.menu.slots
        if (SUBJECT_SLOT >= slots.size - 36) {
            return diagnose(screen, "container has only ${slots.size - 36} slots")
        }

        val stack = slots[SUBJECT_SLOT].item
        if (isNavigationEntry(stack)) return diagnose(screen, "slot 13 lore reads as a link")

        // `bazaarProductOf`, as in the version that worked: it answers "is this on the bazaar",
        // which is the question, and is what keeps the button off items in chests and shops.
        // Loosening it to a plain Skyblock id was an attempt to compensate for the title gate
        // being wrong, and two wrongs left the button appearing on things it can't chart.
        return SkyblockItemId.bazaarProductOf(stack)
            ?: diagnose(screen, "slot 13 is not a bazaar product")
    }

    /**
     * Logs why the button declined to appear, once per screen, when diagnostics are enabled.
     *
     * Added because this has now been fixed twice from inspection and stayed broken both times.
     * Every one of the four conditions above is invisible from outside: the button simply isn't
     * there, and which check rejected it can only be guessed at. Guessing is what cost the two
     * previous attempts, so the code now says which one it was.
     *
     * Always returns null so it reads as the rejection it reports.
     */
    private fun diagnose(screen: AbstractContainerScreen<*>, reason: String): String? {
        val title = screen.title.string

        // Back behind the setting. It was briefly unconditional in "bazaar menus", which was
        // safe only while that meant a handful of screens; now that the check is an exclusion
        // list, nearly every container in the game qualifies and this would report on chests,
        // shops and the player's own inventory.
        if (!SkyQuantConfigManager.config.bazaar.logGraphButtonChecks) return null

        if (title == lastDiagnosedTitle && reason == lastDiagnosedReason) return null
        lastDiagnosedTitle = title
        lastDiagnosedReason = reason

        val slots = screen.menu.slots
        val subject = slots.getOrNull(SUBJECT_SLOT)?.item
        val lore = subject?.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }

        SkyQuantMod.LOGGER.info(
            "Price graph button hidden: {} | title='{}' | slots={} | slot13={} id={} lore={}",
            reason,
            title,
            slots.size,
            subject?.displayName?.string,
            subject?.let { SkyblockItemId.of(it) },
            lore,
        )

        // Also in chat, since the log only helps someone who knows to open it. Throttled to once
        // per distinct title-and-reason, so a menu that is simply not a product page says its
        // piece once rather than every frame.
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal(
                "§e[SkyQuant] §7Price graph hidden: §f$reason " +
                    "§8(slot 13: ${subject?.displayName?.string ?: "empty"})",
            ),
        )
        return null
    }

    private var lastDiagnosedTitle: String? = null
    private var lastDiagnosedReason: String? = null

    /**
     * Records that a container screen was opened and this feature was wired to it.
     *
     * The point is what it proves when the message *doesn't* appear: that the screen hook never
     * ran, which is a different fault from the button's own checks turning it down, and one that
     * no amount of logging inside those checks could ever reveal.
     */
    fun noteScreenOpened(screen: AbstractContainerScreen<*>) {
        if (!SkyQuantConfigManager.config.bazaar.logGraphButtonChecks) return

        val title = screen.title.string
        SkyQuantMod.LOGGER.info("Container opened, button hook attached: '{}'", title)
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§8[SkyQuant] menu: §7$title §8(hook attached)"),
        )
    }

    /**
     * True for Hypixel's bazaar menus, whose titles all carry the word - "Bazaar",
     * "Bazaar Orders", "Farming Bazaar" and so on.
     *
     * Matched on the word rather than the whole title so it survives the category prefixes,
     * and case-insensitively for the same reason. Formatting codes are stripped first: menu
     * titles carry them inline, and a raw match would miss a coloured one.
     */
    private fun screenAllowsButton(screen: AbstractContainerScreen<*>): Boolean =
        allowsButton(screen.title.string)

    /**
     * Exposed for tests: the screen it is asked about can't be built without a running game.
     *
     * Formatting codes are stripped first - menu titles carry them inline, so a raw match would
     * miss a coloured one.
     */
    fun allowsButton(title: String): Boolean {
        val plain = title.stripFormatting()
        return EXCLUDED_TITLES.none { plain.contains(it, ignoreCase = true) }
    }

    /** True when the item is a link to another menu rather than the subject of this one. */
    private fun isNavigationEntry(stack: ItemStack): Boolean {
        val lore = stack.get(DataComponents.LORE)?.lines().orEmpty()
        return lore.any { line -> NAVIGATION_HINTS.any { line.string.contains(it, ignoreCase = true) } }
    }

    private fun bounds(screen: AbstractContainerScreen<*>): ScreenRectangle {
        // Anchored to the container window itself, so it tracks GUI scale and screen size.
        val left = (screen.width - screen.imageWidth) / 2 + screen.imageWidth + GAP
        val top = (screen.height - screen.imageHeight) / 2

        return ScreenRectangle(left, top, WIDTH, HEIGHT)
    }
}
