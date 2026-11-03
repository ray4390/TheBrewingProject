package dev.jsinco.brewery.bukkit.integration.item;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for RayCraft's opt-in Crop & Kettle/TBP recipe overlay.
 * This file is merged by RayCraft Ops into test recipes.yml; the plugin's
 * default bundled recipe set and production configuration stay untouched.
 */
class CnKRecipeOverlayTest {
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "cnk_grape_brandy", List.of("cnk:grapes/8"),
            "cnk_rice_sake", List.of("cnk:rice/8"),
            "cnk_corn_whiskey", List.of("cnk:corn/8", "wheat/2"),
            "cnk_spiced_rum", List.of("brewery:rum/2", "cnk:chili_pepper/1", "sugar/1"),
            "cnk_mulled_wine", List.of("brewery:wine/2", "cnk:cinnamon/1", "apple/1", "sugar/1")
    );

    @Test
    void overlayContainsExactlyFiveWellFormedNamespacedRecipes() throws Exception {
        var resource = CnKRecipeOverlayTest.class.getResourceAsStream("/raycraft-cnk-recipes.yml");
        assertNotNull(resource, "overlay should be distributed inside CI-built plugin");
        YamlConfiguration data;
        try (var stream = resource; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            data = YamlConfiguration.loadConfiguration(reader);
        }
        ConfigurationSection recipes = data.getConfigurationSection("recipes");
        assertNotNull(recipes);
        assertEquals(EXPECTED.keySet(), recipes.getKeys(false));

        var cnk = new CnKItemIntegration();
        for (Map.Entry<String, List<String>> expectation : EXPECTED.entrySet()) {
            String key = expectation.getKey();
            ConfigurationSection recipe = recipes.getConfigurationSection(key);
            assertNotNull(recipe, key);
            assertTrue(recipe.getInt("brew-difficulty") > 0);
            assertTrue(recipe.contains("modifiers.alcohol"));
            assertTrue(recipe.contains("potion-attributes.color"));
            assertTrue(recipe.contains("potion-attributes.name"));
            List<Map<?, ?>> steps = recipe.getMapList("steps");
            assertFalse(steps.isEmpty(), key);
            List<String> found = new java.util.ArrayList<>();
            for (var step : steps) {
                assertTrue(Set.of("cook", "distill", "mix", "age").contains(step.get("type")), key);
                assertTrue(!step.containsKey("ingredients") || step.get("ingredients") instanceof List, key);
                if (step.containsKey("ingredients")) {
                    @SuppressWarnings("unchecked")
                    List<String> itemStrings = (List<String>) step.get("ingredients");
                    for (String item : itemStrings) {
                        assertTrue(item.matches("[a-z0-9_:]+/[1-9][0-9]*"), key + ": " + item);
                        if (item.startsWith("cnk:")) {
                            String id = item.substring(4, item.lastIndexOf('/'));
                            assertTrue(cnk.isIngredient(id), "unrecognized C&K ingredient: " + item);
                            assertNotEquals("grape_seeds", id, "seeds must never be recipe base");
                        }
                    }
                    found.addAll(itemStrings);
                }
            }
            assertEquals(expectation.getValue(), found, key);
        }
        assertEquals(List.of("cook", "distill", "age"),
                recipes.getMapList("cnk_grape_brandy.steps").stream().map(x -> x.get("type")).toList());
        assertEquals(List.of("cook", "age"),
                recipes.getMapList("cnk_rice_sake.steps").stream().map(x -> x.get("type")).toList());
        assertEquals("bamboo", recipes.getString("cnk_rice_sake.steps[1].barrel-type",
                ((Map<?, ?>) recipes.getMapList("cnk_rice_sake.steps").get(1)).get("barrel-type").toString()));
    }
}
