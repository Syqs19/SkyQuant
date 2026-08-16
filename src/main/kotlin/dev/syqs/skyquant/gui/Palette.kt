package dev.syqs.skyquant.gui

/**
 * The mod's colours, in one place, for the theme the player picked.
 *
 * Every screen asks for a colour by **role** ([POSITIVE], not "green"), so swapping the theme
 * repaints the whole mod without a screen knowing a theme exists. That indirection is also what
 * makes the colour-blind themes possible at all: they aren't a special case in the drawing code,
 * just a different table behind the same names.
 *
 * The rules the themes are built on:
 * - **Colour never carries meaning alone.** A rise is green *and* an up arrow; the selected row is
 *   tinted *and* marked. Strip the colour out and the screen still reads - which is the test that
 *   makes a theme swap safe rather than a redesign.
 * - **Depth comes from layered backgrounds, not borders.** [BACKGROUND] -> [SURFACE] -> [RAISED]
 *   each step lighter; the eye reads the gradient. Drawn borders cost geometry in the split-phase
 *   renderer and box everything in. The exception is [BORDER], the hairline around a whole panel,
 *   which separates the mod from the game behind it rather than one region from another.
 * - **Never pure black or pure white**, which cause the text to appear to bleed into its
 *   background. The high-contrast theme comes closest, deliberately.
 * - **The accent is rationed** to what is live, selected or interactive. Spend it everywhere and
 *   it stops meaning anything.
 */
object Palette {

    /** The theme in use. Set from the config at startup and whenever the player changes it. */
    @JvmStatic
    var theme: Theme = Theme.DARK

    // --- Surfaces -----------------------------------------------------------------------------
    // Three steps, each lighter than the last. A panel sits on BACKGROUND, an inset area (a chart
    // plot, a header strip) on SURFACE, and whatever must float above both on RAISED.

    /** Panel backgrounds. Slightly transparent, so the game stays visible behind a screen. */
    val BACKGROUND: Int get() = theme.background

    /** Inset areas within a panel, like the chart's plot area or a header strip. */
    val SURFACE: Int get() = theme.surface

    /** The lightest step: a selected row, a hovered button. */
    val RAISED: Int get() = theme.raised

    /** Tooltips and the overlay, which sit over moving scenery and need more separation. */
    val OVERLAY_BACKGROUND: Int get() = theme.overlayBackground

    /**
     * Hairline around a panel. Panels are translucent and sit over the world, so without an edge
     * a bright scene bleeds into the layout and the panel loses its shape - the one place a
     * drawn line does more work than a change of surface.
     */
    val BORDER: Int get() = theme.border

    // --- Text ---------------------------------------------------------------------------------
    // Four steps of emphasis. Most text is NAME or MUTED; TEXT is for what the eye should land on
    // first, FAINT for what it should find only when looking.

    /** Primary text: off-white. Values, the selected row, anything that answers the question. */
    val TEXT: Int get() = theme.text

    /** Item names and other secondary content. */
    val NAME: Int get() = theme.name

    /** Labels and units - present, but not competing with the values. */
    val MUTED: Int get() = theme.muted

    /** Hints and footnotes, the quietest readable step. */
    val FAINT: Int get() = theme.faint

    /** Column headings and section labels. */
    val HEADING: Int get() = theme.heading

    // --- Meaning ------------------------------------------------------------------------------

    /** Buy side. */
    val BUY: Int get() = theme.buy

    /** Sell side. */
    val SELL: Int get() = theme.sell

    /** Selection, focus, and anything live. Rationed on purpose. */
    val ACCENT: Int get() = theme.accent

    /** A rise. Always drawn with an arrow too, so the meaning survives without the colour. */
    val POSITIVE: Int get() = theme.positive

    /** A fall. Likewise always paired with an arrow. */
    val NEGATIVE: Int get() = theme.negative

    /** A feed that has stopped updating - worse than no feed, so it gets a warning colour. */
    val STALE: Int get() = theme.sell

    // --- Controls -----------------------------------------------------------------------------

    /** Row highlight under the cursor. */
    val ROW_HOVER: Int get() = theme.raised

    val BUTTON: Int get() = theme.button
    val BUTTON_HOVER: Int get() = theme.buttonHover
    val BUTTON_ACTIVE: Int get() = theme.buttonActive

    /**
     * A button that can't be pressed because the data behind it doesn't exist - Coflnet keeps no
     * hourly auction prices, and no monthly bazaar ones.
     *
     * Derived from [BUTTON] at a third of its opacity rather than given its own theme field: it
     * is the same button, receding, so it should follow whatever colour a theme chooses for one.
     * Kept visible instead of hidden so the row of ranges doesn't reshuffle between items - and
     * paired with a hover hint, since colour alone never carries information in this UI.
     */
    val BUTTON_DISABLED: Int get() = theme.button and 0x00FFFFFF or 0x55000000

    /**
     * Buttons the mod draws on top of Hypixel's own menus. Lighter than [BUTTON] so they read as
     * ours rather than as part of the menu, and slightly transparent so they don't look pasted on.
     */
    val FOREIGN_BUTTON: Int get() = theme.foreignButton
    val FOREIGN_BUTTON_HOVER: Int get() = theme.foreignButtonHover

    /** Outline around a panel being positioned; the active one is the same accent, opaque. */
    val OUTLINE: Int get() = theme.accent and 0x00FFFFFF or 0x40000000
    val GUIDE: Int get() = theme.accent and 0x00FFFFFF or 0x80000000.toInt()

    // --- Lines and structure ------------------------------------------------------------------
    // Kept as translucent white so they sit on whatever surface they're drawn over, rather than
    // needing a variant per background.

    /** Separator rules, faint enough to structure without drawing the eye. */
    val RULE: Int get() = theme.rule

    /** Chart gridlines and the divider above the volume band. */
    val GRID: Int get() = theme.grid

    /** The chart's own axes, a step stronger than the gridlines. */
    val AXIS: Int get() = theme.axis

    /** The readout line that follows the cursor across a chart. */
    val HOVER_LINE: Int get() = theme.hoverLine

    /** Volume bars: dim and desaturated, context for the price rather than a series of its own. */
    val VOLUME: Int get() = theme.volume

    /**
     * The low-to-high span of an hour's auction sales, drawn behind the average line.
     *
     * Derived from the buy colour at low opacity rather than declared per theme: it is the same
     * series as the line in front of it, shown as an extent instead of a value, so it should
     * follow that colour wherever a theme takes it. A fifth of full opacity keeps the curve and
     * the gridlines readable through it.
     */
    val RANGE_BAND: Int get() = theme.buy and 0x00FFFFFF or 0x33000000

    /** Track behind a filled bar. */
    val TRACK: Int get() = theme.track

    /**
     * One complete set of colours. Every field is required - a theme can't inherit half of another
     * one, because a half-defined theme is exactly how an unreadable combination gets shipped.
     */
    data class Theme(
        val background: Int,
        val surface: Int,
        val raised: Int,
        val overlayBackground: Int,
        val border: Int,
        val text: Int,
        val name: Int,
        val muted: Int,
        val faint: Int,
        val heading: Int,
        val buy: Int,
        val sell: Int,
        val accent: Int,
        val positive: Int,
        val negative: Int,
        val button: Int,
        val buttonHover: Int,
        val buttonActive: Int,
        val foreignButton: Int,
        val foreignButtonHover: Int,
        val rule: Int,
        val grid: Int,
        val axis: Int,
        val hoverLine: Int,
        val volume: Int,
        val track: Int,
    ) {
        companion object {

            /**
             * The default. Buy and sell are blue and orange rather than green and red: red/green
             * is the pair colour-blind players can least tell apart, and those two carry the most
             * important distinction on the screen. Rises and falls do use green and red, since
             * that convention is deeply set in Skyblock - but always with an arrow beside them.
             */
            val DARK = Theme(
                background = 0xF0131419.toInt(),
                surface = 0xFF1A1C23.toInt(),
                raised = 0xFF22242D.toInt(),
                overlayBackground = 0xF00E0E13.toInt(),
                border = 0xFF2B2D38.toInt(),
                text = 0xFFE7E9EE.toInt(),
                name = 0xFFC4C9D2.toInt(),
                muted = 0xFF9AA1AE.toInt(),
                faint = 0xFF656C7A.toInt(),
                heading = 0xFF7C8593.toInt(),
                buy = 0xFF60A5FA.toInt(),
                sell = 0xFFFB923C.toInt(),
                accent = 0xFF7DD3FC.toInt(),
                positive = 0xFF5EE9A0.toInt(),
                negative = 0xFFF4566E.toInt(),
                button = 0xFF22242D.toInt(),
                buttonHover = 0xFF2C2E38.toInt(),
                buttonActive = 0xFF383A46.toInt(),
                foreignButton = 0xF01E1E26.toInt(),
                foreignButtonHover = 0xF02B2B36.toInt(),
                rule = 0x1FFFFFFF,
                grid = 0x24FFFFFF,
                axis = 0x66FFFFFF,
                hoverLine = 0x59FFFFFF,
                volume = 0x40FFFFFF,
                track = 0x33FFFFFF,
            )

            /**
             * Red-green colour blindness, which is around 99% of all cases.
             *
             * Rises and falls drop the green/red convention for blue and amber - the pair that
             * survives both deuteranopia and protanopia. Buy and sell then have to move too, or
             * they would collide with the very colours that just took their place: they become a
             * lighter blue and a deeper amber, separated by lightness as much as by hue.
             */
            val RED_GREEN = Theme(
                background = 0xF0131419.toInt(),
                surface = 0xFF1A1C23.toInt(),
                raised = 0xFF22242D.toInt(),
                overlayBackground = 0xF00E0E13.toInt(),
                border = 0xFF2B2D38.toInt(),
                text = 0xFFE7E9EE.toInt(),
                name = 0xFFC4C9D2.toInt(),
                muted = 0xFF9AA1AE.toInt(),
                faint = 0xFF656C7A.toInt(),
                heading = 0xFF7C8593.toInt(),
                buy = 0xFF4D94E8.toInt(),
                sell = 0xFFD98324.toInt(),
                accent = 0xFF7DD3FC.toInt(),
                positive = 0xFF7DB8FF.toInt(),
                negative = 0xFFF0A030.toInt(),
                button = 0xFF22242D.toInt(),
                buttonHover = 0xFF2C2E38.toInt(),
                buttonActive = 0xFF383A46.toInt(),
                foreignButton = 0xF01E1E26.toInt(),
                foreignButtonHover = 0xF02B2B36.toInt(),
                rule = 0x1FFFFFFF,
                grid = 0x24FFFFFF,
                axis = 0x66FFFFFF,
                hoverLine = 0x59FFFFFF,
                volume = 0x40FFFFFF,
                track = 0x33FFFFFF,
            )

            /**
             * Blue-yellow colour blindness (tritanopia). Far rarer - roughly 0.003% - but the one
             * theme the other three can't stand in for, because every one of them leans on blue
             * and amber, which is precisely the pair this type cannot separate.
             *
             * Teal and pink replace them: distinguishable here, and still distinguishable to
             * everyone else.
             */
            val BLUE_YELLOW = Theme(
                background = 0xF0131419.toInt(),
                surface = 0xFF1A1C23.toInt(),
                raised = 0xFF22242D.toInt(),
                overlayBackground = 0xF00E0E13.toInt(),
                border = 0xFF2B2D38.toInt(),
                text = 0xFFE7E9EE.toInt(),
                name = 0xFFC4C9D2.toInt(),
                muted = 0xFF9AA1AE.toInt(),
                faint = 0xFF656C7A.toInt(),
                heading = 0xFF7C8593.toInt(),
                buy = 0xFF3FD0C9.toInt(),
                sell = 0xFFE86AA8.toInt(),
                accent = 0xFF5FDDD6.toInt(),
                positive = 0xFF3FD0C9.toInt(),
                negative = 0xFFE86AA8.toInt(),
                button = 0xFF22242D.toInt(),
                buttonHover = 0xFF2C2E38.toInt(),
                buttonActive = 0xFF383A46.toInt(),
                foreignButton = 0xF01E1E26.toInt(),
                foreignButtonHover = 0xF02B2B36.toInt(),
                rule = 0x1FFFFFFF,
                grid = 0x24FFFFFF,
                axis = 0x66FFFFFF,
                hoverLine = 0x59FFFFFF,
                volume = 0x40FFFFFF,
                track = 0x33FFFFFF,
            )

            /**
             * For bright screens and for anyone who finds grey-on-grey hard to read.
             *
             * Darker grounds, brighter text, and rules roughly twice as strong - the faint steps
             * are where readability is lost first, so those move most. The accent stays the same
             * hue: raising its brightness too would leave nothing to stand out against.
             */
            val HIGH_CONTRAST = Theme(
                background = 0xF6000004.toInt(),
                surface = 0xFF0C0C11.toInt(),
                raised = 0xFF1C1C24.toInt(),
                overlayBackground = 0xFA000002.toInt(),
                border = 0xFF6E7686.toInt(),
                text = 0xFFFFFFFF.toInt(),
                name = 0xFFE8ECF4.toInt(),
                muted = 0xFFC8CEDA.toInt(),
                faint = 0xFF9BA3B2.toInt(),
                heading = 0xFFB4BCCA.toInt(),
                buy = 0xFF7AB8FF.toInt(),
                sell = 0xFFFFB03A.toInt(),
                accent = 0xFF8FDCFF.toInt(),
                positive = 0xFF5DFFA8.toInt(),
                negative = 0xFFFF6B7F.toInt(),
                button = 0xFF1C1C24.toInt(),
                buttonHover = 0xFF2A2A34.toInt(),
                buttonActive = 0xFF3A3A46.toInt(),
                foreignButton = 0xF6141419.toInt(),
                foreignButtonHover = 0xF6242430.toInt(),
                rule = 0x4DFFFFFF,
                grid = 0x59FFFFFF,
                axis = 0xB3FFFFFF.toInt(),
                hoverLine = 0xA6FFFFFF.toInt(),
                volume = 0x73FFFFFF,
                track = 0x59FFFFFF,
            )
        }
    }
}
