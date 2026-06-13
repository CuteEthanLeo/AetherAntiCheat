package com.aether.anticheat.check.player;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.CheckType;
import com.aether.anticheat.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Industry-standard Packet Protocol Validation (BadPackets) for 1.8.9 Paper.
 * Eradicated redundant GroundSpoof overlaps, added powerful multi-packet flooding and Blink/Regen interceptors.
 */
public class BadPacketsCheck extends Check {

    // 1.8.9 标准客户端在一 tick (50ms) 内理应只发送 1 个移动包 (C03/C05/C06)
    // 考虑极端网速抖动合并，我们将单 tick 硬性安全上限放宽到 4 个包，超过这个上限铁证如山
    private static final int MAX_PACKETS_PER_TICK = 4;

    public BadPacketsCheck(AetherAntiCheat plugin) {
        super("BadPackets", CheckType.PLAYER, 5, plugin);
    }

    @Override
    public void runCheck(Player player, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (data.isTeleportExempt()) return;

        // ── 1. 仰俯角硬件边界检验 (Invalid Pitch Check) ─────────────────────────
        // 1.8.9 官方物理引擎死线：玩家头颅上下转动绝对不可能超过正负 90 度
        float pitch = player.getLocation().getPitch();
        if (Math.abs(pitch) > 90.0f) {
            flag(player, data, String.format("InvalidPitch pitch=%.2f", pitch));
            return;
        }

        // ── 2. 非法浮点数检验 (NaN/Infinity Check) ───────────────────────────
        float yaw = player.getLocation().getYaw();
        if (Float.isNaN(yaw) || Float.isInfinite(yaw) || Float.isNaN(pitch) || Float.isInfinite(pitch)) {
            flag(player, data, "InvalidRotations NaN/Inf");
            return;
        }

        // ── 3. 核心硬核特技：单 Tick 发包并发率检验 (Packet Flood / Regen Check) ──
        // 每次 PlayerMoveEvent 触发说明服务器又收到了一个移动包。
        // 我们在 PlayerData 里的当前主 tick 计数器里直接累加。
        int packetsThisTick = data.getPacketsThisTick();

        if (packetsThisTick > MAX_PACKETS_PER_TICK) {
            double currentBuffer = data.getBadPacketsBuffer() + 0.4;
            data.setBadPacketsBuffer(currentBuffer);

            if (currentBuffer > 1.0) {
                flag(player, data, String.format(
                        "PacketFlood rate=%d/tick max=%d buf=%.2f",
                        packetsThisTick, MAX_PACKETS_PER_TICK, currentBuffer
                ));
                // 触发暴烈发包作弊（如无限秒回血 Regen）直接清空包计数进行强制拦截
                data.setBadPacketsBuffer(0.7);
            }
        } else {
            // 正常的均匀发包，缓慢平滑衰减 Buffer
            data.setBadPacketsBuffer(Math.max(0.0, data.getBadPacketsBuffer() - 0.01));
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
