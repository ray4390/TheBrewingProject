package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.api.brew.BrewQuality;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.BarrelType;
import dev.jsinco.brewery.api.breweries.BarrelTypes;
import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.recipe.RecipeRegistry;
import dev.jsinco.brewery.brew.AgeStepImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.recipe.BukkitRecipeResult;
import dev.jsinco.brewery.bukkit.recipe.RecipeEffectsImpl;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.configuration.DrunkenModifierSection;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.recipes.RecipeImpl;
import dev.jsinco.brewery.wine.WineBalance;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A dynamic recipe for an actual berry wine (not a separate brewing mode).
 *
 * Constructed from ingredients already in the normal TBP cauldron. We do not
 * register a new recipe ID: finished bottles keep the existing "wine" identity
 * for downstream recipes such as sangria. Distilled and mixed recipes are not
 * candidates for this path.
 */
public final class WineRecipeFactory {
    private WineRecipeFactory() {}

    public static Optional<Recipe<ItemStack>> candidate(List<BrewingStep> steps, RecipeRegistry<ItemStack> registry) {
        if (!Config.config().raycraftWinemakingEnabled()
                || dev.jsinco.brewery.bukkit.TheBrewingProject.getInstance().getWineryRegistry() == null) {
            return Optional.empty();
        }
        return candidateForEnabledServer(steps, registry);
    }

    // Package-private: exercise recipe selection and regression rules without
    // turning on the production feature during isolated unit tests.
    static Optional<Recipe<ItemStack>> candidateForEnabledServer(List<BrewingStep> steps,
                                                                  RecipeRegistry<ItemStack> registry) {
        if (steps.isEmpty() || steps.size() > 2
                || !(steps.getFirst() instanceof BrewingStep.Cook cook)) {
            return Optional.empty();
        }
        if (steps.size() == 2 && !(steps.get(1) instanceof BrewingStep.Age)) {
            return Optional.empty();
        }

        Map<String, Integer> items = new HashMap<>();
        for (Map.Entry<? extends Ingredient, Integer> entry : cook.ingredients().entrySet()) {
            if (!(entry.getKey().toBaseIngredient() instanceof SimpleIngredient simple)) {
                return Optional.empty();
            }
            items.merge(simple.material().name(), entry.getValue(), Integer::sum);
        }
        WineBalance.Plan plan = WineBalance.plan(items).orElse(null);
        if (plan == null || (plan.ingredients().size() == 1 && plan.ingredients().get("SWEET_BERRIES") == 5)) {
            // Preserve the legacy red wine recipe exactly, including its existing score.
            return Optional.empty();
        }

        Recipe<ItemStack> base = registry.getRecipe("wine").orElse(null);
        if (base == null) {
            return Optional.empty();
        }
        // The live RayCraft 'wine' recipe is custom (7m, one distillation run,
        // four aging years in spruce). That restriction belongs exclusively to
        // the legacy recipe; new undistilled fruit wines allow any TBP barrel.
        BarrelType barrelType = BarrelTypes.ANY;
        if (barrelType == null) {
            return Optional.empty();
        }

        // Preserve the configured meaning of a TBP cooking minute and aging year.
        long cookTicks = Math.round(plan.cookingSeconds() * Config.config().cauldrons().cookingMinuteTicks() / 60.0);
        long ageTicks = (long) plan.agingYears() * Config.config().barrels().agingYearTicks();
        List<BrewingStep> target = List.of(
                new CookStepImpl(new PassedMoment(cookTicks), cook.ingredients(), cook.cauldronType()),
                new AgeStepImpl(new PassedMoment(ageTicks), barrelType)
        );

        QualityData<dev.jsinco.brewery.api.recipe.RecipeResult<ItemStack>> results =
                QualityData.fromValueMapper(quality -> wineResult(plan, quality));

        return Optional.of(new RecipeImpl.Builder<ItemStack>("wine")
                .brewDifficulty(2)
                .recipeResults(results)
                .steps(target)
                .build());
    }

    private static BukkitRecipeResult wineResult(WineBalance.Plan plan, BrewQuality quality) {
        WineBalance.Quality tier = WineBalance.Quality.valueOf(quality.name());
        RecipeEffectsImpl effects = new RecipeEffectsImpl.Builder()
                .addModifiers(Map.of(
                        DrunkenModifierSection.modifiers().modifier("alcohol"), (double) plan.alcohol(),
                        DrunkenModifierSection.modifiers().modifier("toxins"), (double) plan.toxins(tier)
                )).build();

        return new BukkitRecipeResult.Builder()
                .name(plan.style())
                .color(Color.fromRGB(0x75213A))
                .recipeEffects(effects)
                .lore(List.of("<gray>RayCraft berry wine</gray>"))
                .wineBenefit(new WineBenefit(
                        plan.foodPoints(tier), plan.healingHalfHearts(tier), plan.speedSeconds(tier)))
                .build();
    }
}
