package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.server.ModManifestEntry;
import com.samton23.modsync.server.ModpackManager;

import java.io.File;
import java.util.*;

/**
 * Compares the server manifest against local mods/ folder.
 * Produces two lists: mods to download, mods to disable.
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

    /**
     * @param serverManifest list of mods required by the server
     * @param modsDir        the local mods/ directory
     * @return a SyncPlan describing what needs to change
     */
    public static SyncPlan compare(List<ModManifestEntry> serverManifest, File modsDir) {
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

        // Check each server mod against local
        for (ModManifestEntry entry : serverManifest) {
            File localFile = localMap.get(entry.filename());
            if (localFile == null) {
                // Mod missing entirely
                ModSync.LOGGER.info("[ModSync] Missing mod: {}", entry.filename());
                toDownload.add(entry);
            } else if (localFile.getName().endsWith(".disabled")) {
                // Mod exists but disabled — re-enable by downloading fresh copy
                ModSync.LOGGER.info("[ModSync] Mod disabled, will re-enable: {}", entry.filename());
                toDownload.add(entry);
            } else {
                // Mod exists — verify hash
                try {
                    String localMd5 = ModpackManager.computeMd5(localFile);
                    if (!localMd5.equalsIgnoreCase(entry.md5())) {
                        ModSync.LOGGER.info("[ModSync] Outdated mod (hash mismatch): {}", entry.filename());
                        toDownload.add(entry);
                    }
                } catch (Exception e) {
                    ModSync.LOGGER.warn("[ModSync] Could not hash {}, will re-download: {}", entry.filename(), e.getMessage());
                    toDownload.add(entry);
                }
            }
        }

        // Mark local mods not in server manifest as to-disable
        for (Map.Entry<String, File> local : localMap.entrySet()) {
            String baseName = local.getKey();
            // Skip our own mod and already-disabled files
            if (baseName.startsWith("modsync") || local.getValue().getName().endsWith(".disabled")) continue;
            if (!serverNames.contains(baseName)) {
                ModSync.LOGGER.info("[ModSync] Extra mod to disable: {}", baseName);
                toDisable.add(local.getValue());
            }
        }

        return new SyncPlan(toDownload, toDisable);
    }
}
