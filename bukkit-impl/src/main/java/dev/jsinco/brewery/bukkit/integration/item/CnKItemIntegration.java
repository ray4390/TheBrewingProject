package dev.jsinco.brewery.bukkit.integration.item;

import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    @Override
    public @Nullable String getItemId(ItemStack stack) {
        if (stack.getType() != Material.POISONOUS_POTATO) return null;
        Key model = stack.getData(DataComponentTypes.ITEM_MODEL);
        // Item names are mutable and are never an identity check. Model keys
        // are supplied by C&K's loot tables and are not an anvil property.
        return model != null && "cnk:grapes".equals(model.asString()) ? GRAPES : null;
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
