package com.aether.anticheat.check;

/**
 * Categories of cheat checks.
 */
public enum CheckType {

    /** Movement-related cheats: Speed, Fly, etc. */
    MOVEMENT("Movement"),

    /** Combat-related cheats: KillAura, Reach, etc. */
    COMBAT("Combat"),

    /** Player-related cheats: NoFall, FastEat, etc. */
    PLAYER("Player"),

    /** Miscellaneous cheats: InventoryMove, etc. */
    MISC("Misc");

    private final String displayName;

    CheckType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
