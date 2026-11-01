package dev.jsinco.brewery.bukkit.wine;

import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import dev.jsinco.brewery.api.breweries.BarrelTypes;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.brew.AgeStepImpl;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.integration.chest_shop.ChestShopNativeIntegration;
import dev.jsinco.brewery.bukkit.testutil.ItemStackMockPDC;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.configuration.Config;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual ChestShop 3.13 PreTransactionEvent's selection contract. */
class WineChestShopTransactionTest {
    ServerMock server;
    TheBrewingProject plugin;
    WineryRegistry registry;
    ChestShopNativeIntegration integration;
    PlayerMock owner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        server = MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
        registry = new WineryRegistry(plugin);
        Field winery = TheBrewingProject.class.getDeclaredField("wineryRegistry");
        winery.setAccessible(true);
        winery.set(plugin, registry);
        server.addSimpleWorld("wine-chestshop-qa");
        owner = server.addPlayer();
        registry.create(owner, "Seaside Cellars");

        integration = new ChestShopNativeIntegration();
        Field map = ChestShopNativeIntegration.class.getDeclaredField("codeToRecipe");
        map.setAccessible(true);
        ((Map<String,String>)map.get(integration)).put("b_wine", "wine");
        Field reverse = ChestShopNativeIntegration.class.getDeclaredField("recipeToCode");
        reverse.setAccessible(true);
        ((Map<String,String>)reverse.get(integration)).put("wine", "B_wine");
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private ItemStack wine() {
        var ingredients = Map.of(
                SimpleIngredient.from("sweet_berries").orElseThrow(), 7,
                SimpleIngredient.from("apple").orElseThrow(), 1);
        long cookTicks = 340L * Config.config().cauldrons().cookingMinuteTicks() / 60L;
        var brew = new BrewImpl(java.util.List.of(
                new CookStepImpl(new PassedMoment(cookTicks), ingredients, CauldronType.WATER),
                new AgeStepImpl(new PassedMoment(22L * Config.config().barrels().agingYearTicks()), BarrelTypes.OAK)));
        ItemStack item = new ItemStackMockPDC(Material.POTION);
        item.editPersistentDataContainer(pdc -> {
            BrewAdapterAccess.applyBrewData(pdc, brew);
            pdc.set(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING, "wine");
            pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.85D);
            pdc.set(BrewAdapterAccess.BREWERY_DISPLAY_NAME, PersistentDataType.STRING, "Orchard Berry Wine");
        });
        return item;
    }

    private Sign sign(String code) {
        World world = Bukkit.getWorlds().getFirst();
        var block = world.getBlockAt(12, 90, 12);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.setLine(0, owner.getName());
        sign.setLine(1, "1");
        sign.setLine(2, "B 10");
        sign.setLine(3, code);
        sign.update(true);
        return sign;
    }

    private PreTransactionEvent buy(Sign sign, Inventory stock) {
        return new PreTransactionEvent(stock, owner.getInventory(),
                new ItemStack[]{new ItemStack(Material.POTION)}, BigDecimal.TEN,
                owner, null, sign, TransactionEvent.TransactionType.BUY);
    }

    @Test void signedBrandShopNeverSubstitutesOrdinaryWine() throws Exception {
        ItemStack ordinary = wine();
        ItemStack reserve = wine();
        assertTrue(registry.register(owner, "Island Reserve", reserve).startsWith("Registered"));
        assertTrue(registry.bottle(owner, "Island Reserve", reserve).startsWith("Branded"));
        String code = registry.verifiedShopCode(reserve).orElseThrow();

        Inventory stock = Bukkit.createInventory(null, 27);
        stock.setItem(0, ordinary);
        stock.setItem(1, reserve);
        PreTransactionEvent trade = buy(sign(code), stock);
        integration.onPreTransaction(trade);

        assertFalse(trade.isCancelled(), "Legitimate signed brand must remain buyable");
        assertEquals(1, trade.getStock().length);
        assertEquals(code, registry.verifiedShopCode(trade.getStock()[0]).orElseThrow());
        assertEquals(BigDecimal.TEN, trade.getExactPrice());
    }

    @Test void genericWineShopCannotSellPremiumOrTamperedStock() throws Exception {
        ItemStack reserve = wine();
        registry.register(owner, "Island Reserve", reserve);
        registry.bottle(owner, "Island Reserve", reserve);
        Inventory stock = Bukkit.createInventory(null, 27);
        stock.setItem(0, reserve);

        PreTransactionEvent trade = buy(sign("B_wine"), stock);
        integration.onPreTransaction(trade);
        assertTrue(trade.isCancelled(), "Premium bottle must not be substituted for generic wine");

        trade = buy(sign("B_wine"), stock);
        trade.setStock(reserve);
        integration.protectBrandedStock(trade);
        assertTrue(trade.isCancelled(), "Legacy stock selection cannot bypass the brand guard");

        reserve.editPersistentDataContainer(pdc ->
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.65D));
        assertTrue(registry.verifiedShopCode(reserve).isEmpty(), "Tampering invalidates branded bottle");
        trade = buy(sign("B_wine"), stock);
        integration.onPreTransaction(trade);
        assertTrue(trade.isCancelled(), "Even invalid/revoked branded bottles cannot enter generic stock");
    }

    @Test void excellentOnlyBrandShopFiltersOutOrdinaryGoodQualityBottles() throws Exception {
        ItemStack excellent = wine();
        assertTrue(registry.register(owner, "Grand Reserve", excellent).startsWith("Registered"));
        assertTrue(registry.bottle(owner, "Grand Reserve", excellent).startsWith("Branded"));
        String brand = registry.verifiedShopCode(excellent).orElseThrow();

        ItemStack merelyGood = wine();
        merelyGood.editPersistentDataContainer(pdc ->
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.65D));
        assertTrue(registry.bottle(owner, "Grand Reserve", merelyGood).startsWith("Branded"));
        Inventory stock = Bukkit.createInventory(null, 27);
        stock.setItem(0, merelyGood);
        stock.setItem(1, excellent);

        PreTransactionEvent excellentOnly = buy(sign(brand + "-E"), stock);
        integration.onPreTransaction(excellentOnly);
        assertFalse(excellentOnly.isCancelled());
        assertEquals(1, excellentOnly.getStock().length);
        assertEquals(0.85D, excellentOnly.getStock()[0].getPersistentDataContainer()
                .get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE));
        assertEquals(brand, registry.verifiedShopCode(excellentOnly.getStock()[0]).orElseThrow());

        PreTransactionEvent goodOrBetter = buy(sign(brand + "-G"), stock);
        integration.onPreTransaction(goodOrBetter);
        assertFalse(goodOrBetter.isCancelled());
        assertTrue(goodOrBetter.getStock()[0].getPersistentDataContainer().get(
                BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE) >= 0.6D);
    }

    @Test void ordinaryWineSupportsMinimumQualityAndPlayerToShopSales() {
        ItemStack good = wine();
        good.editPersistentDataContainer(pdc ->
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0.65D));
        ItemStack excellent = wine();
        Inventory stock = Bukkit.createInventory(null, 27);
        stock.setItem(0, good);
        stock.setItem(1, excellent);

        PreTransactionEvent excellentOnly = buy(sign("B_wine-E"), stock);
        integration.onPreTransaction(excellentOnly);
        assertFalse(excellentOnly.isCancelled());
        assertEquals(0.85D, excellentOnly.getStock()[0].getPersistentDataContainer()
                .get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE));

        owner.getInventory().setItem(0, good);
        PreTransactionEvent sale = new PreTransactionEvent(stock, owner.getInventory(),
                new ItemStack[]{new ItemStack(Material.POTION)}, BigDecimal.TEN,
                owner, null, sign("B_wine-G"), TransactionEvent.TransactionType.SELL);
        integration.onPreTransaction(sale);
        assertFalse(sale.isCancelled(), "A player must be able to sell a good wine back to a buying shop");
        assertEquals(0.65D, sale.getStock()[0].getPersistentDataContainer()
                .get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE));
    }

    @Test void realOrdinaryWineRemainsTradableUnderLegacyCode() {
        ItemStack ordinary = wine();
        Inventory stock = Bukkit.createInventory(null, 27);
        stock.setItem(0, ordinary);
        PreTransactionEvent trade = buy(sign("B_wine"), stock);
        integration.onPreTransaction(trade);
        assertFalse(trade.isCancelled());
        assertEquals("wine", trade.getStock()[0].getPersistentDataContainer()
                .get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING));
    }
}
