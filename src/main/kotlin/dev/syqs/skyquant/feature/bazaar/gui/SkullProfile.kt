package dev.syqs.skyquant.feature.bazaar.gui

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import java.util.UUID

/**
 * Builds the Mojang profile that carries a head's skin.
 *
 * Separate from [ItemIcon] so it can be tested: everything here is authlib, with no Minecraft
 * client behind it, where the rest of that class needs a running game to say anything. That
 * matters because this is where the feature first crashed - `GameProfile` is a record whose
 * properties are immutable, so the natural-looking "make a profile, then put the texture in it"
 * throws `UnsupportedOperationException`, and it throws inside the render pass, which takes the
 * game down rather than dropping one icon.
 *
 * The properties therefore have to exist before the profile does.
 */
object SkullProfile {

    /** The property name Mojang stores a skin under, used as both map key and property name. */
    private const val TEXTURES = "textures"

    /**
     * A profile carrying [texture], the base64 blob exactly as the NEU repository stores it.
     *
     * The texture is passed through untouched rather than decoded and re-encoded: it already
     * holds the skin URL in the form Minecraft expects, and re-encoding would only add a way to
     * get it wrong.
     *
     * The id is derived from the texture rather than random, so the same skin is always the same
     * profile - which is what lets Minecraft's own skin cache recognise it instead of treating
     * every row as a new player to look up.
     */
    fun of(texture: String): GameProfile = GameProfile(
        UUID.nameUUIDFromBytes(texture.toByteArray()),
        NAME,
        PropertyMap(ImmutableMultimap.of(TEXTURES, Property(TEXTURES, texture))),
    )

    /**
     * The name every icon profile carries.
     *
     * Never shown: these profiles exist to hold a texture, and the item's name is drawn from the
     * repository beside it. A constant keeps them from being mistaken for real players.
     */
    private const val NAME = "skyquant"
}
