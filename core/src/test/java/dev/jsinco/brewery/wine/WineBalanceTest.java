package dev.jsinco.brewery.wine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WineBalanceTest {
    @Test
    void baselineRetainsClassicWineTargets() {
        WineBalance.Plan p = WineBalance.plan(Map.of("SWEET_BERRIES", 5)).orElseThrow();
        assertEquals(300, p.cookingSeconds());
        assertEquals(20, p.agingYears());
        assertEquals(8, p.alcohol());
        assertEquals(6, p.toxins(WineBalance.Quality.BAD));
        assertEquals(3, p.toxins(WineBalance.Quality.GOOD));
        assertEquals(1, p.toxins(WineBalance.Quality.EXCELLENT));
    }

    @Test
    void appleHoneyWineHasReproducibleModerateBenefits() {
        WineBalance.Plan p = WineBalance.plan(Map.of(
                "SWEET_BERRIES", 7, "APPLE", 1, "HONEY_BOTTLE", 1)).orElseThrow();
        assertEquals(370, p.cookingSeconds());
        assertEquals(26, p.agingYears());
        assertEquals(10, p.alcohol());
        assertEquals(1, p.healingHalfHearts(WineBalance.Quality.GOOD));
        assertEquals(0, p.healingHalfHearts(WineBalance.Quality.BAD));
        assertEquals(2, p.foodPoints(WineBalance.Quality.EXCELLENT));
        assertEquals(p, WineBalance.plan(Map.of(
                "honey_bottle", 1, "sweet_berries", 7, "apple", 1)).orElseThrow());
    }

    @Test
    void sugarCocoaGivesBriefSpeedOnlyWhenBrewedProperly() {
        WineBalance.Plan p = WineBalance.plan(Map.of(
                "SWEET_BERRIES", 5, "SUGAR", 2, "COCOA_BEANS", 1)).orElseThrow();
        assertEquals(8, p.speedSeconds(WineBalance.Quality.GOOD));
        assertEquals(0, p.speedSeconds(WineBalance.Quality.BAD));
        assertTrue(p.alcohol() <= 13);
    }

    @Test
    void glowAndMelonOnlyReduceContributionNotExistingToxins() {
        WineBalance.Plan p = WineBalance.plan(Map.of(
                "SWEET_BERRIES", 5, "GLOW_BERRIES", 2, "MELON_SLICE", 1)).orElseThrow();
        assertEquals(4, p.toxins(WineBalance.Quality.BAD));
        assertEquals(1, p.toxins(WineBalance.Quality.GOOD));
        assertEquals(0, p.toxins(WineBalance.Quality.EXCELLENT));
        assertTrue(p.alcohol() < 8);
    }

    @Test
    void rejectsArbitraryIngredientsAndExcessTypesWithoutTruncating() {
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 4)).isEmpty());
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 13)).isEmpty());
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 5, "BLAZE_POWDER", 1)).isEmpty());
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 5, "APPLE", 1, "HONEY_BOTTLE", 1, "SUGAR", 1)).isEmpty());
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 5, "HONEY_BOTTLE", 3)).isEmpty());
        assertTrue(WineBalance.plan(Map.of("SWEET_BERRIES", 5, "SUGAR", 0)).isEmpty());
    }

    @Test
    void allAcceptedWinesStayWithinSurvivalLimits() {
        for (int berries = 5; berries <= 12; berries++) {
            for (int sugar = 0; sugar <= 4; sugar++) {
                for (int honey = 0; honey <= 2; honey++) {
                    WineBalance.Plan p = WineBalance.plan(Map.of(
                            "SWEET_BERRIES", berries,
                            "SUGAR", Math.max(1, sugar),
                            "HONEY_BOTTLE", Math.max(1, honey))).orElseThrow();
                    assertTrue(p.alcohol() >= 5 && p.alcohol() <= 13);
                    assertTrue(p.agingYears() >= 12 && p.agingYears() <= 45);
                    for (WineBalance.Quality quality : WineBalance.Quality.values()) {
                        assertTrue(p.toxins(quality) >= 0);
                        assertTrue(p.foodPoints(quality) <= 2);
                        assertTrue(p.healingHalfHearts(quality) <= 1);
                        assertTrue(p.speedSeconds(quality) <= 8);
                    }
                }
            }
        }
    }
}
