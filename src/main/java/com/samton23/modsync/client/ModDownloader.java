package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.server.ModManifestEntry;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Downloads mod files from the server's HTTP endpoint.
 */
public class ModDownloader {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 32_768;

    private final String serverHost;
    private final int serverPort;
    private final File modsDir;

    /** Called with (filename, progressFraction 0.0–1.0) */
    private BiConsumer<String, Double> progressCallback;

    public ModDownloader(String serverHost, int serverPort, File modsDir) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.modsDir = modsDir;
    }

    public void setProgressCallback(BiConsumer<String, Double> callback) {
        this.progressCallback = callback;
    }

    /**
     * Downloads all entries in the plan, removing old version files first.
     * Returns true if all downloads succeeded.
     */
    public boolean downloadAll(List<ModManifestEntry> entries) {
        for (ModManifestEntry entry : entries) {
            boolean ok = downloadMod(entry);
            if (!ok) {
                ModSync.LOGGER.error("[ModSync] Failed to download: {}", entry.filename());
                return false;
            }
        }
        return true;
    }

    private boolean downloadMod(ModManifestEntry entry) {
        String urlStr = "http://" + serverHost + ":" + serverPort + "/mods/" + entry.filename();
        ModSync.LOGGER.info("[ModSync] Downloading {} ({} bytes) from {}", entry.filename(), entry.size(), urlStr);

        // Remove outdated versions (same base name, different hash)
        removeExistingVersions(entry.filename());

        File tempFile = new File(modsDir, entry.filename() + ".tmp");
        File finalFile = new File(modsDir, entry.filename());

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            if (status != 200) {
                ModSync.LOGGER.error("[ModSync] HTTP {} for {}", status, entry.filename());
                return false;
            }

            long totalBytes = entry.size();
            long downloaded = 0;

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile))) {

                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (progressCallback != null && totalBytes > 0) {
                        progressCallback.accept(entry.filename(), (double) downloaded / totalBytes);
                    }
                }
            }

            // Atomic replace
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ModSync.LOGGER.info("[ModSync] Downloaded: {}", entry.filename());
            return true;

        } catch (Exception e) {
            ModSync.LOGGER.error("[ModSync] Download failed for {}: {}", entry.filename(), e.getMessage());
            tempFile.delete();
            return false;
        }
    }

    /**
     * Removes any existing .jar or .jar.disabled files with the same name.
     */
    private void removeExistingVersions(String filename) {
        File jar = new File(modsDir, filename);
        File jarDisabled = new File(modsDir, filename + ".disabled");
        if (jar.exists()) {
            jar.delete();
            ModSync.LOGGER.debug("[ModSync] Deleted old version: {}", filename);
        }
        if (jarDisabled.exists()) {
            jarDisabled.delete();
            ModSync.LOGGER.debug("[ModSync] Deleted disabled version: {}", filename + ".disabled");
        }
    }

    /**
     * Disables extra mods by renaming them to .jar.disabled.
     */
    public static void disableMods(List<File> toDisable) {
        for (File file : toDisable) {
            File renamed = new File(file.getParentFile(), file.getName() + ".disabled");
            if (file.renameTo(renamed)) {
                ModSync.LOGGER.info("[ModSync] Disabled mod: {} → {}", file.getName(), renamed.getName());
            } else {
                ModSync.LOGGER.error("[ModSync] Failed to disable mod: {}", file.getName());
            }
        }
    }
}
