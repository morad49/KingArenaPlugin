package com.kingarena;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class KingArenaPlugin extends JavaPlugin implements Listener {

    public enum ArenaState {
        IDLE,
        WAITING_FOR_CHALLENGER,
        FIGHTING,
        CLEANUP
    }

    private ArenaState currentState = ArenaState.IDLE;
    private final AtomicBoolean isStateTransitioning = new AtomicBoolean(false);

    private UUID currentKing = null;
    private UUID currentChallenger = null;
    private long cooldownEndTime = 0;

    private final Map<Location, String> barrierSnapshot = new HashMap<>();
    private File recoveryFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        recoveryFile = new File(getDataFolder(), "recovery_snapshot.yml");
        
        // Crash Recovery
        restoreSnapshotIfPresent();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("KingArena plugin successfully enabled.");
    }

    @Override
    public void onDisable() {
        if (currentState == ArenaState.FIGHTING) {
            restoreArenaBlocks();
        }
    }

    public ArenaState getCurrentState() {
        return currentState;
    }

    public UUID getCurrentKing() {
        return currentKing;
    }

    // --- State Machine Logic ---
    public synchronized void evaluateArenaState() {
        if (isStateTransitioning.get() || currentState == ArenaState.CLEANUP) return;

        World world = Bukkit.getWorld(getConfig().getString("arena.world", "world"));
        ProtectedRegion region = getArenaRegion();

        if (world == null || region == null) return;

        List<Player> insidePlayers = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(world) && isInsideRegion(p.getLocation(), region)) {
                insidePlayers.add(p);
            }
        }

        if (System.currentTimeMillis() < cooldownEndTime) {
            return;
        }

        if (currentState == ArenaState.IDLE) {
            if (currentKing == null) {
                if (insidePlayers.size() >= 2) {
                    startFight(insidePlayers.get(0), insidePlayers.get(1));
                }
            } else {
                Player kingPlayer = Bukkit.getPlayer(currentKing);
                if (kingPlayer != null && insidePlayers.contains(kingPlayer)) {
                    Player challenger = insidePlayers.stream()
                            .filter(p -> !p.getUniqueId().equals(currentKing))
                            .findFirst().orElse(null);
                    if (challenger != null) {
                        startFight(kingPlayer, challenger);
                    }
                }
            }
        }
    }

    private void startFight(Player p1, Player p2) {
        if (!isStateTransitioning.compareAndSet(false, true)) return;

        try {
            currentState = ArenaState.FIGHTING;
            currentKing = (currentKing != null && currentKing.equals(p1.getUniqueId())) ? p1.getUniqueId() : p1.getUniqueId();
            currentChallenger = p1.getUniqueId().equals(currentKing) ? p2.getUniqueId() : p1.getUniqueId();

            buildGlassBarrier();

            p1.sendMessage(getMessage("messages.fight-start"));
            p2.sendMessage(getMessage("messages.fight-start"));
        } finally {
            isStateTransitioning.set(false);
        }
    }

    public synchronized void endFight(UUID winnerUuid, UUID loserUuid) {
        if (currentState != ArenaState.FIGHTING) return;
        currentState = ArenaState.CLEANUP;

        restoreArenaBlocks();

        if (winnerUuid != null) {
            currentKing = winnerUuid;
            Player winner = Bukkit.getPlayer(winnerUuid);
            if (winner != null) {
                String msg = getMessage("messages.winner").replace("%player%", winner.getName());
                Bukkit.broadcastMessage(msg);
            }
        }

        currentChallenger = null;
        cooldownEndTime = System.currentTimeMillis() + (getConfig().getLong("timers.fight-cooldown-seconds", 60) * 1000);
        currentState = ArenaState.IDLE;

        // Re-evaluate if someone is still inside after cooldown
        Bukkit.getScheduler().runTaskLater(this, this::evaluateArenaState, getConfig().getLong("timers.fight-cooldown-seconds", 60) * 20L);
    }

    // --- Barrier & Recovery Logic ---
    private void buildGlassBarrier() {
        if (!getConfig().getBoolean("barrier.enabled", true)) return;

        World world = Bukkit.getWorld(getConfig().getString("arena.world", "world"));
        ProtectedRegion region = getArenaRegion();
        if (world == null || region == null) return;

        int minX = region.getMinimumPoint().getBlockX();
        int minY = region.getMinimumPoint().getBlockY();
        int minZ = region.getMinimumPoint().getBlockZ();

        int maxX = region.getMaximumPoint().getBlockX();
        int maxY = region.getMaximumPoint().getBlockY();
        int maxZ = region.getMaximumPoint().getBlockZ();

        boolean includeWalls = getConfig().getBoolean("barrier.include-walls", true);
        boolean includeCeiling = getConfig().getBoolean("barrier.include-ceiling", true);
        boolean includeFloor = getConfig().getBoolean("barrier.include-floor", false);
        org.bukkit.Material glassMat = org.bukkit.Material.matchMaterial(getConfig().getString("barrier.material", "GLASS"));
        if (glassMat == null) glassMat = org.bukkit.Material.GLASS;

        barrierSnapshot.clear();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean isWall = includeWalls && (x == minX || x == maxX || z == minZ || z == maxZ);
                    boolean isCeiling = includeCeiling && (y == maxY);
                    boolean isFloor = includeFloor && (y == minY);

                    if (isWall || isCeiling || isFloor) {
                        Location loc = new Location(world, x, y, z);
                        barrierSnapshot.put(loc, loc.getBlock().getBlockData().getAsString());
                        loc.getBlock().setType(glassMat, false);
                    }
                }
            }
        }

        saveSnapshotToDisk();
    }

    private void restoreArenaBlocks() {
        for (Map.Entry<Location, String> entry : barrierSnapshot.entrySet()) {
            Location loc = entry.getKey();
            try {
                loc.getBlock().setBlockData(Bukkit.createBlockData(entry.getValue()), false);
            } catch (Exception ignored) {}
        }
        barrierSnapshot.clear();
        clearSnapshotDisk();
    }

    private void saveSnapshotToDisk() {
        YamlConfiguration config = new YamlConfiguration();
        int idx = 0;
        for (Map.Entry<Location, String> entry : barrierSnapshot.entrySet()) {
            Location l = entry.getKey();
            config.set("blocks." + idx + ".w", l.getWorld().getName());
            config.set("blocks." + idx + ".x", l.getBlockX());
            config.set("blocks." + idx + ".y", l.getBlockY());
            config.set("blocks." + idx + ".z", l.getBlockZ());
            config.set("blocks." + idx + ".d", entry.getValue());
            idx++;
        }
        try {
            config.save(recoveryFile);
        } catch (IOException e) {
            getLogger().severe("Failed to persist barrier recovery snapshot!");
        }
    }

    private void restoreSnapshotIfPresent() {
        if (!recoveryFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(recoveryFile);
        if (config.contains("blocks")) {
            for (String key : config.getConfigurationSection("blocks").getKeys(false)) {
                String wName = config.getString("blocks." + key + ".w");
                int x = config.getInt("blocks." + key + ".x");
                int y = config.getInt("blocks." + key + ".y");
                int z = config.getInt("blocks." + key + ".z");
                String dataStr = config.getString("blocks." + key + ".d");

                World w = Bukkit.getWorld(wName);
                if (w != null) {
                    Location loc = new Location(w, x, y, z);
                    try {
                        loc.getBlock().setBlockData(Bukkit.createBlockData(dataStr), false);
                    } catch (Exception ignored) {}
                }
            }
        }
        clearSnapshotDisk();
    }

    private void clearSnapshotDisk() {
        if (recoveryFile.exists()) {
            recoveryFile.delete();
        }
    }

    // --- Listeners ---
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Block-level checking optimization
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player p = event.getPlayer();
        ProtectedRegion region = getArenaRegion();
        if (region == null) return;

        boolean toInside = isInsideRegion(event.getTo(), region);
        boolean fromInside = isInsideRegion(event.getFrom(), region);

        // Escape Protection
        if (currentState == ArenaState.FIGHTING && fromInside && !toInside) {
            if (p.getUniqueId().equals(currentKing) || p.getUniqueId().equals(currentChallenger)) {
                if (getConfig().getBoolean("behavior.prevent-teleport-escape", true)) {
                    event.setCancelled(true);
                    p.sendMessage(getMessage("messages.escape"));
                    return;
                }
            }
        }

        // Access Control
        if (!fromInside && toInside) {
            if (currentState == ArenaState.FIGHTING) {
                if (!p.getUniqueId().equals(currentKing) && !p.getUniqueId().equals(currentChallenger)) {
                    event.setCancelled(true);
                    p.sendMessage(getMessage("messages.arena-occupied"));
                    return;
                }
            } else if (currentKing != null && getConfig().getBoolean("behavior.reject-extra-players", true)) {
                if (!p.getUniqueId().equals(currentKing) && currentState != ArenaState.WAITING_FOR_CHALLENGER) {
                    event.setCancelled(true);
                    p.sendMessage(getMessage("messages.not-allowed"));
                    return;
                }
            }

            // State evaluation trigger on region entry
            Bukkit.getScheduler().runTaskLater(this, this::evaluateArenaState, 1L);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (currentState != ArenaState.FIGHTING) return;

        Player victim = event.getEntity();
        if (victim.getUniqueId().equals(currentKing)) {
            endFight(currentChallenger, currentKing);
        } else if (victim.getUniqueId().equals(currentChallenger)) {
            endFight(currentKing, currentChallenger);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (currentState != ArenaState.FIGHTING) return;

        Player p = event.getPlayer();
        if (p.getUniqueId().equals(currentKing)) {
            endFight(currentChallenger, currentKing);
        } else if (p.getUniqueId().equals(currentChallenger)) {
            endFight(currentKing, currentChallenger);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (currentState != ArenaState.FIGHTING) return;

        Player p = event.getPlayer();
        if (p.getUniqueId().equals(currentKing) || p.getUniqueId().equals(currentChallenger)) {
            ProtectedRegion region = getArenaRegion();
            if (region != null && isInsideRegion(event.getFrom(), region) && !isInsideRegion(event.getTo(), region)) {
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && getConfig().getBoolean("behavior.prevent-pearl-escape", true)) {
                    event.setCancelled(true);
                    p.sendMessage(getMessage("messages.escape"));
                } else if (getConfig().getBoolean("behavior.prevent-teleport-escape", true)) {
                    event.setCancelled(true);
                    p.sendMessage(getMessage("messages.escape"));
                }
            }
        }
    }

    // --- Helpers ---
    private ProtectedRegion getArenaRegion() {
        World world = Bukkit.getWorld(getConfig().getString("arena.world", "world"));
        if (world == null) return null;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager rm = container.get(BukkitAdapter.adapt(world));
        if (rm == null) return null;
        return rm.getRegion(getConfig().getString("arena.region", "king_arena"));
    }

    private boolean isInsideRegion(Location loc, ProtectedRegion region) {
        return region.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private String getMessage(String path) {
        String msg = getConfig().getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
}
