package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.BarrelTypes;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.brew.AgeStepImpl;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.recipes.RecipeRegistryImpl;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end JVM gameplay path: real TBP matcher -> brew item PDC -> signed winery
 * bottle -> preserved sealing -> player consumption. Never touches the live host.
 */
class WineGameplayTest {
    private ServerMock server;
    private TheBrewingProject plugin;
    private WineryRegistry wineries;

    @BeforeEach
    void setup() throws Exception {
        server = MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
        wineries = new WineryRegistry(plugin);
        Field registryField = TheBrewingProject.class.getDeclaredField("wineryRegistry");
        registryField.setAccessible(true);
        registryField.set(plugin, wineries);
        Field enabled = Config.class.getDeclaredField("raycraftWinemakingEnabled");
        enabled.setAccessible(true);
        enabled.set(Config.config(), true);
        // The production wine recipe is read asynchronously from YAML.
        // MockBukkit's scheduler deliberately does not complete those tasks:
        // install a deterministic legacy fixture rather than relying on test
        // order or a race with asynchronous startup.
        var originalWine = new dev.jsinco.brewery.recipes.RecipeImpl.Builder<ItemStack>("wine")
                .steps(List.of(
                        new CookStepImpl(new PassedMoment(
                                7L * Config.config().cauldrons().cookingMinuteTicks()),
                                Map.of(SimpleIngredient.from("sweet_berries").orElseThrow(), 5),
                                CauldronType.WATER),
                        new dev.jsinco.brewery.brew.DistillStepImpl(1),
                        new AgeStepImpl(new PassedMoment(
                                4L * Config.config().barrels().agingYearTicks()), BarrelTypes.SPRUCE)
                ))
                .recipeResults(dev.jsinco.brewery.api.recipe.QualityData.fromValueMapper(q ->
                        new dev.jsinco.brewery.bukkit.recipe.BukkitRecipeResult.Builder()
                                .name("Legacy Red Wine")
                                .recipeEffects(new dev.jsinco.brewery.bukkit.recipe.RecipeEffectsImpl.Builder().build())
                                .build()))
                .build();
        if (plugin.getRecipeRegistry().getRecipe("wine").isEmpty()) {
            plugin.getRecipeRegistry().registerRecipe(originalWine);
        }
        assertTrue(plugin.getRecipeRegistry().getRecipe("wine").isPresent(),
                "Dynamic wine recipes require a registered legacy wine identifier");
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    private static Brew brew(int berries, int apple, int honey, boolean aged) {
        java.util.Map<dev.jsinco.brewery.api.ingredient.Ingredient, Integer> ingredients = new java.util.HashMap<>();
        ingredients.put(SimpleIngredient.from("sweet_berries").orElseThrow(), berries);
        if (apple > 0) ingredients.put(SimpleIngredient.from("apple").orElseThrow(), apple);
        if (honey > 0) ingredients.put(SimpleIngredient.from("honey_bottle").orElseThrow(), honey);
        var plan = dev.jsinco.brewery.wine.WineBalance.plan(Map.of(
                "SWEET_BERRIES", berries, "APPLE", Math.max(1, apple), "HONEY_BOTTLE", Math.max(1, honey))).orElseThrow();
        long cookTicks = Math.round(plan.cookingSeconds() * Config.config().cauldrons().cookingMinuteTicks() / 60D);
        var cook = new CookStepImpl(new PassedMoment(cookTicks), ingredients, CauldronType.WATER);
        if (!aged) return new BrewImpl(cook);
        long ageTicks = plan.agingYears() * (long) Config.config().barrels().agingYearTicks();
        return new BrewImpl(List.of(cook, new AgeStepImpl(new PassedMoment(ageTicks), BarrelTypes.OAK)));
    }

    @Test
    void wineCanBeBrewedAgedAndConsumedUsingNormalTbpMatcher() {
        Brew actual = brew(7, 1, 1, true);
        ItemStack item = BrewAdapterAccess.toItem(actual, new BrewImpl.State.Other());
        assertEquals(Material.POTION, item.getType());
        assertEquals("wine", item.getPersistentDataContainer().get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING));
        assertTrue(item.getPersistentDataContainer().getOrDefault(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0D) >= 0.6,
                "The normal two-step recipe must produce a completed, saleable wine");
        var roundTrip = BrewAdapterAccess.fromItem(item).orElseThrow();
        assertEquals(2, roundTrip.getCompletedSteps().size(), "Recipe remains stored for re-brewing, blending and ownership checks");
        assertEquals(new WineBenefit(2, 1, 0), WineBenefit.fromItem(item).orElseThrow());

        PlayerMock player = server.addPlayer();
        player.setFoodLevel(10);
        player.setHealth(10);
        WineBenefit.fromItem(item).orElseThrow().consume(player);
        assertEquals(12, player.getFoodLevel());
        assertEquals(11, player.getHealth());
    }

    @Test
    void signedBrandSurvivesSaveAndSealingWithoutRevealingFormula() throws Exception {
        ItemStack wine = BrewAdapterAccess.toItem(brew(7, 1, 1, true), new BrewImpl.State.Other());
        PlayerMock producer = server.addPlayer();
        assertTrue(wineries.create(producer, "Oakbridge Winery").startsWith("Created"));
        assertTrue(wineries.register(producer, "Golden Orchard", wine).startsWith("Registered"));
        assertTrue(wineries.bottle(producer, "Golden Orchard", wine).startsWith("Branded"));
        String code = wineries.verifiedShopCode(wine).orElseThrow();
        assertTrue(code.startsWith("W_"));

        ItemStack sealed = BrewAdapterAccess.toItem(brew(7, 1, 1, true), new BrewImpl.State.Seal(null));
        wineries.retainLabelAfterSeal(wine, sealed);
        assertEquals(code, wineries.verifiedShopCode(sealed).orElseThrow());

        WineryRegistry reloaded = new WineryRegistry(plugin);
        assertEquals(code, reloaded.verifiedShopCode(sealed).orElseThrow());
    }

    @Test
    void unmatchedAndIncompleteInputsNeverClaimToBeFineWine() {
        var early = BrewAdapterAccess.toItem(brew(7, 1, 1, false), new BrewImpl.State.Other());
        assertTrue(early.getPersistentDataContainer().getOrDefault(BrewAdapterAccess.BREWERY_SCORE,
                PersistentDataType.DOUBLE, 0D) < 0.6, "Unaged brew must not receive good wine quality");
        assertTrue(WineBenefit.fromItem(early).isEmpty());
    }
}
