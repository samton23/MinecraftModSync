package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.server.ModManifestEntry;
import com.samton23.modsync.server.ModpackManager;

import java.io.File;
import java.util.*;

/**
 * Compares the server manifest against local mods/ folder.
 *
 * Disabling logic:
 *  - If disableExtraMods=false (default): extra client mods are never touched.
 *  - If disableExtraMods=true: extra mods are disabled UNLESS they are client-only
 *    (detected via displayTest=IGNORE_SERVER_VERSION in their mods.toml).
 */
public class ModComparator {

    public record SyncPlan(
        List<ModManifestEntry> toDownload,
        List<File> toDisable
    ) {
        public boolean isUpToDate() {
            return toDownload.isEmpty() && toDisable.isEmpty();
        }
    }

    public static SyncPlan compare(List<ModManifestEntry> serverManifest, File modsDir, boolean disableExtraMods) {
        File[] localFiles = modsDir.listFiles(f ->
            f.isFile() && (f.getName().endsWith(".jar") || f.getName().endsWith(".jar.disabled"))
        );
        if (localFiles == null) localFiles = new File[0];

        // Build map: base-name → local file (strip .disabled suffix for comparison)
        Map<String, File> localMap = new HashMap<>();
        for (File f : localFiles) {
            String name = f.getName().endsWith(".disabled")
                ? f.getName().substring(0, f.getName().length() - ".disabled".length())
                : f.getName();
            localMap.put(name, f);
        }

        // Build set of server filenames for quick lookup
        Set<String> serverNames = new HashSet<>();
        for (ModManifestEntry entry : serverManifest) {
            serverNames.add(entry.filename());
        }

        List<ModManifestEntry> toDownload = new ArrayList<>();
        List<File> toDisable = new ArrayList<>();

        // --- Check each server mod against local ---
        for (ModManifestEntry entry : serverManifest) {
            File localFile = localMap.get(entry.filename());

            if (localFile == null) {
                ModSync.LOGGER.info("[ModSync] Missing mod: {}", entry.filename());
                toDownload.add(entry);

            } else if (localFile.getName().endsWith(".disabled")) {
                // Exists but disabled — re-download to enable cleanly
                ModSync.LOGGER.info("[ModSync] Mod is disabled, will re-enable: {}", entry.filename());
                toDownload.add(entry);

            } else {
                // Exists — verify hash
                try {
                    String localMd5 = ModpackManager.computeMd5(localFile);
                    if (!localMd5.equalsIgnoreCase(entry.md5())) {
                        ModSync.LOGGER.info("[ModSync] Outdated mod (version mismatch): {}", entry.filename());
                        toDownload.add(entry);
                    }
                } catch (Exception e) {
                    ModSync.LOGGER.warn("[ModSync] Could not hash {}, will re-download: {}",
                        entry.filename(), e.getMessage());
                    toDownload.add(entry);
                }
            }
        }

        // --- Handle extra mods on the client ---
        if (disableExtraMods) {
            for (Map.Entry<String, File> local : localMap.entrySet()) {
                String baseName = local.getKey();
                File localFile = local.getValue();

                // Never touch our own mod or already-disabled files
                if (baseName.startsWith("modsync") || localFile.getName().endsWith(".disabled")) continue;

                // Skip mods that are already covered by the server list
                if (serverNames.contains(baseName)) continue;

                // Inspect JAR: never disable client-only mods (displayTest=IGNORE_SERVER_VERSION)
                if (ModJarInspector.isClientOnlyMod(localFile)) {
                    ModSync.LOGGER.info("[ModSync] Keeping client-only mod: {}", baseName);
                    continue;
                }

                ModSync.LOGGER.info("[ModSync] Extra mod will be disabled: {}", baseName);
                toDisable.add(localFile);
            }
        } else {
            // Log extras for info only — never disable them
            for (Map.Entry<String, File> local : localMap.entrySet()) {
                String baseName = local.getKey();
                if (baseName.startsWith("modsync") || local.getValue().getName().endsWith(".disabled")) continue;
                if (!serverNames.contains(baseName)) {
                    ModSync.LOGGER.debug("[ModSync] Extra client mod (kept, disableExtraMods=false): {}", baseName);
                }
            }
        }

        return new SyncPlan(toDownload, toDisable);
    }
}
