package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.testutil.ItemStackMockPDC;
import dev.jsinco.brewery.api.breweries.BarrelTypes;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.brew.AgeStepImpl;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.configuration.Config;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;
import java.util.Map;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WineryRegistryTest {
    ServerMock server;
    TheBrewingProject plugin;
    WineryRegistry wineries;
    PlayerMock owner;
    PlayerMock worker;

    @BeforeEach
    void setup() throws IOException {
        server = MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
        wineries = new WineryRegistry(plugin);
        owner = server.addPlayer();
        worker = server.addPlayer();
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    @Test
    void creationPersistsAndNamesAreUnique() throws IOException {
        assertTrue(wineries.create(owner, "Westbridge Cellars").contains("Created winery"));
        assertTrue(wineries.info(owner).contains("Westbridge Cellars"));
        assertTrue(wineries.create(worker, "westbridge cellars").contains("already"));
        WineryRegistry reopened = new WineryRegistry(plugin);
        assertTrue(reopened.info(owner).contains("Westbridge Cellars"));
        assertThrows(IllegalArgumentException.class, () -> reopened.create(worker, "<unsafe>"));
    }

    @Test
    void invitationAndOwnershipSurviveReload() throws IOException {
        assertTrue(wineries.create(owner, "Oak Hall").contains("Created"));
        assertTrue(wineries.invite(owner, worker).contains("Invited"));
        assertTrue(wineries.accept(worker).contains("Joined"));
        assertTrue(wineries.info(worker).contains("2 members"));
        assertTrue(wineries.remove(owner, owner.getUniqueId()).contains("transfer"));
        assertTrue(wineries.transfer(owner, worker.getUniqueId()).contains("transferred"));
        WineryRegistry reopened = new WineryRegistry(plugin);
        assertTrue(reopened.info(worker).contains("Owner"));
        assertTrue(reopened.remove(owner, owner.getUniqueId()).contains("Left"));
    }

    @Test
    void brandedWineIsSignedRejectsTamperingAndSurvivesSealing() throws IOException {
        assertTrue(wineries.create(owner, "Westbridge").contains("Created"));
        ItemStack bottle = wineBottle(7, 1);
        assertTrue(wineries.register(owner, "Orchard Reserve", bottle).contains("Registered"));
        assertTrue(wineries.bottle(owner, "Orchard Reserve", bottle).contains("Branded"));
        String code = wineries.verifiedShopCode(bottle).orElseThrow();
        assertTrue(code.matches("W_[0-9A-F]{8}"));

        WineryRegistry restarted = new WineryRegistry(plugin);
        assertEquals(code, restarted.verifiedShopCode(bottle).orElseThrow());

        // The HMAC covers score: changing only the public brew rating invalidates a brand.
        bottle.editPersistentDataContainer(pdc ->
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.65D));
        assertTrue(restarted.verifiedShopCode(bottle).isEmpty());
        bottle.editPersistentDataContainer(pdc ->
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.85D));
        assertEquals(code, restarted.verifiedShopCode(bottle).orElseThrow());

        // TBP sealing strips recipe steps, but winery authenticity must persist.
        ItemStack sealed = new ItemStackMockPDC(Material.POTION);
        sealed.editPersistentDataContainer(pdc -> {
            pdc.set(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING, "wine");
            pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.85D);
        });
        restarted.retainLabelAfterSeal(bottle, sealed);
        assertEquals(code, restarted.verifiedShopCode(sealed).orElseThrow());
    }

    @Test
    void brandCannotBeAppliedToDifferentQuantities() throws IOException {
        assertTrue(wineries.create(owner, "Oak House").contains("Created"));
        assertTrue(wineries.register(owner, "Orchard Reserve", wineBottle(7, 1)).contains("Registered"));
        ItemStack different = wineBottle(8, 1);
        assertTrue(wineries.bottle(owner, "Orchard Reserve", different).contains("does not match"));
        assertTrue(wineries.verifiedShopCode(different).isEmpty());
    }

    private static ItemStack wineBottle(int berries, int apples) {
        ItemStack item = new ItemStackMockPDC(Material.POTION);
        var ingredients = Map.of(
                SimpleIngredient.from("sweet_berries").orElseThrow(), berries,
                SimpleIngredient.from("apple").orElseThrow(), apples);
        var brew = new BrewImpl(List.of(
                new CookStepImpl(new PassedMoment(5L * Config.config().cauldrons().cookingMinuteTicks()),
                        ingredients, CauldronType.WATER),
                new AgeStepImpl(new PassedMoment(20L * Config.config().barrels().agingYearTicks()),
                        BarrelTypes.OAK)
        ));
        item.editPersistentDataContainer(pdc -> {
            BrewAdapterAccess.applyBrewData(pdc, brew);
            pdc.set(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING, "wine");
            pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.85D);
        });
        return item;
    }

    @Test
    void untrustedActorsCannotClaimAnotherWineryOrInviteMembers() throws IOException {
        assertTrue(wineries.create(owner, "Seaside").contains("Created"));
        assertTrue(wineries.invite(worker, owner).contains("Only"));
        assertTrue(wineries.accept(worker).contains("no pending"));
        assertEquals("You do not belong to a winery. Use /winery create <name>.", wineries.info(worker));
    }
}
