package dev.syqs.skyquant.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formatting stripper three Hypixel parsers now share.
 *
 * Worth its own tests precisely because it is shared: a fault here breaks the tax detector, the
 * shop stock reader and the price graph button at once, and each of them fails by quietly finding
 * nothing rather than by erroring.
 */
class MinecraftTextTest {

    @Test
    fun `removes colour codes`() {
        assertEquals("Bazaar Flipper II", "§9Bazaar §9Flipper §9II".stripFormatting())
    }

    @Test
    fun `removes style codes as well as colours`() {
        // k through o are obfuscated, bold, strikethrough, underline and italic; r resets. Hypixel
        // uses the lot, and a stripper covering only 0-9a-f would leave half of them behind.
        //
        // Note the spacing: "§r§6" then "40" would read as the colour code §6 followed by "40",
        // which is what the game means by it - the digit after a code is text, not part of it.
        assertEquals("Stock: 640 remaining", "§lStock: §r640 remaining".stripFormatting())
        assertEquals("mixed", "§k§l§m§n§o§rmixed".stripFormatting())
    }

    @Test
    fun `handles upper-case codes`() {
        // The game accepts either case, so a pattern matching only lower-case would pass a title
        // through with its codes intact - and a title match then fails on text that looks right.
        assertEquals("Your Tax Rate: 1%", "§AYour Tax Rate: §F1%".stripFormatting())
    }

    @Test
    fun `leaves unformatted text exactly as it was`() {
        assertEquals("Enchanted Diamond", "Enchanted Diamond".stripFormatting())
        assertEquals("", "".stripFormatting())
    }

    @Test
    fun `keeps a bare section sign that is not a code`() {
        // Only the sign plus a valid code character is formatting. Dropping a lone § would quietly
        // edit text the game meant to show.
        assertEquals("50§", "50§".stripFormatting())
        assertEquals("a § b", "a § b".stripFormatting())
    }
}
