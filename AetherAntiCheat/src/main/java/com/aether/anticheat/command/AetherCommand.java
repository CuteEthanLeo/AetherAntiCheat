package com.aether.anticheat.command;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.check.Check;
import com.aether.anticheat.data.PlayerData;
import com.aether.anticheat.manager.CheckManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /aac and /aether command — debug, manage, and inspect the anti-cheat.
 */
public class AetherCommand implements CommandExecutor, TabCompleter {

    private final AetherAntiCheat plugin;

    public AetherCommand(AetherAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "alerts":
                return cmdAlerts(sender);

            case "debug":
                return cmdDebug(sender);

            case "info":
                return cmdInfo(sender, args);

            case "vl":
            case "violations":
                return cmdVL(sender, args);

            case "check":
                return cmdCheck(sender, args);

            case "reset":
                return cmdReset(sender, args);

            case "kaai":
                return cmdKaai(sender, args);

            case "reload":
                return cmdReload(sender);

            case "help":
            default:
                sendHelp(sender, label);
                return true;
        }
    }

    // ── Sub-commands ─────────────────────────────────────────────────────

    private boolean cmdAlerts(CommandSender sender) {
        if (!sender.hasPermission("aether.alerts")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        CheckManager mgr = plugin.getCheckManager();
        boolean newState = !mgr.isAlertsEnabled();
        mgr.setAlertsEnabled(newState);
        sender.sendMessage("§8[§4[Aether]§8] §7Alerts: " + (newState ? "§aON" : "§cOFF"));
        return true;
    }

    private boolean cmdDebug(CommandSender sender) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        CheckManager mgr = plugin.getCheckManager();
        boolean newState = !mgr.isDebugMode();
        mgr.setDebugMode(newState);
        plugin.getConfig().set("debug", newState);
        plugin.saveConfig();
        sender.sendMessage("§8[§4[Aether]§8] §7Debug: " + (newState ? "§aON" : "§cOFF"));
        return true;
    }

    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /" + "aac info <player>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not online: " + args[1]);
            return true;
        }
        PlayerData data = plugin.getCheckManager().getPlayerData(target);

        sender.sendMessage("§8§m----§r §4§l[Aether] §c" + target.getName() + " §8§m----");
        sender.sendMessage(" §7onGround: " + tf(data.isOnGround())
                + " §7flying: " + tf(data.isFlying())
                + " §7sneaking: " + tf(data.isSneaking()));
        sender.sendMessage(" §7deltaY: §f" + fmt(data.getDeltaY())
                + " §7lastDY: §f" + fmt(data.getLastDeltaY())
                + " §7hDist: §f" + fmt(data.getHorizontalDistance()));
        sender.sendMessage(" §7teleportExempt: §e" + data.getTeleportExemptTicks()
                + " §7velocityTicks: §e" + data.getVelocityTicksRemaining());
        sender.sendMessage(" §7vbX: §f" + fmt(data.getVelocityBufferX())
                + " §7vbY: §f" + fmt(data.getVelocityBufferY())
                + " §7vbZ: §f" + fmt(data.getVelocityBufferZ()));
        sender.sendMessage(" §7webTicks: " + (data.isRecentlyInWeb() ? "§a" : "§7") + "recent"
                + " §7liquidTicks: " + (data.isRecentlyInLiquid() ? "§a" : "§7") + "recent"
                + " §7climbTicks: " + (data.isRecentlyOnClimbable() ? "§a" : "§7") + "recent");
        sender.sendMessage(" §7multiAura: §c" + data.getMultiAuraCount()
                + " §7consistentAtks: §c" + data.getAttackConsistencyCount());
        sender.sendMessage(" §7timerBalance: §e" + data.getTimerBalance() + "ms"
                + " §7timerBuf: §e" + fmt(data.getTimerViolationBuffer()));
        sender.sendMessage(" §7blocks/sec: §e" + data.getSlidingPlaceCount()
                + " §7scaffoldBuf: §c" + fmt(data.getScaffoldBuffer()));

        // Violations
        Map<String, Integer> vls = data.getAllViolations();
        if (!vls.isEmpty()) {
            StringBuilder sb = new StringBuilder(" §7VLs: ");
            vls.forEach((k, v) -> sb.append("§c").append(k).append("=").append(v).append(" §7"));
            sender.sendMessage(sb.toString().trim());
        } else {
            sender.sendMessage(" §7VLs: §anone");
        }
        return true;
    }

    private boolean cmdVL(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            // Show all online players' VL summary
            sender.sendMessage("§8§m----§r §4§l[Aether] Violations §8§m----");
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData data = plugin.getCheckManager().getPlayerData(p);
                Map<String, Integer> vls = data.getAllViolations();
                if (!vls.isEmpty()) {
                    String vlStr = vls.entrySet().stream()
                            .filter(e -> e.getValue() > 0)
                            .map(e -> "§c" + e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(" §7"));
                    sender.sendMessage(" §7" + p.getName() + ": " + vlStr);
                }
            }
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not online: " + args[1]);
            return true;
        }
        PlayerData data = plugin.getCheckManager().getPlayerData(target);
        Map<String, Integer> vls = data.getAllViolations();
        sender.sendMessage("§8§m----§r §4§lVL: " + target.getName() + " §8§m----");
        if (vls.isEmpty()) {
            sender.sendMessage(" §aNo violations.");
        } else {
            vls.forEach((k, v) -> sender.sendMessage(" §c" + k + "§7: §f" + v));
        }
        return true;
    }

    private boolean cmdCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /aac check <list|toggle> [check]");
            return true;
        }

        if (args[1].equalsIgnoreCase("list")) {
            List<Check> checks = plugin.getCheckManager().getChecks();
            sender.sendMessage("§8§m----§r §4§l[Aether] Checks (" + checks.size() + ") §8§m----");
            for (Check c : checks) {
                String status = c.isEnabled() ? "§a✔" : "§c✘";
                sender.sendMessage(" " + status + " §7[" + c.getType().getDisplayName() + "] §f" + c.getName()
                        + " §8(VL:" + c.getMaxViolations() + ")");
            }
            return true;
        }

        if (args[1].equalsIgnoreCase("toggle") && args.length >= 3) {
            Check check = plugin.getCheckManager().getCheck(args[2]);
            if (check == null) {
                sender.sendMessage("§cUnknown check: " + args[2]);
                sender.sendMessage("§7Use §f/aac check list §7to see available checks.");
                return true;
            }
            check.setEnabled(!check.isEnabled());
            String status = check.isEnabled() ? "§aENABLED" : "§cDISABLED";
            sender.sendMessage("§8[§4[Aether]§8] §f" + check.getName() + " §7→ " + status);

            // Persist to config
            plugin.getConfig().set("checks." + check.getName().toLowerCase() + ".enabled", check.isEnabled());
            plugin.saveConfig();
            return true;
        }

        sender.sendMessage("§cUsage: /aac check <list|toggle> [check]");
        return true;
    }

    private boolean cmdReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /aac reset <player|all>");
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            plugin.getCheckManager().getAllPlayerData().values()
                    .forEach(PlayerData::resetAllViolations);
            sender.sendMessage("§8[§4[Aether]§8] §7Reset all player violations.");
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not online: " + args[1]);
            return true;
        }
        plugin.getCheckManager().getPlayerData(target).resetAllViolations();
        sender.sendMessage("§8[§4[Aether]§8] §7Reset violations for §f" + target.getName());
        return true;
    }

    private boolean cmdReload(CommandSender sender) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        plugin.reloadConfig();
        CheckManager mgr = plugin.getCheckManager();
        // Re-sync enabled state from config
        for (Check c : mgr.getChecks()) {
            boolean enabled = plugin.getConfig().getBoolean(
                    "checks." + c.getName().toLowerCase() + ".enabled", true);
            c.setEnabled(enabled);
        }
        mgr.setDebugMode(plugin.getConfig().getBoolean("debug", false));
        mgr.setAlertsEnabled(plugin.getConfig().getBoolean("alerts.enabled", true));
        sender.sendMessage("§8[§4[Aether]§8] §aConfig reloaded.");
        return true;
    }

    // ── KAAI Sub-commands ──────────────────────────────────────────────────

    private boolean cmdKaai(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.kaai")) {
            sender.sendMessage("§cNo permission. (aether.kaai)");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /aac kaai <record|stop|status|export|train|model|cnn-status|reload-cnn> [args...]");
            sender.sendMessage("§7  /aac kaai record cheat [player]");
            sender.sendMessage("§7  /aac kaai record normal [player]");
            sender.sendMessage("§7  /aac kaai stop [player]");
            sender.sendMessage("§7  /aac kaai status");
            sender.sendMessage("§7  /aac kaai export");
            sender.sendMessage("§7  /aac kaai train  §8— §7Build statistical model from CSV data");
            sender.sendMessage("§7  /aac kaai model  §8— §7View model statistics");
            sender.sendMessage("§7  /aac kaai cnn-status  §8— §7Show CNN model status");
            sender.sendMessage("§7  /aac kaai reload-cnn  §8— §7Hot-reload CNN weights from JSON");
            return true;
        }

        switch (args[1].toLowerCase()) {
            case "record":
                return cmdKaaiRecord(sender, args);
            case "stop":
                return cmdKaaiStop(sender, args);
            case "status":
                return cmdKaaiStatus(sender);
            case "export":
                return cmdKaaiExport(sender);
            case "bot":
                return cmdKaaiBot(sender, args);
            case "debug":
                return cmdKaaiDebug(sender, args);
            case "train":
                return cmdKaaiTrain(sender);
            case "model":
                return cmdKaaiModel(sender);
            case "cnn-status":
                return cmdKaaiCnnStatus(sender);
            case "reload-cnn":
                return cmdKaaiReloadCnn(sender);
            default:
                sender.sendMessage("§cUnknown kaai subcommand: " + args[1]);
                sender.sendMessage("§7Available: record, stop, status, export, bot, train, model, cnn-status, reload-cnn");
                return true;
        }
    }

    private boolean cmdKaaiRecord(CommandSender sender, String[] args) {
        // /aac kaai record <cheat|normal> [player]
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /aac kaai record <cheat|normal> [player]");
            return true;
        }
        String label = args[2].toLowerCase();
        if (!label.equals("cheat") && !label.equals("normal")) {
            sender.sendMessage("§cLabel must be 'cheat' or 'normal'. Got: " + args[2]);
            return true;
        }

        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayer(args[3]);
            if (target == null) {
                sender.sendMessage("§cPlayer not online: " + args[3]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("§cConsole must specify a player: /aac kaai record " + label + " <player>");
            return true;
        }

        PlayerData data = plugin.getCheckManager().getPlayerData(target);
        data.setRecording(true);
        data.setRecordingLabel(label);
        data.setRecordingStartTime(System.currentTimeMillis());
        data.setRecordingPlayerName(target.getName());
        data.getRecordingBuffer().clear(); // fresh session

        String displayLabel = label.equals("cheat") ? "§cCHEAT" : "§aNORMAL";
        sender.sendMessage("§8[§4[Aether]§8] §7Started recording §f" + target.getName()
                + " §7as " + displayLabel);

        // ★ Auto-spawn training bot in front of the player
        plugin.getBotManager().spawnBot(target);

        sender.sendMessage("§7Use §f/aac kaai stop " + target.getName() + " §7to end recording & remove bot.");
        return true;
    }

    private boolean cmdKaaiStop(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§cPlayer not online: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("§cConsole must specify a player: /aac kaai stop <player>");
            return true;
        }

        PlayerData data = plugin.getCheckManager().getPlayerData(target);
        if (!data.isRecording()) {
            sender.sendMessage("§7" + target.getName() + " is not currently being recorded.");
            return true;
        }

        int samples = data.getRecordingBuffer().size();
        long duration = System.currentTimeMillis() - data.getRecordingStartTime();
        String label = data.getRecordingLabel();

        data.setRecording(false);

        // ★ Auto-remove training bot
        plugin.getBotManager().removeBot(target);

        // Auto-export on stop
        String filePath = exportPlayerData(target.getName(), label, data);

        sender.sendMessage("§8[§4[Aether]§8] §7Stopped recording §f" + target.getName());
        sender.sendMessage(" §7Label: " + (label.equals("cheat") ? "§cCHEAT" : "§aNORMAL"));
        sender.sendMessage(" §7Samples: §f" + samples);
        sender.sendMessage(" §7Duration: §f" + (duration / 1000) + "s");
        if (filePath != null) {
            sender.sendMessage(" §7Saved to: §f" + filePath);
        }
        return true;
    }

    private boolean cmdKaaiStatus(CommandSender sender) {
        sender.sendMessage("§8§m----§r §4§lKillAuraAI Recording Status §8§m----");
        boolean anyRecording = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.getCheckManager().getPlayerData(p);
            if (data.isRecording()) {
                anyRecording = true;
                String label = data.getRecordingLabel();
                String displayLabel = label.equals("cheat") ? "§cCHEAT" : "§aNORMAL";
                long elapsed = (System.currentTimeMillis() - data.getRecordingStartTime()) / 1000;
                int samples = data.getRecordingBuffer().size();
                sender.sendMessage(" §7" + p.getName() + " → " + displayLabel
                        + " §7| §f" + samples + " §7samples | §f" + elapsed + "s");
            }
        }
        if (!anyRecording) {
            sender.sendMessage(" §7No active recordings.");
            sender.sendMessage(" §7Start one with §f/aac kaai record <cheat|normal> [player]");
        }
        return true;
    }

    private boolean cmdKaaiExport(CommandSender sender) {
        sender.sendMessage("§8[§4[Aether]§8] §7Exporting all recording buffers...");
        int totalFiles = 0;
        int totalSamples = 0;

        // Export all currently recording players (don't stop them)
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.getCheckManager().getPlayerData(p);
            if (data.isRecording() && !data.getRecordingBuffer().isEmpty()) {
                String filePath = exportPlayerData(p.getName(), data.getRecordingLabel(), data);
                if (filePath != null) {
                    totalFiles++;
                    totalSamples += data.getRecordingBuffer().size();
                    sender.sendMessage(" §a✔ §7" + p.getName() + " → " + filePath);
                }
            }
        }

        // Also check offline/flushed data (players who stopped recording)
        if (totalFiles == 0) {
            sender.sendMessage(" §7No buffered recording data to export.");
            sender.sendMessage(" §7Data is auto-exported when you run §f/aac kaai stop");
        } else {
            sender.sendMessage("§8[§4[Aether]§8] §aExported §f" + totalFiles
                    + " §afiles (§f" + totalSamples + " §asamples total)");
        }
        return true;
    }

    private boolean cmdKaaiDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aether.kaai")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§cPlayer not online: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("§cUsage: /aac kaai debug <player>");
            return true;
        }

        com.aether.anticheat.check.combat.KillAuraAICheck kaai =
                (com.aether.anticheat.check.combat.KillAuraAICheck)
                        plugin.getCheckManager().getCheck("KillAuraAI");
        if (kaai == null) {
            sender.sendMessage("§cKillAuraAI check not found.");
            return true;
        }

        boolean current = kaai.isDebugPlayer(target.getUniqueId());
        kaai.setDebugPlayer(target.getUniqueId(), !current);
        sender.sendMessage("§8[§4[Aether]§8] §7Debug for §f" + target.getName()
                + " §7→ " + (!current ? "§aON" : "§cOFF"));
        sender.sendMessage("§7Every attack will log details to console.");
        return true;
    }

    private boolean cmdKaaiBot(CommandSender sender, String[] args) {
        // /aac kaai bot [player] — toggle training bot
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§cPlayer not online: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("§cConsole must specify a player: /aac kaai bot <player>");
            return true;
        }

        if (plugin.getBotManager().hasBot(target)) {
            plugin.getBotManager().removeBot(target);
            sender.sendMessage("§8[§4[Aether]§8] §7Training bot removed for §f" + target.getName());
        } else {
            plugin.getBotManager().spawnBot(target);
            sender.sendMessage("§8[§4[Aether]§8] §aTraining bot spawned for §f" + target.getName());
        }
        return true;
    }

    private boolean cmdKaaiTrain(CommandSender sender) {
        sender.sendMessage("§8[§4[Aether]§8] §7Training statistical model from recorded data...");
        sender.sendMessage("§8[§4[Aether]§8] §e§lTIP: §7For CNN (deep learning), use:");
        sender.sendMessage("§7  §f1. python training/cnn_train.py §7(trains ResNet+Attention CNN)");
        sender.sendMessage("§7  §f2. Copy cnn_model_weights.json + cnn_model_stats.json to plugin folder");
        sender.sendMessage("§7  §f3. /aac kaai reload-cnn §7(loads CNN into live detection)");

        com.aether.anticheat.prediction.KillAuraAIModel model =
                plugin.getCheckManager().getKillAuraAIModel();
        if (model == null) {
            sender.sendMessage("§cModel system not initialized. Reload the plugin.");
            return true;
        }

        java.io.File trainingDir = new java.io.File(plugin.getDataFolder(), "training");
        int filesLoaded = model.loadAndTrain(trainingDir);

        if (model.isTrained()) {
            sender.sendMessage("§8[§4[Aether]§8] §a§lStatistical model trained successfully!");
            sender.sendMessage(" §7Loaded §f" + filesLoaded + " §7CSV files");
            sender.sendMessage(" §7Cheat samples: §c" + model.getCheatSamples());
            sender.sendMessage(" §7Normal samples: §a" + model.getNormalSamples());
            sender.sendMessage(" §7Statistical model is now §aACTIVE");

            com.aether.anticheat.prediction.KillAuraAIModel.FeatureStats[] weights =
                    model.getStats("cheat");
            if (weights != null) {
                sender.sendMessage("§8§m----§r §eFeature Profiles §8§m----");
                for (com.aether.anticheat.prediction.KillAuraAIModel.FeatureStats fs : weights) {
                    sender.sendMessage(" §7" + fs.toString());
                }
            }
        } else {
            sender.sendMessage("§c§lTraining FAILED — insufficient data.");
            sender.sendMessage(" §7Loaded §f" + filesLoaded + " §7files");
            sender.sendMessage(" §7Cheat samples: §c" + model.getCheatSamples() + " §7(need ≥10)");
            sender.sendMessage(" §7Normal samples: §a" + model.getNormalSamples() + " §7(need ≥10)");
            sender.sendMessage(" §7Record more with §f/aac kaai record cheat/normal");
        }
        return true;
    }

    private boolean cmdKaaiModel(CommandSender sender) {
        com.aether.anticheat.prediction.KillAuraAIModel model =
                plugin.getCheckManager().getKillAuraAIModel();
        if (model == null) {
            sender.sendMessage("§cModel system not initialized.");
            return true;
        }

        String summary = model.getSummary();
        for (String line : summary.split("\n")) {
            sender.sendMessage("§7" + line);
        }
        return true;
    }

    private boolean cmdKaaiCnnStatus(CommandSender sender) {
        com.aether.anticheat.prediction.KillAuraAIModel model =
                plugin.getCheckManager().getKillAuraAIModel();
        if (model == null) {
            sender.sendMessage("§cModel system not initialized.");
            return true;
        }

        sender.sendMessage("§8§m----§r §e§lCNN Model Status §8§m----");
        sender.sendMessage(" §7CNN Loaded: " + (model.isCNNLoaded() ? "§aYES" : "§cNO"));
        sender.sendMessage(" §7CNN Active: " + (model.isUsingCNN() ? "§aYES" : "§eNO (fallback to statistical)"));

        if (model.isCNNLoaded()) {
            sender.sendMessage(" §7Parameters: §f" + String.format("%,d", model.getCnnEngine().getTotalParams()));
            sender.sendMessage(" §7Threshold: §f" + String.format("%.2f", model.getCnnThreshold()));
        }

        sender.sendMessage(" §7Statistical Trained: " + (model.isTrained() ? "§aYES" : "§cNO"));
        sender.sendMessage(" §7Samples: §c" + model.getCheatSamples() + " cheat §a" + model.getNormalSamples() + " normal");
        sender.sendMessage("");
        sender.sendMessage("§7To train CNN: §fpython training/cnn_train.py");
        sender.sendMessage("§7To load CNN:  §f/aac kaai reload-cnn");
        sender.sendMessage("§7To switch mode: §fEdit config.yml → killauraai.model.mode = \"cnn\"");

        return true;
    }

    private boolean cmdKaaiReloadCnn(CommandSender sender) {
        if (!sender.hasPermission("aether.admin")) {
            sender.sendMessage("§cNo permission. (aether.admin)");
            return true;
        }

        sender.sendMessage("§8[§4[Aether]§8] §7Loading CNN model...");

        java.io.File dataFolder = plugin.getDataFolder();
        java.io.File weightsFile = new java.io.File(dataFolder, "cnn_model_weights.json");
        java.io.File statsFile = new java.io.File(dataFolder, "cnn_model_stats.json");

        if (!weightsFile.exists()) {
            sender.sendMessage("§cCNN weights file not found: " + weightsFile.getPath());
            sender.sendMessage("§7Run §fpython training/cnn_train.py §7first, then copy output files to:");
            sender.sendMessage("§7  " + dataFolder.getPath());
            return true;
        }
        if (!statsFile.exists()) {
            sender.sendMessage("§cCNN stats file not found: " + statsFile.getPath());
            return true;
        }

        com.aether.anticheat.prediction.KillAuraAIModel model =
                plugin.getCheckManager().getKillAuraAIModel();
        if (model == null) {
            sender.sendMessage("§cModel system not initialized.");
            return true;
        }

        boolean ok = model.loadCNNModel(weightsFile, statsFile);
        if (ok) {
            sender.sendMessage("§8[§4[Aether]§8] §a§lCNN model loaded successfully!");
            sender.sendMessage(" §7Params: §f" + String.format("%,d", model.getCnnEngine().getTotalParams()));
            sender.sendMessage(" §7Threshold: §f" + String.format("%.2f", model.getCnnThreshold()));

            // Auto-enable CNN in config
            plugin.getConfig().set("checks.killauraai.model.mode", "cnn");
            plugin.saveConfig();

            sender.sendMessage(" §7Mode set to §eCNN §7(saved to config)");
            sender.sendMessage(" §7Use §f/aac kaai cnn-status §7to verify");
        } else {
            sender.sendMessage("§cFailed to load CNN model. Check console for errors.");
        }
        return true;
    }

    /**
     * Write recording buffer to a CSV file in the plugin's training/ directory.
     * Returns the relative file path, or null on failure.
     */
    private String exportPlayerData(String playerName, String label, PlayerData data) {
        java.util.List<String> buffer = data.getRecordingBuffer();
        if (buffer.isEmpty()) return null;

        try {
            java.io.File trainingDir = new java.io.File(plugin.getDataFolder(), "training");
            if (!trainingDir.exists()) trainingDir.mkdirs();

            String fileName = label + "_" + playerName + "_" + System.currentTimeMillis() + ".csv";
            java.io.File csvFile = new java.io.File(trainingDir, fileName);

            java.io.FileWriter fw = new java.io.FileWriter(csvFile);
            // CSV header
            fw.write("label,timestamp,deltaYaw,deltaPitch,aimError,gcdResY,gcdResP,angVel,angAccel,jerk,atkIntervalMs,cps,attackerYaw,attackerPitch,targetYaw,targetPitch,yawError,pitchError,distanceToTarget,movementAngle,sprinting,blocking,flaggedByKA\n");
            for (String row : buffer) {
                fw.write(row + "\n");
            }
            fw.close();

            // Clear buffer after successful write
            buffer.clear();

            return "training/" + fileName;
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[KillAuraAI] Failed to export recording data: " + e.getMessage());
            return null;
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§8§m----§r §4§lAetherAntiCheat §8§m----");
        sender.sendMessage(" §c/" + label + " alerts §7— Toggle alert messages");
        sender.sendMessage(" §c/" + label + " debug §7— Toggle debug mode");
        sender.sendMessage(" §c/" + label + " info <player> §7— Player debug info");
        sender.sendMessage(" §c/" + label + " vl [player] §7— View violations");
        sender.sendMessage(" §c/" + label + " check list §7— List all checks");
        sender.sendMessage(" §c/" + label + " check toggle <check> §7— Toggle a check");
        sender.sendMessage(" §c/" + label + " reset <player|all> §7— Reset violations");
        sender.sendMessage(" §c/" + label + " kaai record <cheat|normal> [player] §7— Record aim data");
        sender.sendMessage(" §c/" + label + " kaai stop [player] §7— Stop recording");
        sender.sendMessage(" §c/" + label + " kaai status §7— Recording status");
        sender.sendMessage(" §c/" + label + " kaai export §7— Export recorded data");
        sender.sendMessage(" §c/" + label + " reload §7— Reload config");
    }

    // ── Tab completion ────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                       String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList(
                    "alerts", "debug", "info", "vl", "check", "kaai", "reset", "reload", "help"), args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "info":
                case "vl":
                case "reset":
                    return filter(getOnlineNames(), args[1]);
                case "check":
                    return filter(Arrays.asList("list", "toggle"), args[1]);
                case "kaai":
                    return filter(Arrays.asList("record", "stop", "status", "export", "bot", "debug", "train", "model", "cnn-status", "reload-cnn"), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("kaai")) {
            switch (args[1].toLowerCase()) {
                case "record":
                    return filter(Arrays.asList("cheat", "normal"), args[2]);
                case "stop":
                    return filter(getOnlineNames(), args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("kaai")
                && args[1].equalsIgnoreCase("record")) {
            return filter(getOnlineNames(), args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("check")
                && args[1].equalsIgnoreCase("toggle")) {
            return plugin.getCheckManager().getChecks().stream()
                    .map(Check::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> opts, String prefix) {
        return opts.stream()
                .filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<String> getOnlineNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private String tf(boolean v) { return v ? "§aT" : "§cF"; }
    private String fmt(double v) { return String.format("%.4f", v); }
}
