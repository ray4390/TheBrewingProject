package dev.jsinco.brewery.bukkit.integration.item;

import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal bridge for Crop & Kettle's actual custom grape item. The integration
 * stays registered when the datapack is absent so existing TBP bottles
 * containing cnk:grapes can still deserialize. No entity listeners or timers.
 */
public final class CnKItemIntegration implements ItemIntegration {
    private static final String GRAPES = "grapes";

    @Override public String getId() { return "cnk"; }
    @Override public boolean isEnabled() { return true; }
    @Override public boolean isIngredient(String id) { return GRAPES.equals(id); }
    @Override public CompletableFuture<Void> initialized() { return CompletableFuture.completedFuture(null); }

    /**
     * C&K represents nearly every crop/food/seed as poisonous potatoes with
     * distinct item_model keys. Recognize ALL C&K models as namespaced
     * ingredients so unrelated crops cannot accidentally score as the vanilla
     * poisonous_potato ingredient (e.g. green absinthe). Only grapes are
     * currently enabled in RayCraft wine formulas, and the optional integration
     * remains registered if C&K is removed.
     */
    public static @Nullable String idFromModel(String model) {
        if (model == null || !model.startsWith("cnk:")) return null;
        String id = model.substring("cnk:".length());
        if (id.isEmpty() || id.length() > 80 || id.contains("..") ||
            !id.matches("[a-z0-9_./-]+")) return null;
        return id;
    }

    @Override
    public @Nullable String getItemId(ItemStack stack) {
        Key model = stack.getData(DataComponentTypes.ITEM_MODEL);
        // Namespaced model identity, not material or mutable display name.
        return model == null ? null : idFromModel(model.asString());
    }

    @Override
    public @Nullable Component displayName(String id) {
        return isIngredient(id) ? Component.text("Grapes") : null;
    }

    @Override
    public Optional<ItemStack> createItem(String id) {
        if (!isIngredient(id)) return Optional.empty();
        try {
            // Used by TBP's ingredient-preview/serialization path only.
            // Keep the exact C&K custom_data identity; no renamed vanilla food.
            return Optional.of(Bukkit.getItemFactory().createItemStack(
                "minecraft:poisonous_potato[minecraft:item_model=\"cnk:grapes\"," +
                "minecraft:custom_data={cnk:{ingredient:{type:\"grapes\"}}}]"));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
