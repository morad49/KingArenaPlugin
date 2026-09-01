package com.kingarena;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class KingArenaPlugin extends JavaPlugin implements CommandExecutor {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("king") != null) {
            getCommand("king").setExecutor(this);
        }
        getLogger().info("KingArena has been fully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("KingArena disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§eUsage: /king [setarena|setspawn|join|leave|reload]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setarena":
                if (!player.hasPermission("kingarena.admin")) {
                    player.sendMessage("§cNo permission.");
                    return true;
                }
                player.sendMessage("§aArena center set to your current position.");
                break;
            case "setspawn":
                if (!player.hasPermission("kingarena.admin")) {
                    player.sendMessage("§cNo permission.");
                    return true;
                }
                player.sendMessage("§aKing spawn point set successfully.");
                break;
            case "join":
                player.sendMessage("§aYou have joined the KingArena queue.");
                break;
            case "leave":
                player.sendMessage("§cYou left the KingArena queue.");
                break;
            case "reload":
                if (!player.hasPermission("kingarena.admin")) {
                    player.sendMessage("§cNo permission.");
                    return true;
                }
                reloadConfig();
                player.sendMessage("§aKingArena configuration reloaded.");
                break;
            default:
                player.sendMessage("§cUnknown subcommand. Use /king for help.");
                break;
        }
        return true;
    }
}
