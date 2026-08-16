package dev.syqs.skyquant.feature.bazaar

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductNameTest {

    @Test
    fun `turns an id into words`() {
        assertEquals("Enchanted Diamond Block", ProductName.of("ENCHANTED_DIAMOND_BLOCK"))
    }

    @Test
    fun `handles a single word`() {
        assertEquals("Coal", ProductName.of("COAL"))
    }

    @Test
    fun `shortens the prefixes that crowd a narrow panel`() {
        assertEquals("Ench Diamond Block", ProductName.short("ENCHANTED_DIAMOND_BLOCK"))
    }

    @Test
    fun `leaves names without a known prefix alone`() {
        assertEquals("Coal", ProductName.short("COAL"))
    }
}
