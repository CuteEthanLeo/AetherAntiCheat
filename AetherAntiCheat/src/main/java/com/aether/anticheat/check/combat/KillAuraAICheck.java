package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import com.aether.anticheat.prediction.KillAuraAIModel;
import com.aether.anticheat.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * KillAuraAI — Simplified 2-layer detection: GCD + AI Model.
 *
 * <h3>Design Philosophy</h3>
 * <p>Every detection layer adds false-positive surface. We deliberately
 * removed the heuristic and silent-aim layers because:
 * <ul>
 *   <li>Heuristic (perfect streak, avg error thresholds) overlaps with
 *       the AI model's anomaly detection — the model already captures
 *       these patterns from training data with proper weighting.</li>
 *   <li>SilentAim (high error + zero rotation) false-positives on
 *       laggy players and tight-space PvP. The GCD layer catches
 *       actual SmoothAim/SilentAim cheats via the more reliable
 *       mouse-hardware GCD fingerprint.</li>
 * </ul>
 *
 * <h3>Detection Layers (2 total)</h3>
 * <ol>
 *   <li><b>GCD</b> — Mouse-hardware gcd residue analysis.
 *       Real mice produce rotation deltas that are integer multiples
 *       of the 1.8.9 sensitivity GCD. Cheat clients using math
 *       functions (sin/cos/lerp) break this integer-multiple pattern.</li>
 *   <li><b>AI Model</b> — Statistical anomaly detection (soon CNN).
 *       Scores how far the live feature vector deviates from the
 *       trained "normal" profile.</li>
 * </ol>
 */
public class KillAuraAICheck extends Check {

    // ── GCD thresholds ────────────────────────────────────────────────
    private static final double GCD_DEV_SUS = 0.008;   // suspicious gcd residue (was 0.004)
    private static final double GCD_DEV_HIGH = 0.018;  // high-confidence gcd break

    // ── Model thresholds ──────────────────────────────────────────────
    private static final double MODEL_SUSPICION_MIN = 0.30; // was 0.25
    private static final double COLOR_YELLOW = MODEL_SUSPICION_MIN; // suspicion > this → yellow bar

    // ── Buffer tuning ─────────────────────────────────────────────────
    private static final double BUF_GCD_HIGH = 0.40;
    private static final double BUF_GCD_NORM = 0.28;
    private static final double BUF_MODEL_BASE = 0.30;

    // ── Debug ─────────────────────────────────────────────────────────
    private final Set<UUID> debugPlayers = new HashSet<>();

    private KillAuraAIModel aiModel;

    public KillAuraAICheck(AetherAntiCheat plugin) {
        super("KillAuraAI", CheckType.COMBAT, 12, plugin);
    }

    public void setAiModel(KillAuraAIModel model) { this.aiModel = model; }
    public KillAuraAIModel getAiModel() { return aiModel; }
    public void setDebugPlayer(UUID uuid, boolean on) {
        if (on) debugPlayers.add(uuid); else debugPlayers.remove(uuid);
    }
    public boolean isDebugPlayer(UUID uuid) { return debugPlayers.contains(uuid); }

    // ── runCheck (movement ticks — decay only) ────────────────────────

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (System.currentTimeMillis() - data.getLastAttackTime() > 150) return;

        float yaw = player.getLocation().getYaw();
        float lastYaw = data.getLastAttackYaw();
        float deltaYaw = (float) MathUtil.distBetweenAngles360(yaw, lastYaw);
        if (deltaYaw < 0.01) return;

        // Decay only
        data.setAiCheckBuffer(Math.max(0.0, data.getAiCheckBuffer() - 0.06));
    }

    // ── Attack-time detection ──────────────────────────────────────────

    public void checkOnAttack(Player attacker, PlayerData data,
                              float deltaYaw, float deltaPitch,
                              LivingEntity victim,
                              float targetYaw, float targetPitch) {
        if (attacker.getGameMode() == GameMode.CREATIVE) return;

        boolean debug = debugPlayers.contains(attacker.getUniqueId());

        if (debug) {
            getPlugin().getLogger().info("[KillAuraAI|DBG] " + attacker.getName()
                    + " dY=" + String.format("%.2f", deltaYaw)
                    + " dP=" + String.format("%.2f", deltaPitch)
                    + " buf=" + String.format("%.3f", data.getAiCheckBuffer())
                    + " samples=" + data.getTotalAimSamples()
                    + " aiErr=" + String.format("%.2f", data.getAvgAimError())
                    + " yawStd=" + String.format("%.4f", data.getYawDeltaStdDev())
                    + " atkInt=" + (data.getLastAttackTime() > 0
                        ? System.currentTimeMillis() - data.getLastAttackTime() : 0) + "ms");
        }

        // GCD needs meaningful rotation; Model ALWAYS runs (for SilentAim detection)
        boolean gcdHit = false;
        if (deltaYaw >= 0.01 || deltaPitch >= 0.01) {
            gcdHit = checkGCD(data, deltaYaw, deltaPitch, debug);
        }
        // Always run model — CNN needs every attack, including zero-rotation ones
        boolean modelHit = checkModel(attacker, data, deltaYaw, deltaPitch,
                victim, targetYaw, targetPitch, debug);

        if (debug) {
            getPlugin().getLogger().info("[KillAuraAI|DBG] " + attacker.getName()
                    + " gcd=" + gcdHit + " model=" + modelHit
                    + " buf=" + String.format("%.3f", data.getAiCheckBuffer()));
        }

        // Decay if nothing flagged
        if (!gcdHit && !modelHit) {
            data.setAiCheckBuffer(Math.max(0.0, data.getAiCheckBuffer() - 0.08));
        }
    }

    // ── GCD (Mouse Hardware Fingerprint) ──────────────────────────────

    /**
     * Every real mouse in 1.8.9 produces rotation deltas that are integer
     * multiples of the sensitivity GCD. Cheat clients using smooth
     * interpolation (sin, cos, lerp) produce non-integer multiples.
     *
     * <p>The GCD residue is how far the delta is from the nearest
     * multiple. A real mouse: residue ≈ 0. A smooth-aim cheat: residue ≠ 0.
     */
    private boolean checkGCD(PlayerData data, float deltaYaw, float deltaPitch, boolean debug) {
        double gcd = data.getMouseGCD();
        if (gcd <= 0.0001) return false;

        double remY = deltaYaw % gcd;
        double remP = deltaPitch % gcd;
        double devY = Math.min(remY, gcd - remY);
        double devP = Math.min(remP, gcd - remP);

        boolean yHigh = devY > GCD_DEV_HIGH;
        boolean pHigh = devP > GCD_DEV_HIGH;
        boolean ySus = devY > GCD_DEV_SUS;
        boolean pSus = devP > GCD_DEV_SUS;
        boolean extreme = deltaYaw >= 60.0f || deltaPitch >= 45.0f;

        if (debug) {
            getPlugin().getLogger().info("[KillAuraAI|DBG] GCD devY="
                    + String.format("%.6f", devY) + " devP="
                    + String.format("%.6f", devP) + " extreme=" + extreme);
        }

        // Extreme rotations (60°+ yaw, 45°+ pitch) → ignore, likely legit flick
        if (extreme) return false;

        // Strong signal: both yaw AND pitch deviate from GCD
        if (yHigh && pHigh) {
            data.setAiCheckBuffer(data.getAiCheckBuffer() + BUF_GCD_HIGH);

            if (data.getAiCheckBuffer() > 1.0) {
                flag(playerFromData(data), data, String.format(
                        "SmoothAim(GCD) devY=%.5f devP=%.5f gcd=%.6f dy=%.2f dp=%.2f",
                        devY, devP, gcd, deltaYaw, deltaPitch));
                data.setAiCheckBuffer(0.12);
                return true;
            }
        }
        // Moderate signal: one axis deviates, the other is suspicious
        else if ((yHigh && pSus) || (pHigh && ySus)) {
            data.setAiCheckBuffer(data.getAiCheckBuffer() + BUF_GCD_NORM);

            if (data.getAiCheckBuffer() > 1.0) {
                flag(playerFromData(data), data, String.format(
                        "SmoothAim(GCD-mild) devY=%.5f devP=%.5f gcd=%.6f dy=%.2f",
                        devY, devP, gcd, deltaYaw));
                data.setAiCheckBuffer(0.12);
                return true;
            }
        }

        return false;
    }

    // ── AI Model (Anomaly Detection) ──────────────────────────────────

    /**
     * Score the current attack via statistical anomaly detection AND/OR
     * CNN temporal inference. Features are pushed into the 16-step
     * sliding window for CNN processing.
     */
    private boolean checkModel(Player player, PlayerData data,
                                float deltaYaw, float deltaPitch,
                                LivingEntity victim,
                                float targetYaw, float targetPitch,
                                boolean debug) {
        if (aiModel == null) return false;

        float currentYaw = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();

        // Build the full 20-feature vector
        double[] featVec = buildFeatureVector(player, victim, data,
                deltaYaw, deltaPitch,
                currentYaw, currentPitch,
                targetYaw, targetPitch);
        data.pushAttackFeature(featVec);

        // ── Scoring ────────────────────────────────────────────────
        double suspicion;

        if (aiModel.isUsingCNN() && data.getAttackSequenceSize() >= PlayerData.CNN_SEQUENCE_LENGTH) {
            // CNN temporal inference
            float[][] seq = data.getAttackFeatureSequence();
            suspicion = aiModel.scoreCNN(seq, data.getAttackSequenceSize());
        } else if (aiModel.isTrained()) {
            // Fallback: statistical model uses the full 20-feature vector
            suspicion = aiModel.scoreSample(featVec);
        } else {
            return false;
        }

        double threshold = aiModel.isUsingCNN() ? aiModel.getCnnThreshold() : MODEL_SUSPICION_MIN;
        String layerMode = aiModel.isUsingCNN() ? "CNN" : "STAT";

        // ── Confidence display (console + chat) ─────────────────────
        String bar = buildConfidenceBar(suspicion);
        int vl = data.getViolation("KillAuraAI");

        // Console: always log
        getPlugin().getLogger().info("[KillAuraAI] " + player.getName()
                + " | " + layerMode + " " + String.format("%.1f%%", suspicion * 100)
                + " " + bar
                + " | VL:" + vl
                + " err=" + String.format("%.1f", featVec[2])
                + " dy=" + String.format("%.1f", featVec[0])
                + " seq=" + data.getAttackSequenceSize());

        // Chat: always show to attacker
        String color = suspicion > threshold ? "§c" : (suspicion >= COLOR_YELLOW ? "§e" : "§a");
        player.sendMessage("§8[§4[Aether]§8] " + color
                + bar + " §f" + String.format("%.0f%%", suspicion * 100)
                + " §8VL:" + vl + " §7" + layerMode);

        // Alert admins when confidence is high
        if (suspicion > threshold) {
            String adminMsg = "§8[§4[Aether]§8] §c" + player.getName()
                    + " §7KillAuraAI " + layerMode + ": "
                    + "§c" + String.format("%.1f%%", suspicion * 100)
                    + " " + bar;
            for (org.bukkit.entity.Player onlinePlayer :
                    org.bukkit.Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.hasPermission("aether.alerts")
                        && !onlinePlayer.equals(player)) {
                    onlinePlayer.sendMessage(adminMsg);
                }
            }
        }

        if (debug) {
            getPlugin().getLogger().info("[KillAuraAI|DBG] " + player.getName()
                    + " suspicion=" + String.format("%.4f", suspicion)
                    + " mode=" + layerMode
                    + " seqSize=" + data.getAttackSequenceSize()
                    + " aimErr=" + String.format("%.2f", featVec[2])
                    + " angAccel=" + String.format("%.6f", featVec[6])
                    + " jerk=" + String.format("%.6f", featVec[7])
                    + " atkInt=" + (long) featVec[8] + "ms");
        }

        if (suspicion > threshold) {
            double inc = BUF_MODEL_BASE + suspicion * 0.35;
            data.setAiCheckBuffer(data.getAiCheckBuffer() + inc);

            if (data.getAiCheckBuffer() > 1.0) {
                String mode = aiModel.isUsingCNN() ? "CNN" : "STAT";
                flag(player, data, String.format(
                        "AIModel[%s] s=%.3f err=%.2f dy=%.2f jerk=%.5f",
                        mode, suspicion, featVec[2], featVec[0], featVec[7]));
                data.setAiCheckBuffer(0.10);
                return true;
            }
        }
        return false;
    }

    // ── Feature Vector Builder ──────────────────────────────────────────

    /**
     * Compute the full 20-feature vector from attack context.
     * Must stay in sync with FEATURE_NAMES in KillAuraAIModel.
     * Features 0-9 are rotation/timing (original), 10-19 are new multi-dimensional.
     */
    private double[] buildFeatureVector(Player attacker, LivingEntity victim,
                                         PlayerData data,
                                         float deltaYaw, float deltaPitch,
                                         float currentYaw, float currentPitch,
                                         float targetYaw, float targetPitch) {
        double aimError = data.getLastAimError();
        double gcd = data.getMouseGCD();
        double gcdResY = 0, gcdResP = 0;
        if (gcd > 0.0001) {
            gcdResY = Math.min(deltaYaw % gcd, gcd - (deltaYaw % gcd));
            gcdResP = Math.min(deltaPitch % gcd, gcd - (deltaPitch % gcd));
        }
        long atkInterval = data.getLastAttackTime() > 0
                ? System.currentTimeMillis() - data.getLastAttackTime() : 50;
        double cps = atkInterval > 0 ? 1000.0 / atkInterval : 0;
        double angVel = deltaYaw;
        double angAccel = data.getLastAngularAccel();
        double jerk = data.getAvgJerk();

        // New features — normalize yaw to canonical [-180, 180] so atkYaw and tYaw
        // are in the same coordinate system. Pitch is always in [-90, 90] — no wrap needed.
        double atkYaw = normalizeYaw(currentYaw);
        double atkPitch = currentPitch;
        double tYaw = normalizeYaw(targetYaw);
        double tPitch = targetPitch;
        double yawErr = normalizeYaw(data.getLastYawError());
        double pitchErr = data.getLastPitchError();
        double dist = attacker.getLocation().distance(victim.getLocation());
        double moveAngle = computeMovementAngle(data, attacker, victim);
        double sprinting = (data.isSprinting() || data.isInferredSprinting()) ? 1.0 : 0.0;
        double blocking = data.isBlocking() ? 1.0 : 0.0;

        return new double[]{
                deltaYaw, deltaPitch, aimError, gcdResY, gcdResP,
                angVel, angAccel, jerk, (double) atkInterval, cps,
                atkYaw, atkPitch, tYaw, tPitch,
                yawErr, pitchErr, dist, moveAngle,
                sprinting, blocking
        };
    }

    /**
     * Compute the angle between the player's movement direction and
     * the direction toward the victim.
     * <ul>
     *   <li>0° = sprinting straight at the target</li>
     *   <li>90° = stationary or strafing perpendicular</li>
     *   <li>180° = running directly away from target</li>
     * </ul>
     */
    private double computeMovementAngle(PlayerData data, Player attacker, LivingEntity victim) {
        double dx = data.getDeltaX();
        double dz = data.getDeltaZ();
        double hMove = Math.sqrt(dx * dx + dz * dz);
        if (hMove < 0.001) return 90.0; // stationary → ambiguous, default 90°

        double victimDx = victim.getLocation().getX() - attacker.getLocation().getX();
        double victimDz = victim.getLocation().getZ() - attacker.getLocation().getZ();
        double vDist = Math.sqrt(victimDx * victimDx + victimDz * victimDz);
        if (vDist < 0.001) return 0.0;

        double dot = (dx * victimDx + dz * victimDz) / (hMove * vDist);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    // ── Training Data Recording ────────────────────────────────────────

    public void recordAttackFeatures(Player attacker, LivingEntity victim,
                                     PlayerData data, boolean flaggedByKA,
                                     float oldYaw, float oldPitch,
                                     float newYaw, float newPitch,
                                     long oldLastAttackTime,
                                     float targetYaw, float targetPitch) {
        if (!data.isRecording()) return;

        String label = data.getRecordingLabel();

        double deltaYaw = Math.abs(newYaw - oldYaw) % 360;
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        double deltaPitch = Math.abs(newPitch - oldPitch);

        // Build the full 20-feature vector for recording
        double[] feat = buildFeatureVector(attacker, victim, data,
                (float) deltaYaw, (float) deltaPitch,
                newYaw, newPitch,
                targetYaw, targetPitch);

        // Console feedback (quick model check)
        if (aiModel != null && aiModel.isTrained()) {
            double modelScore = aiModel.scoreSample(feat);
            if (modelScore > 0.2) {
                getPlugin().getLogger().info("[KillAuraAI] Rec " + label
                        + " " + attacker.getName()
                        + " score=" + String.format("%.3f", modelScore));
            }
        }

        data.recordFeatureVector(label,
                feat[0], feat[1], feat[2], feat[3], feat[4],
                feat[5], feat[6], feat[7], (long) feat[8], feat[9],
                feat[10], feat[11], feat[12], feat[13],
                feat[14], feat[15], feat[16], feat[17],
                feat[18], feat[19], flaggedByKA);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Player playerFromData(PlayerData data) {
        return org.bukkit.Bukkit.getPlayer(data.getUuid());
    }

    /**
     * Normalize any yaw value to the canonical [-180, 180] range.
     * Handles both [0, 360) format (Minecraft protocol) and [-180, 180] format
     * (Math.atan2), plus unbounded rollover values via modulo.
     */
    private static double normalizeYaw(double yaw) {
        yaw = ((yaw % 360.0) + 360.0) % 360.0;
        if (yaw > 180.0) yaw -= 360.0;
        return yaw;
    }

    /** Build a visual confidence bar: ████░░░░ (10 segments). */
    private static String buildConfidenceBar(double confidence) {
        int filled = (int) Math.round(confidence * 10);
        filled = Math.max(0, Math.min(10, filled));
        StringBuilder sb = new StringBuilder("§a[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? "§c█" : "§7░");
        }
        sb.append("§a]");
        return sb.toString();
    }
}
