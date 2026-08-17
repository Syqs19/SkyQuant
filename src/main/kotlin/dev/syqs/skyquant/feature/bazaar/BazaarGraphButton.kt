package dev.syqs.skyquant.feature.bazaar

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.config.SkyQuantConfigManager
import dev.syqs.skyquant.feature.bazaar.gui.BazaarGraphScreen
import dev.syqs.skyquant.feature.bazaar.gui.RoundedRectRenderState
import dev.syqs.skyquant.gui.Palette
import dev.syqs.skyquant.util.escapeNonAscii
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

    /**
     * Centre of the second row, where a product page puts the item it is about.
     *
     * Read off a real page rather than described from memory: in the 36-slot grid the item sits at
     * row 1, column 4, with the two instant trades to its left and the two order forms to its
     * right. The previous note here called it "the middle of the top row", which is neither the
     * row nor the reason - and a wrong landmark is how a slot index gets "corrected" to something
     * that no longer points at the subject.
     */
    private const val SUBJECT_SLOT = 13

    /** Lore phrases Hypixel puts on entries that lead somewhere else. */
    private val NAVIGATION_HINTS = listOf("Click to view", "Click to open", "Click to browse")

    /**
     * The two trades every bazaar product page offers, matched on the item names in the menu.
     *
     * This is the positive signal the gate is built on, and it was read off a running game rather
     * than reasoned about: a survey of 23 menus found both names present in exactly the 6 product
     * pages and in none of the other 17 - the category lists, The Forge, Select Process, Refining,
     * Forging, Reforge Stones, Drill Parts, Pets and the rest. No false positives, no misses.
     *
     * It replaces a title blocklist, which was fragile in both directions. Titles carry nothing
     * stable to match on: Hypixel names a product page after the *item* ("Wheat & Seeds ➜ Wheat"),
     * so requiring the word "bazaar" silenced the button everywhere and stayed broken for three
     * sessions. Excluding "forge" instead worked for The Forge but also killed "Reforge Stones",
     * which merely contains the word - and it could only ever list menus already seen.
     *
     * Matching what the screen *offers to do* holds up because it is what the screen is for. A
     * menu with both an instant buy and an instant sell is a bazaar product page; that is the
     * definition rather than a heuristic about it.
     */
    private const val BUY_ENTRY = "Buy Instantly"
    private const val SELL_ENTRY = "Sell Instantly"

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
     * Four checks, narrowing from the screen to the item. The first settles what kind of menu this
     * is; the rest are about the item itself, since a menu can be the right kind and still hold
     * something uncharted in the subject slot.
     *
     * The lore check is kept although the gate ahead of it already rejects every category list
     * observed - on a list each entry says "Click to view details!", and the gate turns those away
     * for offering no trade. The two rules are close but not the same claim, and the cheaper one
     * covers the case the survey didn't contain: a menu that offers both trades while its subject
     * slot points elsewhere. Redundancy is the right price for a check that costs a lore read.
     *
     * Read every frame rather than cached on open: Hypixel sends the container empty and fills
     * it a moment later, so anything sampled once at init would always come back blank.
     */
    private fun productOf(screen: AbstractContainerScreen<*>): String? {
        if (!screenAllowsButton(screen)) return diagnose(screen, "menu offers no instant buy and sell")

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

    /** Titles already dumped by [surveyMenu], so each menu reports once rather than every frame. */
    private val surveyedTitles = mutableSetOf<String>()

    /**
     * Logs every filled slot of the open menu, once per menu, when diagnostics are enabled.
     *
     * Here to answer a question the existing diagnostics can't: *what does a bazaar product page
     * actually look like*. Turning this gate from a blocklist into an allowlist needs a signal
     * that says "this screen is a bazaar product page" positively, and the obvious candidates -
     * an item whose lore offers to buy or sell - are only guesses until a real menu has been read.
     * Guessing at Hypixel's wording is precisely what kept the button broken for three sessions,
     * so the wording gets read off the game before anything is written against it.
     *
     * Called from the per-frame draw rather than from screen init: Hypixel sends the container
     * empty and fills it a moment later, so a survey taken on open would report an empty menu and
     * prove nothing. Waits for a non-empty container for the same reason.
     */
    fun surveyMenu(screen: AbstractContainerScreen<*>) {
        if (!SkyQuantConfigManager.config.bazaar.logMenuSurvey) return

        val title = screen.title.string
        if (title in surveyedTitles) return

        // Everything before the player's own 36 inventory slots is the menu Hypixel sent.
        val menuSlots = screen.menu.slots.take((screen.menu.slots.size - 36).coerceAtLeast(0))
        val filled = menuSlots.withIndex().filter { !it.value.item.isEmpty }
        if (filled.isEmpty()) return

        surveyedTitles += title

        SkyQuantMod.LOGGER.info(
            "=== SkyQuant menu survey === title='{}' escaped='{}' menuSlots={} filled={}",
            title,
            title.escapeNonAscii(),
            menuSlots.size,
            filled.size,
        )
        for ((index, slot) in filled) {
            val stack = slot.item
            val lore = stack.get(DataComponents.LORE)?.lines().orEmpty().map { it.string }
            SkyQuantMod.LOGGER.info(
                "  slot {} | name='{}' | id={} | lore={}",
                index,
                stack.displayName.string,
                SkyblockItemId.of(stack),
                lore,
            )
        }

        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal(
                "§e[SkyQuant] §7surveyed §f$title §7- ${filled.size} items, written to the log",
            ),
        )
    }

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

        // Codepoints alongside the title: a title that *looks* right in a log can carry a
        // non-breaking space or a lookalike glyph, and a match against it would keep failing on a
        // line that reads as a match to the eye. The gate no longer depends on the title, but this
        // is the line someone reads when reporting a menu that behaves oddly, and a title that
        // can't be trusted at face value is worth showing honestly.
        SkyQuantMod.LOGGER.info(
            "Container opened, button hook attached: '{}' | escaped='{}'",
            title,
            title.escapeNonAscii(),
        )
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§8[SkyQuant] menu: §7$title §8(hook attached)"),
        )
    }

    /**
     * True when the open menu is a bazaar product page, judged by the trades it offers.
     *
     * Reads the menu's own slots rather than the player's: `menu.slots` ends with the 36 the
     * player carries, and an item named "Buy Instantly" sitting in the inventory would otherwise
     * vouch for any screen at all.
     */
    private fun screenAllowsButton(screen: AbstractContainerScreen<*>): Boolean {
        val slots = screen.menu.slots
        val menuSlots = slots.take((slots.size - 36).coerceAtLeast(0))
        return allowsButton(menuSlots.map { it.item.displayName.string })
    }

    /**
     * Exposed for tests: the screen it is asked about can't be built without a running game.
     *
     * Takes the menu's item names and answers whether both trades are on offer. Formatting codes
     * are stripped first - Hypixel's item names carry them inline, so a raw match would miss a
     * coloured one.
     */
    fun allowsButton(itemNames: List<String>): Boolean {
        val plain = itemNames.map { it.stripFormatting() }
        return plain.any { it.contains(BUY_ENTRY, ignoreCase = true) } &&
            plain.any { it.contains(SELL_ENTRY, ignoreCase = true) }
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
