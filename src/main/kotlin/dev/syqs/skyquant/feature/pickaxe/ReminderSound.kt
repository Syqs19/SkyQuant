package dev.syqs.skyquant.feature.pickaxe

import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

/** Short, non-intrusive sounds suitable for a reminder that can repeat every few tens of seconds. */
enum class ReminderSound(private val displayName: String, val event: SoundEvent) {
    EXPERIENCE_ORB("Experience Orb", SoundEvents.EXPERIENCE_ORB_PICKUP),
    NOTE_BLOCK("Note Block", SoundEvents.NOTE_BLOCK_PLING.value()),
    CLICK("Click", SoundEvents.UI_BUTTON_CLICK.value()),
    ANVIL_LAND("Anvil Land", SoundEvents.ANVIL_LAND),
    BELL("Bell", SoundEvents.BELL_BLOCK),
    LEVEL_UP("Level Up", SoundEvents.PLAYER_LEVELUP),
    ITEM_PICKUP("Item Pickup", SoundEvents.ITEM_PICKUP),
    ;

    override fun toString(): String = displayName
}
