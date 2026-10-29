package dev.jsinco.brewery.bukkit.command;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.wine.WineryRegistry;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/** Original /brewshop UX, now provided natively inside TBP when the legacy adapter is retired. */
public final class WineShopCommand {
    private WineShopCommand() {}

    public static void register(ReloadableRegistrarEvent<Commands> commands) {
        // Do not fight the pre-existing RayBrewShops /brewshop command during transition.
        if (Bukkit.getPluginManager().getPlugin("RayBrewShops") != null) return;
        commands.registrar().register(Commands.literal("brewshop").executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) return 0;
            ItemStack item = player.getInventory().getItemInMainHand();
            WineryRegistry wineries = TheBrewingProject.getInstance().getWineryRegistry();
            String brandCode = wineries == null ? null : wineries.verifiedShopCode(item).orElse(null);
            if (brandCode != null) {
                player.sendMessage(Component.text("ChestShop signature wine code: " + brandCode, NamedTextColor.GREEN));
                return 1;
            }
            if (item == null || item.getType() != Material.POTION || !BrewAdapterAccess.isBrew(item)) {
                player.sendMessage(Component.text("Hold a finished TBP bottle.", NamedTextColor.RED));
                return 0;
            }
            if (item.getPersistentDataContainer().has(TheBrewingProject.key("raycraft_winery"))) {
                player.sendMessage(Component.text("This bottle has an invalid or unverified winery signature.", NamedTextColor.RED));
                return 0;
            }
            var pdc = item.getPersistentDataContainer();
            String id = pdc.get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING);
            if (id == null || !pdc.has(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE)
                    || !pdc.has(BrewAdapterAccess.BREWERY_DISPLAY_NAME, PersistentDataType.STRING)) {
                player.sendMessage(Component.text("This is not a complete authenticated TBP drink.", NamedTextColor.RED));
                return 0;
            }
            id = id.startsWith("brewery:") ? id.substring(8) : id;
            if (TheBrewingProject.getInstance().getRecipeRegistry().getRecipe(id).isEmpty()) {
                player.sendMessage(Component.text("Unknown recipe in this brew.", NamedTextColor.RED));
                return 0;
            }
            String code;
            if (id.length() <= 10) {
                code = "B_" + id;
            } else {
                CRC32 crc = new CRC32();
                crc.update(id.getBytes(StandardCharsets.UTF_8));
                code = String.format(Locale.ROOT, "B_%08X", crc.getValue());
            }
            player.sendMessage(Component.text("ChestShop recipe code: " + code
                    + " (all qualities of this drink).", NamedTextColor.GREEN));
            return 1;
        }).build());
    }
}
