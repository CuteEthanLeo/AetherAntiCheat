package com.aether.anticheat.check.player;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Detects FastPlace — blocks placed faster than vanilla mechanics allow.
 *
 * <p>In vanilla 1.8.9, block placement is limited by the right-click
 * action cooldown (4 ticks = 200ms between placements).  FastPlace
 * bypasses this cooldown, allowing 10-20 blocks/sec.</p>
 */
public class FastPlaceCheck extends Check {

    /** Minimum ms between block placements in vanilla (4 ticks). */
    private static final long MIN_PLACE_INTERVAL = 180; // 180ms = slightly less than 4 ticks for latency buffer

    /** Flag threshold — consecutive fast placements before alerting. */
    private static final int FAST_PLACE_THRESHOLD = 5;

    public FastPlaceCheck(AetherAntiCheat plugin) {
        super("FastPlace", CheckType.PLAYER, 8, plugin);
    }

    private int consecutiveFast = 0;

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE) return;

        long lastPlace = data.getLastBlockPlaceTime();
        long now = System.currentTimeMillis();

        if (lastPlace > 0) {
            long interval = now - lastPlace;
            if (interval < MIN_PLACE_INTERVAL && interval > 0) {
                consecutiveFast++;
                if (consecutiveFast >= FAST_PLACE_THRESHOLD) {
                    flag(player, data, String.format(
                            "interval=%dms count=%d", interval, consecutiveFast));
                }
            } else {
                consecutiveFast = Math.max(0, consecutiveFast - 1);
            }
        }
    }
}
