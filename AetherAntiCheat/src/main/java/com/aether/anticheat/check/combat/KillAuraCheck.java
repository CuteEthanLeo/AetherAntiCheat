package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Enhanced KillAura & Aimbot check supporting all LivingEntities.
 *
 * <h3>Detection Layers</h3>
 * <ol>
 *   <li><b>MultiAura</b> — attacks on multiple targets in one tick.</li>
 *   <li><b>NoSwing</b> — consecutive attacks without swing animation.</li>
 *   <li><b>360° Aim</b> — attack direction vs look direction angle.</li>
 *   <li><b>AimLock</b> — consecutive perfect aim (&lt;1.5° error) is humanly impossible.</li>
 *   <li><b>SnapAim</b> — head snapping &gt;15° to exact target coords (silent aim).</li>
 *   <li><b>Consistency</b> — yaw delta std-dev too low (machine-like rotation).</li>
 *   <li><b>LowJerk</b> — angular jerk too low (missing human micro-jitter).</li>
 *   <li><b>AvgError</b> — sustained sub-degree aim accuracy over many attacks.</li>
 * </ol>
 */
public class KillAuraCheck extends Check {

    private static final int MAX_MULTI_AURA = 1;
    private static final int NO_SWING_THRESHOLD = 3;
    private static final double MAX_LOOK_ANGLE = 110.0;
    private static final double MAX_CPS = 22.0;

    // ── Deep AIM thresholds ─────────────────────────────────────────────
    private static final int AIMLOCK_CONSECUTIVE = 6;       // 连续完美瞄准次数阈值
    private static final int SNAPAIM_MIN_SNAPS = 3;         // 最少瞬移次数
    private static final int SNAPAIM_MAX_SAMPLES = 60;      // 瞬移检测窗口内的最大样本数
    private static final double CONSISTENCY_STDDEV = 0.08;  // yaw delta 标准差过低阈值
    private static final int CONSISTENCY_MIN_SAMPLES = 8;   // 一致性检测最少样本
    private static final double LOWJERK_THRESHOLD = 0.015;  // jerk 过低阈值
    private static final int LOWJERK_MIN_SAMPLES = 15;      // jerk 检测最少样本
    private static final double AVGERROR_THRESHOLD = 0.8;   // 平均瞄准误差过低阈值
    private static final int AVGERROR_MIN_SAMPLES = 25;     // 平均误差检测最少样本

    // ── Buffer increments per detection type ────────────────────────────
    private static final double BUF_AIMLOCK = 0.30;
    private static final double BUF_SNAPAIM = 0.40;
    private static final double BUF_CONSISTENCY = 0.25;
    private static final double BUF_LOWJERK = 0.20;
    private static final double BUF_AVGERROR = 0.30;
    private static final double BUF_360AIM = 0.35;
    private static final double BUF_NOSWING = 0.35;

    public KillAuraCheck(AetherAntiCheat plugin) {
        super("KillAura", CheckType.COMBAT, 15, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // ── 1. Multi-Aura ───────────────────────────────────────────────
        if (data.getMultiAuraCount() > MAX_MULTI_AURA) {
            flag(player, data, String.format("MultiAura targets=%d", data.getMultiAuraCount()));
            data.setKillAuraBuffer(0);
            return;
        }

        // ── 2. No-Swing ─────────────────────────────────────────────────
        if (!data.isUsingItem()) {
            int noSwing = data.getAttacksWithoutSwing();
            if (noSwing >= NO_SWING_THRESHOLD && data.getLastAttackTime() > 0) {
                double currentBuffer = data.getKillAuraBuffer() + BUF_NOSWING;
                data.setKillAuraBuffer(currentBuffer);

                if (currentBuffer > 1.0) {
                    flag(player, data, String.format(
                            "NoSwing cnt=%d/%d buf=%.2f", noSwing, NO_SWING_THRESHOLD, currentBuffer));
                    data.setKillAuraBuffer(0.7);
                }
                return; // 已触发 NoSwing，跳过深层检测避免重复 flag
            }
        }

        // ── 3. Deep AIM rotation analysis ───────────────────────────────
        // Only run when player has recent attacks and enough rotation samples
        if (data.getLastAttackTime() > 0
                && System.currentTimeMillis() - data.getLastAttackTime() < 500
                && data.getTotalAimSamples() >= 5) {

            boolean anySuspicious = false;
            StringBuilder details = new StringBuilder();

            // 3a. AimLock — 连续完美瞄准（误差<1.5°）
            int perfectStreak = data.getConsecutivePerfectAim();
            if (perfectStreak >= AIMLOCK_CONSECUTIVE) {
                double buf = data.getKillAuraBuffer() + BUF_AIMLOCK;
                data.setKillAuraBuffer(buf);
                details.append(String.format("AimLock streak=%d ", perfectStreak));
                anySuspicious = true;
            }

            // 3b. SnapAim — 头部瞬移到精确目标
            int snapCount = data.getRotationSnapCount();
            int totalSamples = data.getTotalAimSamples();
            if (snapCount >= SNAPAIM_MIN_SNAPS && totalSamples <= SNAPAIM_MAX_SAMPLES) {
                double buf = data.getKillAuraBuffer() + BUF_SNAPAIM;
                data.setKillAuraBuffer(buf);
                details.append(String.format("SnapAim snaps=%d/%d ", snapCount, totalSamples));
                anySuspicious = true;
            }

            // 3c. Consistency — yaw delta 标准差过低（机器般一致）
            double yawStdDev = data.getYawDeltaStdDev();
            if (yawStdDev > 0.001 && yawStdDev < CONSISTENCY_STDDEV
                    && data.getTotalAimSamples() >= CONSISTENCY_MIN_SAMPLES) {
                double buf = data.getKillAuraBuffer() + BUF_CONSISTENCY;
                data.setKillAuraBuffer(buf);
                details.append(String.format("Consistency std=%.4f ", yawStdDev));
                anySuspicious = true;
            }

            // 3d. LowJerk — 角加速度变化过小（缺少人类微抖动）
            double avgJerk = data.getAvgJerk();
            if (avgJerk > 0.0001 && avgJerk < LOWJERK_THRESHOLD
                    && data.getTotalAimSamples() >= LOWJERK_MIN_SAMPLES) {
                double buf = data.getKillAuraBuffer() + BUF_LOWJERK;
                data.setKillAuraBuffer(buf);
                details.append(String.format("LowJerk jerk=%.6f ", avgJerk));
                anySuspicious = true;
            }

            // 3e. AvgError — 长时间极高瞄准精度
            double avgAimError = data.getAvgAimError();
            if (avgAimError > 0.01 && avgAimError < AVGERROR_THRESHOLD
                    && data.getTotalAimSamples() >= AVGERROR_MIN_SAMPLES) {
                double buf = data.getKillAuraBuffer() + BUF_AVGERROR;
                data.setKillAuraBuffer(buf);
                details.append(String.format("AvgError err=%.2f ", avgAimError));
                anySuspicious = true;
            }

            // ── Flag check ──────────────────────────────────────────────
            if (anySuspicious && data.getKillAuraBuffer() > 1.0) {
                flag(player, data, details.toString().trim());
                data.setKillAuraBuffer(0.70);
                // Reset deep model to avoid stale data stacking
                data.resetAimModel();
                return;
            }
        }

        // ── 4. Buffer decay (normal gameplay) ───────────────────────────
        if (data.getLastAttackTime() > 0 && data.getLastSwingTime() > 0) {
            data.setKillAuraBuffer(Math.max(0, data.getKillAuraBuffer() - 0.025));
        }

        decayVL(data);
    }

    /**
     * 核心修复：基于严格三维点积（Dot Product）的视线方向检测。
     * 支持所有 LivingEntity，修复 Y 轴高度差误报。
     */
    public void checkAngle(Player attacker, LivingEntity victim, PlayerData data) {
        if (attacker.getGameMode() == GameMode.CREATIVE) return;

        Vector lookDir = attacker.getEyeLocation().getDirection().normalize();
        Vector targetDir = victim.getEyeLocation().toVector()
                .subtract(attacker.getEyeLocation().toVector()).normalize();

        double dot = lookDir.dot(targetDir);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double angleDiff = Math.toDegrees(Math.acos(dot));

        // 360° 自瞄检测：视线偏离目标超过 110°（不可能打到背后的人）
        if (angleDiff > MAX_LOOK_ANGLE && data.getLastAttackTime() > 0) {
            double currentBuffer = data.getKillAuraBuffer() + BUF_360AIM;
            data.setKillAuraBuffer(currentBuffer);

            if (currentBuffer > 1.0) {
                flag(attacker, data, String.format(
                        "360Aim target=%s angle=%.1f° buf=%.2f",
                        victim.getType().name(), angleDiff, currentBuffer));
                data.setKillAuraBuffer(0.8);
            }
        } else {
            data.setKillAuraBuffer(Math.max(0.0, data.getKillAuraBuffer() - 0.02));
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
