package dev.jsinco.brewery.wine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * RayCraft's deterministic wine rules. All amounts are per ordinary TBP cauldron recipe unit.
 * No randomness, hidden unlocks, new crafting stations, or player modes.
 *
 * The vanilla five-berry wine remains a registered TBP recipe; this planner handles
 * valid variations without changing the legacy recipe or serialized brew schema.
 */
public final class WineBalance {
    private static final Map<String, Integer> CAPS = Map.of(
            "GLOW_BERRIES", 3,
            "APPLE", 2,
            "HONEY_BOTTLE", 2,
            "SUGAR", 4,
            "MELON_SLICE", 3,
            "COCOA_BEANS", 2
    );

    private WineBalance() {}

    /**
     * @return empty for ingredients outside the public wine rules, including invalid counts.
     */
    public static Optional<Plan> plan(Map<String, Integer> input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> ingredients = new HashMap<>();
        for (Map.Entry<String, Integer> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                return Optional.empty();
            }
            String name = entry.getKey().toUpperCase(Locale.ROOT);
            if (!"SWEET_BERRIES".equals(name) && !CAPS.containsKey(name)) {
                return Optional.empty();
            }
            ingredients.merge(name, entry.getValue(), Integer::sum);
        }
        int berries = ingredients.getOrDefault("SWEET_BERRIES", 0);
        if (berries < 5 || berries > 12 || ingredients.size() > 3) {
            return Optional.empty();
        }
        for (Map.Entry<String, Integer> entry : ingredients.entrySet()) {
            if (!"SWEET_BERRIES".equals(entry.getKey())
                    && entry.getValue() > CAPS.get(entry.getKey())) {
                return Optional.empty();
            }
        }

        int glow = ingredients.getOrDefault("GLOW_BERRIES", 0);
        int apple = ingredients.getOrDefault("APPLE", 0);
        int honey = ingredients.getOrDefault("HONEY_BOTTLE", 0);
        int sugar = ingredients.getOrDefault("SUGAR", 0);
        int melon = ingredients.getOrDefault("MELON_SLICE", 0);
        int cocoa = ingredients.getOrDefault("COCOA_BEANS", 0);

        // This deliberately matches classic red wine at the five-berry baseline.
        int cookingSeconds = 300 + 20 * (berries - 5) + 30 * honey + 15 * sugar + 20 * cocoa;
        int agingYears = Math.clamp(20 + 2 * (berries - 5) + 2 * honey + 3 * cocoa - 3 * melon, 12, 45);
        int alcohol = Math.clamp(8 + (berries - 5) / 2 + honey + (sugar + 1) / 2 - Math.min(2, melon), 5, 13);
        int toxinReduction = (glow > 0 ? 1 : 0) + (glow > 0 && melon > 0 ? 1 : 0);
        int nourishment = Math.min(2, (apple > 0 ? 1 : 0) + (honey > 0 ? 2 : 0) + (melon > 0 ? 1 : 0));
        boolean restorative = apple > 0 && honey > 0;
        boolean quickstep = cocoa > 0 && sugar > 0;
        String style = melon > 0 ? "Light Berry Wine"
                : honey > 0 ? "Honeyed Berry Wine"
                : cocoa > 0 ? "Rich Berry Wine"
                : apple > 0 ? "Orchard Berry Wine"
                : glow > 0 ? "Tart Berry Wine" : "Red Wine";

        return Optional.of(new Plan(Map.copyOf(ingredients), cookingSeconds, agingYears,
                alcohol, toxinReduction, nourishment, restorative, quickstep, style,
                sugar == 4 ? 1 : 0));
    }

    public record Plan(Map<String, Integer> ingredients,
                       int cookingSeconds,
                       int agingYears,
                       int alcohol,
                       int toxinReduction,
                       int nourishment,
                       boolean restorative,
                       boolean quickstep,
                       String style,
                       int sugarFaultRisk) {

        /**
         * TBP default wine uses 6/3/1 toxins (bad/good/excellent).
         * Toxicity can never be negative. Smooth wine never removes existing toxins.
         */
        public int toxins(Quality quality) {
            int baseline = switch (quality) {
                case BAD -> 6;
                case GOOD -> 3;
                case EXCELLENT -> 1;
            };
            int reduced = baseline - toxinReduction;
            // Over-sweetened wine is harder to brew cleanly, never a guaranteed advantage.
            if (quality == Quality.BAD) {
                reduced += sugarFaultRisk;
            }
            return Math.max(0, reduced);
        }

        public int foodPoints(Quality quality) {
            return quality == Quality.BAD ? 0 : nourishment;
        }

        public int healingHalfHearts(Quality quality) {
            return quality == Quality.BAD || !restorative ? 0 : 1;
        }

        public int speedSeconds(Quality quality) {
            return quality == Quality.BAD || !quickstep ? 0 : 8;
        }
    }

    public enum Quality { BAD, GOOD, EXCELLENT }
}
