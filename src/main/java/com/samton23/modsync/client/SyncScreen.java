package com.samton23.modsync.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.samton23.modsync.ModSync;
import com.samton23.modsync.server.ModManifestEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full-screen overlay shown during mod synchronization.
 * Blocks the user from doing anything until sync is complete,
 * then shows a "Restart Minecraft" button.
 */
public class SyncScreen extends Screen {

    private enum State { SYNCING, DONE, ERROR }

    private volatile State state = State.SYNCING;
    private volatile String currentFile = "";
    private volatile double fileProgress = 0.0;
    private volatile int totalFiles = 0;
    private volatile int doneFiles = 0;
    private volatile String errorMessage = "";

    private Button restartButton;

    private final List<ModManifestEntry> toDownload;
    private final List<File> toDisable;
    private final ModDownloader downloader;

    private final AtomicBoolean syncStarted = new AtomicBoolean(false);

    public SyncScreen(List<ModManifestEntry> toDownload, List<File> toDisable, ModDownloader downloader) {
        super(Component.literal("ModSync — Synchronizing mods..."));
        this.toDownload = toDownload;
        this.toDisable = toDisable;
        this.downloader = downloader;
    }

    @Override
    protected void init() {
        restartButton = addRenderableWidget(Button.builder(
            Component.literal("Restart Minecraft"),
            btn -> restartGame()
        ).pos(this.width / 2 - 100, this.height / 2 + 60).size(200, 20).build());
        restartButton.visible = false;
        restartButton.active = false;

        if (syncStarted.compareAndSet(false, true)) {
            startSyncThread();
        }
    }

    private void startSyncThread() {
        totalFiles = toDownload.size();

        downloader.setProgressCallback((filename, progress) -> {
            this.currentFile = filename;
            this.fileProgress = progress;
        });

        Thread thread = new Thread(() -> {
            try {
                // Disable extra mods first (fast, local operation)
                ModDownloader.disableMods(toDisable);

                // Download missing/outdated mods
                for (int i = 0; i < toDownload.size(); i++) {
                    ModManifestEntry entry = toDownload.get(i);
                    doneFiles = i;
                    currentFile = entry.filename();
                    fileProgress = 0.0;

                    boolean ok = downloader.downloadAll(List.of(entry));
                    if (!ok) {
                        errorMessage = "Failed to download: " + entry.filename();
                        state = State.ERROR;
                        showRestartButton();
                        return;
                    }
                }

                doneFiles = totalFiles;
                state = State.DONE;
                ModSync.LOGGER.info("[ModSync] Sync complete. Restart required.");
                showRestartButton();

            } catch (Exception e) {
                ModSync.LOGGER.error("[ModSync] Sync error: {}", e.getMessage());
                errorMessage = "Error: " + e.getMessage();
                state = State.ERROR;
                showRestartButton();
            }
        }, "ModSync-Downloader");
        thread.setDaemon(true);
        thread.start();
    }

    private void showRestartButton() {
        // Must run on render thread
        Minecraft.getInstance().execute(() -> {
            restartButton.visible = true;
            restartButton.active = true;
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dark background
        renderBackground(graphics);

        int cx = this.width / 2;
        int cy = this.height / 2;

        // Title
        graphics.drawCenteredString(this.font, "ModSync — Synchronizing mods", cx, cy - 80, 0xFFFFFF);

        switch (state) {
            case SYNCING -> renderSyncing(graphics, cx, cy);
            case DONE    -> renderDone(graphics, cx, cy);
            case ERROR   -> renderError(graphics, cx, cy);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSyncing(GuiGraphics graphics, int cx, int cy) {
        // Status line
        String status = String.format("Downloading %d / %d", doneFiles, totalFiles);
        graphics.drawCenteredString(this.font, status, cx, cy - 50, 0xAAAAAA);

        // Current filename
        if (!currentFile.isEmpty()) {
            String label = "File: " + trimFilename(currentFile, 40);
            graphics.drawCenteredString(this.font, label, cx, cy - 35, 0xDDDDDD);
        }

        // Per-file progress bar
        if (!currentFile.isEmpty()) {
            drawProgressBar(graphics, cx - 100, cy - 20, 200, 10, (float) fileProgress, 0xFF44AA44);
            int pct = (int) (fileProgress * 100);
            graphics.drawCenteredString(this.font, pct + "%", cx, cy - 8, 0xFFFFFF);
        }

        // Overall progress bar
        if (totalFiles > 0) {
            float overall = (float) doneFiles / totalFiles;
            drawProgressBar(graphics, cx - 120, cy + 10, 240, 8, overall, 0xFF2266CC);
        }
    }

    private void renderDone(GuiGraphics graphics, int cx, int cy) {
        graphics.drawCenteredString(this.font, "Sync complete!", cx, cy - 40, 0xFF44FF44);
        graphics.drawCenteredString(this.font, "Please restart Minecraft to apply changes.", cx, cy - 25, 0xCCCCCC);
    }

    private void renderError(GuiGraphics graphics, int cx, int cy) {
        graphics.drawCenteredString(this.font, "Sync failed!", cx, cy - 40, 0xFFFF4444);
        graphics.drawCenteredString(this.font, errorMessage, cx, cy - 25, 0xFF8888);
        graphics.drawCenteredString(this.font, "You may need to restart and retry.", cx, cy - 10, 0xCCCCCC);
    }

    private void drawProgressBar(GuiGraphics graphics, int x, int y, int w, int h, float progress, int color) {
        // Background
        graphics.fill(x, y, x + w, y + h, 0xFF333333);
        // Fill
        int filled = (int) (w * Math.min(1f, Math.max(0f, progress)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, color);
        }
        // Border
        graphics.fill(x, y, x + w, y + 1, 0xFF888888);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF888888);
        graphics.fill(x, y, x + 1, y + h, 0xFF888888);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF888888);
    }

    private String trimFilename(String name, int maxLen) {
        if (name.length() <= maxLen) return name;
        return "..." + name.substring(name.length() - (maxLen - 3));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Don't allow closing with Escape during sync
        return state != State.SYNCING;
    }

    private void restartGame() {
        ModSync.LOGGER.info("[ModSync] Restarting Minecraft...");
        // Get current JVM launch command and relaunch
        try {
            String javaBin = ProcessHandle.current()
                .info()
                .command()
                .orElse(null);
            List<String> command = ProcessHandle.current()
                .info()
                .arguments()
                .map(args -> {
                    java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
                    if (javaBin != null) cmd.add(javaBin);
                    for (String arg : args) cmd.add(arg);
                    return cmd;
                })
                .orElse(null);

            if (command != null && !command.isEmpty()) {
                new ProcessBuilder(command)
                    .inheritIO()
                    .start();
            }
        } catch (IOException e) {
            ModSync.LOGGER.error("[ModSync] Could not relaunch: {}", e.getMessage());
        }
        // Exit regardless
        System.exit(0);
    }
}
