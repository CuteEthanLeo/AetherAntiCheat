package com.aether.anticheat.check.combat;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Detects AntiKnockback — reducing/negating knockback from attacks.
 *
 * <h3>How KB works in 1.8.9 PvP</h3>
 * <p>When a player is hit, the attacker's weapon knockback attribute
 * (usually 0-1 for swords, 0 for bows) produces a velocity impulse.
 * The victim's movement MUST show a horizontal delta after being hit.</p>
 *
 * <h3>Detection</h3>
 * <ol>
 *   <li>Track damage events — when a player takes attack damage, record
 *       the tick and expect velocity.</li>
 *   <li>On the next few movement ticks, check if the player moved
 *       horizontally.  Zero or near-zero movement after a hit with
 *       knockback → AntiKB.</li>
 *   <li>Vertical AntiKB: if the player takes damage mid-air and doesn't
 *       move vertically at all → vertical AntiKB.</li>
 * </ol>
 */
public class AntiKBCheck extends Check {

    /** Max ticks after damage to check for KB response. */
    private static final int KB_CHECK_TICKS = 5;

    /** Min horizontal movement expected after a knockback hit (blocks/tick). */
    private static final double MIN_KB_HORIZONTAL = 0.01;

    /** Min vertical movement expected after an air hit (blocks/tick). */
    private static final double MIN_KB_VERTICAL = 0.005;

    public AntiKBCheck(AetherAntiCheat plugin) {
        super("AntiKB", CheckType.COMBAT, 10, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) return;
        if (data.isFlying() || player.isFlying()) return;
        if (player.isInsideVehicle()) return;
        if (data.isTeleportExempt()) return;
        if (data.isRecentlyInLiquid() || data.isRecentlyInWeb()) return;

        int ticksSinceDmg = data.getTicksSinceDamage();
        if (ticksSinceDmg > KB_CHECK_TICKS) return;
        if (!data.damageContainedKnockback()) return;

        // ── Horizontal KB check ─────────────────────────────────────────
        double hDist = data.getHorizontalDistance();
        if (ticksSinceDmg <= KB_CHECK_TICKS && hDist < MIN_KB_HORIZONTAL
                && data.getLastDamageAmount() > 0.0) {
            // Player took damage with KB but didn't move horizontally
            flag(player, data, String.format(
                    "NoHorizontalKB hDist=%.4f ticksAgo=%d dmg=%.1f",
                    hDist, ticksSinceDmg, data.getLastDamageAmount()));
            return;
        }

        // ── Vertical KB check (air hits) ────────────────────────────────
        if (!data.isOnGround() && ticksSinceDmg <= 3) {
            double absDY = Math.abs(data.getDeltaY());
            // Take some vertical velocity from knockback in the prediction
            // If deltaY didn't change at all after being hit mid-air → antiKB
            if (absDY < MIN_KB_VERTICAL && data.getVelocityTicksRemaining() <= 0
                    && data.getLastDamageAmount() > 1.0) {
                flag(player, data, String.format(
                        "NoVerticalKB dy=%.4f ticksAgo=%d",
                        data.getDeltaY(), ticksSinceDmg));
                return;
            }
        }

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
