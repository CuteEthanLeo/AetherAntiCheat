package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * AutoBlock(C) — Multi-Action / State Conflict Detection.
 * <p>
 * Detects when blocking coincides with actions that are mutually exclusive
 * in vanilla 1.8.9:
 * <ul>
 *   <li>Blocking + Attacking in the same tick (impossible — right-click and
 *       left-click cannot both be active in the same client tick).</li>
 *   <li>Blocking immediately after receiving knockback damage (the damage
 *       event would have interrupted any prior block).</li>
 *   <li>Holding block while also flagged as using an item (eating, drinking,
 *       bow-drawing — right-click can only serve one purpose).</li>
 * </ul>
 * This is the hardest variant for cheat developers to bypass because it
 * requires packet-order manipulation that the server can directly observe.
 */
public class AutoBlockCheckC extends Check {

    /** Maximum allowed time (ms) for block+attack in same window. */
    private static final long BLOCK_ATTACK_COEXIST_MS = 20;

    /** Buffer increment per violation step. */
    private static final double BUFFER_STEP = 0.45;

    /** Buffer decay per valid tick. */
    private static final double BUFFER_DECAY = 0.015;

    public AutoBlockCheckC(AetherAntiCheat plugin) {
        super("AutoBlockC", CheckType.COMBAT, 10, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!data.isBlocking()) return;

        long now = System.currentTimeMillis();

        // ── 1. Block + Attack same-tick detection ─────────────────────────
        // If the player attacked while blocking inside the coexistence window,
        // it's packet manipulation — vanilla cannot do both simultaneously.
        long lastBlockWhileAttack = data.getLastBlockWhileAttackTime();
        if (lastBlockWhileAttack > 0) {
            long delta = now - lastBlockWhileAttack;
            if (delta < BLOCK_ATTACK_COEXIST_MS) {
                int count = data.getBlockAttackSameTickCount();
                if (count >= 1) {
                    double buf = data.getAutoBlockCBuffer() + BUFFER_STEP;
                    data.setAutoBlockCBuffer(buf);
                    if (buf > 1.0) {
                        flag(player, data, String.format(
                                "BlockAttackSameTick delta=%dms count=%d buf=%.2f",
                                delta, count, buf));
                        data.setAutoBlockCBuffer(0.7);
                        data.setBlockAttackSameTickCount(0);
                    }
                    return;
                }
            }
        }

        // ── 2. Block + ItemUse conflict detection ──────────────────────────
        // Player cannot both block (sword right-click) and use an item
        // (eat/drink/bow) simultaneously — the same right-click action
        // can only serve one purpose per tick.
        if (data.isUsingItem()) {
            double buf = data.getAutoBlockCBuffer() + BUFFER_STEP;
            data.setAutoBlockCBuffer(buf);
            if (buf > 1.0) {
                flag(player, data, String.format(
                        "BlockWhileUsingItem itemTicks=%d buf=%.2f",
                        data.getUsingItemTicks(), buf));
                data.setAutoBlockCBuffer(0.7);
            }
            return;
        }

        // ── 3. Block immediately after taking damage ───────────────────────
        // When a player takes damage in vanilla 1.8.9, any active block is
        // released. If block_start happens within 5 ms of receiving damage,
        // the cheat re-engaged the block in the same tick as the hit landed.
        long damageTime = data.getLastDamageReceivedTime();
        long blockStart = data.getBlockStartTime();
        if (damageTime > 0 && blockStart > 0) {
            long delta = Math.abs(blockStart - damageTime);
            if (delta <= 5) {
                double buf = data.getAutoBlockCBuffer() + BUFFER_STEP;
                data.setAutoBlockCBuffer(buf);
                if (buf > 1.0) {
                    flag(player, data, String.format(
                            "BlockDamageSameTick delta=%dms buf=%.2f",
                            delta, buf));
                    data.setAutoBlockCBuffer(0.7);
                }
                return;
            }
        }

        // No violation — decay buffer
        data.setAutoBlockCBuffer(Math.max(0.0, data.getAutoBlockCBuffer() - BUFFER_DECAY));
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
