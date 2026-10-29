package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.BarrelTypes;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.brew.AgeStepImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.brew.MixStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.recipe.BukkitRecipeResult;
import dev.jsinco.brewery.bukkit.recipe.RecipeEffectsImpl;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.recipes.RecipeImpl;
import dev.jsinco.brewery.recipes.RecipeRegistryImpl;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WineRecipeFactoryTest {
    private RecipeRegistryImpl<ItemStack> registry;

    @BeforeEach
    void setup() {
        MockBukkit.mock(new TBPServerMock());
        MockBukkit.load(TheBrewingProject.class);
        registry = new RecipeRegistryImpl<>();
        registry.registerRecipe(new RecipeImpl.Builder<ItemStack>("wine")
                .steps(List.of(
                        cook(Map.of("sweet_berries", 5), 300),
                        new AgeStepImpl(new PassedMoment(20L * Config.config().barrels().agingYearTicks()), BarrelTypes.ANY)
                ))
                .recipeResults(QualityData.fromValueMapper(q ->
                        new BukkitRecipeResult.Builder().name("Legacy Wine")
                                .recipeEffects(new RecipeEffectsImpl.Builder().build()).build()))
                .build());
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    private static CookStepImpl cook(Map<String, Integer> materials, int seconds) {
        java.util.Map<dev.jsinco.brewery.api.ingredient.Ingredient, Integer> ingredients = new java.util.HashMap<>();
        materials.forEach((material, count) -> ingredients.put(SimpleIngredient.from(material).orElseThrow(), count));
        long ticks = Math.round(seconds * Config.config().cauldrons().cookingMinuteTicks() / 60D);
        return new CookStepImpl(new PassedMoment(ticks), ingredients, CauldronType.WATER);
    }

    @Test
    void naturalIngredientsProduceDeterministicNormalWineRecipe() {
        List<BrewingStep> steps = List.of(
                cook(Map.of("sweet_berries", 7, "apple", 1, "honey_bottle", 1), 370),
                new AgeStepImpl(new PassedMoment(26L * Config.config().barrels().agingYearTicks()), BarrelTypes.OAK)
        );
        var recipe = WineRecipeFactory.candidateForEnabledServer(steps, registry).orElseThrow();
        assertEquals("wine", recipe.getRecipeName());
        assertEquals(2, recipe.getSteps().size());
        var score = recipe.score(steps);
        assertTrue(score.completed(), "Cook + age recipe must complete naturally without another mode");
        assertTrue(score.rawScore() > 0.5, "Matched recipe should be usable");
        assertEquals("wine", WineRecipeFactory.candidateForEnabledServer(steps, registry).orElseThrow().getRecipeName());
    }

    @Test
    void classicFiveBerriesRemainStrictlyWithRegisteredRecipe() {
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(cook(Map.of("sweet_berries", 5), 300)), registry).isEmpty());
    }

    @Test
    void wineIsNeverCreatedFromDistillingOrMixing() {
        var base = cook(Map.of("sweet_berries", 7, "apple", 1), 340);
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(base, new DistillStepImpl(1)), registry).isEmpty());
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(base, new MixStepImpl(new PassedMoment(0),
                        Map.of(), CauldronType.WATER)), registry).isEmpty());
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(base, new DistillStepImpl(1),
                        new AgeStepImpl(new PassedMoment(20), BarrelTypes.OAK)), registry).isEmpty());
    }

    @Test
    void invalidIngredientsAreNotSilentlyAcceptedAsWine() {
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(cook(Map.of("sweet_berries", 5, "blaze_powder", 1), 300)), registry).isEmpty());
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(cook(Map.of("sweet_berries", 5, "apple", 1, "honey_bottle", 1, "sugar", 1), 300)), registry).isEmpty());
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(cook(Map.of("sweet_berries", 4, "apple", 1), 300)), registry).isEmpty());
    }

    @Test
    void requiresLegacyWineIdentifierToExistForDownstreamRecipes() {
        registry.clear();
        assertTrue(WineRecipeFactory.candidateForEnabledServer(
                List.of(cook(Map.of("sweet_berries", 7, "apple", 1), 340)), registry).isEmpty());
    }
}
