package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.bukkit.api.ingredient.PluginIngredient;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.integration.item.CnKItemIntegration;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WineIngredientNamesTest {
    @Test void mapsOnlySupportedCustomGrapes() {
        var cnk = new CnKItemIntegration();
        assertEquals("CNK_GRAPES", WineIngredientNames.of(
            new PluginIngredient(new BreweryKey("cnk", "grapes"), cnk)).orElseThrow());
        assertTrue(WineIngredientNames.of(
            new PluginIngredient(new BreweryKey("cnk", "coffee"), cnk)).isEmpty());
        assertEquals("SWEET_BERRIES", WineIngredientNames.of(
            new SimpleIngredient(Material.SWEET_BERRIES)).orElseThrow());
    }
}
