package com.aether.anticheat.check.player;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Advanced Packet-State Aware NoSlow Check for 1.8.9 Paper.
 *
 * <h3>Detection Layers</h3>
 * <ol>
 *   <li><b>SlotSwitch Bypass</b> — detects Grim-style rapid hotbar switching
 *       to reset the "using item" flag while still eating/blocking.</li>
 *   <li><b>Speed Limit</b> — hard cap on horizontal speed while using items,
 *       with buffer accumulation for sustained violations.</li>
 * </ol>
 *
 * <h3>How the SlotSwitch Bypass Works (Cheater Perspective)</h3>
 * <p>While eating a golden apple: every 1-2 ticks, send HeldItemSlot packet
 * to switch to a different slot and back. The server clears {@code isUsingItem},
 * allowing full sprint speed. The client continues eating because the switch
 * is packet-level only — the actual eating animation continues client-side.
 *
 * <h3>Our Fix</h3>
 * <p>Slot switches during item use set a 5-tick "suspicion window." During this
 * window, even though {@code isUsingItem} might be false, we still enforce
 * the using-item speed limit. Rapid switching (≥3/sec) is treated as
 * definitive automation and punished severely.
 */
public class NoSlowCheck extends Check {

    // 1.8.9 client max ground speed while using an item (eating/bow/blocking)
    private static final double MAX_USING_SPEED = 0.175;

    // Slot-switch bypass thresholds
    private static final int    SWITCH_SUSPICION_TICKS = 5;    // window after switch
    private static final int    SWITCH_RAPID_THRESHOLD = 3;    // 3+ switches/sec = cheat
    private static final double SWITCH_BYPASS_BUF_ADD = 0.45;  // heavy penalty for bypass
    private static final double SWITCH_RAPID_BUF_ADD = 0.35;   // penalty for rapid switching
    private static final double SWITCH_WINDOW_BUF_ADD = 0.25;  // base add for speed in window

    public NoSlowCheck(AetherAntiCheat plugin) {
        super("NoSlow", CheckType.PLAYER, 10, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (data.isFlying() || player.isFlying() || player.getAllowFlight()) return;
        if (player.isInsideVehicle() || data.isTeleportExempt()) return;

        // Air movement: defer to Speed/LongJump checks
        if (!data.isOnGround()) return;
        if (data.isRecentlyInLiquid() || data.isRecentlyInWeb()) return;

        boolean isBlocking = player.isBlocking();
        boolean isUsingItem = data.isUsingItem();
        boolean inSwitchWindow = data.getTickSinceSlotSwitch() <= SWITCH_SUSPICION_TICKS
                && data.wasUsingBeforeSwitch();

        // ── Layer 0: Slot-switch bypass detection ─────────────────────
        if (inSwitchWindow) {
            double hSpeed = data.getHorizontalDistance();

            // Player was using an item, switched slots, now moving fast = bypass attempt
            if (hSpeed > MAX_USING_SPEED) {
                if (data.getIceTicks() > 0) return; // ice momentum exemption

                // Rapid switching (3+ switches/sec) = definitive automation
                if (data.getSlotSwitchCount() >= SWITCH_RAPID_THRESHOLD) {
                    data.setNoSlowBuffer(data.getNoSlowBuffer() + SWITCH_RAPID_BUF_ADD);

                    if (data.getNoSlowBuffer() > 1.0) {
                        flag(player, data, String.format(
                                "SlotSwitchBypass(RAPID) switches=%d hSpeed=%.4f max=%.3f buf=%.2f",
                                data.getSlotSwitchCount(), hSpeed, MAX_USING_SPEED,
                                data.getNoSlowBuffer()));
                        data.setNoSlowBuffer(0.75);
                        return;
                    }
                }

                // Base penalty for moving fast during switch window
                double deviation = hSpeed - MAX_USING_SPEED;
                double increment = (deviation * 8.0) + SWITCH_BYPASS_BUF_ADD;
                data.setNoSlowBuffer(data.getNoSlowBuffer() + increment);

                if (data.getNoSlowBuffer() > 1.0) {
                    flag(player, data, String.format(
                            "SlotSwitchBypass hSpeed=%.4f max=%.3f dev=%.4f switches=%d ticks=%d buf=%.2f",
                            hSpeed, MAX_USING_SPEED, deviation,
                            data.getSlotSwitchCount(), data.getTickSinceSlotSwitch(),
                            data.getNoSlowBuffer()));
                    data.setNoSlowBuffer(0.75);
                    return;
                }
            }
            // Even if not speeding yet, rapid switching alone is punished
            else if (data.getSlotSwitchCount() >= SWITCH_RAPID_THRESHOLD) {
                data.setNoSlowBuffer(data.getNoSlowBuffer() + 0.20);
            }
        }

        // ── Decay switch window after it expires ──────────────────────
        if (data.getTickSinceSlotSwitch() > SWITCH_SUSPICION_TICKS
                && data.wasUsingBeforeSwitch()) {
            // Window expired — now we can safely clear the using state
            data.resetSlotSwitchState();
            if (!isBlocking && !isUsingItem) {
                data.setUsingItem(false);
            }
        }

        // ── Layer 1: Standard speed check ────────────────────────────
        // Only run if player is actually using an item (or we're in switch window)
        if (!isBlocking && !isUsingItem && !inSwitchWindow) {
            // Player not using anything — smooth decay
            data.setNoSlowBuffer(Math.max(0.0, data.getNoSlowBuffer() - 0.04));
            decayVL(data);
            return;
        }

        // Grace period: first 3 ticks of item use have momentum from previous state
        if (data.getUsingItemTicks() < 3 && !inSwitchWindow) {
            return;
        }

        double hSpeed = data.getHorizontalDistance();

        if (hSpeed > MAX_USING_SPEED) {
            if (data.getIceTicks() > 0) return;

            double deviation = hSpeed - MAX_USING_SPEED;
            double increment = (deviation * 10.0) + 0.2;
            double currentBuffer = data.getNoSlowBuffer() + increment;
            data.setNoSlowBuffer(currentBuffer);

            if (currentBuffer > 1.0) {
                Material hand = player.getItemInHand().getType();
                String mode = isBlocking ? "SwordBlock" : "ItemUse";

                flag(player, data, String.format(
                        "%s hSpeed=%.4f max=%.3f dev=%.4f buf=%.2f hand=%s",
                        mode, hSpeed, MAX_USING_SPEED, deviation, currentBuffer, hand.name()));
                data.setNoSlowBuffer(0.8);
            }
        } else {
            // Normal deceleration while using — slow decay
            data.setNoSlowBuffer(Math.max(0.0, data.getNoSlowBuffer() - 0.02));
            decayVL(data);
        }
    }

    private void decayVL(PlayerData data) {
        int vl = data.getViolation(getName());
        if (vl > 0) {
            data.resetViolation(getName());
            data.addViolationWithValue(getName(), Math.max(0, vl - 1));
        }
    }
}
