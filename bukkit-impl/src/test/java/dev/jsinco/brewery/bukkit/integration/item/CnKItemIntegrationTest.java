package dev.jsinco.brewery.bukkit.integration.item;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: C&K foods share a vanilla backing item, but must never become
 * vanilla poisonous_potato when resolved by TBP's ingredient manager.
 */
class CnKItemIntegrationTest {
    @Test
    void recognizesAllNamespacedCnKModelsWithoutAllowingVanillaFallback() {
        assertEquals("grapes", CnKItemIntegration.idFromModel("cnk:grapes"));
        assertEquals("tomato", CnKItemIntegration.idFromModel("cnk:tomato"));
        assertEquals("corn", CnKItemIntegration.idFromModel("cnk:corn"));
        assertEquals("rice", CnKItemIntegration.idFromModel("cnk:rice"));
        assertEquals("lettuce", CnKItemIntegration.idFromModel("cnk:lettuce"));
        assertEquals("chili_pepper", CnKItemIntegration.idFromModel("cnk:chili_pepper"));
        assertEquals("coffee", CnKItemIntegration.idFromModel("cnk:coffee"));
        assertEquals("grape_seeds", CnKItemIntegration.idFromModel("cnk:grape_seeds"));
        assertEquals("coffee_cherries", CnKItemIntegration.idFromModel("cnk:coffee_cherries"));
        assertEquals("cinnamon", CnKItemIntegration.idFromModel("cnk:cinnamon"));
    }

    @Test
    void ignoresOrdinaryItemsAndMalformedOrUntrustedModelNames() {
        assertNull(CnKItemIntegration.idFromModel(null));
        assertNull(CnKItemIntegration.idFromModel("minecraft:poisonous_potato"));
        assertNull(CnKItemIntegration.idFromModel("cnk:"));
        assertNull(CnKItemIntegration.idFromModel("cnk:../../grapes"));
        assertNull(CnKItemIntegration.idFromModel("cnk:Invalid Id"));
        assertNull(CnKItemIntegration.idFromModel("cnk:" + "x".repeat(81)));
    }

    @Test
    void realCustomModelStacksResolveAndSerializeByNamespace() {
        MockBukkit.mock(new TBPServerMock());
        try {
            var integration = new CnKItemIntegration();
            for (String id : new String[]{"grapes", "tomato", "corn", "rice", "chili_pepper"}) {
                ItemStack original = Bukkit.getItemFactory().createItemStack(
                    "minecraft:poisonous_potato[minecraft:item_model=\"cnk:" + id + "\"," +
                    "minecraft:custom_data={cnk:{ingredient:{type:\"" + id + "\"}}}]");
                assertEquals(id, integration.getItemId(original));
                assertTrue(integration.isIngredient(id));
                ItemStack roundTrip = integration.createItem(id).orElseThrow();
                assertEquals(id, integration.getItemId(roundTrip));
            }
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void originalGrapeOnlyWineRecipeRegistrationRemainsUnchanged() {
        var integration = new CnKItemIntegration();
        assertTrue(integration.isIngredient("grapes"));
        assertTrue(integration.isIngredient("tomato"), "unmatched C&K foods still need safe namespace round-trip");
        assertTrue(integration.isIngredient("rice"));
        assertTrue(integration.isIngredient("poisonous_potato"), "namespace cnk is distinct from vanilla poisonous potato");
        assertFalse(integration.isIngredient("Upper Case"));
        assertFalse(integration.isIngredient("../invalid"));
    }
}
