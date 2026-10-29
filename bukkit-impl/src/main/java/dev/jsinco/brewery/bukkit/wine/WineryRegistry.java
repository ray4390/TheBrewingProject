package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.configuration.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainerView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Small persistent winery registry embedded in TBP.
 *
 * Records only *explicit* winery/brand registrations, never a player's recipes
 * or brewing history. Formulas are stored as opaque digests, not as ingredients.
 * Signed labels distinguish real winery output from anvil-renamed imitations.
 */
public final class WineryRegistry {
    private static final NamespacedKey WINERY = TheBrewingProject.key("raycraft_winery");
    private static final NamespacedKey BRAND = TheBrewingProject.key("raycraft_brand");
    private static final NamespacedKey SIGNATURE = TheBrewingProject.key("raycraft_brand_signature");
    private static final NamespacedKey RECIPE = BrewAdapterAccess.BREWERY_TAG;
    private static final NamespacedKey SCORE = BrewAdapterAccess.BREWERY_SCORE;
    private static final long INVITE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private final File file;
    private YamlConfiguration state;
    private byte[] key;

    public WineryRegistry(TheBrewingProject plugin) throws IOException {
        file = new File(plugin.getDataFolder(), "wineries.yml");
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Refusing symlink winery database");
        }
        state = YamlConfiguration.loadConfiguration(file);
        String encoded = state.getString("signature-key");
        if (encoded == null) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            key = generated;
            mutate(cfg -> cfg.set("signature-key", Base64.getEncoder().encodeToString(generated)));
        } else {
            try {
                key = Base64.getDecoder().decode(encoded);
                if (key.length != 32) throw new IllegalArgumentException("Unexpected secret length");
            } catch (IllegalArgumentException ex) {
                throw new IOException("Existing winery signature secret is invalid; not overwriting it", ex);
            }
        }
    }

    private synchronized void mutate(Consumer<YamlConfiguration> change) throws IOException {
        YamlConfiguration next = new YamlConfiguration();
        try {
            next.loadFromString(state.saveToString());
        } catch (InvalidConfigurationException ex) {
            throw new IOException("Unable to copy winery registry", ex);
        }
        change.accept(next);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create winery directory");
        }
        File temp = new File(parent, "wineries.yml.pending");
        if (Files.isSymbolicLink(temp.toPath())) throw new IOException("Refusing symlink staging file");
        next.save(temp);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        state = next;
    }

    private synchronized String wineryFor(UUID player) {
        var group = state.getConfigurationSection("wineries");
        if (group == null) return null;
        for (String id : group.getKeys(false)) {
            if (state.getStringList("wineries." + id + ".members").contains(player.toString())) return id;
        }
        return null;
    }

    private synchronized String byName(String name) {
        var section = state.getConfigurationSection("wineries");
        if (section == null) return null;
        for (String id : section.getKeys(false)) {
            if (name.equalsIgnoreCase(state.getString("wineries." + id + ".name", ""))) return id;
        }
        return null;
    }

    private synchronized boolean isOwner(String id, UUID player) {
        return player.toString().equals(state.getString("wineries." + id + ".owner"));
    }

    private static String normalized(String input, int min, int max) {
        String value = input.trim().replaceAll("\\s+", " ");
        if (value.length() < min || value.length() > max || !value.matches("[a-zA-Z0-9 _-]+")) {
            throw new IllegalArgumentException("Use " + min + "–" + max + " ordinary letters, digits, spaces, _ or -");
        }
        return value;
    }

    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static UUID uuid(String raw) {
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public synchronized String create(Player player, String name) throws IOException {
        name = normalized(name, 3, 24);
        if (wineryFor(player.getUniqueId()) != null) return "Leave your current winery first.";
        if (byName(name) != null) return "A winery already uses that name.";
        String id = UUID.randomUUID().toString();
        String path = "wineries." + id;
        String label = name;
        mutate(cfg -> {
            cfg.set(path + ".name", label);
            cfg.set(path + ".color", "blue");
            cfg.set(path + ".owner", player.getUniqueId().toString());
            cfg.set(path + ".members", List.of(player.getUniqueId().toString()));
        });
        return "Created winery " + name + ". Use /winery invite <player> to add members.";
    }

    public synchronized String info(Player player) {
        String id = wineryFor(player.getUniqueId());
        if (id == null) return "You do not belong to a winery. Use /winery create <name>.";
        String root = "wineries." + id;
        String name = state.getString(root + ".name", "Unnamed winery");
        List<String> people = state.getStringList(root + ".members");
        long registered = state.getConfigurationSection(root + ".brands") == null ? 0
                : state.getConfigurationSection(root + ".brands").getKeys(false).size();
        return name + " • " + people.size() + " members • " + registered + " registered wines"
                + (isOwner(id, player.getUniqueId()) ? " • Owner" : " • Member");
    }

    public synchronized String members(Player player) {
        String id = wineryFor(player.getUniqueId());
        if (id == null) return "You are not in a winery.";
        List<String> names = new ArrayList<>();
        for (String raw : state.getStringList("wineries." + id + ".members")) {
            UUID uuid = uuid(raw);
            if (uuid == null) continue;
            OfflinePlayer other = Bukkit.getOfflinePlayer(uuid);
            String display = other.getName() == null ? uuid.toString().substring(0, 8) : other.getName();
            names.add(display + (isOwner(id, uuid) ? " (owner)" : ""));
        }
        return "Members: " + String.join(", ", names);
    }

    public synchronized String invite(Player player, Player invited) throws IOException {
        String id = wineryFor(player.getUniqueId());
        if (id == null || !isOwner(id, player.getUniqueId())) return "Only your winery owner can invite.";
        if (wineryFor(invited.getUniqueId()) != null) return "That player already belongs to a winery.";
        String path = "wineries." + id + ".invites." + invited.getUniqueId();
        mutate(cfg -> cfg.set(path, System.currentTimeMillis() + INVITE_MILLIS));
        return "Invited " + invited.getName() + " to " + state.getString("wineries." + id + ".name")
                + ". They can use /winery accept.";
    }

    public synchronized String accept(Player player) throws IOException {
        if (wineryFor(player.getUniqueId()) != null) return "You already belong to a winery.";
        var section = state.getConfigurationSection("wineries");
        if (section == null) return "You have no pending invitation.";
        String invitedId = null;
        for (String id : section.getKeys(false)) {
            if (state.getLong("wineries." + id + ".invites." + player.getUniqueId(), 0) > System.currentTimeMillis()) {
                invitedId = id; break;
            }
        }
        if (invitedId == null) return "You have no pending invitation.";
        String id = invitedId;
        String root = "wineries." + id;
        mutate(cfg -> {
            List<String> members = new ArrayList<>(cfg.getStringList(root + ".members"));
            if (!members.contains(player.getUniqueId().toString())) members.add(player.getUniqueId().toString());
            cfg.set(root + ".members", members);
            cfg.set(root + ".invites." + player.getUniqueId(), null);
        });
        return "Joined " + state.getString(root + ".name") + ".";
    }

    public synchronized String remove(Player actor, UUID target) throws IOException {
        String id = wineryFor(actor.getUniqueId());
        if (id == null) return "You do not belong to a winery.";
        boolean self = actor.getUniqueId().equals(target);
        if (!self && !isOwner(id, actor.getUniqueId())) return "Only the winery owner can kick members.";
        if (isOwner(id, target)) return "An owner must transfer ownership before leaving.";
        String path = "wineries." + id + ".members";
        List<String> current = state.getStringList(path);
        if (!current.contains(target.toString())) return "Player is not a member of your winery.";
        mutate(cfg -> {
            List<String> newMembers = new ArrayList<>(cfg.getStringList(path));
            newMembers.remove(target.toString());
            cfg.set(path, newMembers);
        });
        return self ? "Left the winery." : "Removed member from your winery.";
    }

    public synchronized String transfer(Player actor, UUID target) throws IOException {
        String id = wineryFor(actor.getUniqueId());
        if (id == null || !isOwner(id, actor.getUniqueId())) return "Only the winery owner can transfer ownership.";
        if (!state.getStringList("wineries." + id + ".members").contains(target.toString())) {
            return "New owner must already be a winery member.";
        }
        mutate(cfg -> cfg.set("wineries." + id + ".owner", target.toString()));
        return "Winery ownership transferred.";
    }

    public synchronized String color(Player actor, String value) throws IOException {
        String id = wineryFor(actor.getUniqueId());
        if (id == null || !isOwner(id, actor.getUniqueId())) return "Only the winery owner can change its color.";
        String color = value.toLowerCase(Locale.ROOT);
        if (!List.of("blue", "red", "green", "gold", "gray").contains(color)) {
            return "Colors: blue, red, green, gold, gray.";
        }
        mutate(cfg -> cfg.set("wineries." + id + ".color", color));
        return "Winery color set to " + color + ".";
    }

    public synchronized String register(Player actor, String proposed, ItemStack held) throws IOException {
        String id = wineryFor(actor.getUniqueId());
        if (id == null || !isOwner(id, actor.getUniqueId())) return "Only a winery owner can register a signature wine.";
        String name = normalized(proposed, 3, 30);
        String code = slug(name);
        String path = "wineries." + id + ".brands." + code;
        if (state.contains(path)) return "That signature wine already exists.";
        String formula = fingerprint(held).orElse(null);
        if (formula == null) return "Hold an unsealed, properly aged good-quality TBP wine.";
        String itemCode = codeFor(id, code);
        if (codeIsUsed(itemCode)) return "Signature code collision; choose a different name.";
        mutate(cfg -> {
            cfg.set(path + ".name", name);
            cfg.set(path + ".formula", formula);
            cfg.set(path + ".shopcode", itemCode);
        });
        return "Registered " + name + ". Use /winery bottle " + name + " on matching wine.";
    }

    public synchronized String bottle(Player actor, String proposed, ItemStack held) {
        String id = wineryFor(actor.getUniqueId());
        if (id == null) return "You are not part of a winery.";
        String slug = slug(normalized(proposed, 3, 30));
        String root = "wineries." + id;
        String path = root + ".brands." + slug;
        String formula = state.getString(path + ".formula");
        if (formula == null) return "This winery has no registered wine by that name.";
        String actual = fingerprint(held).orElse(null);
        if (!formula.equals(actual)) return "The held wine does not match the registered formula and quality requirement.";
        if (held.getPersistentDataContainer().has(WINERY)) return "That bottle is already branded.";
        double score = held.getPersistentDataContainer().getOrDefault(SCORE, PersistentDataType.DOUBLE, 0.0D);
        String signature = signature(id, slug, formula, score);
        held.editPersistentDataContainer(pdc -> {
            pdc.set(WINERY, PersistentDataType.STRING, id);
            pdc.set(BRAND, PersistentDataType.STRING, slug);
            pdc.set(SIGNATURE, PersistentDataType.STRING, signature);
        });
        String winery = state.getString(root + ".name", "Winery");
        String wine = state.getString(path + ".name", proposed);
        NamedTextColor color = switch (state.getString(root + ".color", "blue")) {
            case "red" -> NamedTextColor.RED;
            case "green" -> NamedTextColor.GREEN;
            case "gold" -> NamedTextColor.GOLD;
            case "gray" -> NamedTextColor.GRAY;
            default -> NamedTextColor.BLUE;
        };
        held.editMeta(meta -> meta.displayName(Component.text(wine, color)
                .append(Component.text(" • " + winery, NamedTextColor.GRAY))));
        return "Branded this bottle as " + winery + " — " + wine + ".";
    }

    public synchronized String shopCode(Player actor, String proposed) {
        String id = wineryFor(actor.getUniqueId());
        if (id == null) return "You are not part of a winery.";
        String code = slug(normalized(proposed, 3, 30));
        String found = state.getString("wineries." + id + ".brands." + code + ".shopcode");
        return found == null ? "Unknown registered wine." : "ChestShop sign item code: " + found;
    }

    public synchronized Optional<String> verifiedShopCode(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return Optional.empty();
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        String id = pdc.get(WINERY, PersistentDataType.STRING);
        String brand = pdc.get(BRAND, PersistentDataType.STRING);
        String signed = pdc.get(SIGNATURE, PersistentDataType.STRING);
        Double score = pdc.get(SCORE, PersistentDataType.DOUBLE);
        String tag = pdc.get(RECIPE, PersistentDataType.STRING);
        if (id == null || brand == null || signed == null || score == null
                || (!"wine".equals(tag) && !"brewery:wine".equals(tag))) return Optional.empty();
        String root = "wineries." + id + ".brands." + brand;
        String formula = state.getString(root + ".formula");
        String code = state.getString(root + ".shopcode");
        if (formula == null || code == null || score < 0.6) return Optional.empty();
        String expected = signature(id, brand, formula, score);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signed.getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        return Optional.of(code);
    }

    public synchronized boolean isShopCode(String code) {
        return code != null && code.matches("W_[0-9A-Fa-f]{8}") && codeIsUsed(code);
    }

    private synchronized boolean codeIsUsed(String code) {
        var group = state.getConfigurationSection("wineries");
        if (group == null) return false;
        for (String id : group.getKeys(false)) {
            var brands = state.getConfigurationSection("wineries." + id + ".brands");
            if (brands == null) continue;
            for (String slug : brands.getKeys(false)) {
                if (code.equalsIgnoreCase(state.getString("wineries." + id + ".brands." + slug + ".shopcode"))) return true;
            }
        }
        return false;
    }

    private static String codeFor(String winery, String brand) {
        CRC32 crc = new CRC32();
        crc.update((winery + ":" + brand).getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "W_%08X", crc.getValue());
    }

    private String signature(String winery, String brand, String formula, double score) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] bytes = mac.doFinal((winery + "|" + brand + "|" + formula + "|"
                    + Double.doubleToLongBits(score)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Winery signature failed", ex);
        }
    }

    /**
     * Hash only explicitly brewed, correctly aged, unsealed, good+ wine.
     * Never exposes a recipe journal; brewing steps remain TBP-encrypted on items.
     */
    private static Optional<String> fingerprint(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return Optional.empty();
        var pdc = item.getPersistentDataContainer();
        String tag = pdc.get(RECIPE, PersistentDataType.STRING);
        double score = pdc.getOrDefault(SCORE, PersistentDataType.DOUBLE, 0D);
        if ((!"wine".equals(tag) && !"brewery:wine".equals(tag)) || score < 0.6) return Optional.empty();
        Brew brew = BrewAdapterAccess.fromItem(item).orElse(null);
        if (brew == null || brew.getCompletedSteps().size() != 2
                || !(brew.getCompletedSteps().getFirst() instanceof BrewingStep.Cook cook)
                || !(brew.getCompletedSteps().get(1) instanceof BrewingStep.Age age)) return Optional.empty();
        Map<String, Integer> ingredients = new TreeMap<>();
        for (Map.Entry<? extends Ingredient, Integer> entry : cook.ingredients().entrySet()) {
            if (!(entry.getKey().toBaseIngredient() instanceof SimpleIngredient simple)) return Optional.empty();
            ingredients.merge(simple.material().name(), entry.getValue(), Integer::sum);
        }
        if (dev.jsinco.brewery.wine.WineBalance.plan(ingredients).isEmpty()) return Optional.empty();
        long cookSecondsBucket = Math.round(cook.time().moment() * 60D /
                Config.config().cauldrons().cookingMinuteTicks() / 10D);
        long ageYearsBucket = Math.round(age.time().moment() /
                (double) Config.config().barrels().agingYearTicks());
        String material = ingredients + "|" + cookSecondsBucket + "|" + ageYearsBucket
                + "|" + age.barrelType().key();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Optional.of(HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash winery formula", ex);
        }
    }
}
