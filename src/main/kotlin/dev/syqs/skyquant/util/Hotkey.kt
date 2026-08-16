package dev.syqs.skyquant.util

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * A key polled while playing, firing once per press rather than every tick it's held.
 *
 * Polled rather than registered as a vanilla `KeyMapping` because the bindings already live in
 * the mod's own config screen; registering them again in the vanilla controls list would give
 * one action two places to be rebound, which then disagree.
 *
 * The edge detection is the part worth sharing: without remembering the previous state, holding
 * the key would fire twenty times a second.
 */
class Hotkey(
    /** Read fresh each tick so rebinding takes effect without a restart. */
    private val key: () -> Int,
    private val onPress: () -> Unit,
) {

    private var wasDown = false

    /** Called every client tick. [enabled] lets the caller suppress it without losing the state. */
    fun tick(minecraft: Minecraft, enabled: Boolean) {
        if (!enabled) {
            // Reset rather than return: otherwise a key held while a screen was open would fire
            // the moment the screen closes.
            wasDown = false
            return
        }

        val code = key()
        // An unbound key reads as -1, which GLFW would reject.
        if (code == GLFW.GLFW_KEY_UNKNOWN) {
            wasDown = false
            return
        }

        val down = InputConstants.isKeyDown(minecraft.window, code)
        if (down && !wasDown) onPress()
        wasDown = down
    }

    companion object {
        /** Readable name for a key code, e.g. "G" - falls back to the raw code if unnamed. */
        fun nameOf(key: Int): String =
            GLFW.glfwGetKeyName(key, 0)?.uppercase() ?: key.toString()
    }
}
