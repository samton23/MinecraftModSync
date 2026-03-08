package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.network.packets.ModManifestPacket;
import com.samton23.modsync.server.ModManifestEntry;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.io.File;
import java.util.List;

/**
 * Handles the ModManifestPacket on the client side.
 * Computes the diff and opens SyncScreen if needed.
 */
public class ClientEventHandler {

    /**
     * Called on the client when a ModManifestPacket arrives from the server.
     * Must be called from the game thread (via ctx.enqueueWork).
     */
    public static void handleManifest(ModManifestPacket packet, NetworkEvent.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        List<ModManifestEntry> serverManifest = packet.entries();
        int httpPort = packet.httpPort();

        ModSync.LOGGER.info("[ModSync] Received manifest: {} mod(s) from server (HTTP port {})",
            serverManifest.size(), httpPort);

        // Determine local mods directory
        File modsDir = mc.gameDirectory.toPath().resolve("mods").toFile();
        if (!modsDir.isDirectory()) {
            ModSync.LOGGER.warn("[ModSync] mods/ directory not found at: {}", modsDir.getAbsolutePath());
            return;
        }

        // Compute diff
        ModComparator.SyncPlan plan = ModComparator.compare(serverManifest, modsDir);

        if (plan.isUpToDate()) {
            ModSync.LOGGER.info("[ModSync] Mods are up to date, no sync needed.");
            return;
        }

        ModSync.LOGGER.info("[ModSync] Sync needed: {} to download, {} to disable",
            plan.toDownload().size(), plan.toDisable().size());

        // Get server IP from connection
        String serverHost = getServerHost(mc);
        if (serverHost == null) {
            ModSync.LOGGER.error("[ModSync] Could not determine server IP, aborting sync.");
            return;
        }

        ModDownloader downloader = new ModDownloader(serverHost, httpPort, modsDir);

        // Open the sync screen
        mc.setScreen(new SyncScreen(plan.toDownload(), plan.toDisable(), downloader));
    }

    private static String getServerHost(Minecraft mc) {
        // getCurrentServer() returns the server the client is connected to
        var serverData = mc.getCurrentServer();
        if (serverData == null) return null;
        // ip field may contain "host:port" — strip the port if present
        String ip = serverData.ip;
        int colon = ip.lastIndexOf(':');
        if (colon > 0 && colon < ip.length() - 1) {
            try {
                Integer.parseInt(ip.substring(colon + 1));
                return ip.substring(0, colon);
            } catch (NumberFormatException ignored) {}
        }
        return ip;
    }
}
