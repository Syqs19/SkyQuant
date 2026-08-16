package dev.syqs.skyquant.gui

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the themes against the faults that can't be seen from the code: a colour that reads as
 * invisible, a pair that a colour-blind player can't separate, or a theme that quietly reuses the
 * default's value for something it meant to override.
 *
 * These check relationships between colours rather than the colours themselves, so retuning a
 * theme doesn't break them - only making one unreadable does.
 */
class PaletteTest {

    private val themes = mapOf(
        "dark" to Palette.Theme.DARK,
        "red-green" to Palette.Theme.RED_GREEN,
        "blue-yellow" to Palette.Theme.BLUE_YELLOW,
        "high-contrast" to Palette.Theme.HIGH_CONTRAST,
    )

    @AfterTest
    fun restoreDefaultTheme() {
        Palette.theme = Palette.Theme.DARK
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun alpha(color: Int) = (color ushr 24) and 0xFF

    /** Perceived lightness, 0..255. Green dominates because the eye is most sensitive to it. */
    private fun luminance(color: Int): Double =
        0.2126 * red(color) + 0.7152 * green(color) + 0.0722 * blue(color)

    /**
     * Simulates deuteranopia by collapsing the red and green cones onto one response, then
     * reports whether the two colours still differ.
     *
     * An earlier version of this just compared lightness, and passed a theme that had gone back
     * to green vs red - those two happen to differ in lightness, so the check said "fine" about
     * the exact pairing this theme exists to avoid. Simulating the vision is the only way to
     * answer the question actually being asked.
     */
    private fun separableWithoutRedGreen(a: Int, b: Int): Boolean {
        // Both cones see the same mix, so red and green become one channel; blue is untouched.
        fun merged(color: Int) = 0.5 * red(color) + 0.5 * green(color)

        val redGreenGap = abs(merged(a) - merged(b))
        val blueGap = abs(blue(a) - blue(b))

        return redGreenGap > 40 || blueGap > 60
    }

    // --- tests -----------------------------------------------------------------------------

    @Test
    fun `every theme keeps text well clear of its background`() {
        // The failure this catches is a theme shipped with grey-on-grey somewhere: legible on the
        // machine it was tuned on, unreadable on a dimmer screen.
        for ((name, theme) in themes) {
            val gap = luminance(theme.text) - luminance(theme.background)
            assertTrue(gap > 120, "$name: text is only $gap from its background")
        }
    }

    @Test
    fun `every theme keeps the quietest text readable`() {
        // FAINT is where readability is lost first, so it gets its own floor rather than being
        // covered by the check on TEXT.
        for ((name, theme) in themes) {
            val gap = luminance(theme.faint) - luminance(theme.background)
            assertTrue(gap > 40, "$name: faint text is only $gap from its background")
        }
    }

    @Test
    fun `surfaces get lighter in order`() {
        // The whole design depends on depth reading as background then surface then raised. One
        // theme with those out of order would invert the layering everywhere at once.
        for ((name, theme) in themes) {
            val background = luminance(theme.background)
            val surface = luminance(theme.surface)
            val raised = luminance(theme.raised)

            assertTrue(surface > background, "$name: surface ($surface) is not above background ($background)")
            assertTrue(raised > surface, "$name: raised ($raised) is not above surface ($surface)")
        }
    }

    @Test
    fun `rise and fall are separable in the red-green theme`() {
        val theme = Palette.Theme.RED_GREEN
        assertTrue(
            separableWithoutRedGreen(theme.positive, theme.negative),
            "positive and negative collapse together without red-green vision",
        )
    }

    @Test
    fun `buy and sell are separable in the red-green theme`() {
        // Buy/sell carries the most important distinction on the chart, so it gets the same
        // check as rise/fall rather than being assumed safe for being blue and orange.
        val theme = Palette.Theme.RED_GREEN
        assertTrue(
            separableWithoutRedGreen(theme.buy, theme.sell),
            "buy and sell collapse together without red-green vision",
        )
    }

    @Test
    fun `the blue-yellow theme avoids the blue and amber pairing`() {
        // This theme exists precisely because the other three lean on blue vs amber, which is the
        // pair tritanopia cannot separate. Reusing it here would make the theme pointless.
        val theme = Palette.Theme.BLUE_YELLOW

        for ((label, color) in listOf("positive" to theme.positive, "negative" to theme.negative)) {
            val isBlue = blue(color) > red(color) + 40 && blue(color) > green(color) + 40
            val isAmber = red(color) > blue(color) + 60 && green(color) > blue(color) + 30
            assertTrue(!isBlue && !isAmber, "$label is a blue or amber tone, which this theme must avoid")
        }
    }

    @Test
    fun `the high-contrast theme is actually higher contrast than the default`() {
        // Named after a promise, so the promise gets checked: a rename or a retune could easily
        // leave it equal to the default without anything failing to compile.
        val standard = luminance(Palette.Theme.DARK.text) - luminance(Palette.Theme.DARK.background)
        val boosted = luminance(Palette.Theme.HIGH_CONTRAST.text) - luminance(Palette.Theme.HIGH_CONTRAST.background)

        assertTrue(boosted > standard, "high contrast ($boosted) is not above the default ($standard)")
    }

    @Test
    fun `panel backgrounds stay opaque enough to read against the game`() {
        // These are drawn over whatever the player is looking at. Too transparent and a bright
        // scene shows through the figures.
        for ((name, theme) in themes) {
            assertTrue(alpha(theme.background) > 0xD0, "$name: panel background is too transparent")
            assertTrue(alpha(theme.overlayBackground) > 0xD0, "$name: overlay background is too transparent")
        }
    }

    @Test
    fun `the panel border is visible against the panel it frames`() {
        // A border that matches its own background is just wasted geometry - and the failure is
        // silent, since the panel still draws, only without the edge it was given for.
        for ((name, theme) in themes) {
            val gap = abs(luminance(theme.border) - luminance(theme.background))
            assertTrue(gap > 8, "$name: border is only $gap from the panel it frames")
        }
    }

    @Test
    fun `each theme separates rise from fall`() {
        for ((name, theme) in themes) {
            assertNotEquals(theme.positive, theme.negative, "$name: rise and fall share one colour")
        }
    }

    @Test
    fun `switching the theme changes what the palette hands out`() {
        // The point of the whole indirection: screens read Palette, so setting the theme has to
        // change what they get without them being involved.
        Palette.theme = Palette.Theme.DARK
        val default = Palette.POSITIVE

        Palette.theme = Palette.Theme.RED_GREEN

        assertNotEquals(default, Palette.POSITIVE, "the palette kept handing out the old theme's colour")
    }
}
