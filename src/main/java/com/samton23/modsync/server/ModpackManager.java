package com.samton23.modsync.server;

import com.samton23.modsync.ModSync;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Scans the clientmodpack/ directory and builds a manifest of mod files.
 */
public class ModpackManager {

    public static final ModpackManager INSTANCE = new ModpackManager();

    private static final String MODPACK_FOLDER = "clientmodpack";

    private File modpackDir;
    private List<ModManifestEntry> manifest = Collections.emptyList();

    private ModpackManager() {}

    public void init(MinecraftServer server) {
        modpackDir = new File(server.getServerDirectory(), MODPACK_FOLDER);
        if (!modpackDir.exists()) {
            if (modpackDir.mkdirs()) {
                ModSync.LOGGER.info("[ModSync] Created clientmodpack/ folder at: {}", modpackDir.getAbsolutePath());
            }
        }
        refresh();
    }

    /**
     * Re-scans the modpack folder and rebuilds the manifest.
     */
    public void refresh() {
        if (modpackDir == null || !modpackDir.isDirectory()) {
            manifest = Collections.emptyList();
            return;
        }

        File[] files = modpackDir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null || files.length == 0) {
            manifest = Collections.emptyList();
            ModSync.LOGGER.info("[ModSync] No mods found in clientmodpack/");
            return;
        }

        List<ModManifestEntry> entries = new ArrayList<>();
        for (File file : files) {
            try {
                String md5 = computeMd5(file);
                entries.add(new ModManifestEntry(file.getName(), md5, file.length()));
                ModSync.LOGGER.debug("[ModSync] Indexed: {} ({})", file.getName(), md5);
            } catch (Exception e) {
                ModSync.LOGGER.error("[ModSync] Failed to hash file {}: {}", file.getName(), e.getMessage());
            }
        }

        manifest = Collections.unmodifiableList(entries);
        ModSync.LOGGER.info("[ModSync] Manifest built: {} mod(s) in clientmodpack/", manifest.size());
    }

    public List<ModManifestEntry> getManifest() {
        return manifest;
    }

    public File getModpackDir() {
        return modpackDir;
    }

    public File getModFile(String filename) {
        if (modpackDir == null) return null;
        // Security: ensure the file is directly inside modpackDir (no path traversal)
        File target = new File(modpackDir, filename);
        try {
            if (!target.getCanonicalPath().startsWith(modpackDir.getCanonicalPath())) {
                ModSync.LOGGER.warn("[ModSync] Path traversal attempt blocked: {}", filename);
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return target.exists() ? target : null;
    }

    public static String computeMd5(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
