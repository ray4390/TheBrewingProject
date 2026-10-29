package dev.jsinco.brewery.bukkit.integration.chest_shop;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.ChestShopIntegration;
import dev.jsinco.brewery.bukkit.wine.WineryRegistry;

import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Events.ItemParseEvent;
import com.Acrobot.ChestShop.Events.ItemStringQueryEvent;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Utils.uBlock;
import com.Acrobot.Breeze.Utils.ImplementationAdapter;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import dev.jsinco.brewery.util.ClassUtil;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Native optional ChestShop 3.13 adapter for TBP wine brands and legacy recipe-wide shops.
 *
 * ChestShop still handles pricing, Vault, authorization, capacity checks, and the
 * actual item transfer. Every stock entry is an untouched clone of a real brew.
 * Absolutely no potion-item rewriting, generic metadata bypass, or direct economy calls.
 */
public final class ChestShopNativeIntegration implements ChestShopIntegration, Listener {
    private static final NamespacedKey BREW_TAG = new NamespacedKey("brewery", "tag");
    private static final NamespacedKey BREW_SCORE = new NamespacedKey("brewery", "score");
    private static final NamespacedKey BREW_NAME = new NamespacedKey("brewery", "display_name");
    private static final NamespacedKey BREW_VERSION = new NamespacedKey("brewery", "version");
    private static final NamespacedKey BREW_STEPS = new NamespacedKey("brewery", "steps");
    private static final NamespacedKey DESCRIPTOR = new NamespacedKey("raybrewshops", "descriptor");

    private final Map<String, String> codeToRecipe = new HashMap<>();
    private final Map<String, String> recipeToCode = new HashMap<>();

    private boolean legacyPresent;

    @Override
    public String getId() { return "chestshop_native"; }

    @Override
    public boolean isEnabled() { return ClassUtil.exists("com.Acrobot.ChestShop.Events.PreTransactionEvent"); }

    @Override
    public void onEnable() {
        // TBP starts before RayBrewShops (which declares a hard TBP dependency).
        // Detect loaded legacy integration even if it has not enabled yet.
        legacyPresent = Bukkit.getPluginManager().getPlugin("RayBrewShops") != null;
        Plugin self = TheBrewingProject.getInstance();
        Plugin chestShop = Bukkit.getPluginManager().getPlugin("ChestShop");
        Plugin brewery = Bukkit.getPluginManager().getPlugin("TheBrewingProject");
        if (chestShop == null || !chestShop.isEnabled() || brewery == null || !brewery.isEnabled()) {
            self.getLogger().severe("Both ChestShop and TheBrewingProject must be enabled.");
            // Fail closed: do not register native event handlers on invalid state.
            return;
        }

        File recipesFile = new File(brewery.getDataFolder(), "recipes.yml");
        if (!recipesFile.isFile()) {
            self.getLogger().severe("No active TheBrewingProject recipes.yml: refusing unvalidated item codes.");
            // Fail closed: do not register native event handlers on invalid state.
            return;
        }
        ConfigurationSection recipes = YamlConfiguration.loadConfiguration(recipesFile).getConfigurationSection("recipes");
        if (recipes == null || recipes.getKeys(false).isEmpty()) {
            self.getLogger().severe("Could not identify active brew recipes.");
            // Fail closed: do not register native event handlers on invalid state.
            return;
        }
        Set<String> ids = new TreeSet<>(recipes.getKeys(false));
        for (String id : ids) {
            if (!id.matches("[a-z0-9_\\-]{1,64}")) {
                self.getLogger().warning("Skipping unsupported recipe id: " + id);
                continue;
            }
            String code = codeFor(id);
            // ChestShop 3.13 validates the fourth sign line independently:
            // colons or overly long codes would silently invalidate the shop.
            if (code.length() > 15 || Arrays.stream(ChestShopSign.SHOP_SIGN_PATTERN[2])
                    .noneMatch(pattern -> pattern.matcher(code).matches())) {
                self.getLogger().severe("Invalid ChestShop sign code for recipe " + id + ": " + code);
                // Fail closed: do not register native event handlers on invalid state.
                return;
            }
            String previous = codeToRecipe.putIfAbsent(code.toLowerCase(Locale.ROOT), id);
            if (previous != null && !previous.equals(id)) {
                self.getLogger().severe("Recipe code collision: " + id + " / " + previous + "; refusing to start.");
                // Fail closed: do not register native event handlers on invalid state.
                return;
            }
            recipeToCode.put(id, code);
        }
        Bukkit.getPluginManager().registerEvents(this, TheBrewingProject.getInstance());
        self.getLogger().info("Recipe-based ChestShops enabled for " + recipeToCode.size()
                + " active brews. Original bottles and scores are preserved.");
    }

    // Namespaced recipe identifiers are only read from authentic TBP-tagged bottles.
    // Brewing Project uses the 'brewery' namespace even though its plugin name differs.
    private String recipeOf(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String raw = pdc.get(BREW_TAG, PersistentDataType.STRING);
        if (raw == null) return null;
        String id = raw.startsWith("brewery:") ? raw.substring(8) : raw;
        if (!recipeToCode.containsKey(id)) return null;

        // Synthetic descriptors exist only inside ChestShop's parsing pipeline.
        if (pdc.has(DESCRIPTOR, PersistentDataType.BYTE)) return id;

        // Finished, real drinks have recipe identity, evaluated score and name.
        // Brewing steps/version are optional for a TBP-sealed bottle.
        if (!pdc.has(BREW_SCORE, PersistentDataType.DOUBLE)
                || !pdc.has(BREW_NAME, PersistentDataType.STRING)) return null;
        if (pdc.has(BREW_STEPS) != pdc.has(BREW_VERSION)) return null;
        return id;
    }

    private ItemStack descriptor(String id) {
        ItemStack stack = new ItemStack(Material.POTION);
        stack.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(BREW_TAG, PersistentDataType.STRING, id);
            pdc.set(DESCRIPTOR, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private static String codeFor(String id) {
        if (id.length() <= 10) return "B_" + id;
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "B_%08X", crc.getValue());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStringQuery(ItemStringQueryEvent event) {
        WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
        if (registry != null) {
            var branded = registry.verifiedShopCode(event.getItem());
            if (branded.isPresent()) {
                event.setItemString(branded.get()); // Registered brand takes precedence over the generic B_wine code.
                return;
            }
        }
        if (legacyPresent || event.getItemString() != null) return;
        String id = recipeOf(event.getItem());
        if (id != null) event.setItemString(recipeToCode.get(id));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemParse(ItemParseEvent event) {
        if (event.hasItem()) return;
        WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
        if (registry != null && registry.isShopCode(event.getItemString().trim())) {
            event.setItem(new ItemStack(Material.POTION));
            return;
        }
        if (legacyPresent) return;
        String id = codeToRecipe.get(event.getItemString().trim().toLowerCase(Locale.ROOT));
        if (id != null) event.setItem(descriptor(id));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreTransaction(PreTransactionEvent event) {
        String itemLine = ChestShopSign.getItem(event.getSign());
        String id = codeToRecipe.get(itemLine.trim().toLowerCase(Locale.ROOT));
        WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
        String brandCode = registry != null && registry.isShopCode(itemLine.trim())
                ? itemLine.trim().toUpperCase(Locale.ROOT) : null;
        if (brandCode == null && (id == null || legacyPresent)) return;
        // Never permit recipe-code shops to act as creative/unlimited admin shops.
        if (ChestShopSign.isAdminShop(event.getSign())) {
            event.setCancelled(PreTransactionEvent.TransactionOutcome.INVALID_SHOP);
            return;
        }
        Inventory source = event.getTransactionType() ==
                com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY
                ? event.getOwnerInventory() : event.getClientInventory();
        if (source == null) {
            event.setCancelled(PreTransactionEvent.TransactionOutcome.INVALID_SHOP);
            return;
        }

        int requested = Arrays.stream(event.getStock())
                .filter(stack -> stack != null)
                .mapToInt(ItemStack::getAmount).sum();
        if (requested <= 0 || requested > Properties.MAX_SHOP_AMOUNT) {
            event.setCancelled(PreTransactionEvent.TransactionOutcome.INVALID_SHOP);
            return;
        }

        List<ItemStack> selected = new ArrayList<>();
        int remaining = requested;
        for (ItemStack item : source.getContents()) {
            if (remaining == 0) break;
            if (brandCode != null) {
                if (!brandCode.equals(registry.verifiedShopCode(item).orElse(null))) continue;
            } else if (!id.equals(recipeOf(item))
                    || (registry != null && registry.verifiedShopCode(item).isPresent())) continue;
            ItemStack exactBottle = item.clone();
            int count = Math.min(remaining, exactBottle.getAmount());
            exactBottle.setAmount(count);
            selected.add(exactBottle);
            remaining -= count;
        }
        int found = requested - remaining;
        if (found == 0 || (remaining > 0 && !Properties.ALLOW_PARTIAL_TRANSACTIONS)) {
            event.setCancelled(event.getTransactionType() ==
                    com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY
                    ? PreTransactionEvent.TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST
                    : PreTransactionEvent.TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY);
            return;
        }
        // Partial trades are opt-in via ChestShop's existing setting. Never
        // give an entire priced batch away just because one variant remains.
        if (remaining > 0) {
            BigDecimal scaled = event.getExactPrice().multiply(BigDecimal.valueOf(found))
                    .divide(BigDecimal.valueOf(requested), Properties.PRICE_PRECISION, RoundingMode.HALF_UP);
            if (event.getExactPrice().signum() > 0 && scaled.signum() <= 0) {
                event.setCancelled(PreTransactionEvent.TransactionOutcome.INVALID_SHOP);
                return;
            }
            event.setExactPrice(scaled);
        }
        event.setStock(selected.toArray(new ItemStack[0]));
    }

    // StockCounterModule compares complete ItemStack metadata against our
    // recipe-only descriptor, so we must count verified bottles by recipe.
    private void updateStock(Inventory inventory) {
        if (!Properties.USE_STOCK_COUNTER || inventory == null || inventory.getLocation() == null) return;
        InventoryHolder holder = ImplementationAdapter.getHolder(inventory, false);
        if (!uBlock.couldBeShopContainer(holder)) return;
        for (Sign sign : uBlock.findConnectedShopSigns(holder)) {
            if (ChestShopSign.isAdminShop(sign)) continue;
            String raw = ChestShopSign.getItem(sign).trim();
            String id = codeToRecipe.get(raw.toLowerCase(Locale.ROOT));
            WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
            String brandCode = registry != null && registry.isShopCode(raw)
                    ? raw.toUpperCase(Locale.ROOT) : null;
            if (brandCode == null && (id == null || legacyPresent)) continue;
            int quantity;
            try { quantity = ChestShopSign.getQuantity(sign); }
            catch (IllegalArgumentException ignored) { continue; }
            int count = 0;
            for (ItemStack item : inventory.getStorageContents())
                if (brandCode != null
                        ? brandCode.equals(registry.verifiedShopCode(item).orElse(null))
                        : id.equals(recipeOf(item)) && (registry == null || registry.verifiedShopCode(item).isEmpty()))
                    count += item.getAmount();
            String line = "Q " + quantity + " : C " + count;
            if (!line.equals(ChestShopSign.getQuantityLine(sign))) {
                sign.setLine(ChestShopSign.QUANTITY_LINE, line);
                sign.update(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        updateStock(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransaction(TransactionEvent event) {
        // ChestShop transfers bottles at HIGH priority; this runs afterward.
        updateStock(event.getOwnerInventory());
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Hold an original TBP bottle in-game and run /brewshop.");
            return true;
        }
        WineryRegistry registry = TheBrewingProject.getInstance().getWineryRegistry();
        if (registry != null) {
            var brandCode = registry.verifiedShopCode(player.getInventory().getItemInMainHand());
            if (brandCode.isPresent()) {
                player.sendMessage(ChatColor.GREEN + "Signature wine code: " + ChatColor.GOLD + brandCode.get());
                return true;
            }
        }
        String id = recipeOf(player.getInventory().getItemInMainHand());
        if (id == null) {
            player.sendMessage(ChatColor.RED + "Hold a finished TheBrewingProject bottle.");
            return true;
        }
        player.sendMessage(ChatColor.GREEN + "ChestShop code: " + ChatColor.GOLD + recipeToCode.get(id));
        player.sendMessage(ChatColor.GRAY + "Use that exact code on the fourth line of a shop sign."
                + " A single shop accepts every rating of " + id + ".");
        return true;
    }
}
