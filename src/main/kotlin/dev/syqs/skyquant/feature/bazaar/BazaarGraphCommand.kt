package dev.syqs.skyquant.feature.bazaar

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.syqs.skyquant.feature.bazaar.data.BazaarLivePrices
import dev.syqs.skyquant.feature.bazaar.data.BazaarPriceTrend
import dev.syqs.skyquant.feature.bazaar.gui.BazaarGraphScreen
import dev.syqs.skyquant.feature.bazaar.gui.BazaarHomeScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.util.concurrent.CompletableFuture

/**
 * Commands that open the bazaar screens.
 *
 * Deliberately not `/bazaar`: Hypixel already owns that name, and a client command registered
 * under it shadows the server's, which would break opening the actual bazaar. The terminal lives
 * under the mod's own `/skyquant bazaar`, with `/bloomberg` as a short alias.
 */
object BazaarGraphCommand {

    private const val MAX_SUGGESTIONS = 50

    fun register() {
        // Warms the product list so command completion works on the first use, and keeps the
        // snapshot current for anything else that reads prices. The fetch self-throttles to
        // Hypixel's 60s cache window, so ticking it every second costs one request a minute.
        ClientTickEvents.END_CLIENT_TICK.register {
            BazaarLivePrices.refreshIfStale()
            // Recorded here rather than while drawing: the overlay's trend line needs a history
            // that was accumulating before it was first looked at, and screens that draw it are
            // open for seconds at a time.
            BazaarPriceTrend.record()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(bazaarBranch("bloomberg"))
        }
    }

    /**
     * The command tree, under whichever [name] it's being mounted.
     *
     * Handed out rather than registered here so `/skyquant bazaar` can graft the same tree onto
     * the existing `/skyquant` command: Brigadier keeps only the last registration of a given
     * literal, so registering a second `/skyquant` separately would silently drop the config one.
     */
    fun bazaarBranch(name: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        literal<FabricClientCommandSource>(name)
            // Bare command opens the overview, since that's the screen a player wants when they
            // don't already have a specific item in mind.
            .executes {
                openLater { BazaarHomeScreen() }
                0
            }
            .then(
                argument<FabricClientCommandSource, String>("item", StringArgumentType.greedyString())
                    .suggests { _, builder -> suggestProducts(builder) }
                    .executes { context ->
                        val item = StringArgumentType.getString(context, "item")
                        openLater { BazaarGraphScreen(normalize(item)) }
                        0
                    },
            )

    /**
     * Opens a screen on the next tick: submitting a chat command closes the chat screen right
     * afterwards, which would otherwise close whatever this opened along with it.
     */
    private fun openLater(screen: () -> Screen) {
        Minecraft.getInstance().execute { Minecraft.getInstance().setScreen(screen()) }
    }

    /**
     * Completes from the live product list, so the names always match what the bazaar actually
     * sells. Empty until the first price fetch lands, in which case the player can still type
     * an id by hand.
     */
    private fun suggestProducts(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val typed = builder.remaining.replace(' ', '_').uppercase()

        BazaarLivePrices.productIds
            .asSequence()
            .filter { it.contains(typed) }
            .sortedBy { it.length }
            .take(MAX_SUGGESTIONS)
            .forEach(builder::suggest)

        return builder.buildFuture()
    }

    /** Accepts "enchanted diamond" as readily as "ENCHANTED_DIAMOND". */
    private fun normalize(input: String): String = input.trim().replace(' ', '_').uppercase()
}
