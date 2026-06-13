package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Detects Reach — extended attack distance.
 *
 * <p>In vanilla 1.8.9, the maximum player attack reach is 3.0 blocks
 * (center-to-center).  With latency compensation, this can stretch to
 * ~3.1-3.3 blocks.  Anything consistently above ~3.5 blocks indicates
 * a reach hack.</p>
 *
 * <p>We measure the distance between attacker and victim at the moment
 * of attack using the server-side location tracking.</p>
 */
public class ReachCheck extends Check {

    /** Vanilla max reach (center-to-center). */
    private static final double VANILLA_REACH = 3.0;

    /** Generous buffer for latency / sprint knockback. */
    private static final double LATENCY_BUFFER = 0.6;

    /** Combined reach threshold.  Anything above this is flagged. */
    private static final double MAX_REACH = VANILLA_REACH + LATENCY_BUFFER;

    public ReachCheck(AetherAntiCheat plugin) {
        super("Reach", CheckType.COMBAT, 12, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // Reach is checked in the attack handler with distance measurement
        // The actual distance calculation happens in CheckManager.onPlayerAttack()
        // because we need the victim's location.  This runCheck is kept for
        // the tick-based decay.
    }

    /**
     * Check reach distance for a specific attack.  Called from
     * CheckManager with the victim player reference.
     */
    public void checkReach(Player attacker, Player victim, PlayerData data) {
        if (attacker.getGameMode() == GameMode.CREATIVE) return;

        double dist = attacker.getLocation().distance(victim.getLocation());
        // Subtract the victim's bounding box radius (~0.4) for center-to-edge
        double adjustedDist = dist - 0.4;

        if (adjustedDist > MAX_REACH) {
            // Additional buffer per 50ms of latency
            double latencyBuffer = (data.getLatency() / 50.0) * 0.03;
            double finalMax = MAX_REACH + latencyBuffer;

            if (adjustedDist > finalMax) {
                flag(attacker, data, String.format(
                        "dist=%.2f adjusted=%.2f max=%.2f ping=%d",
                        dist, adjustedDist, finalMax, data.getLatency()));
            }
        }
    }
}
