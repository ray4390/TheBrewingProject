package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import dev.jsinco.brewery.structure.PlacedStructureRegistryImpl;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.concurrent.atomic.AtomicLong;

public class WorldEventListener implements Listener {

    private final SqlDatabase database;
    private final PlacedStructureRegistryImpl placedStructureRegistry;
    private final BreweryRegistry registry;
    private final AtomicLong loadGeneration = new AtomicLong();

    public WorldEventListener(SqlDatabase database, PlacedStructureRegistryImpl placedStructureRegistry, BreweryRegistry registry) {
        this.database = database;
        this.placedStructureRegistry = placedStructureRegistry;
        this.registry = registry;
    }

    public void init() {
        long generation = loadGeneration.incrementAndGet();
        Bukkit.getServer().getWorlds().forEach(world -> loadWorld(world, generation));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        loadWorld(event.getWorld(), loadGeneration.get());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        registry.unloadWorld(event.getWorld().getUID());
        placedStructureRegistry.unloadWorld(event.getWorld().getUID());
    }

    public void invalidatePendingLoads() {
        loadGeneration.incrementAndGet();
    }

    private void loadWorld(World world, long generation) {
        try {
            database.startSession(SessionTypes.BARREL_SESSION_TYPE).findBarrels(world)
                    .thenAccept(barrels -> runWhenWorldIsLoaded(world, generation, () -> {
                        placedStructureRegistry.registerStructures(barrels.stream().map(BukkitBarrel::getStructure).toList());
                        registry.registerInventories(barrels);
                    })).exceptionally(Logger::logAndTrackErr);
            database.startSession(SessionTypes.CAULDRON_SESSION_TYPE).findCauldrons(world.getUID())
                    .thenAccept(cauldrons -> runWhenWorldIsLoaded(world, generation, () -> {
                        cauldrons.forEach(registry::addActiveSinglePositionStructure);
                    })).exceptionally(Logger::logAndTrackErr);
            database.startSession(SessionTypes.DISTILLERY_SESSION_TYPE).findDistilleries(world)
                    .thenAccept(distilleries -> runWhenWorldIsLoaded(world, generation, () -> {
                        placedStructureRegistry.registerStructures(distilleries.stream().map(BukkitDistillery::getStructure).toList());
                        registry.registerInventories(distilleries);
                    })).exceptionally(Logger::logAndTrackErr);
        } catch (PersistenceException e) {
            Logger.logErr(e);
        }
    }

    private void runWhenWorldIsLoaded(World world, long generation, Runnable runnable) {
        TheBrewingProject plugin = TheBrewingProject.getInstance();
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
            if (generation != loadGeneration.get() || Bukkit.getWorld(world.getUID()) != world) {
                return;
            }
            try {
                runnable.run();
            } catch (RuntimeException exception) {
                Logger.logAndTrackErr(exception);
            }
        });
    }
}
