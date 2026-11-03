package dev.jsinco.brewery.bukkit.integration.item;

import com.Acrobot.Breeze.Utils.MaterialUtil;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.ingredient.PluginIngredient;
import dev.jsinco.brewery.bukkit.ingredient.BukkitIngredientManager;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the actual ChestShop 3.13 equality matcher compiled with TBP; checks
 * C&K item_model and custom_data, preventing poisonous-potato seed aliasing.
 * Does not represent an economy/debit transaction or a real network client.
 */
class CnKItemIdentityChestShopTest {
    private static final List<String> MODELS = List.of(
        "grapes", "grape_seeds",
        "rice", "rice_seeds",
        "corn", "corn_seeds",
        "tomato", "tomato_seeds",
        "lettuce", "lettuce_seeds",
        "chili_pepper", "chili_pepper_seeds",
        "coffee_cherries", "cinnamon"
    );

    private static ItemStack model(String id) {
        return Bukkit.getItemFactory().createItemStack(
            "minecraft:poisonous_potato[minecraft:item_model=\"cnk:" + id + "\"," +
            "minecraft:custom_data={cnk:{ingredient:{type:\"" + id + "\"}}}]");
    }

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
    void distinctCustomItemTypesNeverMatchEachOtherOrVanillaAsChestShopStock() {
        ItemStack ordinary = new ItemStack(Material.POISONOUS_POTATO);
        var integration = new CnKItemIntegration();
        for (String id : MODELS) {
            ItemStack item = model(id);
            assertEquals(Material.POISONOUS_POTATO, item.getType(), id);
            assertEquals(id, integration.getItemId(item), id);
            assertFalse(MaterialUtil.equals(item, ordinary),
                "Custom " + id + " must not match an ordinary poisonous potato");
            assertTrue(MaterialUtil.equals(item, item.clone()),
                "Authentic matching stock should trade as its own type");
            PluginIngredient ingredient = assertInstanceOf(PluginIngredient.class,
                BukkitIngredientManager.INSTANCE.getIngredient(item).toBaseIngredient());
            assertEquals("cnk", ingredient.key().namespace());
            assertEquals(id, ingredient.key().key());
            assertEquals(id, integration.getItemId(integration.createItem(id).orElseThrow()));
            for (String other : MODELS) {
                if (!id.equals(other)) {
                    assertFalse(MaterialUtil.equals(item, model(other)),
                        id + " must not match " + other + " in ChestShop");
                }
            }
        }
    }

    @Test
    void clonedSeedKeepsIdentityButFruitIsNotItsSeed() {
        ItemStack seed = model("grape_seeds");
        ItemStack fruit = model("grapes");
        assertFalse(MaterialUtil.equals(seed, fruit));
        assertFalse(MaterialUtil.equals(fruit, seed));
        seed.setAmount(8);
        var boughtOne = seed.clone();
        boughtOne.setAmount(1);
        assertTrue(MaterialUtil.equals(seed, boughtOne),
            "ChestShop count changes must not rewrite item model or custom data");
        assertEquals("grape_seeds", new CnKItemIntegration().getItemId(boughtOne));
    }
}
