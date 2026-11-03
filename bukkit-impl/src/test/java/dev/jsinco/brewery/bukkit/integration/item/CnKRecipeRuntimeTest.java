package dev.jsinco.brewery.bukkit.integration.item;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import dev.jsinco.brewery.bukkit.ingredient.BukkitIngredientManager;
import dev.jsinco.brewery.bukkit.recipe.BukkitRecipeResultReader;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.recipes.RecipeReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CnKRecipeRuntimeTest {
    @TempDir Path temp;

    @BeforeEach
    void setup() {
        MockBukkit.mock(new TBPServerMock());
        MockBukkit.load(TheBrewingProject.class);
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    @Test
    void tbpActuallyParsesAndMatchesAllFiveCnKRecipes() throws Exception {
        try (var stream = CnKRecipeRuntimeTest.class.getResourceAsStream("/raycraft-cnk-recipes.yml")) {
            assertNotNull(stream);
            Files.copy(stream, temp.resolve("recipes.yml"));
        }
        var reader = new RecipeReader<>(temp.toFile(),
                new BukkitRecipeResultReader(), BukkitIngredientManager.INSTANCE);
        var candidates = reader.readRecipes().stream().map(f -> f.join()).toList();
        assertEquals(5, candidates.size(), "TBP should parse all five namespaced recipes");
        assertEquals(Set.of("cnk_grape_brandy", "cnk_rice_sake", "cnk_corn_whiskey",
                        "cnk_spiced_rum", "cnk_mulled_wine"),
                candidates.stream().map(r -> r.getRecipeName()).collect(Collectors.toSet()));
        for (var recipe : candidates) {
            var result = recipe.score(recipe.getSteps());
            assertTrue(result.completed(), recipe.getRecipeName() + " cannot be completed");
            assertTrue(result.rawScore() > 0.5, recipe.getRecipeName() + " fails its own target");
        }
    }

    @Test
    void allFiveRecipesCreateAndDecodeRealTbpBottles() throws Exception {
        try (var stream = CnKRecipeRuntimeTest.class.getResourceAsStream("/raycraft-cnk-recipes.yml")) {
            assertNotNull(stream);
            Files.copy(stream, temp.resolve("recipes.yml"));
        }
        var reader = new RecipeReader<ItemStack>(temp.toFile(),
                new BukkitRecipeResultReader(), BukkitIngredientManager.INSTANCE);
        var candidates = reader.readRecipes().stream().map(f -> f.join()).toList();
        assertEquals(5, candidates.size());

        var plugin = TheBrewingProject.getInstance();
        for (var recipe : candidates) {
            // Register a controlled opt-in recipe fixture, not a production
            // database or mutable player inventory. Test TBP's actual matcher.
            plugin.getRecipeRegistry().registerRecipe(recipe);
        }
        for (var recipe : candidates) {
            var brew = new BrewImpl(recipe.getSteps());
            var item = BrewAdapterAccess.toItem(brew, new Brew.State.Other());
            assertEquals(Material.POTION, item.getType(), recipe.getRecipeName());
            assertEquals(recipe.getRecipeName(), item.getPersistentDataContainer().get(
                    BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING),
                    "Finished bottle must retain the correct new C&K recipe ID");
            assertTrue(item.getPersistentDataContainer().getOrDefault(
                    BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0D) > 0.5,
                    recipe.getRecipeName() + " finished bottle should have usable quality");
            var decoded = BrewAdapterAccess.fromItem(item).orElseThrow();
            assertEquals(brew.getSteps(), decoded.getSteps(),
                    recipe.getRecipeName() + " must preserve complete cook/distill/age/mix steps");
        }
    }
}
