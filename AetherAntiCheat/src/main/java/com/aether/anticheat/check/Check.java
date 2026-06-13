package com.aether.anticheat.check;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Abstract base class for all cheat checks.
 *
 * <h3>Anti False-Positive Mechanisms</h3>
 * <ul>
 *   <li><b>Flag Cooldown</b> — minimum 2s between flags for the same
 *       check on the same player, preventing rapid-fire false positives
 *       from stacking VL during a single combat encounter.</li>
 *   <li><b>Buffer Cap</b> — all check buffers are soft-capped at 1.5,
 *       preventing unbounded accumulation from long play sessions.</li>
 *   <li><b>Global VL Decay</b> — {@code CheckManager} periodically
 *       decays all violation levels by 1 (every 30s), so innocent
 *       players' VL naturally returns to 0 over time.</li>
 * </ul>
 */
public abstract class Check {

    private static final long MIN_FLAG_INTERVAL_MS = 2000; // 2s cooldown between flags
    public static final double BUFFER_SOFT_CAP = 1.5;      // prevent unbounded accumulation
    public static final int VL_DECAY_INTERVAL_TICKS = 600; // decay VL every 30s
    public static final int VL_DECAY_AMOUNT = 1;           // reduce VL by 1 per decay

    private final String name;
    private final CheckType type;
    private final int maxViolations;
    private final AetherAntiCheat plugin;
    private boolean enabled;

    public Check(String name, CheckType type, int maxViolations, AetherAntiCheat plugin) {
        this.name = name;
        this.type = type;
        this.maxViolations = maxViolations;
        this.plugin = plugin;
        this.enabled = true;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public String getName() { return name; }
    public CheckType getType() { return type; }
    public int getMaxViolations() { return maxViolations; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    protected AetherAntiCheat getPlugin() { return plugin; }

    // ── Core contract ─────────────────────────────────────────────────────

    /**
     * Execute this check against a player's current state.
     * Called every relevant event tick. Subclasses implement detection logic.
     */
    public abstract void runCheck(Player player, PlayerData data);

    // ── Flag & Alert ──────────────────────────────────────────────────────

    /**
     * Flag a player for suspicious behavior.
     *
     * <p>Enforces a minimum interval between flags (2s) to prevent
     * rapid false-positive accumulation during a single encounter.
     *
     * @param player  the flagged player
     * @param data    per-player state
     * @param details human-readable detection details
     */
    public void flag(Player player, PlayerData data, String details) {
        // ── Cooldown check ─────────────────────────────────────────
        long now = System.currentTimeMillis();
        long lastFlag = data.getLastFlagTime(name);
        if (now - lastFlag < MIN_FLAG_INTERVAL_MS) {
            return; // still in cooldown — skip
        }
        data.setLastFlagTime(name, now);

        // ── Increment VL ───────────────────────────────────────────
        int vl = data.addViolation(this);

        // Build alert
        String alertMsg = "§8[§4§l[Aether]§8] §7" + player.getName()
                + " §ffailed §c" + name
                + " §7[§e" + type.getDisplayName() + "§7]"
                + " §8(§cVL:" + vl + "§8/§c" + maxViolations + "§8)"
                + (details != null && !details.isEmpty() ? " §7→ §f" + details : "");

        // Broadcast to staff
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("aether.alerts")) {
                onlinePlayer.sendMessage(alertMsg);
            }
        }

        // Console log
        plugin.getLogger().warning("[" + name + "] " + player.getName()
                + " VL:" + vl + "/" + maxViolations
                + (details != null ? " | " + details : ""));

        // Punish if threshold reached
        if (vl >= maxViolations) {
            handlePunishment(player, data);
        }
    }

    /**
     * Decay this check's violation level by the given amount.
     * Called periodically by CheckManager for all online players.
     *
     * @param data   player data
     * @param amount amount to reduce (clamped to >= 0)
     */
    public void decayViolation(PlayerData data, int amount) {
        int vl = data.getViolation(name);
        if (vl > 0) {
            data.addViolationWithValue(name, Math.max(0, vl - amount));
        }
    }

    /**
     * Reset this check's violation level to 0.
     * Called on player quit or admin reset.
     */
    public void resetViolation(PlayerData data) {
        data.resetViolation(name);
    }

    /**
     * Called when a player exceeds the maximum violations.
     */
    public void handlePunishment(Player player, PlayerData data) {
        String command = plugin.getConfig().getString(
                "checks." + name.toLowerCase() + ".punish-command",
                plugin.getConfig().getString("punishment-command",
                        "kick %player% §c[Aether] §fUnfair advantage detected")
        );
        String formatted = command
                .replace("%player%", player.getName())
                .replace("%check%", name)
                .replace("%vl%", String.valueOf(data.getViolation(name)));

        final String cmd = formatted;
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        );

        // Broadcast punishment
        String punishMsg = "§8[§4§l[Aether]§8] §c" + player.getName()
                + " §7was punished for §c" + name + " §8(§cVL:" + data.getViolation(name) + "§8)";
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("aether.alerts")) {
                onlinePlayer.sendMessage(punishMsg);
            }
        }

        // Reset VL after punishment
        resetViolation(data);
    }

    // ── Buffer helper ─────────────────────────────────────────────────────

    /**
     * Safely add to a buffer with a soft cap, preventing unbounded growth.
     *
     * @param current current buffer value
     * @param add     amount to add
     * @return new buffer value (capped at BUFFER_SOFT_CAP)
     */
    public static double addBuffer(double current, double add) {
        double next = current + add;
        return Math.min(next, BUFFER_SOFT_CAP);
    }

    /**
     * Safely decrement a buffer, floor at 0.
     */
    public static double decayBuffer(double current, double amount) {
        return Math.max(0.0, current - amount);
    }
}
