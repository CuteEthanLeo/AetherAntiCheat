package com.aether.anticheat;

import com.aether.anticheat.bot.TrainingBotManager;
import com.aether.anticheat.command.AetherCommand;
import com.aether.anticheat.listener.PlayerListener;
import com.aether.anticheat.manager.CheckManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AetherAntiCheat — Advanced cheat detection for Paper 1.8.9.
 */
public class AetherAntiCheat extends JavaPlugin {

    private static AetherAntiCheat instance;
    private CheckManager checkManager;
    private TrainingBotManager botManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        getLogger().info("╔══════════════════════════════════════╗");
        getLogger().info("║     AetherAntiCheat v" + getDescription().getVersion() + "              ║");
        getLogger().info("║  Advanced AntiCheat for 1.8.9 PvP   ║");
        getLogger().info("╚══════════════════════════════════════╝");

        checkManager = new CheckManager(this);
        botManager = new TrainingBotManager(this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register commands (pass botManager to AetherCommand)
        AetherCommand cmd = new AetherCommand(this);
        getCommand("aether").setExecutor(cmd);
        getCommand("aac").setExecutor(cmd);

        getLogger().info("Loaded " + checkManager.getChecks().size() + " checks. Ready.");
    }

    @Override
    public void onDisable() {
        if (botManager != null) botManager.removeAllBots();
        if (checkManager != null) checkManager.shutdown();
        getLogger().info("AetherAntiCheat disabled.");
        instance = null;
    }

    public static AetherAntiCheat getInstance() { return instance; }
    public CheckManager getCheckManager() { return checkManager; }
    public TrainingBotManager getBotManager() { return botManager; }
}
