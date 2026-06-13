package com.aether.anticheat.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Training bot with anti-combo AI — spawns a resilient zombie that mimics
 * real PvP behavior for high-quality KillAuraAI training data collection.
 *
 * <h3>Anti-Combo Features</h3>
 * <ul>
 *   <li><b>ComboState machine</b> — NORMAL / AIR_COMBO / WALL_PIN / RESET</li>
 *   <li><b>Knockback resistance</b> — DAMAGE_RESISTANCE II + velocity dampening</li>
 *   <li><b>Sword blocking</b> — simulated 1.8.9 block when under pressure</li>
 *   <li><b>Predictive dodge</b> — strafes on predicted attack ticks</li>
 *   <li><b>Distance management</b> — maintains 2.5-3.2 block sweet spot</li>
 *   <li><b>FEINT</b> — fake advance → retreat → counter-attack</li>
 * </ul>
 *
 * <p>noDamageTicks is reset every AI tick via NMS reflection so every
 * player click registers as a hit — critical for dense training data.
 */
public class TrainingBotManager {

    private final JavaPlugin plugin;
    private final Map<UUID, Zombie> activeBots = new HashMap<>();
    private final Map<UUID, BukkitTask> botTasks = new HashMap<>();
    private final Map<UUID, BotState> botStates = new HashMap<>();
    private final Random rng = new Random();

    // ── AI tuning ──────────────────────────────────────────────────────
    private static final double BOT_SPEED = 0.22;
    private static final double BLOCK_SPEED = 0.13;          // slower during block
    private static final double ATTACK_RANGE = 3.2;
    private static final int    ATTACK_COOLDOWN_TICKS = 28;
    private static final double STRAFE_CHANCE = 0.30;
    private static final double BACKUP_CHANCE = 0.12;
    private static final double CIRCLE_CHANCE = 0.12;
    private static final double JUMP_CHANCE = 0.08;
    private static final double FEINT_CHANCE = 0.06;
    private static final double DODGE_CHANCE = 0.10;
    private static final double BOT_MAX_HEALTH = 100.0;
    private static final double BOT_ATTACK_DMG_MIN = 0.8;
    private static final double BOT_ATTACK_DMG_MAX = 2.5;
    private static final double HEALTH_REGEN_PER_TICK = 2.0;
    private static final double HEALTH_REGEN_CHANCE = 0.10;
    private static final int    AI_TICK_INTERVAL = 3;
    private static final int    BLOCK_COOLDOWN_TICKS = 40;   // 2s between blocks
    private static final int    BLOCK_DURATION_TICKS = 14;   // ~0.7s block hold
    private static final int    COMBO_DAMAGE_THRESHOLD = 3;  // 3 hits in 1s → block
    private static final double AIR_COMBO_VELOCITY_Y = 0.30;
    private static final double WALL_PROXIMITY = 1.5;        // blocks from wall → WALL_PIN
    // ── Anti-fly / velocity control ─────────────────────────────────────────
    private static final double MAX_UPWARD_VELOCITY = 1.2;   // hard Y cap — prevents moon launch
    private static final double VELOCITY_DAMPEN_TRIGGER = 0.10; // start dampening early
    private static final double PROGRESSIVE_DAMPEN = 0.50;   // halve upward vel every AI tick (was 0.65)
    private static final double MAX_AIR_HEIGHT = 6.0;        // max blocks above ground before emergency pull-down
    private static final int    AIR_COMBO_TIMEOUT = 30;      // max AI ticks in AIR_COMBO before forced reset

    // ── Combo state machine ────────────────────────────────────────────

    enum ComboState {
        NORMAL,      // standard evasive movement
        AIR_COMBO,   // being juggled — escape + block
        WALL_PIN,    // trapped against wall — lateral breakout
        RESET        // recovering from combo — reposition
    }

    static class BotState {
        ComboState comboState = ComboState.NORMAL;
        int stateTicks = 0;
        int blockTicksRemaining = 0;
        int blockCooldownTicks = 0;
        int damageWindowHits = 0;
        long damageWindowStart = 0;
        long lastBlockEndTime = 0;
        long lastAttackTime = 0;
        int feintTicksRemaining = 0;
        boolean feintPhase = false; // true=advance, false=retreat
        int dodgeTicksRemaining = 0;
        int dodgeDirection = 0;      // -1 left, +1 right
        int botTickCounter = 0;      // alternates damage immunity for anti-knockback-stacking
    }

    public TrainingBotManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Spawn / Remove ─────────────────────────────────────────────────

    public Zombie spawnBot(Player target) {
        removeBot(target);

        Location spawnLoc = getSpawnLocation(target);
        Zombie bot = (Zombie) target.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);

        bot.setCustomName("§c[训练假人] §7" + target.getName());
        bot.setCustomNameVisible(true);
        bot.setBaby(false);
        bot.setRemoveWhenFarAway(false);
        bot.setCanPickupItems(false);
        bot.setMaxHealth(BOT_MAX_HEALTH);
        bot.setHealth(BOT_MAX_HEALTH);

        // Anti-combo: damage resistance + jump boost (prevents bounce fatigue)
        bot.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));

        // Iron sword + leather armor
        bot.getEquipment().setItemInHand(new ItemStack(Material.IRON_SWORD));
        bot.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        bot.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
        bot.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        bot.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        bot.getEquipment().setHelmetDropChance(0f);
        bot.getEquipment().setChestplateDropChance(0f);
        bot.getEquipment().setLeggingsDropChance(0f);
        bot.getEquipment().setBootsDropChance(0f);
        bot.getEquipment().setItemInHandDropChance(0f);

        activeBots.put(target.getUniqueId(), bot);
        botStates.put(target.getUniqueId(), new BotState());

        // AI task
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!bot.isValid() || !target.isOnline()) {
                removeBot(target);
                return;
            }
            tickAI(bot, target);
        }, 0L, AI_TICK_INTERVAL);

        botTasks.put(target.getUniqueId(), task);

        target.sendMessage("§8[§4[Aether]§8] §a反COMBO假人生成！§7100HP 抗KB 格挡 走位多变 预判闪避");
        return bot;
    }

    public void removeBot(Player target) {
        UUID uuid = target.getUniqueId();
        BukkitTask task = botTasks.remove(uuid);
        if (task != null) task.cancel();
        Zombie bot = activeBots.remove(uuid);
        if (bot != null && bot.isValid()) {
            bot.setHealth(0);
            bot.remove();
        }
        botStates.remove(uuid);
    }

    public void removeAllBots() {
        new ArrayList<>(activeBots.keySet()).forEach(uuid -> {
            Zombie bot = activeBots.remove(uuid);
            if (bot != null && bot.isValid()) {
                bot.setHealth(0);
                bot.remove();
            }
        });
        botTasks.values().forEach(BukkitTask::cancel);
        botTasks.clear();
        botStates.clear();
    }

    public boolean hasBot(Player target) {
        Zombie bot = activeBots.get(target.getUniqueId());
        return bot != null && bot.isValid();
    }

    // ── Main AI tick ───────────────────────────────────────────────────

    private void tickAI(Zombie bot, Player target) {
        BotState state = botStates.get(target.getUniqueId());
        if (state == null) { removeBot(target); return; }

        // Alternating damage immunity: even ticks = full knockback, odd ticks = brief immunity
        state.botTickCounter++;
        if (state.botTickCounter % 2 == 0) {
            resetNoDamageTicks(bot);   // zero immunity → full knockback for dense data
        } else {
            setNoDamageTicks(bot, 2);  // 2 ticks immunity → prevents infinite KB stacking
        }
        if (state == null) { removeBot(target); return; }

        Location bLoc = bot.getLocation();
        Location tLoc = target.getLocation();
        double dist = bLoc.distance(tLoc);
        Vector toPlayer = tLoc.toVector().subtract(bLoc.toVector()).normalize();

        // ── State management ─────────────────────────────────────────
        state.stateTicks++;

        // Detect air combo: bot is being juggled upward
        boolean inAir = bot.getVelocity().getY() > AIR_COMBO_VELOCITY_Y;
        boolean nearWall = isNearWall(bLoc, WALL_PROXIMITY);

        if (inAir) {
            state.comboState = ComboState.AIR_COMBO;
            state.stateTicks = 0;
        } else if (nearWall && dist < 2.0) {
            state.comboState = ComboState.WALL_PIN;
            state.stateTicks = 0;
        } else if (state.comboState == ComboState.AIR_COMBO && !inAir && state.stateTicks > 8) {
            state.comboState = ComboState.RESET;
            state.stateTicks = 0;
        } else if (state.comboState == ComboState.RESET && state.stateTicks > 15) {
            state.comboState = ComboState.NORMAL;
            state.stateTicks = 0;
        } else if (state.comboState == ComboState.WALL_PIN && (!nearWall || dist > 3.0)) {
            state.comboState = ComboState.NORMAL;
            state.stateTicks = 0;
        }

        // ── Block management ─────────────────────────────────────────
        if (state.blockCooldownTicks > 0) state.blockCooldownTicks--;
        if (state.blockTicksRemaining > 0) state.blockTicksRemaining--;

        // Auto-block trigger: 3+ hits in 1 second window
        if (state.damageWindowHits >= COMBO_DAMAGE_THRESHOLD
                && state.blockTicksRemaining <= 0
                && state.blockCooldownTicks <= 0) {
            startBlocking(bot, state);
        }

        // Expire damage window
        if (System.currentTimeMillis() - state.damageWindowStart > 1000) {
            state.damageWindowHits = 0;
        }

        boolean isBlocking = state.blockTicksRemaining > 0;

        // ── Progressive velocity dampening (anti-fly) — unconditional, not state-dependent ─
        Vector vel = bot.getVelocity();
        if (vel.getY() > MAX_UPWARD_VELOCITY) {
            // Hard cap: prevent moon-launch knockback cascading
            bot.setVelocity(new Vector(vel.getX(), MAX_UPWARD_VELOCITY, vel.getZ()));
        } else if (vel.getY() > VELOCITY_DAMPEN_TRIGGER) {
            // Gentle early dampening: start cutting before velocity builds up
            bot.setVelocity(new Vector(vel.getX(), vel.getY() * PROGRESSIVE_DAMPEN, vel.getZ()));
        }

        // ── Emergency ground recovery — punish bot that's stuck in the sky ─
        // On landing from AIR_COMBO, immediately transition to RESET
        if (bot.isOnGround() && bot.getVelocity().getY() < 0.05 && state.comboState == ComboState.AIR_COMBO) {
            state.comboState = ComboState.RESET;
            state.stateTicks = 0;
        }
        // Time-based fallback: AIR_COMBO > 30 ticks → force pull-down regardless of height
        if (state.comboState == ComboState.AIR_COMBO && state.stateTicks > AIR_COMBO_TIMEOUT) {
            bot.setVelocity(new Vector(bot.getVelocity().getX(), -2.0, bot.getVelocity().getZ()));
            state.comboState = ComboState.RESET;
            state.stateTicks = 0;
        }
        // Height-based pull-down: more than 6 blocks above ground → emergency
        if (!bot.isOnGround() && state.comboState != ComboState.NORMAL) {
            Location groundScan = bLoc.clone();
            int airBelow = 0;
            while (airBelow < 10 && groundScan.getY() > 0 && groundScan.getBlock().getType() == Material.AIR) {
                groundScan.subtract(0, 1, 0);
                airBelow++;
            }
            if (airBelow >= (int) MAX_AIR_HEIGHT) {
                // Hard pull-down — teleport-grade force
                Vector ev = bot.getVelocity();
                double pullY = Math.min(ev.getY(), -2.0); // force downward even if currently rising
                if (ev.getY() > -MAX_AIR_HEIGHT) pullY = -MAX_AIR_HEIGHT; // hard override
                bot.setVelocity(new Vector(ev.getX() * 0.5, pullY, ev.getZ() * 0.5));
                state.comboState = ComboState.RESET;
                state.stateTicks = 0;
            }
        }

        // ── Movement (state-dependent) ───────────────────────────────
        double currentSpeed = isBlocking ? BLOCK_SPEED : BOT_SPEED;

        switch (state.comboState) {
            case AIR_COMBO:
                moveAirCombo(bot, target, toPlayer, currentSpeed);
                break;
            case WALL_PIN:
                moveWallPin(bot, target, toPlayer, bLoc, currentSpeed);
                break;
            case RESET:
                moveReset(bot, target, toPlayer, currentSpeed);
                break;
            default:
                moveNormal(bot, target, toPlayer, dist, currentSpeed, state);
                break;
        }

        // ── Jumping (disabled during aerial combat to prevent sky-launch) ──
        if (bot.isOnGround()
                && rng.nextDouble() < JUMP_CHANCE
                && !isBlocking
                && state.comboState != ComboState.AIR_COMBO
                && state.comboState != ComboState.RESET
                && bot.getVelocity().getY() < VELOCITY_DAMPEN_TRIGGER) {
            bot.setVelocity(bot.getVelocity().setY(0.42));
        }

        // ── Attack ───────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        if (dist <= ATTACK_RANGE
                && (now - state.lastAttackTime) >= ATTACK_COOLDOWN_TICKS * 50L
                && !isBlocking) {
            double dmg = BOT_ATTACK_DMG_MIN + rng.nextDouble() * (BOT_ATTACK_DMG_MAX - BOT_ATTACK_DMG_MIN);
            target.damage(dmg, bot);
            state.lastAttackTime = now;
        }

        // ── Health regen ─────────────────────────────────────────────
        if (bot.getHealth() < bot.getMaxHealth() && rng.nextDouble() < HEALTH_REGEN_CHANCE) {
            bot.setHealth(Math.min(bot.getMaxHealth(), bot.getHealth() + HEALTH_REGEN_PER_TICK));
        }

    }

    // ── Movement behaviors ────────────────────────────────────────────

    /** Normal evasive movement with predictive dodge, feint, and distance management. */
    private void moveNormal(Zombie bot, Player target, Vector toPlayer,
                            double dist, double speed, BotState state) {
        // FEINT: fake advance then retreat
        if (state.feintTicksRemaining > 0) {
            state.feintTicksRemaining--;
            if (state.feintPhase) {
                // Advance aggressively
                moveToward(bot, toPlayer, speed * 1.3);
            } else {
                // Retreat quickly
                moveToward(bot, toPlayer.clone().multiply(-1), speed * 1.2);
            }
            if (state.feintTicksRemaining <= 0) {
                state.feintPhase = !state.feintPhase;
                if (state.feintPhase) {
                    state.feintTicksRemaining = 5 + rng.nextInt(4); // retreat 5-8 ticks
                }
                // After full feint cycle, reset
                if (!state.feintPhase && state.feintTicksRemaining <= 0) {
                    state.feintTicksRemaining = 0;
                }
            }
            return;
        }

        // Roll for feint initiation
        if (dist >= 2.5 && dist <= 4.0 && rng.nextDouble() < FEINT_CHANCE
                && state.feintTicksRemaining <= 0) {
            state.feintPhase = true;
            state.feintTicksRemaining = 6 + rng.nextInt(5); // advance 6-10 ticks
            return;
        }

        // Predictive dodge: strafe when player is about to attack
        if (state.dodgeTicksRemaining > 0) {
            state.dodgeTicksRemaining--;
            Vector strafe = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
            if (state.dodgeDirection < 0) strafe.multiply(-1);
            strafe.normalize().multiply(speed * 1.15);
            bot.setVelocity(strafe.setY(bot.getVelocity().getY()));
            return;
        }

        // Distance management
        if (dist > ATTACK_RANGE + 2.0) {
            // Far — chase
            moveToward(bot, toPlayer, speed * 1.4);
        } else if (dist < 1.5) {
            // Too close — back up fast
            moveToward(bot, toPlayer.clone().multiply(-1), speed * 1.1);
        } else if (dist < 2.2 && rng.nextDouble() < DODGE_CHANCE) {
            // Player closing in — predictive dodge
            state.dodgeTicksRemaining = 3 + rng.nextInt(4);
            state.dodgeDirection = rng.nextBoolean() ? 1 : -1;
        } else {
            // Standard evasive patterns within sweet spot (2.5-3.2)
            double roll = rng.nextDouble();
            double accum = 0.0;

            if ((accum += STRAFE_CHANCE) > 0 && roll < accum) {
                Vector strafe = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
                if (rng.nextBoolean()) strafe.multiply(-1);
                strafe.normalize().multiply(speed * 0.75);
                bot.setVelocity(strafe.setY(bot.getVelocity().getY()));
            } else if ((accum += BACKUP_CHANCE) > 0 && roll < accum) {
                moveToward(bot, toPlayer.clone().multiply(-1), speed * 0.6);
            } else if ((accum += CIRCLE_CHANCE) > 0 && roll < accum) {
                Vector circle = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
                if (rng.nextBoolean()) circle.multiply(-1);
                circle.normalize().multiply(speed * 0.6);
                circle.add(toPlayer.clone().multiply(speed * 0.12));
                bot.setVelocity(circle.setY(bot.getVelocity().getY()));
            } else {
                // Micro-jitter at preferred range
                Vector jitter = new Vector(
                        (rng.nextDouble() - 0.5) * 0.22,
                        0,
                        (rng.nextDouble() - 0.5) * 0.22
                );
                bot.setVelocity(jitter.setY(bot.getVelocity().getY()));
            }
        }
    }

    /** Air combo escape: move away from player + prioritize landing. */
    private void moveAirCombo(Zombie bot, Player target, Vector toPlayer, double speed) {
        // Move away from player — escape the juggle
        Vector away = toPlayer.clone().multiply(-1);
        // Add lateral component to make it harder to track
        Vector lateral = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
        if (rng.nextBoolean()) lateral.multiply(-1);
        away.add(lateral.normalize().multiply(0.5));
        away.normalize().multiply(speed * 1.3);
        // Never add upward velocity in air combo — let gravity bring bot down
        bot.setVelocity(new Vector(away.getX(), Math.min(bot.getVelocity().getY(), -0.1), away.getZ()));

        // On landing, immediately switch to RESET (no burst — that would re-launch)
        // The RESET transition is handled in the main tick loop
    }

    /** Wall pin escape: lateral breakout perpendicular to wall. */
    private void moveWallPin(Zombie bot, Player target, Vector toPlayer,
                             Location bLoc, double speed) {
        // Find free direction — move perpendicular to the player-wall axis
        Vector lateral = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
        if (rng.nextBoolean()) lateral.multiply(-1);

        // Check which lateral direction has more space
        Location checkLeft = bLoc.clone().add(lateral.clone().multiply(2.0));
        Location checkRight = bLoc.clone().add(lateral.clone().multiply(-2.0));
        boolean leftFree = checkLeft.getBlock().getType() == Material.AIR;
        boolean rightFree = checkRight.getBlock().getType() == Material.AIR;

        if (leftFree && !rightFree) {
            // go left
        } else if (rightFree && !leftFree) {
            lateral.multiply(-1); // go right
        } else if (!leftFree && !rightFree) {
            // Boxed in — jump + random direction
            lateral = new Vector(rng.nextDouble() - 0.5, 0, rng.nextDouble() - 0.5).normalize();
            bot.setVelocity(lateral.setY(0.42));
            return;
        }

        lateral.normalize().multiply(speed * 1.5);
        bot.setVelocity(lateral.setY(bot.getVelocity().getY()));
    }

    /** Reset after combo: reposition to maintain distance. */
    private void moveReset(Zombie bot, Player target, Vector toPlayer, double speed) {
        Location bLoc = bot.getLocation();
        Location tLoc = target.getLocation();
        double dist = bLoc.distance(tLoc);

        if (dist < 2.5) {
            // Back up to regain spacing
            moveToward(bot, toPlayer.clone().multiply(-1), speed * 1.2);
        } else if (dist > 4.0) {
            // Re-engage slowly
            moveToward(bot, toPlayer, speed * 0.8);
        } else {
            // Good distance — strafe to reset angle
            Vector strafe = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
            if (rng.nextBoolean()) strafe.multiply(-1);
            strafe.normalize().multiply(speed * 0.6);
            bot.setVelocity(strafe.setY(bot.getVelocity().getY()));
        }
    }

    // ── Sword blocking (simulated for Zombie) ─────────────────────────

    /**
     * Initiates a simulated 1.8.9 sword block. Since Zombie entities don't
     * have a native blocking state, we simulate it via:
     *   - DAMAGE_RESISTANCE III (60% reduction) on top of existing II
     *   - Movement speed cut to BLOCK_SPEED
     *   - Visual: zombie holds sword up via equipment refresh
     */
    private void startBlocking(Zombie bot, BotState state) {
        state.blockTicksRemaining = BLOCK_DURATION_TICKS + rng.nextInt(8);
        state.blockCooldownTicks = BLOCK_COOLDOWN_TICKS;
        state.damageWindowHits = 0;

        // Apply extra resistance during block (stacks with base RESISTANCE II)
        bot.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE,
                state.blockTicksRemaining * AI_TICK_INTERVAL, 2, false, false));

        state.lastBlockEndTime = System.currentTimeMillis();
    }

    /**
     * Call this whenever the bot takes damage to track combo pressure.
     */
    public void recordBotDamage(Player target) {
        BotState state = botStates.get(target.getUniqueId());
        if (state == null) return;

        long now = System.currentTimeMillis();
        if (now - state.damageWindowStart > 1000) {
            state.damageWindowStart = now;
            state.damageWindowHits = 1;
        } else {
            state.damageWindowHits++;
        }
    }

    // ── NMS helpers ───────────────────────────────────────────────────

    /**
     * Resets the zombie's noDamageTicks to 0 via NMS reflection.
     * Without this, the 0.5s vanilla damage immunity blocks most player
     * clicks → only ~10 samples per 30s. With this → 100+ samples.
     */
    private void resetNoDamageTicks(Zombie bot) {
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Class<?> clazz = handle.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField("noDamageTicks");
                    f.setAccessible(true);
                    f.setInt(handle, 0);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Sets the zombie's noDamageTicks to a non-zero value via NMS reflection.
     * Used to provide brief damage immunity — prevents knockback cascading
     * while still allowing dense training data collection.
     */
    private void setNoDamageTicks(Zombie bot, int ticks) {
        try {
            Object handle = bot.getClass().getMethod("getHandle").invoke(bot);
            Class<?> clazz = handle.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField("noDamageTicks");
                    f.setAccessible(true);
                    f.setInt(handle, ticks);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ── Utility ───────────────────────────────────────────────────────

    private Location getSpawnLocation(Player target) {
        Location eye = target.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Location spawn = eye.clone().add(dir.multiply(3.0));
        spawn.setY(Math.floor(target.getLocation().getY()));
        return spawn;
    }

    private void moveToward(Zombie bot, Vector direction, double speed) {
        direction = direction.clone().normalize().multiply(speed);
        bot.setVelocity(direction.setY(bot.getVelocity().getY()));
    }

    private boolean isNearWall(Location loc, double threshold) {
        // Check in 4 cardinal + 4 diagonal directions for solid blocks
        double[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {0.707, 0.707}, {-0.707, 0.707}, {0.707, -0.707}, {-0.707, -0.707}
        };
        for (double[] d : dirs) {
            Location check = loc.clone().add(d[0] * threshold, 0, d[1] * threshold);
            if (check.getBlock().getType().isSolid()) return true;
            // Also check at body height
            Location checkUp = check.clone().add(0, 1, 0);
            if (checkUp.getBlock().getType().isSolid()) return true;
        }
        return false;
    }
}
