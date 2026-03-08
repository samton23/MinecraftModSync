package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inspects a mod JAR to determine if it is client-side only.
 *
 * A mod is considered client-only if its META-INF/mods.toml contains:
 *   displayTest = "IGNORE_SERVER_VERSION"
 * This is the standard Forge way for mods to declare they don't need to be on the server.
 * Examples: OptiFine, JEI, REI, minimaps, shader packs, performance mods, etc.
 */
public class ModJarInspector {

    private static final String MODS_TOML_PATH = "META-INF/mods.toml";
    private static final String CLIENT_ONLY_MARKER = "IGNORE_SERVER_VERSION";

    /**
     * Returns true if this JAR is a client-only mod and should never be disabled
     * by the sync process regardless of server configuration.
     */
    public static boolean isClientOnlyMod(File jarFile) {
        if (!jarFile.exists() || !jarFile.getName().endsWith(".jar")) return false;

        try (ZipFile zip = new ZipFile(jarFile)) {
            ZipEntry entry = zip.getEntry(MODS_TOML_PATH);
            if (entry == null) {
                // No mods.toml — likely a library/coremod, don't touch it
                return true;
            }

            try (InputStream is = zip.getInputStream(entry)) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                boolean isClientOnly = content.contains(CLIENT_ONLY_MARKER);
                if (isClientOnly) {
                    ModSync.LOGGER.debug("[ModSync] Client-only mod detected (IGNORE_SERVER_VERSION): {}",
                        jarFile.getName());
                }
                return isClientOnly;
            }
        } catch (Exception e) {
            // If we can't read the JAR, play it safe and don't touch it
            ModSync.LOGGER.warn("[ModSync] Could not inspect {}, treating as client-only: {}",
                jarFile.getName(), e.getMessage());
            return true;
        }
    }
}
