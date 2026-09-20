package dev.jsinco.brewery.bukkit.compat;

import org.bukkit.Bukkit;

import java.util.Set;

/**
 * Central source of truth for the Paper/Minecraft versions validated by the
 * RayCraft fork. Keep feature-specific compatibility code in this package and
 * update this class when qualifying a new Paper release.
 */
public final class PaperCompatibility {

    public static final String TARGET_MINECRAFT_VERSION = "26.2";
    private static final Set<String> SUPPORTED_MINECRAFT_VERSIONS = Set.of(TARGET_MINECRAFT_VERSION);

    private PaperCompatibility() {
        throw new IllegalStateException("Utility class");
    }

    public static String serverMinecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    public static boolean isSupportedServer() {
        return SUPPORTED_MINECRAFT_VERSIONS.contains(serverMinecraftVersion());
    }
}
