package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.bukkit.api.ingredient.PluginIngredient;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;

import java.util.Optional;

/** Canonical names used by both wine scoring and winery formula signatures. */
public final class WineIngredientNames {
    private WineIngredientNames() {}

    public static Optional<String> of(Ingredient ingredient) {
        return switch (ingredient.toBaseIngredient()) {
            case SimpleIngredient simple -> Optional.of(simple.material().name());
            case PluginIngredient plugin
                when "cnk".equals(plugin.key().namespace()) && "grapes".equals(plugin.key().key())
                -> Optional.of("CNK_GRAPES");
            default -> Optional.empty();
        };
    }
}
