package com.samton23.modsync.server;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.config.ModSyncConfig;
import com.samton23.modsync.network.ModSyncChannel;
import com.samton23.modsync.network.packets.ModManifestPacket;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * Sends the mod manifest to the client when a player logs in.
 */
public class ServerEventHandler {

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ModSyncConfig.ENABLED.get()) return;

        // Only runs on the logical server
        if (event.getEntity().level().isClientSide()) return;

        List<ModManifestEntry> manifest = ModpackManager.INSTANCE.getManifest();
        int httpPort = ModSyncConfig.HTTP_PORT.get();

        ModSync.LOGGER.info("[ModSync] Sending manifest ({} mods) to player {}",
            manifest.size(), event.getEntity().getName().getString());

        ModSyncChannel.sendToPlayer(
            new ModManifestPacket(manifest, httpPort),
            event.getEntity()
        );
    }
}
