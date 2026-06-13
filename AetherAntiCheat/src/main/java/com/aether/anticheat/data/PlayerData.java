package com.aether.anticheat.data;

import com.aether.anticheat.check.Check;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Per-player tracking data for prediction-based cheat detection.
 *
 * <h3>Trackers</h3>
 * <ul>
 *   <li><b>TeleportTracker</b> — exempt ticks after teleport.</li>
 *   <li><b>VelocityTracker</b> — knockback/impulse with drag-decay buffer.</li>
 *   <li><b>BlockStateCache</b> — recent (≤3 tick) web/liquid/climbable contact.</li>
 *   <li><b>MovementHistory</b> — last-tick deltas for physics prediction.</li>
 *   <li><b>CombatTracker</b> — attack/swing timing, multi-aura detection.</li>
 *   <li><b>BlockPlaceTracker</b> — recent placement speed for Scaffold detection.</li>
 * </ul>
 */
public class PlayerData {

    private final UUID uuid;
    private final Map<String, Integer> violations = new HashMap<>();
    private final Map<String, Long> lastFlagTime = new HashMap<>(); // check name → last flag ms

    // ── Movement history ─────────────────────────────────────────────────
    private Location lastLocation;
    private Location currentLocation;
    private double deltaX, deltaY, deltaZ;
    private double horizontalDistance;
    private double lastHorizontalDistance;
    private double lastDeltaX, lastDeltaY, lastDeltaZ;
    private double lastLastDeltaX, lastLastDeltaY, lastLastDeltaZ;

    // ── Prediction tracking (Speed prediction model) ──────────────────
    private double lastX, lastZ;
    private double predictionX, predictionZ;
    private boolean wasOnGround;

    // ── State flags ──────────────────────────────────────────────────────
    private boolean onGround;
    private boolean lastOnGround;
    private boolean flying;
    private boolean sneaking;
    private boolean sprinting;

    // ── TeleportTracker ──────────────────────────────────────────────────
    private int teleportExemptTicks;

    // ── VelocityTracker ──────────────────────────────────────────────────
    private double velocityX, velocityY, velocityZ;
    private int velocityTicksRemaining;
    private static final int VELOCITY_MAX_TICKS = 20;

    // ── BlockStateCache ──────────────────────────────────────────────────
    private int webContactTicks = 127;
    private int liquidContactTicks = 127;
    private int climbableContactTicks = 127;
    private boolean webContactSeen, liquidContactSeen, climbableContactSeen;

    // ── CombatTracker ────────────────────────────────────────────────────
    private long lastAttackTime;
    private long lastSwingTime;
    private int swingsSinceLastAttack;
    private UUID lastAttackedEntity;
    private final Set<UUID> attackedThisTick = new HashSet<>();
    private int multiAuraCount;        // how many times multi-aura was detected
    private int attackConsistencyCount; // consecutive attacks with identical timing

    // ── ScaffoldTracker (Ray-Cast + Pitch-Lock + Telly-Bridge) ──────────
    private double scaffoldBuffer = 0.0;
    private float lastPlacePitch = 0.0f;
    private float lastPlaceYaw = 0.0f;
    private int scaffoldRotationStreak = 0;
    private int tellySnapTicks = 0;
    private double scaffoldPitchFrozenHits = 0.0;  // how many times pitch was frozen
    private long lastScaffoldAnalysisTime = 0;
    private final java.util.List<Long> placeTimestamps = new java.util.ArrayList<>();

    // ── NoFall tracking (server-authoritative) ──────────────────────────
    private float lastFallDistance;
    private float clientFallDistance;
    private double noFallBuffer = 0.0;
    private double serverFallDistance = 0.0;

    // ── Timer tracking (Balance-Based Time Credit System) ─────────────────
    private long timerBalance = 0L;
    private long lastTimerPacketTime = 0L;
    private double timerViolationBuffer = 0.0;

    // ── BadPackets / Packet Flood tracking ────────────────────────────────
    private int packetsThisTick = 0;
    private double badPacketsBuffer = 0.0;

    // ── AutoBlock tracking (1.8.9 Sword Blocking) ─────────────────────────
    private boolean blocking;
    private long blockStartTime;
    private long blockStopTime;
    private long lastBlockDuration;
    private long lastDamageReceivedTime;
    private long blockToggleWindowStart;
    private int blockToggleCount;
    private int blockAttackSameTickCount;
    private long lastBlockWhileAttackTime;
    private double autoBlockABuffer = 0.0;
    private double autoBlockBBuffer = 0.0;
    private double autoBlockCBuffer = 0.0;

    // ── Fly buffer ──────────────────────────────────────────────────────
    private double flyBuffer = 0.0;

    // ── Speed / horizontal movement tracking ────────────────────────────
    private double speedBuffer = 0.0;
    private double longJumpBuffer = 0.0;
    private int iceTicks = 0;

    // ── Global tick counter ──────────────────────────────────────────────
    private int tick;

    // ── Tick-since counters (increment in tick(), reset on events) ────────
    private int tickSinceTeleport = 127;
    private int tickSinceVelocity = 127;
    private int tickSinceAttack = 127;
    private int tickSincePushedByPiston = 127;
    private int tickSinceAbilityChange = 127;
    private int tickSinceSteerVehicle = 127;
    private int tickSinceClientGround = 127;
    private int tickSinceNearWall = 127;
    private int tickSinceInFlowingLava = 127;
    private int tickSinceDroppedItem = 127;
    // ★ Fly 新增
    private int tickSinceNearStep = 127;
    private int tickSinceClimbing = 127;
    private int tickSinceUnderBlock = 127;
    private int tickSinceInLiquid = 127;
    private int tickSinceOnSlime = 127;
    private int tickSinceOtherVelocity = 127;

    // ── Extended state flags ─────────────────────────────────────────────
    private boolean inLiquid;
    private boolean inWater;
    private boolean inFlowingWater;
    private boolean inLava;
    private boolean inFlowingLava;
    private boolean underBlock;
    private boolean inWeb;
    private boolean offsetMotion;
    private boolean offsetYMotion;
    private boolean nearBoat;
    private boolean nearWall;
    private boolean onSlime;
    private boolean eating;
    private boolean climbing;
    private boolean jumped;
    private boolean nearStep;

    // ── Speed sub-check violation buffers ─────────────────────────────────
    private double speedABuffer = 0.0;
    private double speedBBuffer = 0.0;
    private double speedCBuffer = 0.0;
    private double speedDBuffer = 0.0;

    // ── SpeedA internal tracking ──────────────────────────────────────────
    private Double lastSpeed;
    private boolean lastSprinted;
    private boolean wasSneakOnEdge;
    private int lastFlagTicks;
    private boolean clientGround;
    private boolean lastClientGround;
    private boolean inferredSprinting;

    // ── SpeedD internal tracking ──────────────────────────────────────────
    private boolean didSlotChangeLastTick;
    private boolean flaggedLastTick;

    // ── Raw velocity components (for SpeedB direction switch) ─────────────
    private double velocityRawX;
    private double velocityRawZ;

    // ── Tick-since eating ────────────────────────────────────────────────
    private int tickSinceEating;

    // ── KillAura buffer ─────────────────────────────────────────────────
    private double killAuraBuffer = 0.0;
    private int attacksWithoutSwing; // 连续无挥臂攻击次数

    // ── KillAura AI / GCD tracking ─────────────────────────────────────
    private double aiCheckBuffer = 0.0;

    // ── CNN temporal feature sequence (16-step sliding window, matching Python SEQUENCE_LENGTH) ────
    private final java.util.ArrayDeque<double[]> attackFeatureSequence = new java.util.ArrayDeque<>(16);
    public static final int CNN_SEQUENCE_LENGTH = 16;  // must match Python SEQUENCE_LENGTH
    private static final int CNN_N_FEATURES = 20;

    // ── KillAuraAI Training Data Recording ────────────────────────────
    private boolean recordingActive = false;
    private String recordingLabel = "normal"; // "cheat" or "normal"
    private final List<String> recordingBuffer = new ArrayList<>();
    private long recordingStartTime = 0;
    private String recordingPlayerName = "";

    // ── NoSlow buffer ──────────────────────────────────────────────────
    private double noSlowBuffer = 0.0;

    // ── Ping / latency (from keep-alive or ping packet) ──────────────────
    private int latency;  // approximate ping in ms

    // ── Air ticks ────────────────────────────────────────────────────────
    private int airTicks;

    // ── KillAura rotation ────────────────────────────────────────────────
    private float lastAttackYaw;
    private float lastAttackPitch;
    private float lastAttackYawDelta;
    private float lastAttackPitchDelta;

    // ── AntiKB ───────────────────────────────────────────────────────────
    private long lastDamageTime;
    private double lastDamageAmount;
    private int ticksSinceDamage;
    private boolean damageContainedKnockback;

    // ── NoSlow ───────────────────────────────────────────────────────────
    private boolean usingItem;
    private long usingItemStartTime;
    private int usingItemTicks;        // ticks since isUsingItem became true

    // ── NoSlow slot-switch bypass tracking ──────────────────────────
    private int tickSinceSlotSwitch = 127;     // ticks since last held-slot change
    private boolean wasUsingBeforeSwitch;       // was using item / blocking before switch
    private int slotSwitchCount;                // slot switches in current window
    private long slotSwitchWindowStart;         // start of current switch-count window

    // ── Deep Aimbot rotation model ──────────────────────────────────────
    // Circular buffers for recent yaw/pitch deltas (per-tick rotation change)
    private final double[] recentYawDeltas = new double[12];
    private final double[] recentPitchDeltas = new double[12];
    private int rotationBufferIdx;
    private int rotationSamples;

    // Aim-to-target tracking
    private double lastAimError;        // degrees between look-vector and target
    private double lastYawError;        // |yaw - targetYaw| (per-axis for rich features)
    private double lastPitchError;      // |pitch - targetPitch|
    private double aimErrorSum;         // cumulative error over samples
    private int aimErrorSamples;
    private int consecutivePerfectAim;  // ticks where aim error < 1°

    // Kinematic derivatives (angular)
    private double lastAngularVelocity; // deg/tick — 1st derivative of yaw
    private double lastAngularAccel;    // deg/tick² — 2nd derivative
    private double jerkRunningSum;      // running sum of |jerk| (3rd derivative)
    private int jerkSamples;

    // Snap counter
    private int rotationSnapCount;      // times yaw jumped > 15° toward exact target in one tick
    private int totalAimSamples;

    // ── Constructor ──────────────────────────────────────────────────────

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        Location loc = player.getLocation().clone();
        this.lastLocation = loc;
        this.currentLocation = loc;
        this.onGround = player.isOnGround();
        this.lastOnGround = player.isOnGround();
        this.flying = player.isFlying();
        this.sneaking = player.isSneaking();
        this.sprinting = player.isSprinting();
    }

    // ── Movement update ──────────────────────────────────────────────────

    public void updateMovement(Location to) {
        if (this.currentLocation != null) {
            this.lastLocation = this.currentLocation.clone();
        } else {
            this.lastLocation = to.clone();
        }
        this.currentLocation = to.clone();

        this.lastLastDeltaX = this.lastDeltaX;
        this.lastLastDeltaY = this.lastDeltaY;
        this.lastLastDeltaZ = this.lastDeltaZ;

        this.lastDeltaX = this.deltaX;
        this.lastDeltaY = this.deltaY;
        this.lastDeltaZ = this.deltaZ;

        this.deltaX = currentLocation.getX() - lastLocation.getX();
        this.deltaY = currentLocation.getY() - lastLocation.getY();
        this.deltaZ = currentLocation.getZ() - lastLocation.getZ();

        this.horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        this.lastOnGround = this.onGround;
        this.lastClientGround = this.clientGround;

        // Client ground tracking (reset counter when client reports on ground)
        if (this.clientGround) {
            this.tickSinceClientGround = 0;
        }

        // Air tick tracking
        if (!this.onGround) {
            this.airTicks++;
        } else {
            this.airTicks = 0;
        }

        // AntiKB decay
        if (ticksSinceDamage < 127) ticksSinceDamage++;

        // Per-packet counter — incremented on every move event, reset by tick()
        this.packetsThisTick++;

        // Sync rotation history for GCD-based aimbot detection (KillAuraAI)
        this.lastAttackYaw = to.getYaw();
        this.lastAttackPitch = to.getPitch();
    }

    // ── Tick decay ───────────────────────────────────────────────────────

    public void tick() {
        // Global tick
        this.tick++;

        if (teleportExemptTicks > 0) teleportExemptTicks--;
        if (velocityTicksRemaining > 0) velocityTicksRemaining--;
        if (webContactTicks < 127) webContactTicks++;
        if (liquidContactTicks < 127) liquidContactTicks++;
        if (climbableContactTicks < 127) climbableContactTicks++;

        // Tick-since counters (increment up to 127 max)
        if (tickSinceTeleport < 127) tickSinceTeleport++;
        if (tickSinceVelocity < 127) tickSinceVelocity++;
        if (tickSinceAttack < 127) tickSinceAttack++;
        if (tickSincePushedByPiston < 127) tickSincePushedByPiston++;
        if (tickSinceAbilityChange < 127) tickSinceAbilityChange++;
        if (tickSinceSteerVehicle < 127) tickSinceSteerVehicle++;
        if (tickSinceClientGround < 127) tickSinceClientGround++;
        if (tickSinceNearWall < 127) tickSinceNearWall++;
        if (tickSinceInFlowingLava < 127) tickSinceInFlowingLava++;
        if (tickSinceDroppedItem < 127) tickSinceDroppedItem++;
        if (tickSinceEating < 127) tickSinceEating++;
        if (tickSinceNearStep < 127) tickSinceNearStep++;
        if (tickSinceClimbing < 127) tickSinceClimbing++;
        if (tickSinceUnderBlock < 127) tickSinceUnderBlock++;
        if (tickSinceInLiquid < 127) tickSinceInLiquid++;
        if (tickSinceOnSlime < 127) tickSinceOnSlime++;
        if (tickSinceOtherVelocity < 127) tickSinceOtherVelocity++;

        // NoSlow tick counter
        if (usingItem) usingItemTicks++;
        if (tickSinceSlotSwitch < 127) tickSinceSlotSwitch++;

        // Slot-switch window reset (1 second window)
        if (System.currentTimeMillis() - slotSwitchWindowStart > 1000) {
            slotSwitchCount = 0;
            slotSwitchWindowStart = System.currentTimeMillis();
        }

        // Ice ticks decay
        if (this.iceTicks > 0) {
            this.iceTicks--;
        }

        // Reset per-tick attack set
        attackedThisTick.clear();

        // Reset packet flood counter — fresh count every server tick
        this.packetsThisTick = 0;
    }

    // ── Block state ──────────────────────────────────────────────────────

    public void updateBlockState(Player player) {
        Location feet = player.getLocation();
        Material feetBlock = feet.getBlock().getType();
        Material headBlock = feet.clone().add(0, 1.0, 0).getBlock().getType();

        if (feetBlock == Material.WEB || headBlock == Material.WEB) {
            webContactTicks = 0; webContactSeen = true;
        }
        if (isLiquid(feetBlock) || isLiquid(headBlock)) {
            liquidContactTicks = 0; liquidContactSeen = true;
        }
        if (isClimbable(feetBlock) || isClimbable(headBlock)) {
            climbableContactTicks = 0; climbableContactSeen = true;
        }

        // Extended state detection for Speed checks
        this.inLiquid = isLiquid(feetBlock) || isLiquid(headBlock);
        this.inWater = feetBlock == Material.WATER || feetBlock == Material.STATIONARY_WATER
                || headBlock == Material.WATER || headBlock == Material.STATIONARY_WATER;
        this.inFlowingWater = feetBlock == Material.WATER || headBlock == Material.WATER;
        this.inLava = feetBlock == Material.LAVA || feetBlock == Material.STATIONARY_LAVA
                || headBlock == Material.LAVA || headBlock == Material.STATIONARY_LAVA;
        this.inFlowingLava = feetBlock == Material.LAVA || headBlock == Material.LAVA;
        this.inWeb = feetBlock == Material.WEB || headBlock == Material.WEB;
        this.climbing = isClimbable(feetBlock) || isClimbable(headBlock);
        this.underBlock = isSolidBlocking(feet.clone().add(0, 1.8, 0).getBlock().getType())
                || isSolidBlocking(feet.clone().add(0, 2.0, 0).getBlock().getType());
        if (this.underBlock) this.tickSinceUnderBlock = 0;

        // Near step detection (slab, stair, bed, etc.)
        if (isStepOrSlab(feetBlock)) {
            this.nearStep = true;
            this.tickSinceNearStep = 0;
        }

        // Lava contact tracking
        if (feetBlock == Material.LAVA || feetBlock == Material.STATIONARY_LAVA
                || headBlock == Material.LAVA || headBlock == Material.STATIONARY_LAVA) {
            this.tickSinceInFlowingLava = 0;
            this.tickSinceInLiquid = 0;
        }

        // Water / liquid tracking
        if (isLiquid(feetBlock) || isLiquid(headBlock)) {
            this.tickSinceInLiquid = 0;
        }

        // Climbing tracking
        if (isClimbable(feetBlock) || isClimbable(headBlock)) {
            this.tickSinceClimbing = 0;
        }

        // Slime block detection
        Material belowType = feet.clone().subtract(0, 1, 0).getBlock().getType();
        boolean wasOnSlime = this.onSlime;
        this.onSlime = belowType == Material.SLIME_BLOCK;
        if (this.onSlime && !wasOnSlime) {
            this.tickSinceOnSlime = 0;
        }

        // Near step detection (below feet)
        if (!this.nearStep && (isStepOrSlab(belowType) || isStepOrSlab(feet.clone().add(0, -1, 0).getBlock().getType()))) {
            this.nearStep = true;
            this.tickSinceNearStep = 0;
        }

        // Ice detection — grant momentum buffer when standing on/near ice
        if (belowType == org.bukkit.Material.ICE || belowType == org.bukkit.Material.PACKED_ICE
                || feetBlock == org.bukkit.Material.ICE || feetBlock == org.bukkit.Material.PACKED_ICE) {
            this.iceTicks = 15;
        }
    }

    // ── Combat tracking ──────────────────────────────────────────────────

    /** Record an attack on an entity. Returns true if this tick already had an attack (multi-aura). */
    public boolean recordAttack(UUID targetId) {
        long now = System.currentTimeMillis();

        // Multi-aura: same tick, different targets
        if (!attackedThisTick.isEmpty() && !attackedThisTick.contains(targetId)) {
            multiAuraCount++;
        }
        attackedThisTick.add(targetId);

        // Consistency check: compare interval to previous
        if (lastAttackTime > 0) {
            long interval = now - lastAttackTime;
            // If 3+ attacks within 20ms of each other, likely aura
            if (interval > 0 && interval < 40) {
                attackConsistencyCount++;
            }
        }

        // ★ NoSwing 检测：如果在两次攻击之间没有挥臂事件则计数
        if (swingsSinceLastAttack == 0 && lastAttackTime > 0) {
            attacksWithoutSwing++;
        } else {
            attacksWithoutSwing = 0;
        }

        this.lastAttackedEntity = targetId;
        this.lastAttackTime = now;
        this.swingsSinceLastAttack = 0;
        return attackedThisTick.size() > 1;
    }

    public void recordSwing() {
        this.lastSwingTime = System.currentTimeMillis();
        this.swingsSinceLastAttack++;
        this.attacksWithoutSwing = 0; // 有挥臂 → 重置无挥臂计数
    }

    public int getAttacksWithoutSwing() { return attacksWithoutSwing; }

    // ── Block place tracking (sliding-window timestamps) ─────────────────

    public void recordBlockPlace() {
        this.placeTimestamps.add(System.currentTimeMillis());
    }

    // ── TeleportTracker API ──────────────────────────────────────────────

    public void setTeleportExempt(int ticks) {
        this.teleportExemptTicks = Math.max(this.teleportExemptTicks, ticks);
    }
    public boolean isTeleportExempt() { return teleportExemptTicks > 0; }
    public int getTeleportExemptTicks() { return teleportExemptTicks; }

    // ── VelocityTracker API ──────────────────────────────────────────────

    public void setVelocity(double vx, double vy, double vz) {
        this.velocityX = vx; this.velocityY = vy; this.velocityZ = vz;
        this.velocityRawX = vx; this.velocityRawZ = vz;
        this.velocityTicksRemaining = VELOCITY_MAX_TICKS;
        this.tickSinceVelocity = 0;
    }

    public double getVelocityBufferY() {
        if (velocityTicksRemaining <= 0) return 0.0;
        int elapsed = VELOCITY_MAX_TICKS - velocityTicksRemaining;
        double drag = Math.pow(0.98, elapsed);
        double gravitySum = 0.08 * (1.0 - drag) / 0.02;
        return velocityY * drag - gravitySum;
    }

    public double getVelocityY() { return velocityY; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityZ() { return velocityZ; }

    /** Returns the velocity buffer Y value from the PREVIOUS tick (for marginal delta calc). */
    public double getLastVelocityBufferY() {
        if (velocityTicksRemaining <= 0) return 0.0;
        int elapsedPrev = VELOCITY_MAX_TICKS - velocityTicksRemaining - 1;
        if (elapsedPrev < 0) return 0.0;
        double drag = Math.pow(0.98, elapsedPrev);
        double gravitySum = 0.08 * (1.0 - drag) / 0.02;
        return velocityY * drag - gravitySum;
    }

    public double getVelocityBufferX() {
        if (velocityTicksRemaining <= 0) return 0.0;
        double drag = Math.pow(0.91, VELOCITY_MAX_TICKS - velocityTicksRemaining);
        return velocityX * drag;
    }

    public double getVelocityBufferZ() {
        if (velocityTicksRemaining <= 0) return 0.0;
        double drag = Math.pow(0.91, VELOCITY_MAX_TICKS - velocityTicksRemaining);
        return velocityZ * drag;
    }

    public int getVelocityTicksRemaining() { return velocityTicksRemaining; }

    // ── BlockStateCache queries ──────────────────────────────────────────

    public boolean isRecentlyInWeb() { return webContactSeen && webContactTicks <= 3; }
    public boolean isRecentlyInLiquid() { return liquidContactSeen && liquidContactTicks <= 3; }
    public boolean isRecentlyOnClimbable() { return climbableContactSeen && climbableContactTicks <= 3; }

    // ── Combat queries ───────────────────────────────────────────────────

    public long getLastAttackTime() { return lastAttackTime; }
    public long getLastSwingTime() { return lastSwingTime; }
    public int getSwingsSinceLastAttack() { return swingsSinceLastAttack; }
    public UUID getLastAttackedEntity() { return lastAttackedEntity; }
    public int getMultiAuraCount() { return multiAuraCount; }
    public int getAttackConsistencyCount() { return attackConsistencyCount; }

    // ── Scaffold queries ──────────────────────────────────────────────────

    public double getScaffoldBuffer() { return this.scaffoldBuffer; }
    public void setScaffoldBuffer(double v) { this.scaffoldBuffer = Math.min(v, com.aether.anticheat.check.Check.BUFFER_SOFT_CAP); }
    public float getLastPlacePitch() { return this.lastPlacePitch; }
    public void setLastPlacePitch(float v) { this.lastPlacePitch = v; }
    public float getLastPlaceYaw() { return this.lastPlaceYaw; }
    public void setLastPlaceYaw(float v) { this.lastPlaceYaw = v; }
    public int getScaffoldRotationStreak() { return this.scaffoldRotationStreak; }
    public void setScaffoldRotationStreak(int v) { this.scaffoldRotationStreak = v; }
    public int getTellySnapTicks() { return this.tellySnapTicks; }
    public void setTellySnapTicks(int v) { this.tellySnapTicks = v; }
    public double getScaffoldPitchFrozenHits() { return this.scaffoldPitchFrozenHits; }
    public void setScaffoldPitchFrozenHits(double v) { this.scaffoldPitchFrozenHits = v; }
    public long getLastScaffoldAnalysisTime() { return this.lastScaffoldAnalysisTime; }
    public void setLastScaffoldAnalysisTime(long v) { this.lastScaffoldAnalysisTime = v; }

    /**
     * 1-second sliding window placement frequency with stale timestamp pruning.
     * @return count of block placements within the last 1000 ms.
     */
    public int getSlidingPlaceCount() {
        long now = System.currentTimeMillis();
        placeTimestamps.removeIf(time -> (now - time) > 1000L);
        return placeTimestamps.size();
    }

    /**
     * Convenience — returns the most recent block-placement timestamp (ms),
     * or 0 if no placements recorded yet.  Used by FastPlaceCheck.
     */
    public long getLastBlockPlaceTime() {
        if (placeTimestamps.isEmpty()) return 0;
        return placeTimestamps.get(placeTimestamps.size() - 1);
    }

    // ── NoFall ───────────────────────────────────────────────────────────

    public float getLastFallDistance() { return lastFallDistance; }
    public void setLastFallDistance(float v) { this.lastFallDistance = v; }
    public float getClientFallDistance() { return clientFallDistance; }
    public void setClientFallDistance(float v) { this.clientFallDistance = v; }
    public double getNoFallBuffer() { return this.noFallBuffer; }
    public void setNoFallBuffer(double v) { this.noFallBuffer = v; }
    public double getServerFallDistance() { return this.serverFallDistance; }
    public void setServerFallDistance(double v) { this.serverFallDistance = v; }

    // ── Timer (Balance-Based Time Credit System) ──────────────────────────

    public long getTimerBalance() { return this.timerBalance; }
    public void setTimerBalance(long v) { this.timerBalance = v; }
    public long getLastTimerPacketTime() { return this.lastTimerPacketTime; }
    public void setLastTimerPacketTime(long v) { this.lastTimerPacketTime = v; }
    public double getTimerViolationBuffer() { return this.timerViolationBuffer; }
    public void setTimerViolationBuffer(double v) { this.timerViolationBuffer = v; }

    // ── BadPackets (Packet Flood Filter) ──────────────────────────────────

    public int getPacketsThisTick() { return this.packetsThisTick; }
    public void setPacketsThisTick(int v) { this.packetsThisTick = v; }
    public double getBadPacketsBuffer() { return this.badPacketsBuffer; }
    public void setBadPacketsBuffer(double v) { this.badPacketsBuffer = v; }

    // ── AutoBlock (1.8.9 Sword Blocking) ──────────────────────────────────

    public boolean isBlocking() { return this.blocking; }
    public void setBlocking(boolean v) { this.blocking = v; }
    public long getBlockStartTime() { return this.blockStartTime; }
    public void setBlockStartTime(long v) { this.blockStartTime = v; }
    public long getBlockStopTime() { return this.blockStopTime; }
    public void setBlockStopTime(long v) { this.blockStopTime = v; }
    public long getLastBlockDuration() { return this.lastBlockDuration; }
    public void setLastBlockDuration(long v) { this.lastBlockDuration = v; }
    public long getLastDamageReceivedTime() { return this.lastDamageReceivedTime; }
    public void setLastDamageReceivedTime(long v) { this.lastDamageReceivedTime = v; }
    public long getBlockToggleWindowStart() { return this.blockToggleWindowStart; }
    public void setBlockToggleWindowStart(long v) { this.blockToggleWindowStart = v; }
    public int getBlockToggleCount() { return this.blockToggleCount; }
    public void setBlockToggleCount(int v) { this.blockToggleCount = v; }
    public int getBlockAttackSameTickCount() { return this.blockAttackSameTickCount; }
    public void setBlockAttackSameTickCount(int v) { this.blockAttackSameTickCount = v; }
    public long getLastBlockWhileAttackTime() { return this.lastBlockWhileAttackTime; }
    public void setLastBlockWhileAttackTime(long v) { this.lastBlockWhileAttackTime = v; }
    public double getAutoBlockABuffer() { return this.autoBlockABuffer; }
    public void setAutoBlockABuffer(double v) { this.autoBlockABuffer = v; }
    public double getAutoBlockBBuffer() { return this.autoBlockBBuffer; }
    public void setAutoBlockBBuffer(double v) { this.autoBlockBBuffer = v; }
    public double getAutoBlockCBuffer() { return this.autoBlockCBuffer; }
    public void setAutoBlockCBuffer(double v) { this.autoBlockCBuffer = v; }

    /**
     * Record a blocking state transition. Called when block starts or stops.
     */
    public void recordBlockToggle(boolean started) {
        long now = System.currentTimeMillis();
        if (started) {
            this.blocking = true;
            this.blockStartTime = now;
            if (this.blockStopTime > 0) {
                this.lastBlockDuration = this.blockStartTime - this.blockStopTime;
            }
        } else {
            this.blocking = false;
            this.blockStopTime = now;
            if (this.blockStartTime > 0) {
                this.lastBlockDuration = now - this.blockStartTime;
            }
        }

        // Track toggle frequency within a 1-second window
        if (this.blockToggleWindowStart == 0 || now - this.blockToggleWindowStart > 1000) {
            this.blockToggleWindowStart = now;
            this.blockToggleCount = 1;
        } else {
            this.blockToggleCount++;
        }
    }

    /**
     * Record incoming damage timestamp for AutoBlock(A) reaction-time check.
     */
    public void recordIncomingDamage(long time) {
        this.lastDamageReceivedTime = time;
    }

    // ── Air ticks ────────────────────────────────────────────────────────

    public int getAirTicks() { return airTicks; }
    public void resetAirTicks() { airTicks = 0; }

    // ── Attack rotation (KillAura) ───────────────────────────────────────

    public void recordAttackRotation(float yaw, float pitch) {
        this.lastAttackYawDelta = yaw - this.lastAttackYaw;
        this.lastAttackPitchDelta = pitch - this.lastAttackPitch;
        this.lastAttackYaw = yaw;
        this.lastAttackPitch = pitch;
    }
    public float getLastAttackYaw() { return lastAttackYaw; }
    public float getLastAttackPitch() { return lastAttackPitch; }
    public float getLastAttackYawDelta() { return lastAttackYawDelta; }
    public float getLastAttackPitchDelta() { return lastAttackPitchDelta; }

    // ── AntiKB ──────────────────────────────────────────────────────────

    public void recordDamage(double amount, boolean kb) {
        this.lastDamageTime = System.currentTimeMillis();
        this.lastDamageAmount = amount;
        this.damageContainedKnockback = kb;
        this.ticksSinceDamage = 0;
    }
    public long getLastDamageTime() { return lastDamageTime; }
    public double getLastDamageAmount() { return lastDamageAmount; }
    public int getTicksSinceDamage() { return ticksSinceDamage; }
    public boolean damageContainedKnockback() { return damageContainedKnockback; }

    // ── NoSlow ───────────────────────────────────────────────────────────

    public boolean isUsingItem() { return usingItem; }
    public void setUsingItem(boolean v) {
        this.usingItem = v;
        if (v) {
            this.usingItemStartTime = System.currentTimeMillis();
            this.usingItemTicks = 0;
        }
    }
    public long getUsingItemStartTime() { return usingItemStartTime; }
    public int getUsingItemTicks() { return usingItemTicks; }

    // ── NoSlow slot-switch bypass ────────────────────────────────────

    public int getTickSinceSlotSwitch() { return tickSinceSlotSwitch; }
    public void setTickSinceSlotSwitch(int v) { this.tickSinceSlotSwitch = v; }
    public boolean wasUsingBeforeSwitch() { return wasUsingBeforeSwitch; }
    public void setWasUsingBeforeSwitch(boolean v) { this.wasUsingBeforeSwitch = v; }
    public int getSlotSwitchCount() { return slotSwitchCount; }
    public void incrementSlotSwitchCount() {
        long now = System.currentTimeMillis();
        if (now - slotSwitchWindowStart > 1000) {
            slotSwitchWindowStart = now;
            slotSwitchCount = 1;
        } else {
            slotSwitchCount++;
        }
    }
    public void resetSlotSwitchState() {
        this.wasUsingBeforeSwitch = false;
        this.slotSwitchCount = 0;
        this.tickSinceSlotSwitch = 127;
    }

    // ── Deep Aimbot rotation model ─────────────────────────────────────

    /**
     * Feed an attack rotation sample.  Called on every melee attack.
     * @param yaw    attacker current yaw
     * @param pitch  attacker current pitch
     * @param tYaw   exact yaw to face target center
     * @param tPitch exact pitch to face target center
     */
    public void feedAimSample(float yaw, float pitch, float tYaw, float tPitch) {
        totalAimSamples++;
        if (totalAimSamples > 5000) totalAimSamples = 500;

        // 1. Aim error
        double yErr = Math.abs(yaw - tYaw);
        if (yErr > 180.0) yErr = 360.0 - yErr;
        double pErr = Math.abs(pitch - tPitch);
        this.lastYawError = yErr;
        this.lastPitchError = pErr;
        this.lastAimError = Math.sqrt(yErr * yErr + pErr * pErr);
        this.aimErrorSum += lastAimError;
        this.aimErrorSamples++;
        if (lastAimError < 1.5) consecutivePerfectAim++;
        else consecutivePerfectAim = Math.max(0, consecutivePerfectAim - 2);

        // 2. Rotation deltas into circular buffer
        if (rotationSamples > 0) {
            double yD = yaw - lastAttackYaw;
            if (yD > 180) yD -= 360; else if (yD < -180) yD += 360;
            recentYawDeltas[rotationBufferIdx] = yD;
            recentPitchDeltas[rotationBufferIdx] = pitch - lastAttackPitch;
            rotationBufferIdx = (rotationBufferIdx + 1) % 12;
            if (rotationSamples < 12) rotationSamples++;
        } else {
            rotationBufferIdx = 0;
            rotationSamples = 1;
        }

        // 3. Kinematic derivatives (angular vel → accel → jerk)
        if (rotationSamples >= 3) {
            int p2 = (rotationBufferIdx - 3 + 12) % 12;
            int p1 = (rotationBufferIdx - 2 + 12) % 12;
            int c  = (rotationBufferIdx - 1 + 12) % 12;
            double v0 = recentYawDeltas[p2];
            double v1 = recentYawDeltas[p1];
            double v2 = recentYawDeltas[c];
            double a0 = v1 - v0;           // acceleration (2nd derivative)
            double a1 = v2 - v1;
            double jerk = a1 - a0;         // jerk (3rd derivative)
            this.jerkRunningSum += Math.abs(jerk);
            this.jerkSamples++;
            this.lastAngularVelocity = v2;
            this.lastAngularAccel = a1;
        }

        // 4. Snap detection — large single-tick yaw change landing on target
        if (rotationSamples >= 2) {
            int c = (rotationBufferIdx - 1 + 12) % 12;
            if (Math.abs(recentYawDeltas[c]) > 15.0 && lastAimError < 3.0) {
                rotationSnapCount++;
            }
        }
    }

    public double getYawDeltaStdDev() {
        if (rotationSamples < 3) return 0;
        double m = 0;
        for (int i = 0; i < rotationSamples; i++) m += recentYawDeltas[i];
        m /= rotationSamples;
        double sq = 0;
        for (int i = 0; i < rotationSamples; i++) {double d = recentYawDeltas[i] - m; sq += d*d;}
        return Math.sqrt(sq / rotationSamples);
    }

    public double getAvgJerk() {return jerkSamples > 0 ? jerkRunningSum / jerkSamples : 0;}
    public double getLastAimError() {return lastAimError;}
    public double getLastYawError() {return lastYawError;}
    public double getLastPitchError() {return lastPitchError;}
    public double getAvgAimError() {return aimErrorSamples > 0 ? aimErrorSum / aimErrorSamples : 999;}
    public double getLastAngularAccel() {return lastAngularAccel;}
    public int getConsecutivePerfectAim() {return consecutivePerfectAim;}
    public int getRotationSnapCount() {return rotationSnapCount;}
    public int getTotalAimSamples() {return totalAimSamples;}

    public void resetAimModel() {
        // Only reset streak/snap counters — preserve angular kinematics
        // so CNN feature vectors don't get zeroed out after a flag.
        rotationSamples = 0;
        rotationBufferIdx = 0;
        aimErrorSum = 0;
        aimErrorSamples = 0;
        consecutivePerfectAim = 0;
        rotationSnapCount = 0;
        // KEEP: jerkRunningSum, jerkSamples, lastAngularAccel,
        //        lastYawError, lastPitchError, lastAngularVelocity
    }

    // ── CNN temporal feature sequence ─────────────────────────────

    /**
     * Push a 20-dimensional feature vector into the sliding window.
     * Maintains exactly CNN_SEQUENCE_LENGTH entries (newest last, oldest evicted).
     */
    public void pushAttackFeature(double[] features) {
        if (attackFeatureSequence.size() >= CNN_SEQUENCE_LENGTH) {
            attackFeatureSequence.pollFirst();
        }
        attackFeatureSequence.addLast(features.clone());
    }

    /**
     * Get the full feature sequence as a float[CNN_SEQUENCE_LENGTH][CNN_N_FEATURES] matrix.
     * If fewer than CNN_SEQUENCE_LENGTH samples exist, pads with zeros at the front.
     */
    public float[][] getAttackFeatureSequence() {
        float[][] matrix = new float[CNN_SEQUENCE_LENGTH][CNN_N_FEATURES];
        int offset = CNN_SEQUENCE_LENGTH - attackFeatureSequence.size();
        int idx = 0;
        for (double[] feat : attackFeatureSequence) {
            float[] row = matrix[offset + idx];
            for (int f = 0; f < CNN_N_FEATURES && f < feat.length; f++) {
                row[f] = (float) feat[f];
            }
            idx++;
        }
        return matrix;
    }

    /** Number of samples currently in the CNN feature sequence. */
    public int getAttackSequenceSize() {
        return attackFeatureSequence.size();
    }

    // ── Latency ──────────────────────────────────────────────────────────

    public double getFlyBuffer() { return this.flyBuffer; }
    public void setFlyBuffer(double value) { this.flyBuffer = value; }

    public double getSpeedBuffer() { return this.speedBuffer; }
    public void setSpeedBuffer(double value) { this.speedBuffer = value; }
    public double getLongJumpBuffer() { return this.longJumpBuffer; }
    public void setLongJumpBuffer(double value) { this.longJumpBuffer = value; }
    public double getKillAuraBuffer() { return this.killAuraBuffer; }
    public void setKillAuraBuffer(double value) { this.killAuraBuffer = value; }
    public double getAiCheckBuffer() { return this.aiCheckBuffer; }
    public void setAiCheckBuffer(double value) { this.aiCheckBuffer = value; }
    public double getNoSlowBuffer() { return this.noSlowBuffer; }
    public void setNoSlowBuffer(double value) { this.noSlowBuffer = value; }

    // ── KillAuraAI Recording ─────────────────────────────────────────

    public boolean isRecording() { return recordingActive; }
    public void setRecording(boolean v) { this.recordingActive = v; }
    public String getRecordingLabel() { return recordingLabel; }
    public void setRecordingLabel(String v) { this.recordingLabel = v; }
    public List<String> getRecordingBuffer() { return recordingBuffer; }
    public long getRecordingStartTime() { return recordingStartTime; }
    public void setRecordingStartTime(long v) { this.recordingStartTime = v; }
    public String getRecordingPlayerName() { return recordingPlayerName; }
    public void setRecordingPlayerName(String v) { this.recordingPlayerName = v; }

    /**
     * Add a CSV row to the recording buffer.
     * Format: label,timestamp,deltaYaw,deltaPitch,aimError,gcdResY,gcdResP,angVel,angAccel,jerk,
     *         atkIntervalMs,cps,attackerYaw,attackerPitch,targetYaw,targetPitch,
     *         yawError,pitchError,distanceToTarget,movementAngle,sprinting,blocking,flaggedByKA
     */
    public void recordFeatureVector(String label, double deltaYaw, double deltaPitch,
                                     double aimError, double gcdResY, double gcdResP,
                                     double angVel, double angAccel, double jerk,
                                     long atkIntervalMs, double cps,
                                     double attackerYaw, double attackerPitch,
                                     double targetYaw, double targetPitch,
                                     double yawError, double pitchError,
                                     double distanceToTarget, double movementAngle,
                                     double sprinting, double blocking,
                                     boolean flaggedByKA) {
        if (!recordingActive) return;
        String row = String.format(
                "%s,%d,%.4f,%.4f,%.4f,%.6f,%.6f,%.4f,%.4f,%.6f,%d,%.2f," +
                "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.0f,%.0f,%s",
                label, System.currentTimeMillis(),
                deltaYaw, deltaPitch, aimError,
                gcdResY, gcdResP,
                angVel, angAccel, jerk,
                atkIntervalMs, cps,
                attackerYaw, attackerPitch, targetYaw, targetPitch,
                yawError, pitchError, distanceToTarget, movementAngle,
                sprinting, blocking,
                flaggedByKA);
        recordingBuffer.add(row);
    }

    /**
     * 逆向反自瞄的核心：获取 1.8.9 客户端标准的鼠标硬件转动公约数基准。
     * <p>
     * 原版客户端转头角度计算必然包含灵敏度因子，delta 必须是它的整数倍。
     * 黑客客户端用数学函数（如 Smooth / Linear Interpolation）伪造的平滑曲线
     * 会完全偏离原版物理引擎的格子步进。
     */
    public double getMouseGCD() {
        // 1.8.9 物理引擎标准的鼠标像素步进常数计算
        float f = (float) (0.5 * 0.6 + 0.2);
        float f1 = f * f * f * 8.0F;
        return f1 * 0.15F;
    }
    public int getIceTicks() { return this.iceTicks; }
    public void setIceTicks(int value) { this.iceTicks = value; }

    public int getLatency() { return latency; }
    public void setLatency(int latency) { this.latency = latency; }

    // ── Violations ───────────────────────────────────────────────────────

    public int addViolation(Check check) {
        String key = check.getName();
        int cur = violations.getOrDefault(key, 0) + 1;
        violations.put(key, cur);
        return cur;
    }

    public void addViolationWithValue(String k, int v) { violations.put(k, v); }
    public void resetViolation(String k) { violations.remove(k); }
    public int getViolation(String k) { return violations.getOrDefault(k, 0); }
    public Map<String, Integer> getAllViolations() { return new HashMap<>(violations); }
    public void resetAllViolations() { violations.clear(); }

    // ── Flag cooldown tracking ──────────────────────────────────────

    public long getLastFlagTime(String checkName) {
        return lastFlagTime.getOrDefault(checkName, 0L);
    }

    public void setLastFlagTime(String checkName, long time) {
        lastFlagTime.put(checkName, time);
    }

    // ── Simple getters ───────────────────────────────────────────────────

    public UUID getUuid() { return uuid; }
    public Location getLastLocation() { return lastLocation; }
    public Location getCurrentLocation() { return currentLocation; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public double getDeltaZ() { return deltaZ; }
    public double getHorizontalDistance() { return horizontalDistance; }
    public double getLastHorizontalDistance() { return lastHorizontalDistance; }
    public void setLastHorizontalDistance(double v) { this.lastHorizontalDistance = v; }

    public double getLastX() { return lastX; }
    public void setLastX(double v) { this.lastX = v; }
    public double getLastZ() { return lastZ; }
    public void setLastZ(double v) { this.lastZ = v; }
    public double getPredictionX() { return predictionX; }
    public void setPredictionX(double v) { this.predictionX = v; }
    public double getPredictionZ() { return predictionZ; }
    public void setPredictionZ(double v) { this.predictionZ = v; }
    public boolean wasOnGround() { return wasOnGround; }
    public void setWasOnGround(boolean v) { this.wasOnGround = v; }
    public double getLastDeltaX() { return lastDeltaX; }
    public double getLastDeltaY() { return lastDeltaY; }
    public double getLastDeltaZ() { return lastDeltaZ; }
    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean v) { this.onGround = v; }
    public boolean isLastOnGround() { return lastOnGround; }
    public boolean isFlying() { return flying; }
    public void setFlying(boolean v) { this.flying = v; }
    public boolean isSneaking() { return sneaking; }
    public void setSneaking(boolean v) { this.sneaking = v; }
    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean v) { this.sprinting = v; }

    // ── Global tick ──────────────────────────────────────────────────────

    public int getTick() { return tick; }
    public void setTick(int v) { this.tick = v; }

    // ── Tick-since counter getters ──────────────────────────────────────

    public int getTickSinceTeleport() { return tickSinceTeleport; }
    public void setTickSinceTeleport(int v) { this.tickSinceTeleport = v; }
    public int getTickSinceVelocity() { return tickSinceVelocity; }
    public void setTickSinceVelocity(int v) { this.tickSinceVelocity = v; }
    public int getTickSinceAttack() { return tickSinceAttack; }
    public void setTickSinceAttack(int v) { this.tickSinceAttack = v; }
    public int getTickSincePushedByPiston() { return tickSincePushedByPiston; }
    public void setTickSincePushedByPiston(int v) { this.tickSincePushedByPiston = v; }
    public int getTickSinceAbilityChange() { return tickSinceAbilityChange; }
    public void setTickSinceAbilityChange(int v) { this.tickSinceAbilityChange = v; }
    public int getTickSinceSteerVehicle() { return tickSinceSteerVehicle; }
    public void setTickSinceSteerVehicle(int v) { this.tickSinceSteerVehicle = v; }
    public int getTickSinceClientGround() { return tickSinceClientGround; }
    public void setTickSinceClientGround(int v) { this.tickSinceClientGround = v; }
    public int getTickSinceNearWall() { return tickSinceNearWall; }
    public void setTickSinceNearWall(int v) { this.tickSinceNearWall = v; }
    public int getTickSinceInFlowingLava() { return tickSinceInFlowingLava; }
    public void setTickSinceInFlowingLava(int v) { this.tickSinceInFlowingLava = v; }
    public int getTickSinceDroppedItem() { return tickSinceDroppedItem; }
    public void setTickSinceDroppedItem(int v) { this.tickSinceDroppedItem = v; }
    public int getTickSinceEating() { return tickSinceEating; }
    public void setTickSinceEating(int v) { this.tickSinceEating = v; }
    public int getMaxVelocityTicks() { return VELOCITY_MAX_TICKS; }

    // ── Fly tick-since counters ──────────────────────────────────────────
    public int getTickSinceNearStep() { return tickSinceNearStep; }
    public void setTickSinceNearStep(int v) { this.tickSinceNearStep = v; }
    public int getTickSinceClimbing() { return tickSinceClimbing; }
    public void setTickSinceClimbing(int v) { this.tickSinceClimbing = v; }
    public int getTickSinceUnderBlock() { return tickSinceUnderBlock; }
    public void setTickSinceUnderBlock(int v) { this.tickSinceUnderBlock = v; }
    public int getTickSinceInLiquid() { return tickSinceInLiquid; }
    public void setTickSinceInLiquid(int v) { this.tickSinceInLiquid = v; }
    public int getTickSinceOnSlime() { return tickSinceOnSlime; }
    public void setTickSinceOnSlime(int v) { this.tickSinceOnSlime = v; }
    public int getTickSinceOtherVelocity() { return tickSinceOtherVelocity; }
    public void setTickSinceOtherVelocity(int v) { this.tickSinceOtherVelocity = v; }

    public int getLiquidTicks() { return liquidContactTicks; }
    public int getClimbingTicks() { return climbableContactTicks; }
    public double getAttributeJump() { return 0.42F; }
    public int getJumpEffect() {
        // 简化的跳跃提升等级检测
        return 0;
    }
    public double getLastLastDeltaY() { return lastLastDeltaY; }

    // ── Extended state flag getters/setters ─────────────────────────────

    public boolean isInLiquid() { return inLiquid; }
    public void setInLiquid(boolean v) { this.inLiquid = v; }
    public boolean isInWater() { return inWater; }
    public void setInWater(boolean v) { this.inWater = v; }
    public boolean isInFlowingWater() { return inFlowingWater; }
    public void setInFlowingWater(boolean v) { this.inFlowingWater = v; }
    public boolean isInLava() { return inLava; }
    public void setInLava(boolean v) { this.inLava = v; }
    public boolean isInFlowingLava() { return inFlowingLava; }
    public void setInFlowingLava(boolean v) { this.inFlowingLava = v; }
    public boolean isUnderBlock() { return underBlock; }
    public void setUnderBlock(boolean v) { this.underBlock = v; }
    public boolean isInWeb() { return inWeb; }
    public void setInWeb(boolean v) { this.inWeb = v; }
    public boolean isOffsetMotion() { return offsetMotion; }
    public void setOffsetMotion(boolean v) { this.offsetMotion = v; }
    public boolean isOffsetYMotion() { return offsetYMotion; }
    public void setOffsetYMotion(boolean v) { this.offsetYMotion = v; }
    public boolean isNearBoat() { return nearBoat; }
    public void setNearBoat(boolean v) { this.nearBoat = v; }
    public boolean isNearWall() { return nearWall; }
    public void setNearWall(boolean v) { this.nearWall = v; }
    public boolean isOnSlime() { return onSlime; }
    public void setOnSlime(boolean v) { this.onSlime = v; }
    public boolean isEating() { return eating; }
    public void setEating(boolean v) { this.eating = v; }
    public boolean isClimbing() { return climbing; }
    public void setClimbing(boolean v) { this.climbing = v; }
    public boolean isJumped() { return jumped; }
    public void setJumped(boolean v) { this.jumped = v; }
    public boolean isNearStep() { return nearStep; }
    public void setNearStep(boolean v) { this.nearStep = v; }

    // ── Speed sub-check buffer getters/setters ──────────────────────────

    public double getSpeedABuffer() { return speedABuffer; }
    public void setSpeedABuffer(double v) { this.speedABuffer = v; }
    public double getSpeedBBuffer() { return speedBBuffer; }
    public void setSpeedBBuffer(double v) { this.speedBBuffer = v; }
    public double getSpeedCBuffer() { return speedCBuffer; }
    public void setSpeedCBuffer(double v) { this.speedCBuffer = v; }
    public double getSpeedDBuffer() { return speedDBuffer; }
    public void setSpeedDBuffer(double v) { this.speedDBuffer = v; }

    // ── SpeedA internal tracking getters/setters ────────────────────────

    public Double getLastSpeed() { return lastSpeed; }
    public void setLastSpeed(Double v) { this.lastSpeed = v; }
    public boolean isLastSprinted() { return lastSprinted; }
    public void setLastSprinted(boolean v) { this.lastSprinted = v; }
    public boolean isWasSneakOnEdge() { return wasSneakOnEdge; }
    public void setWasSneakOnEdge(boolean v) { this.wasSneakOnEdge = v; }
    public int getLastFlagTicks() { return lastFlagTicks; }
    public void setLastFlagTicks(int v) { this.lastFlagTicks = v; }

    // ── Client ground ─────────────────────────────────────────────────
    public boolean isClientGround() { return clientGround; }
    public void setClientGround(boolean v) { this.clientGround = v; }
    public boolean isLastClientGround() { return lastClientGround; }

    // ── Inferred sprinting ────────────────────────────────────────────
    public boolean isInferredSprinting() { return inferredSprinting; }

    /**
     * 基于移动速度推断真实疾跑状态。
     *
     * 问题：Bukkit player.isSprinting() 在疾跑切换同一 tick 可能滞后，
     * 导致 SpeedCheckA 用行走/潜行速度预测实际是疾跑的移动 → 大量误判。
     *
     * 解决：当水平速度明显超过行走上限（0.22 block/tick）时推断为疾跑。
     */
    public void inferSprintingState() {
        if (this.sprinting) {
            this.inferredSprinting = true;
            return;
        }
        // 行走最大速度约 0.22（含速度 I 药水），疾跑最低约 0.26
        // 如果水平位移 > 0.22 且不在潜行/使用物品 → 推断为疾跑
        if (this.horizontalDistance > 0.22 && !this.sneaking && !this.usingItem) {
            this.inferredSprinting = true;
            return;
        }
        this.inferredSprinting = false;
    }

    // ── SpeedD internal tracking getters/setters ────────────────────────

    public boolean isDidSlotChangeLastTick() { return didSlotChangeLastTick; }
    public void setDidSlotChangeLastTick(boolean v) { this.didSlotChangeLastTick = v; }
    public boolean isFlaggedLastTick() { return flaggedLastTick; }
    public void setFlaggedLastTick(boolean v) { this.flaggedLastTick = v; }

    // ── Raw velocity components ─────────────────────────────────────────

    public double getVelocityRawX() { return velocityRawX; }
    public double getVelocityRawZ() { return velocityRawZ; }

    // ── Extended helpers ────────────────────────────────────────────────

    /**
     * Returns the horizontal velocity magnitude (XZ plane) from the velocity buffer.
     */
    public double getVelocityXZ() {
        double vx = getVelocityBufferX();
        double vz = getVelocityBufferZ();
        return Math.sqrt(vx * vx + vz * vz);
    }

    /**
     * Check if the player is standing near the edge of a block.
     * Used for sneak-on-edge false positive mitigation.
     */
    public boolean isOnEdge(double threshold) {
        if (currentLocation == null) return false;
        double x = currentLocation.getX();
        double z = currentLocation.getZ();
        double bx = x % 1.0;
        double bz = z % 1.0;
        if (bx < 0) bx += 1.0;
        if (bz < 0) bz += 1.0;
        return bx < threshold || bx > (1.0 - threshold) || bz < threshold || bz > (1.0 - threshold);
    }

    /**
     * Force the player to stop sneaking. Used as a setback measure.
     */
    public void resetSneak(Player player) {
        if (player != null) {
            player.setSneaking(false);
            this.sneaking = false;
        }
    }

    /**
     * Teleport the player back to their last known location (setback).
     */
    public void setback(Player player) {
        if (player != null && lastLocation != null) {
            player.teleport(lastLocation.clone());
        }
    }

    /**
     * Force a random hotbar slot change. Used as a setback measure for SpeedD.
     */
    public void randomChangeSlot(Player player) {
        if (player != null) {
            int current = player.getInventory().getHeldItemSlot();
            int next = (current + 1) % 9;
            player.getInventory().setHeldItemSlot(next);
            this.didSlotChangeLastTick = true;
        }
    }

    private static boolean isSolidBlocking(Material m) {
        if (!m.isSolid()) return false;
        return m != Material.GLASS && m != Material.STAINED_GLASS
                && m != Material.GLOWSTONE && m != Material.ICE
                && m != Material.PACKED_ICE && m != Material.LEAVES
                && m != Material.LEAVES_2 && m != Material.SLIME_BLOCK
                && m != Material.CARPET;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.STATIONARY_WATER
                || m == Material.LAVA || m == Material.STATIONARY_LAVA;
    }

    private static boolean isClimbable(Material m) {
        return m == Material.LADDER || m == Material.VINE;
    }

    private static boolean isStepOrSlab(Material m) {
        return m == Material.STEP || m == Material.WOOD_STEP || m == Material.STONE_SLAB2
                || m.name().contains("STAIRS") || m == Material.BED_BLOCK
                || m == Material.BED || m == Material.CARPET;
    }
}
