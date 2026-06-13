package com.aether.anticheat.listener;

import com.aether.anticheat.AetherAntiCheat;
import com.aether.anticheat.data.PlayerData;
import com.aether.anticheat.manager.CheckManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;

/**
 * Main event listener — all Bukkit events routed through here to CheckManager.
 */
public class PlayerListener implements Listener {

    private final AetherAntiCheat plugin;
    private final CheckManager checkManager;

    public PlayerListener(AetherAntiCheat plugin) {
        this.plugin = plugin;
        this.checkManager = plugin.getCheckManager();
    }

    // ── Connection ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        checkManager.getPlayerData(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        checkManager.removePlayerData(event.getPlayer().getUniqueId());
    }

    // ── Movement ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        // AutoBlock: detect block release (no dedicated Bukkit event for this)
        Player player = event.getPlayer();
        PlayerData data = checkManager.getPlayerData(player);
        if (data.isBlocking() && !player.isBlocking()) {
            data.recordBlockToggle(false);
        }

        checkManager.onPlayerMove(event);
    }

    // ── Toggles ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        checkManager.getPlayerData(e.getPlayer()).setFlying(e.isFlying());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent e) {
        checkManager.getPlayerData(e.getPlayer()).setSneaking(e.isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent e) {
        checkManager.getPlayerData(e.getPlayer()).setSprinting(e.isSprinting());
    }

    // ── Teleport ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        checkManager.onPlayerTeleport(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        checkManager.getPlayerData(e.getPlayer()).setTeleportExempt(5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        checkManager.getPlayerData(e.getPlayer()).setTeleportExempt(5);
    }

    // ── Velocity ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        checkManager.onPlayerVelocity(e);
    }

    // ── Combat ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof org.bukkit.entity.LivingEntity)) return;

        // AutoBlock(C): detect block + attack coexistence in same tick
        Player attacker = (Player) e.getDamager();
        PlayerData attackerData = checkManager.getPlayerData(attacker);
        if (attackerData.isBlocking()) {
            attackerData.setLastBlockWhileAttackTime(System.currentTimeMillis());
            attackerData.setBlockAttackSameTickCount(attackerData.getBlockAttackSameTickCount() + 1);
        }

        // Record incoming damage on victim for AutoBlock(A) reaction-time check
        if (e.getEntity() instanceof Player) {
            Player victim = (Player) e.getEntity();
            checkManager.getPlayerData(victim).recordIncomingDamage(System.currentTimeMillis());
        }

        checkManager.onPlayerAttack(e);
    }

    // ── Swing ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        // PlayerAnimationEvent fires when player swings arm
        checkManager.onPlayerSwing(e.getPlayer());
    }

    // ── Block Place ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        checkManager.onBlockPlace(e);
    }

    // ── Item Use (NoSlow + AutoBlock) ──────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        PlayerData data = checkManager.getPlayerData(player);

        if (e.getAction() == Action.RIGHT_CLICK_AIR
                || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Material hand = e.getPlayer().getItemInHand().getType();

            // Consumable tracking (NoSlow)
            if (isConsumable(hand)) {
                data.setUsingItem(true);
            }

            // AutoBlock: right-click with sword → start blocking
            if (isSword(hand) && player.isBlocking()) {
                if (!data.isBlocking()) {
                    data.recordBlockToggle(true);
                }
            }
        } else if (e.getAction() == Action.LEFT_CLICK_AIR
                || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Release bow / cancel usage
            data.setUsingItem(false);

            // If player left-clicks while blocking → unblock
            if (data.isBlocking()) {
                data.recordBlockToggle(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        // Player finished eating — no longer using item
        checkManager.getPlayerData(e.getPlayer()).setUsingItem(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeldChange(PlayerItemHeldEvent e) {
        PlayerData data = checkManager.getPlayerData(e.getPlayer());

        // ★ NoSlow fix: Don't immediately clear using/blocking state!
        // Cheat clients abuse slot-switching to reset the server-side
        // "using item" flag while still eating/blocking client-side.
        // Instead, record the switch event and let NoSlowCheck decide.
        data.setTickSinceSlotSwitch(0);
        data.setWasUsingBeforeSwitch(data.isUsingItem() || data.isBlocking());
        data.incrementSlotSwitchCount();

        // Only clear for legitimate non-consumable slot switches
        // (handled in NoSlowCheck after a safe decay window)
        if (!data.wasUsingBeforeSwitch()) {
            data.setUsingItem(false);
            if (data.isBlocking()) {
                data.recordBlockToggle(false);
            }
        }
        // If was using before switch, keep the state flagged for NoSlowCheck
        // — it will decay naturally after tickSinceSlotSwitch > 5
    }

    // ── Damage (NoFall) ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        checkManager.onPlayerDamage(e);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isConsumable(Material m) {
        return m == Material.BOW || m == Material.POTION
                || m == Material.MILK_BUCKET || m == Material.MUSHROOM_SOUP
                || m == Material.GOLDEN_APPLE || m == Material.GOLDEN_CARROT
                || m == Material.APPLE || m == Material.BREAD
                || m == Material.COOKED_BEEF || m == Material.COOKED_CHICKEN
                || m == Material.COOKED_FISH || m == Material.COOKED_MUTTON
                || m == Material.COOKED_RABBIT || m == Material.COOKIE
                || m == Material.PUMPKIN_PIE || m == Material.ROTTEN_FLESH
                || m == Material.SPIDER_EYE || m == Material.MELON
                || m == Material.CARROT_ITEM || m == Material.BAKED_POTATO
                || m == Material.POISONOUS_POTATO || m == Material.RAW_BEEF
                || m == Material.RAW_CHICKEN || m == Material.RAW_FISH
                || m == Material.PORK || m == Material.MUTTON
                || m == Material.RABBIT || m == Material.GRILLED_PORK;
    }

    /** Returns true if the material is a 1.8.9 sword (can block). */
    private static boolean isSword(Material m) {
        return m == Material.WOOD_SWORD || m == Material.STONE_SWORD
                || m == Material.IRON_SWORD || m == Material.GOLD_SWORD
                || m == Material.DIAMOND_SWORD;
    }

    // ── BadPackets pre-checks ───────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveEarly(PlayerMoveEvent e) {
        // Early pitch/yaw validation prevents some packet exploits
        if (Math.abs(e.getTo().getPitch()) > 90.1f) {
            e.setCancelled(true);
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning(e.getPlayer().getName()
                        + " sent invalid pitch: " + e.getTo().getPitch());
            }
        }
    }
}
