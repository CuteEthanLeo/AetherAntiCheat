package com.aether.anticheat.manager;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.check.combat.*;
import com.aether.anticheat.check.player.NoSlowCheck;
import com.aether.anticheat.data.PlayerData;
import com.aether.anticheat.prediction.KillAuraAIModel;
import com.aether.anticheat.util.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all cheat checks, player data, and debug state.
 */
public class CheckManager {

    private final AetherAntiCheat plugin;
    private final List<Check> checks = new ArrayList<>();
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    private int tickTaskId = -1;
    private boolean debugMode;
    private boolean alertsEnabled = true;
    private KillAuraAIModel killAuraAIModel;

    public CheckManager(AetherAntiCheat plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("debug", false);
        this.alertsEnabled = plugin.getConfig().getBoolean("alerts.enabled", true);
        registerChecks();
        startTickTask();
    }

    // ── Check registration ──────────────────────────────────────────────────

    private void registerChecks() {
        // ── Combat only ────────────────────────────────────────────────
        checks.add(new KillAuraCheck(plugin));
        checks.add(new KillAuraAICheck(plugin));
        checks.add(new ReachCheck(plugin));
        checks.add(new AntiKBCheck(plugin));
        checks.add(new AutoBlockCheckA(plugin));
        checks.add(new AutoBlockCheckB(plugin));
        checks.add(new AutoBlockCheckC(plugin));
        checks.add(new NoSlowCheck(plugin));

        // Load enabled state from config
        for (Check check : checks) {
            boolean enabled = plugin.getConfig().getBoolean(
                    "checks." + check.getName().toLowerCase() + ".enabled", true);
            check.setEnabled(enabled);
        }

        plugin.getLogger().info("Registered " + checks.size() + " checks (combat only).");

        // ── Initialize KillAuraAI Model ────────────────────────────
        killAuraAIModel = new KillAuraAIModel(plugin.getLogger());

        for (Check check : checks) {
            if (check instanceof KillAuraAICheck) {
                ((KillAuraAICheck) check).setAiModel(killAuraAIModel);
                break;
            }
        }

        killAuraAIModel.setCnnThreshold((float) plugin.getConfig().getDouble("checks.killauraai.cnn.threshold", 0.55));
        killAuraAIModel.setMinSeqForInference(plugin.getConfig().getInt("checks.killauraai.cnn.min-sequence-for-inference", 32));

        // ★ Always train statistical model from CSVs (cheap, always works)
        loadStatisticalModel();

        // ★ Then try CNN — if it loads, it takes priority for scoring
        java.io.File weightsFile = new java.io.File(plugin.getDataFolder(), "cnn_model_weights.json");
        java.io.File statsFile = new java.io.File(plugin.getDataFolder(), "cnn_model_stats.json");

        if (weightsFile.exists() && statsFile.exists()) {
            boolean cnnOk = killAuraAIModel.loadCNNModel(weightsFile, statsFile);
            if (cnnOk) {
                killAuraAIModel.setUseCNN(true);
                plugin.getLogger().info("[KillAuraAI] CNN ACTIVE (" + killAuraAIModel.getCnnEngine().getTotalParams() + " params). Statistical model as fallback.");
            } else {
                plugin.getLogger().warning("[KillAuraAI] CNN file broken, using statistical model only.");
            }
        } else {
            plugin.getLogger().info("[KillAuraAI] No CNN weights — using statistical model. Run python training/cnn_train.py for CNN.");
        }
    }

    /** Load and train the statistical model from CSV data (fallback). */
    private void loadStatisticalModel() {
        java.io.File trainingDir = new java.io.File(plugin.getDataFolder(), "training");
        int filesLoaded = killAuraAIModel.loadAndTrain(trainingDir);
        if (killAuraAIModel.isTrained()) {
            plugin.getLogger().info("[KillAuraAI] Statistical model loaded from " + filesLoaded
                    + " CSV files. Live detection ACTIVE.");
        } else {
            plugin.getLogger().info("[KillAuraAI] No trained model available. "
                    + "Use /aac kaai record + /aac kaai train for statistical, or python training/cnn_train.py for CNN.");
        }
    }

    // ── Tick task ────────────────────────────────────────────────────────────

    private void startTickTask() {
        // Per-tick: update PlayerData
        tickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = playerDataMap.get(player.getUniqueId());
                if (data != null) data.tick();
            }
        }, 0L, 1L);

        // Global VL decay: every 30s, reduce all violations by 1
        // Prevents false-positive accumulation over long play sessions
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = playerDataMap.get(player.getUniqueId());
                if (data == null) continue;
                for (Check check : checks) {
                    if (!check.isEnabled()) continue;
                    check.decayViolation(data, com.aether.anticheat.check.Check.VL_DECAY_AMOUNT);
                }
            }
        }, 600L, com.aether.anticheat.check.Check.VL_DECAY_INTERVAL_TICKS);
    }

    // ── PlayerData ───────────────────────────────────────────────────────────

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), u -> new PlayerData(player));
    }

    public void removePlayerData(UUID uuid) {
        playerDataMap.remove(uuid);
    }

    public Map<UUID, PlayerData> getAllPlayerData() {
        return Collections.unmodifiableMap(playerDataMap);
    }

    // ── Movement dispatch ────────────────────────────────────────────────────

    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = getPlayerData(player);

        data.updateMovement(event.getTo());
        data.setOnGround(player.isOnGround());
        data.setFlying(player.isFlying());
        data.setSneaking(player.isSneaking());
        data.setSprinting(player.isSprinting());
        // 客户端地面：用于 SpeedCheckA 预测（参考实现使用客户端地面）
        data.setClientGround(player.isOnGround());
        // 推断真实疾跑状态：修复 Bukkit API 在疾跑切换同 tick 滞后的问题
        data.inferSprintingState();
        data.updateBlockState(player);

        if (data.isTeleportExempt()) return;
        if (player.hasPermission("aether.bypass")) return;

        runEnabledChecks(player, data);
    }

    // ── Combat dispatch ──────────────────────────────────────────────────────

    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player attacker = (Player) event.getDamager();
        LivingEntity victim = (LivingEntity) event.getEntity();
        PlayerData attackerData = getPlayerData(attacker);

        // ★ Save old values BEFORE recordAttack overwrites them
        long oldLastAttackTime = attackerData.getLastAttackTime();
        float oldYaw = attackerData.getLastAttackYaw();
        float oldPitch = attackerData.getLastAttackPitch();

        attackerData.recordAttack(victim.getUniqueId());

        // Record damage on VICTIM for AntiKB tracking (Player victims only)
        if (victim instanceof Player) {
            PlayerData victimData = getPlayerData((Player) victim);
            victimData.recordDamage(event.getFinalDamage(), true);
        }

        // Record bot damage for anti-combo state tracking
        if (victim instanceof org.bukkit.entity.Zombie) {
            // Check all active bots — record damage to the player who owns this bot
            for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (plugin.getBotManager().hasBot(onlinePlayer)) {
                    // The bot belongs to this player — record the damage for combo tracking
                    plugin.getBotManager().recordBotDamage(onlinePlayer);
                }
            }
        }

        // Feed aimbot rotation model
        float[] targetRot = getRotationToTarget(attacker, victim);
        float currentYaw = attacker.getLocation().getYaw();
        float currentPitch = attacker.getLocation().getPitch();

        // ★ feedAimSample MUST run before recordAttackRotation:
        //    feedAimSample reads lastAttackYaw (from previous attack/move) to compute
        //    the genuine inter-attack rotation delta. recordAttackRotation overwrites
        //    lastAttackYaw to currentYaw — calling it first caused ALL rotation deltas
        //    to be zero, killing angAccel/jerk features and causing permanent 0% suspicion.
        attackerData.feedAimSample(currentYaw, currentPitch, targetRot[0], targetRot[1]);
        attackerData.recordAttackRotation(currentYaw, currentPitch);

        if (!attacker.hasPermission("aether.bypass")) {
            for (Check check : checks) {
                if (!check.isEnabled()) continue;
                try {
                    if (check instanceof ReachCheck) {
                        // ReachCheck currently supports Player victims only
                        if (victim instanceof Player) {
                            ((ReachCheck) check).checkReach(attacker, (Player) victim, attackerData);
                        }
                    } else if (check instanceof KillAuraCheck) {
                        ((KillAuraCheck) check).checkAngle(attacker, victim, attackerData);
                        check.runCheck(attacker, attackerData);
                    } else if (check instanceof KillAuraAICheck) {
                        // ★ Use pre-computed deltas directly (avoids zero-delta bug)
                        float dYaw = (float) MathUtil.distBetweenAngles360(currentYaw, oldYaw);
                        float dPitch = Math.abs(currentPitch - oldPitch);
                        ((KillAuraAICheck) check).checkOnAttack(attacker, attackerData, dYaw, dPitch,
                                victim, targetRot[0], targetRot[1]);

                        // Recording: log feature vector with full 20 features
                        boolean flaggedByKA = attackerData.getViolation("KillAura") > 0;
                        ((KillAuraAICheck) check).recordAttackFeatures(
                                attacker, victim, attackerData, flaggedByKA,
                                oldYaw, oldPitch, currentYaw, currentPitch,
                                oldLastAttackTime,
                                targetRot[0], targetRot[1]);
                    } else if (check instanceof AutoBlockCheckA) {
                        // Run on victim — check if victim auto-blocked the incoming hit
                        if (victim instanceof Player) {
                            check.runCheck((Player) victim, getPlayerData((Player) victim));
                        }
                    } else if (check instanceof AutoBlockCheckB) {
                        // Run on victim — check toggle patterns
                        if (victim instanceof Player) {
                            check.runCheck((Player) victim, getPlayerData((Player) victim));
                        }
                    } else if (check instanceof AutoBlockCheckC) {
                        // Run on attacker — check block+attack coexistence
                        check.runCheck(attacker, attackerData);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in " + check.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    // ── Block place dispatch ─────────────────────────────────────────────────

    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        PlayerData data = getPlayerData(player);
        data.recordBlockPlace();

        if (player.hasPermission("aether.bypass")) return;

        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                // ★ Scaffold: run deep placement analysis (ray-trace, rotation, telly)
                if (check instanceof com.aether.anticheat.check.player.ScaffoldCheck) {
                    ((com.aether.anticheat.check.player.ScaffoldCheck) check)
                            .analyzePlacement(player, data, event.getBlock(), event.getBlockAgainst().getFace(event.getBlock()));
                }
                check.runCheck(player, data);
            } catch (Exception e) {
                plugin.getLogger().warning("Error in " + check.getName() + ": " + e.getMessage());
            }
        }
    }

    // ── Swing dispatch ───────────────────────────────────────────────────────

    public void onPlayerSwing(Player player) {
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) data.recordSwing();
    }

    // ── Velocity / Teleport ──────────────────────────────────────────────────

    public void onPlayerVelocity(PlayerVelocityEvent event) {
        PlayerData data = getPlayerData(event.getPlayer());
        data.setVelocity(
                event.getVelocity().getX(),
                event.getVelocity().getY(),
                event.getVelocity().getZ());
    }

    public void onPlayerTeleport(PlayerTeleportEvent event) {
        getPlayerData(event.getPlayer()).setTeleportExempt(3);
    }

    // ── Damage ────────────────────────────────────────────────────────────

    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        PlayerData data = getPlayerData(player);

        // Track damage with knockback for AntiKB (if damage by entity)
        boolean hasKB = event instanceof EntityDamageByEntityEvent;

        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                if (check instanceof AntiKBCheck && hasKB) {
                    // Record that this player (victim) took damage with KB
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in damage dispatch: " + e.getMessage());
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void runEnabledChecks(Player player, PlayerData data) {
        for (Check check : checks) {
            if (!check.isEnabled()) continue;
            try {
                check.runCheck(player, data);
            } catch (Exception e) {
                plugin.getLogger().warning("Error in " + check.getName()
                        + " on " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    // ── Debug / Alerts ───────────────────────────────────────────────────────

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean v) { this.debugMode = v; }
    public boolean isAlertsEnabled() { return alertsEnabled; }
    public void setAlertsEnabled(boolean v) { this.alertsEnabled = v; }

    // ── Query ────────────────────────────────────────────────────────────────

    public List<Check> getChecks() { return Collections.unmodifiableList(checks); }

    public KillAuraAIModel getKillAuraAIModel() { return killAuraAIModel; }

    public Check getCheck(String name) {
        for (Check c : checks) {
            if (c.getName().equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    // ── Math helpers ─────────────────────────────────────────────────────

    /** Compute the exact [yaw, pitch] needed to face a target entity. */
    public static float[] getRotationToTarget(Player from, LivingEntity to) {
        double dx = to.getLocation().getX() - from.getLocation().getX();
        double dy = (to.getLocation().getY() + 1.0) - (from.getLocation().getY() + 1.62);
        double dz = to.getLocation().getZ() - from.getLocation().getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));
        return new float[]{targetYaw, targetPitch};
    }

    // ── Shutdown ─────────────────────────────────────────────────────────────

    public void shutdown() {
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        checks.clear();
        playerDataMap.clear();
    }
}
