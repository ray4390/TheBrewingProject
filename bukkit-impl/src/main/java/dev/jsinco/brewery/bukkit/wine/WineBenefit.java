package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Very small additional benefits for genuinely brewed RayCraft wine.
 * Alcohol and toxin modifiers remain with TBP's ordinary consumption logic.
 * Cooldown never prevents drinking or the intoxication that drinking causes.
 */
public record WineBenefit(int food, int healingHalfHearts, int speedSeconds) {
    private static final NamespacedKey MARKER = TheBrewingProject.key("raycraft_wine");
    private static final NamespacedKey FOOD = TheBrewingProject.key("raycraft_wine_food");
    private static final NamespacedKey HEAL = TheBrewingProject.key("raycraft_wine_heal");
    private static final NamespacedKey SPEED = TheBrewingProject.key("raycraft_wine_speed");
    private static final long COOLDOWN_MILLIS = 120_000L;
    private static final Map<UUID, Long> NEXT_BENEFIT = new ConcurrentHashMap<>();

    public WineBenefit {
        if (food < 0 || food > 2 || healingHalfHearts < 0 || healingHalfHearts > 1
                || speedSeconds < 0 || speedSeconds > 8) {
            throw new IllegalArgumentException("Wine benefits exceed RayCraft's approved bounds");
        }
    }

    public void applyTo(ItemStack item) {
        item.editPersistentDataContainer(pdc -> {
            pdc.set(MARKER, PersistentDataType.BOOLEAN, true);
            pdc.set(FOOD, PersistentDataType.INTEGER, food);
            pdc.set(HEAL, PersistentDataType.INTEGER, healingHalfHearts);
            pdc.set(SPEED, PersistentDataType.INTEGER, speedSeconds);
        });
    }

    public static Optional<WineBenefit> fromItem(ItemStack item) {
        if (!Config.config().raycraftWinemakingEnabled()) {
            return Optional.empty();
        }
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        if (!pdc.has(MARKER, PersistentDataType.BOOLEAN)
                || !Boolean.TRUE.equals(pdc.get(MARKER, PersistentDataType.BOOLEAN))
                || !Objects.equals("wine", pdc.get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING))) {
            return Optional.empty();
        }
        Integer food = pdc.get(FOOD, PersistentDataType.INTEGER);
        Integer heal = pdc.get(HEAL, PersistentDataType.INTEGER);
        Integer speed = pdc.get(SPEED, PersistentDataType.INTEGER);
        if (food == null || heal == null || speed == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WineBenefit(food, heal, speed));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Called only after TBP has accepted an actual consumption event.
     * Existing stronger or longer Speed is not shortened by wine.
     */
    public void consume(Player player) {
        if (food == 0 && healingHalfHearts == 0 && speedSeconds == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        long availableAt = NEXT_BENEFIT.getOrDefault(uuid, 0L);
        if (availableAt > now) {
            return;
        }
        NEXT_BENEFIT.put(uuid, now + COOLDOWN_MILLIS);
        if (food > 0) {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + food));
        }
        if (healingHalfHearts > 0) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                player.setHealth(Math.min(health.getValue(), player.getHealth() + healingHalfHearts));
            }
        }
        if (speedSeconds > 0) {
            PotionEffect active = player.getPotionEffect(PotionEffectType.SPEED);
            if (active == null || (active.getAmplifier() == 0 && active.getDuration() < speedSeconds * 20)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedSeconds * 20, 0, false, false, true));
            }
        }
    }
}
