package com.kingarena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class KingArenaPlugin extends JavaPlugin implements CommandExecutor, Listener {

    public enum ArenaState { WAITING, FIGHTING, COOLDOWN }

    private ArenaState currentState = ArenaState.WAITING;
    private Player currentKing = null;
    private Player challenger = null;
    private final Map<Location, BlockData> savedBlocks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("king") != null) {
            getCommand("king").setExecutor(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("KingArena has been fully loaded with physical glass barrier mechanics!");
    }

    @Override
    public void onDisable() {
        restoreArena();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§eKingArena Commands: /king [join|leave|setarena|setspawn|reload]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "join":
                handleJoin(player);
                break;
            case "leave":
                handleLeave(player);
                break;
            case "reload":
                if (player.hasPermission("kingarena.admin")) {
                    reloadConfig();
                    player.sendMessage("§aKingArena config reloaded.");
                }
                break;
            default:
                player.sendMessage("§cUnknown subcommand.");
                break;
        }
        return true;
    }

    private void handleJoin(Player player) {
        if (currentState == ArenaState.FIGHTING) {
            player.sendMessage("§cThe arena is currently occupied by a fight!");
            return;
        }
        if (currentKing == null) {
            currentKing = player;
            player.sendMessage("§aYou are now the reigning King waiting for a challenger!");
        } else if (challenger == null && !player.equals(currentKing)) {
            challenger = player;
            startFight();
        }
    }

    private void handleLeave(Player player) {
        if (player.equals(challenger)) {
            challenger = null;
            player.sendMessage("§cYou left the challenger queue.");
        }
    }

    private void startFight() {
        currentState = ArenaState.FIGHTING;
        buildGlassBarrier();
        if (currentKing != null) currentKing.sendMessage("§6The fight has started!");
        if (challenger != null) challenger.sendMessage("§6The fight has started!");
    }

    private void endFight(Player winner) {
        currentState = ArenaState.COOLDOWN;
        restoreArena();
        currentKing = winner;
        challenger = null;
        Bukkit.broadcastMessage("§e" + winner.getName() + " §6is now The King!");

        Bukkit.getScheduler().runTaskLater(this, () -> currentState = ArenaState.WAITING, 20L * getConfig().getInt("timers.fight-cooldown-seconds", 10));
    }

    private void buildGlassBarrier() {
        Location loc = currentKing != null ? currentKing.getLocation() : null;
        if (loc == null) return;

        int radius = 5;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = 0; y <= 4; y++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius || y == 4) {
                        Block b = loc.clone().add(x, y, z).getBlock();
                        if (b.getType() == Material.AIR) {
                            savedBlocks.put(b.getLocation(), b.getBlockData());
                            b.setType(Material.GLASS);
                        }
                    }
                }
            }
        }
    }

    private void restoreArena() {
        for (Map.Entry<Location, BlockData> entry : savedBlocks.entrySet()) {
            entry.getKey().getBlock().setBlockData(entry.getValue());
        }
        savedBlocks.clear();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (currentState != ArenaState.FIGHTING) return;
        Player dead = e.getEntity();
        if (dead.equals(currentKing) && challenger != null) {
            endFight(challenger);
        } else if (dead.equals(challenger) && currentKing != null) {
            endFight(currentKing);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (currentState == ArenaState.FIGHTING) {
            if (e.getPlayer().equals(currentKing) && challenger != null) {
                endFight(challenger);
            } else if (e.getPlayer().equals(challenger) && currentKing != null) {
                endFight(currentKing);
            }
        }
    }
}
