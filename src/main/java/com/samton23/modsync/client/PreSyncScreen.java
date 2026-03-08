package com.samton23.modsync.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.samton23.modsync.ModSync;
import com.samton23.modsync.config.ModSyncConfig;
import com.samton23.modsync.server.ModManifestEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shown BEFORE the Minecraft connection is established.
 * Fetches the server's mod manifest via HTTP, downloads what's needed,
 * then either:
 *   - Restarts the game (if mods were downloaded), or
 *   - Proceeds with the real connection (if already up-to-date).
 */
public class PreSyncScreen extends Screen {

    private enum State {
        CHECKING,    // HTTP request in progress
        DOWNLOADING, // Downloading mod files
        UP_TO_DATE,  // Nothing to do → will auto-connect
        SYNC_DONE,   // Downloads complete → restart needed
        NO_MODSYNC,  // Server has no ModSync HTTP endpoint → auto-connect
        ERROR        // Something went wrong
    }

    private static final Gson GSON = new Gson();

    private volatile State state = State.CHECKING;
    private volatile String statusLine = "";
    private volatile String currentFile = "";
    private volatile double fileProgress = 0.0;
    private volatile int totalFiles = 0;
    private volatile int doneFiles = 0;

    private final ServerData serverData;
    private final Screen lastScreen;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private Button cancelButton;
    private Button restartButton;
    private Button connectButton;

    public PreSyncScreen(ServerData serverData, Screen lastScreen) {
        super(Component.literal("ModSync — Pre-connection sync"));
        this.serverData = serverData;
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        // "Restart Minecraft" — shown after downloading mods
        restartButton = addRenderableWidget(Button.builder(
            Component.literal("Restart Minecraft"),
            btn -> restartGame()
        ).pos(width / 2 - 102, height / 2 + 46).size(100, 20).build());
        restartButton.visible = false;
        restartButton.active = false;

        // "Connect Now" — shown when mods are already up-to-date or after non-restart scenarios
        connectButton = addRenderableWidget(Button.builder(
            Component.literal("Connect Now"),
            btn -> connectNow()
        ).pos(width / 2 + 2, height / 2 + 46).size(100, 20).build());
        connectButton.visible = false;
        connectButton.active = false;

        cancelButton = addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            btn -> cancel()
        ).pos(width / 2 - 100, height / 2 + 70).size(200, 20).build());

        if (started.compareAndSet(false, true)) {
            startSyncThread();
        }
    }

    // -------------------------------------------------------------------------
    // Background sync thread
    // -------------------------------------------------------------------------

    private void startSyncThread() {
        Thread t = new Thread(() -> {
            try {
                String host = parseHost(serverData.ip);
                int httpPort = ModSyncConfig.HTTP_PORT.get();

                // 1. Fetch manifest from server HTTP endpoint
                List<ModManifestEntry> serverManifest = fetchManifest(host, httpPort);

                if (serverManifest == null) {
                    // Server doesn't have ModSync (or HTTP not reachable) — show connect button
                    state = State.NO_MODSYNC;
                    statusLine = "Server has no ModSync endpoint.";
                    ModSync.LOGGER.info("[ModSync] No HTTP response from {}:{}, showing connect button", host, httpPort);
                    showConnectButton();
                    return;
                }

                ModSync.LOGGER.info("[ModSync] Got manifest: {} mod(s)", serverManifest.size());

                // 2. Compare with local mods/
                File modsDir = Minecraft.getInstance().gameDirectory.toPath().resolve("mods").toFile();
                // disableExtraMods is unknown pre-connect → default false (safe)
                ModComparator.SyncPlan plan = ModComparator.compare(serverManifest, modsDir, false);

                if (plan.isUpToDate()) {
                    state = State.UP_TO_DATE;
                    statusLine = "Mods are up to date.";
                    ModSync.LOGGER.info("[ModSync] Mods up to date, showing connect button");
                    showConnectButton();
                    return;
                }

                // 3. Sync needed — download
                totalFiles = plan.toDownload().size();
                state = State.DOWNLOADING;
                ModSync.LOGGER.info("[ModSync] Pre-sync: {} to download, {} to disable",
                    plan.toDownload().size(), plan.toDisable().size());

                ModDownloader downloader = new ModDownloader(host, httpPort, modsDir);
                downloader.setProgressCallback((filename, prog) -> {
                    currentFile = filename;
                    fileProgress = prog;
                });

                // Disable extras first (fast)
                ModDownloader.disableMods(plan.toDisable());

                // Download each mod
                for (int i = 0; i < plan.toDownload().size(); i++) {
                    ModManifestEntry entry = plan.toDownload().get(i);
                    doneFiles = i;
                    currentFile = entry.filename();
                    fileProgress = 0.0;

                    boolean ok = downloader.downloadAll(List.of(entry));
                    if (!ok) {
                        state = State.ERROR;
                        statusLine = "Failed to download: " + entry.filename();
                        return;
                    }
                }

                doneFiles = totalFiles;
                state = State.SYNC_DONE;
                statusLine = "Sync complete! Restart to apply changes.";
                ModSync.LOGGER.info("[ModSync] Pre-sync done — restart required");

                // Show restart + connect buttons on the render thread
                Minecraft.getInstance().execute(() -> {
                    restartButton.visible = true;
                    restartButton.active = true;
                    connectButton.visible = true;
                    connectButton.active = true;
                });

            } catch (Exception e) {
                ModSync.LOGGER.error("[ModSync] Pre-sync error: {}", e.getMessage());
                state = State.ERROR;
                statusLine = "Error: " + e.getMessage();
            }
        }, "ModSync-PreSync");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void showConnectButton() {
        Minecraft.getInstance().execute(() -> {
            connectButton.visible = true;
            connectButton.active = true;
        });
    }

    /** Fetches manifest JSON from the server's HTTP endpoint. Returns null if not available. */
    private List<ModManifestEntry> fetchManifest(String host, int port) {
        String urlStr = "http://" + host + ":" + port + "/manifest";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) return null;

            try (InputStream is = conn.getInputStream()) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return GSON.fromJson(json, new TypeToken<List<ModManifestEntry>>() {}.getType());
            }
        } catch (Exception e) {
            ModSync.LOGGER.info("[ModSync] HTTP manifest fetch failed ({}): {}", urlStr, e.getMessage());
            return null;
        }
    }

    /** Initiates the real Minecraft connection (after sync is confirmed unnecessary or done). */
    private void connectNow() {
        PreConnectHandler.skipNextIntercept = true;
        PreConnectHandler.isPresyncing = false;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> ConnectScreen.startConnecting(
            lastScreen, mc, ServerAddress.parseString(serverData.ip), serverData, false
        ));
    }

    private void cancel() {
        PreConnectHandler.isPresyncing = false;
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void restartGame() {
        PreConnectHandler.isPresyncing = false;
        ModSync.LOGGER.info("[ModSync] Restarting game after pre-sync...");
        try {
            String javaBin = ProcessHandle.current().info().command().orElse(null);
            List<String> args = ProcessHandle.current().info().arguments()
                .map(a -> {
                    java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
                    if (javaBin != null) cmd.add(javaBin);
                    for (String s : a) cmd.add(s);
                    return cmd;
                })
                .orElse(null);
            if (args != null && !args.isEmpty()) {
                new ProcessBuilder(args).inheritIO().start();
            }
        } catch (IOException e) {
            ModSync.LOGGER.error("[ModSync] Relaunch failed: {}", e.getMessage());
        }
        System.exit(0);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        int cx = width / 2, cy = height / 2;

        g.drawCenteredString(font, "ModSync — Pre-connection sync", cx, cy - 95, 0xFFFFFF);
        g.drawCenteredString(font, "Server: " + serverData.ip, cx, cy - 80, 0x888888);

        switch (state) {
            case CHECKING -> {
                g.drawCenteredString(font, "Checking server mods...", cx, cy - 55, 0xDDDDDD);
                drawSpinner(g, cx, cy - 30);
            }
            case DOWNLOADING -> renderDownloading(g, cx, cy);
            case UP_TO_DATE -> {
                g.drawCenteredString(font, "Mods are up to date", cx, cy - 50, 0xFF44FF44);
                g.drawCenteredString(font, "Connecting...", cx, cy - 35, 0xAAAAAA);
            }
            case NO_MODSYNC -> {
                g.drawCenteredString(font, "Server does not have ModSync", cx, cy - 50, 0xFFDDAA44);
                g.drawCenteredString(font, "Connecting normally...", cx, cy - 35, 0xAAAAAA);
            }
            case SYNC_DONE -> {
                g.drawCenteredString(font, "Sync complete!", cx, cy - 55, 0xFF44FF44);
                g.drawCenteredString(font, "Restart Minecraft to load the updated mods.", cx, cy - 40, 0xCCCCCC);
            }
            case ERROR -> {
                g.drawCenteredString(font, "Sync failed", cx, cy - 55, 0xFFFF4444);
                g.drawCenteredString(font, statusLine, cx, cy - 40, 0xFF8888);
            }
        }

        super.render(g, mouseX, mouseY, partial);
    }

    private void renderDownloading(GuiGraphics g, int cx, int cy) {
        g.drawCenteredString(font,
            String.format("Downloading mods: %d / %d", doneFiles, totalFiles),
            cx, cy - 60, 0xAAAAAA);

        if (!currentFile.isEmpty()) {
            g.drawCenteredString(font, trimName(currentFile, 44), cx, cy - 45, 0xDDDDDD);
            drawBar(g, cx - 100, cy - 30, 200, 10, (float) fileProgress, 0xFF44AA44);
            g.drawCenteredString(font, (int)(fileProgress * 100) + "%", cx, cy - 18, 0xFFFFFF);
        }

        if (totalFiles > 0) {
            drawBar(g, cx - 120, cy - 3, 240, 8, (float) doneFiles / totalFiles, 0xFF2266CC);
        }
    }

    private void drawBar(GuiGraphics g, int x, int y, int w, int h, float p, int color) {
        g.fill(x, y, x + w, y + h, 0xFF333333);
        int filled = (int)(w * Math.min(1f, Math.max(0f, p)));
        if (filled > 0) g.fill(x, y, x + filled, y + h, color);
        g.fill(x, y, x + w, y + 1, 0xFF888888);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF888888);
        g.fill(x, y, x + 1, y + h, 0xFF888888);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF888888);
    }

    private void drawSpinner(GuiGraphics g, int cx, int cy) {
        String[] frames = {"|", "/", "—", "\\"};
        int f = (int)(System.currentTimeMillis() / 200 % 4);
        g.drawCenteredString(font, frames[f], cx, cy, 0xFFFFFF);
    }

    private String trimName(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - (max - 3));
    }

    private String parseHost(String ip) {
        int colon = ip.lastIndexOf(':');
        if (colon > 0) {
            try { Integer.parseInt(ip.substring(colon + 1)); return ip.substring(0, colon); }
            catch (NumberFormatException ignored) {}
        }
        return ip;
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() { cancel(); }
}
