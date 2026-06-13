package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * AutoBlock(B) — Block Toggle Consistency / Rapid Cycle Detection.
 * <p>
 * Detects impossibly fast block → unblock → block toggle cycles.
 * <p>
 * In vanilla 1.8.9, the right-click-to-block mechanic requires a physical
 * mouse button press and release. A human cannot toggle block more than
 * ~6–8 times per second due to finger biomechanics. Cheats that automate
 * blocking (e.g. block-hit macros or killaura with auto-block) produce
 * toggle rates of 15+ per second with near-perfect mechanical consistency.
 * <p>
 * This check also flags block durations that are too short to be human
 * (&lt; 30 ms) — a human finger cannot press and release a mouse button
 * that fast.
 */
public class AutoBlockCheckB extends Check {

    /** Maximum humanly possible block toggles per second. */
    private static final int MAX_TOGGLES_PER_SEC = 8;

    /** Minimum block duration (ms) a human can physically produce. */
    private static final long MIN_HUMAN_BLOCK_DURATION_MS = 30;

    /** Buffer increment per violation step. */
    private static final double BUFFER_STEP = 0.35;

    /** Buffer decay per valid tick. */
    private static final double BUFFER_DECAY = 0.015;

    public AutoBlockCheckB(AetherAntiCheat plugin) {
        super("AutoBlockB", CheckType.COMBAT, 8, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        long now = System.currentTimeMillis();

        // ── 1. Toggle rate check ──────────────────────────────────────────
        int toggleCount = data.getBlockToggleCount();
        long windowStart = data.getBlockToggleWindowStart();

        if (windowStart > 0 && toggleCount > 1) {
            long windowDuration = now - windowStart;
            if (windowDuration > 0 && windowDuration <= 1000) {
                double togglesPerSec = toggleCount * (1000.0 / windowDuration);
                if (togglesPerSec > MAX_TOGGLES_PER_SEC) {
                    double buf = data.getAutoBlockBBuffer() + BUFFER_STEP;
                    data.setAutoBlockBBuffer(buf);
                    if (buf > 1.0) {
                        flag(player, data, String.format(
                                "RapidToggle rate=%.1f/s toggles=%d window=%dms buf=%.2f",
                                togglesPerSec, toggleCount, windowDuration, buf));
                        data.setAutoBlockBBuffer(0.6);
                    }
                    return;
                }
            }
        }

        // ── 2. Ghost-block duration check ──────────────────────────────────
        // If block lasted less than MIN_HUMAN_BLOCK_DURATION_MS,
        // the mouse button was pressed and released faster than humanly possible.
        long lastDuration = data.getLastBlockDuration();
        if (lastDuration > 0 && lastDuration < MIN_HUMAN_BLOCK_DURATION_MS) {
            double buf = data.getAutoBlockBBuffer() + BUFFER_STEP;
            data.setAutoBlockBBuffer(buf);
            if (buf > 1.0) {
                flag(player, data, String.format(
                        "GhostBlock duration=%dms min=%dms buf=%.2f",
                        lastDuration, MIN_HUMAN_BLOCK_DURATION_MS, buf));
                data.setAutoBlockBBuffer(0.6);
            }
            return;
        }

        // No violation — decay buffer
        data.setAutoBlockBBuffer(Math.max(0.0, data.getAutoBlockBBuffer() - BUFFER_DECAY));
        decayVL(data);
    }

    private void decayVL(PlayerData data) {
        int vl = data.getViolation(getName());
        if (vl > 0) {
            data.resetViolation(getName());
            data.addViolationWithValue(getName(), Math.max(0, vl - 1));
        }
    }
}
