package dev.syqs.skyquant.feature.bazaar.gui

import dev.syqs.skyquant.SkyQuantMod
import dev.syqs.skyquant.feature.bazaar.data.ItemIconData
import dev.syqs.skyquant.feature.bazaar.data.ItemIconIndex
import dev.syqs.skyquant.feature.bazaar.data.LegacyItemIds
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws the item's own icon beside its name.
 *
 * The whole feature is two components on an ordinary [ItemStack]. `ITEM_MODEL` points at the
 * model Hypixel's server resource pack supplies, which is what makes a Tungsten Plate look like a
 * tungsten plate rather than the sheet of paper it is built on; `PROFILE` carries a head's skin,
 * which covers armour and pets. Both are set from strings the NEU repository already gives us,
 * and Minecraft does the rest - there is nothing here to download, and no texture cache of our
 * own, because the resource pack is already loaded while playing on Hypixel and skins go through
 * the game's own cache.
 *
 * A player who declines the server's resource pack has none of those models. That case draws the
 * base item instead of failing, which is why the model is only applied when the client actually
 * has it.
 */
object ItemIcon {

    /**
     * Size an icon is drawn at, against a 12px table row.
     *
     * Item sprites are authored for 16px and this scales them down, which costs some crispness.
     * Growing the row to fit instead would cost a third of the rows on every tab - on Flip and
     * Forge, which are rankings dozens of items long, that is the more expensive trade. SkyHanni
     * makes the same choice for the same reason.
     */
    const val SIZE = 11

    /** Gap between the icon and the name that follows it. */
    const val GAP = 3

    /** Total width an icon takes out of the name column. */
    const val WIDTH = SIZE + GAP

    /**
     * Built stacks, keyed by Skyblock id.
     *
     * A table is rebuilt every frame, so without this the same twenty stacks would be constructed
     * sixty times a second - each one a registry lookup, a component write and, for the 55% of
     * items that are heads, a base64 decode. The entries are immutable and small, and the map is
     * bounded by how many distinct items the player has actually looked at.
     *
     * Null is cached as well as a stack. "This item has no icon" is the answer for anything the
     * repo has never described, and re-deriving that every frame costs exactly as much as deriving a
     * real one.
     *
     * Concurrent because it is *cleared* off-thread: every read and write happens while drawing,
     * but the repository download finishes on a background thread and drops the cache from there.
     * A plain HashMap being cleared underneath an in-progress lookup is the kind of fault that
     * appears once a session, on the frame a download lands, and never in testing.
     */
    private val cache = ConcurrentHashMap<String, Optional<ItemStack>>()

    /**
     * The stack to draw for a Skyblock id, or null if there is nothing to draw.
     *
     * Null rather than a placeholder is deliberate: the name simply sits where it would have sat
     * anyway, which reads as an item without a picture rather than as something missing.
     */
    fun stackFor(itemId: String): ItemStack? {
        // Wrapped in an Optional because "no icon" is a result worth remembering and a
        // ConcurrentHashMap cannot hold null. Caching the absence is the point: an item the repo
        // has never described is looked up on every frame it stays on screen.
        //
        // Keyed on the id as given, only uppercased. Reconciling the bazaar's `INK_SACK:3` with
        // the repository's `INK_SACK-3` is the index's job, and doing it here as well would mean
        // two places that have to agree about a rule only one of them owns.
        val cached = cache.computeIfAbsent(itemId.uppercase()) { id ->
            Optional.ofNullable(ItemIconIndex.iconFor(id)?.let { build(it) })
        }

        return cached.orElse(null)
    }

    /**
     * Draws the icon for [itemId] at [x], vertically centred in a row of [rowHeight].
     *
     * Takes the row rather than a y so the caller doesn't repeat the centring arithmetic, and
     * returns nothing: a row with no icon leaves the space empty by design.
     */
    fun draw(graphics: GuiGraphicsExtractor, itemId: String, x: Int, y: Int, rowHeight: Int) {
        val stack = stackFor(itemId) ?: return

        val scale = SIZE / 16f
        val top = y + (rowHeight - SIZE) / 2f

        // Item rendering always draws a 16x16 sprite, so the size is set by scaling the matrix
        // around the target corner rather than by any argument. Pushed and popped around the one
        // call: leaving a scale on the shared stack would shrink everything drawn afterwards.
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), top)
        graphics.pose().scale(scale, scale)
        graphics.item(stack, 0, 0)
        graphics.pose().popMatrix()
    }

    /**
     * Draws the icon at the size its sprite was authored for, with no scaling.
     *
     * For the one-off icon on a heading, where there is no row to fit inside. The tables accept
     * scaling because it buys them a third more rows; a single icon on a title buys nothing, and
     * at 16px the softness that scaling costs would be plain to see.
     */
    fun drawFullSize(graphics: GuiGraphicsExtractor, itemId: String, x: Int, y: Int) {
        val stack = stackFor(itemId) ?: return
        graphics.item(stack, x, y)
    }

    /** Drops every built stack. Called when the icon index is replaced by a fresh download. */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Rebuilds icons whenever the game's resources change.
     *
     * Necessary because [hasModel] is answered once per item and then cached, while the answer it
     * gives depends on which packs are mounted *at that moment*. Hypixel's textures arrive as a
     * server pack - mounted on joining, unmounted on leaving - so an icon first drawn from the
     * main menu records "this model does not exist" and keeps that verdict for the rest of the
     * session, staying flat long after the player has joined SkyBlock and the texture became
     * available. Clearing on reload is what lets that answer be revised.
     *
     * The listener is cheap: it drops a map the tables refill on their next frame.
     */
    fun register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): Identifier =
                    Identifier.fromNamespaceAndPath(SkyQuantMod.MOD_ID, "item_icons")

                override fun onResourceManagerReload(manager: ResourceManager) = clearCache()
            },
        )
    }

    /**
     * Builds the stack, or null if anything about this entry can't be turned into one.
     *
     * Wrapped because this runs inside `extractRenderState`, where an exception is a crash rather
     * than a missing icon - the first build of this took the game down on the frame the Status
     * tab drew a head, from one unsupported call. The data comes from a repository nobody here
     * controls, so the honest posture is that any single entry may be unusable, and the cost of
     * that has to be one blank icon.
     */
    private fun build(icon: ItemIconData): ItemStack? = runCatching { buildOrThrow(icon) }
        .onFailure { SkyQuantMod.LOGGER.warn("Could not build an icon for {}", icon.itemId, it) }
        .getOrNull()

    private fun buildOrThrow(icon: ItemIconData): ItemStack? {
        val modernId = LegacyItemIds.modernise(icon.itemId, icon.damage)
        val identifier = Identifier.tryParse(modernId) ?: return null

        // An id the registry doesn't know draws nothing rather than falling back to a stand-in.
        // The alternative is an icon that is confidently wrong, which on a table of prices is
        // worse than an icon that is absent.
        val item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null) ?: return null

        val stack = ItemStack(item)

        icon.itemModel
            ?.let { Identifier.tryParse(it) }
            ?.takeIf { hasModel(it) }
            ?.let { stack.set(DataComponents.ITEM_MODEL, it) }

        icon.skullTexture?.let { texture ->
            stack.set(DataComponents.PROFILE, profileFor(texture))
        }

        // The shimmer an Enchanted item has in game. Set explicitly rather than left to the item:
        // these stacks carry no enchantments of their own, so nothing would make them glint.
        if (icon.enchanted) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)

        return stack
    }

    /**
     * Whether this client can actually draw [model].
     *
     * Hypixel ships a server resource pack, and a player may decline it - in which case
     * `hypixel_skyblock:item/...` names a model that isn't loaded. Applying it anyway renders the
     * "missing model" cube, so an unavailable model is left off and the base item shows through.
     *
     * Looked up under `items/`, **not** `models/`. Both directories exist and hold different
     * things: `assets/<ns>/items/` holds the item-model *definitions* that `ITEM_MODEL` names,
     * while `assets/<ns>/models/item/` holds the geometry those definitions point at. Checking
     * the wrong one finds nothing for a Hypixel path, so every custom texture would be discarded
     * and every forge item would draw as its base - the exact failure this feature exists to
     * avoid, and one that looks like "Hypixel changed something" rather than like a bug here.
     */
    private fun hasModel(model: Identifier): Boolean {
        val path = Identifier.fromNamespaceAndPath(model.namespace, "items/${model.path}.json")
        return Minecraft.getInstance().resourceManager.getResource(path).isPresent
    }

    /**
     * The repository's texture blob as a component Minecraft will draw.
     *
     * Resolved rather than unresolved: an unresolved profile sends Mojang a name to look up,
     * which for a texture already in hand would be a network round trip per head - and 4888 of
     * the repo's items are heads. See [SkullProfile] for how the profile itself is put together.
     */
    private fun profileFor(texture: String): ResolvableProfile =
        ResolvableProfile.createResolved(SkullProfile.of(texture))

}
