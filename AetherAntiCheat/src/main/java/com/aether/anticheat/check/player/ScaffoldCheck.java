package com.aether.anticheat.check.player;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Scaffold Check — multi-layer fast-placement detection for 1.8.9.
 *
 * <h3>Detection Layers</h3>
 * <ol>
 *   <li><b>Placement Speed</b> — 12+ blocks/sec is impossible for a
 *       human (world record is ~8 with godbridge).</li>
 *   <li><b>Pitch Freeze (Silent Rotation)</b> — cheat clients freeze
 *       pitch while the real player moves freely. A streak of identical
 *       pitch values while moving = scaffold cheat.</li>
 *   <li><b>Telly-Bridge Yaw Snap</b> — &gt;135° yaw jump while airborne
 *       is the signature of telly-bridge cheats.</li>
 *   <li><b>Ray-Trace Validation</b> — verifies the placed block lies
 *       along the player's actual look ray. Ghost placements that don't
 *       align with the crosshair are impossible for legit clients.</li>
 * </ol>
 */
public class ScaffoldCheck extends Check {

    // ── Thresholds ─────────────────────────────────────────────────────
    private static final int    MAX_PLACES_PER_SEC = 12;    // human impossible above this
    private static final float  PITCH_FREEZE_EPSILON = 0.5f; // realistic pitch jitter (was 1e-4!)
    private static final int    PITCH_FREEZE_STREAK = 5;     // 5 consecutive frozen pitches
    private static final float  TELLY_YAW_SNAP = 135.0f;     // degrees — telly bridge signature
    private static final int    TELLY_MIN_SNAPS = 2;         // 2 snaps in a session = cheat
    private static final double RAY_STEP = 0.15;             // meters per step
    private static final double MAX_PLACE_REACH = 5.0;       // max placement distance
    private static final double RAY_BLOCK_TOLERANCE = 0.25;  // block distance tolerance for ray

    // ── Buffer increments ──────────────────────────────────────────────
    private static final double BUF_RATE_HIGH = 0.45;
    private static final double BUF_PITCH_FREEZE = 0.35;
    private static final double BUF_TELLY = 0.50;
    private static final double BUF_RAY_MISS = 0.40;
    private static final double DECAY_PER_TICK = 0.015;

    public ScaffoldCheck(AetherAntiCheat plugin) {
        super("Scaffold", CheckType.PLAYER, 10, plugin);
    }

    // ── Tick-level: placement speed check ─────────────────────────────

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) return;
        if (data.isFlying() || player.isFlying()) return;

        int placements = data.getSlidingPlaceCount();

        // 12+ blocks/sec is human-impossible (godbridge record ≈ 8/sec)
        if (placements > MAX_PLACES_PER_SEC) {
            data.setScaffoldBuffer(addBuffer(data.getScaffoldBuffer(), BUF_RATE_HIGH));

            if (data.getScaffoldBuffer() > 1.0) {
                flag(player, data, String.format(
                        "HighSpeed rate=%d/sec buf=%.2f", placements, data.getScaffoldBuffer()));
                data.setScaffoldBuffer(0.7);
            }
            return;
        }

        // Decay scaffold buffer when placement rate is normal
        data.setScaffoldBuffer(decayBuffer(data.getScaffoldBuffer(), DECAY_PER_TICK));

        // Decay telly snap counter over time (not placing → reset)
        if (placements == 0) {
            data.setTellySnapTicks(Math.max(0, data.getTellySnapTicks() - 1));
            data.setScaffoldPitchFrozenHits(
                    decayBuffer(data.getScaffoldPitchFrozenHits(), 0.02));
        }

        decayVL(data);
    }

    // ── Per-placement: deep analysis ──────────────────────────────────

    /**
     * Called on every BlockPlaceEvent for ray-trace + rotation analysis.
     * Runs BEFORE {@link #runCheck} in the event pipeline.
     */
    public void analyzePlacement(Player player, PlayerData data,
                                 Block blockPlaced, BlockFace againstFace) {
        if (player.getGameMode() == GameMode.CREATIVE) return;

        long now = System.currentTimeMillis();
        data.setLastScaffoldAnalysisTime(now);

        // ── Store placement rotation ────────────────────────────────
        float pitch = player.getLocation().getPitch();
        float yaw = player.getLocation().getYaw();
        float lastPitch = data.getLastPlacePitch();
        float lastYaw = data.getLastPlaceYaw();
        data.setLastPlacePitch(pitch);
        data.setLastPlaceYaw(yaw);

        // ── Layer 1: Ray-trace validation ──────────────────────────
        boolean rayOk = validateRayTrace(player, blockPlaced, againstFace);
        if (!rayOk) {
            data.setScaffoldBuffer(addBuffer(data.getScaffoldBuffer(), BUF_RAY_MISS));

            if (data.getScaffoldBuffer() > 1.0) {
                double eyeDist = player.getEyeLocation().toVector()
                        .distance(blockPlaced.getLocation().toVector());
                flag(player, data, String.format(
                        "RayMiss dist=%.2f face=%s", eyeDist, againstFace.name()));
                data.setScaffoldBuffer(0.75);
            }
            return;
        }

        // ── Layer 2: Pitch freeze (silent rotation) ─────────────────
        // Only flag if pitch is frozen while player IS moving
        boolean moving = player.getVelocity().lengthSquared() > 0.005;
        if (moving && Math.abs(pitch - lastPitch) < PITCH_FREEZE_EPSILON) {
            data.setScaffoldRotationStreak(data.getScaffoldRotationStreak() + 1);

            if (data.getScaffoldRotationStreak() >= PITCH_FREEZE_STREAK) {
                double inc = BUF_PITCH_FREEZE + (data.getScaffoldRotationStreak() - PITCH_FREEZE_STREAK) * 0.05;
                data.setScaffoldBuffer(addBuffer(data.getScaffoldBuffer(), inc));

                if (data.getScaffoldBuffer() > 1.0) {
                    flag(player, data, String.format(
                            "PitchFreeze streak=%d pitch=%.1f",
                            data.getScaffoldRotationStreak(), pitch));
                    data.setScaffoldRotationStreak(0);
                    data.setScaffoldBuffer(0.7);
                }
            }
        } else {
            // Natural pitch movement — decay streak
            data.setScaffoldRotationStreak(
                    Math.max(0, data.getScaffoldRotationStreak() - 1));
        }

        // ── Layer 3: Telly-bridge yaw snap ──────────────────────────
        if (!data.isOnGround() && lastYaw != 0.0f) {
            float deltaYaw = Math.abs(yaw - lastYaw) % 360;
            if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;

            if (deltaYaw > TELLY_YAW_SNAP) {
                data.setTellySnapTicks(data.getTellySnapTicks() + 1);

                if (data.getTellySnapTicks() >= TELLY_MIN_SNAPS) {
                    data.setScaffoldBuffer(addBuffer(data.getScaffoldBuffer(), BUF_TELLY));

                    if (data.getScaffoldBuffer() > 1.0) {
                        flag(player, data, String.format(
                                "TellyBridge yawSnap=%.1f° snaps=%d air=%d",
                                deltaYaw, data.getTellySnapTicks(), data.getAirTicks()));
                        data.setTellySnapTicks(0);
                        data.setScaffoldBuffer(0.7);
                    }
                }
            }
        } else if (data.isOnGround()) {
            data.setTellySnapTicks(Math.max(0, data.getTellySnapTicks() - 1));
        }
    }

    // ── Ray-trace: does the look ray pass through the placed block? ──

    /**
     * Casts a ray from the player's eyes along their look direction.
     * Verifies the ray passes through (or near) the placed block's
     * bounding box. Legitimate clients can ONLY place blocks where
     * the crosshair is pointing.
     *
     * <p>Accounts for block placement mechanics: the block is placed
     * adjacent to the face being clicked. We check both the placed
     * block AND the area near the clicked face.
     */
    private boolean validateRayTrace(Player player, Block placed, BlockFace againstFace) {
        Vector eye = player.getEyeLocation().toVector();
        Vector dir = player.getEyeLocation().getDirection().normalize();

        // Get the target block (the block against which placement happened)
        Block targetBlock = placed.getRelative(againstFace.getOppositeFace());

        // Step along ray, checking proximity to the placed block's location
        for (double dist = 0; dist <= MAX_PLACE_REACH; dist += RAY_STEP) {
            Vector point = eye.clone().add(dir.clone().multiply(dist));

            // Check if the ray passes near the placed block
            if (pointNearBlock(point, placed, 1.5)) {
                return true;
            }

            // Also check near the target block face
            if (pointNearBlock(point, targetBlock, 1.0)) {
                return true;
            }
        }

        return false;
    }

    /** Check if a 3D point is within tolerance of a block's center. */
    private static boolean pointNearBlock(Vector point, Block block, double tolerance) {
        double cx = block.getX() + 0.5;
        double cy = block.getY() + 0.5;
        double cz = block.getZ() + 0.5;
        double dx = point.getX() - cx;
        double dy = point.getY() - cy;
        double dz = point.getZ() - cz;
        return (dx * dx + dy * dy + dz * dz) <= (tolerance * tolerance);
    }

    // ── VL decay ─────────────────────────────────────────────────────

    private void decayVL(PlayerData data) {
        int vl = data.getViolation(getName());
        if (vl > 0) {
            data.resetViolation(getName());
            data.addViolationWithValue(getName(), Math.max(0, vl - 1));
        }
    }
}


