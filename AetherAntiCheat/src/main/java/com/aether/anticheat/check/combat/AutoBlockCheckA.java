package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * AutoBlock(A) — Reaction-Time / Predictive Block Detection.
 * <p>
 * Detects when a player starts blocking within an impossibly short
 * window (≤ 50ms) before or after receiving damage.
 * <p>
 * Normal human visual reaction time is 150–250 ms. A cheat that reads
 * the incoming attack packet and auto-blocks can react in &lt; 5 ms.
 * Any block initiated within 50 ms of damage is physically impossible
 * for a human and indicates packet-level automation.
 */
public class AutoBlockCheckA extends Check {

    /** Maximum allowed delta between block start and damage (ms). */
    private static final long MAX_REACTION_DELTA_MS = 50;

    /** How much buffer we add per violation step. */
    private static final double BUFFER_STEP = 0.40;

    /** Slow decay per valid tick. */
    private static final double BUFFER_DECAY = 0.015;

    public AutoBlockCheckA(AetherAntiCheat plugin) {
        super("AutoBlockA", CheckType.COMBAT, 8, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!data.isBlocking()) return; // not blocking — nothing to check this tick

        long now = System.currentTimeMillis();
        long damageTime = data.getLastDamageReceivedTime();

        // No recent damage to compare against
        if (damageTime <= 0) return;

        long blockStart = data.getBlockStartTime();

        // Case 1: Block started AFTER damage arrived — check reaction window.
        // If block starts within MAX_REACTION_DELTA_MS of damage, it's automated.
        if (blockStart >= damageTime) {
            long delta = blockStart - damageTime;
            if (delta < MAX_REACTION_DELTA_MS) {
                double buf = data.getAutoBlockABuffer() + BUFFER_STEP;
                data.setAutoBlockABuffer(buf);
                if (buf > 1.0) {
                    flag(player, data, String.format(
                            "ReactionBlock delta=%dms blockStart=%d dmgTime=%d buf=%.2f",
                            delta, blockStart, damageTime, buf));
                    data.setAutoBlockABuffer(0.6);
                }
                return;
            }
        }

        // Case 2: Block started BEFORE damage — predictive block.
        // If block starts just before damage (≤50ms), the cheat anticipated the hit.
        if (blockStart < damageTime) {
            long delta = damageTime - blockStart;
            if (delta < MAX_REACTION_DELTA_MS) {
                double buf = data.getAutoBlockABuffer() + BUFFER_STEP;
                data.setAutoBlockABuffer(buf);
                if (buf > 1.0) {
                    flag(player, data, String.format(
                            "PredictiveBlock delta=%dms blockStart=%d dmgTime=%d buf=%.2f",
                            delta, blockStart, damageTime, buf));
                    data.setAutoBlockABuffer(0.6);
                }
                return;
            }
        }

        // No violation — decay buffer slowly
        data.setAutoBlockABuffer(Math.max(0.0, data.getAutoBlockABuffer() - BUFFER_DECAY));
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
