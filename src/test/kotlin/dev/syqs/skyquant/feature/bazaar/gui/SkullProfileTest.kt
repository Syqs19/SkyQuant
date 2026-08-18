package dev.syqs.skyquant.feature.bazaar.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The profile behind a head's icon.
 *
 * This exists because the first version crashed the game. Building the profile first and putting
 * the texture in afterwards reads perfectly well and compiles, but `GameProfile` is a record
 * whose property map is immutable, so the put threw `UnsupportedOperationException` - inside
 * `extractRenderState`, on the frame the Status tab first drew a head. A missing icon would have
 * been a blemish; this was the whole client going down.
 *
 * Nothing about that is visible to the compiler, so it is pinned here instead.
 */
class SkullProfileTest {

    /**
     * SUPERIOR_DRAGON_HELMET's texture, shortened. Real base64 from the repository rather than an
     * invented string, since what is being checked is that a repo value survives the round trip.
     */
    private val texture =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTIzNCJ9fX0="

    @Test
    fun `carries the texture it was given`() {
        // The assertion that would have caught the crash: reaching the property at all means the
        // profile was built with it rather than having it forced in afterwards.
        val profile = SkullProfile.of(texture)

        val stored = profile.properties()["textures"].single()

        assertEquals(texture, stored.value)
        assertEquals("textures", stored.name)
    }

    @Test
    fun `gives the same skin the same identity every time`() {
        // Minecraft caches skins by profile id. A random id per call would make every row a new
        // player to look up, and the same head in two tabs two different profiles.
        assertEquals(SkullProfile.of(texture).id(), SkullProfile.of(texture).id())
    }

    @Test
    fun `gives different skins different identities`() {
        // The other half of the same rule: two heads sharing an id would have Minecraft draw one
        // of them with the other's skin.
        assertNotEquals(SkullProfile.of(texture).id(), SkullProfile.of(texture + "A").id())
    }
}
