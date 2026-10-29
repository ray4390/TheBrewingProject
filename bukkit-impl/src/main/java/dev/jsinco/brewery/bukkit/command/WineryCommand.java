package dev.jsinco.brewery.bukkit.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.wine.WineryRegistry;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Plain chat commands, no special brewing mode or client-side GUI.
 * Recipes aren't recorded automatically. All winery changes are explicit.
 */
public final class WineryCommand {
    private WineryCommand() {}

    public static void register(ReloadableRegistrarEvent<Commands> commands) {
        commands.registrar().register(Commands.literal("winery")
                .executes(ctx -> run(ctx.getSource(), "info"))
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "command"))))
                .build());
    }

    private static int run(CommandSourceStack source, String arguments) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Use /winery in game.");
            return 0;
        }
        WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
        if (registry == null) {
            player.sendMessage(Component.text("Winemaking is not enabled on this server.", NamedTextColor.RED));
            return 0;
        }
        String[] parts = arguments.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length == 2 ? parts[1].trim() : "";
        try {
            String result = switch (cmd) {
                case "info" -> registry.info(player);
                case "members" -> registry.members(player);
                case "create" -> arg.isBlank() ? "Usage: /winery create <name>" : registry.create(player, arg);
                case "accept" -> registry.accept(player);
                case "invite" -> {
                    Player target = Bukkit.getPlayerExact(arg);
                    yield target == null ? "That player must be online to receive an invitation."
                            : registry.invite(player, target);
                }
                case "leave" -> registry.remove(player, player.getUniqueId());
                case "kick", "transfer" -> {
                    UUID target = findMember(player, registry, arg);
                    if (target == null) yield "That player is not an identifiable winery member.";
                    yield "kick".equals(cmd) ? registry.remove(player, target) : registry.transfer(player, target);
                }
                case "color" -> registry.color(player, arg);
                case "register" -> arg.isBlank() ? "Usage: /winery register <wine name>"
                        : registry.register(player, arg, player.getInventory().getItemInMainHand());
                case "bottle" -> arg.isBlank() ? "Usage: /winery bottle <registered name>"
                        : registry.bottle(player, arg, player.getInventory().getItemInMainHand());
                case "code" -> arg.isBlank() ? "Usage: /winery code <registered name>"
                        : registry.shopCode(player, arg);
                case "help" -> "Commands: create, info, members, invite, accept, kick, leave, transfer, color, register, bottle, code";
                default -> "Unknown subcommand. Use /winery help.";
            };
            player.sendMessage(Component.text(result, NamedTextColor.GRAY));
            return 1;
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return 0;
        } catch (IOException ex) {
            TheBrewingProject.getInstance().getLogger().severe("Failed to persist winery update: " + ex.getMessage());
            player.sendMessage(Component.text("Could not safely save the change; contact staff.", NamedTextColor.RED));
            return 0;
        }
    }

    private static UUID findMember(Player actor, WineryRegistry registry, String name) {
        if (name.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        // Resolve only cached profiles to avoid blocking the main thread on a network lookup.
        for (OfflinePlayer cached : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(cached.getName())) {
                return cached.getUniqueId();
            }
        }
        return null;
    }
}
