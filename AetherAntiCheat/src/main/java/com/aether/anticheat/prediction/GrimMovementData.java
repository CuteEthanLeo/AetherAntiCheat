package com.aether.anticheat.prediction;

public class GrimMovementData {
    // 完美的 1.8.9 物理状态双精度浮点数追踪
    public double motionX, motionY, motionZ;
    public double lastX, lastY, lastZ;

    public boolean onGround;
    public boolean sprinting;
    public boolean sneaking;

    // 药水等级
    public int speedAmplifier = 0;
    public int slowAmplifier = 0;

    // 关键：玩家的键盘输入向量（Grim 通过拦截 C03/C0C 数据包或计算得出）
    // 如果无法精准获取，默认模拟最大化操作（forward = 1.0, strafe = 1.0）
    public float moveForward = 1.0F;
    public float moveStrafe = 0.0F;

    public void copyFrom(GrimMovementData other) {
        this.motionX = other.motionX;
        this.motionY = other.motionY;
        this.motionZ = other.motionZ;
        this.onGround = other.onGround;
        this.sprinting = other.sprinting;
        this.sneaking = other.sneaking;
    }
}
