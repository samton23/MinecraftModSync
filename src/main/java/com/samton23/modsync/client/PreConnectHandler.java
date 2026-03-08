package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;

/**
 * Intercepts the ConnectScreen BEFORE Forge starts its handshake/registry sync.
 *
 * Flow:
 *  1. User clicks "Join" → ConnectScreen is about to open
 *  2. We cancel the screen change (ScreenEvent.Opening is cancellable)
 *  3. Show PreSyncScreen which fetches the manifest via HTTP
 *  4. Download missing mods if needed
 *  5. If restart needed → show restart button
 *  6. If already up-to-date → open ConnectScreen for real (skipNextIntercept=true)
 *
 * While presyncing, any DisconnectedScreen from the orphaned connect() thread is suppressed.
 */
@Mod.EventBusSubscriber(modid = ModSync.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class PreConnectHandler {

    /** True while PreSyncScreen is active — suppresses stray screen changes. */
    public static volatile boolean isPresyncing = false;

    /**
     * Set to true before calling ConnectScreen.startConnecting() after a successful sync
     * so we don't intercept our own second connect attempt.
     */
    public static volatile boolean skipNextIntercept = false;

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {

        // --- Intercept ConnectScreen ---
        if (event.getScreen() instanceof ConnectScreen connectScreen) {

            if (skipNextIntercept) {
                skipNextIntercept = false; // one-shot flag, reset and let it through
                ModSync.LOGGER.debug("[ModSync] Allowing connect (post-sync)");
                return;
            }

            ServerData serverData = extractServerData(connectScreen);
            if (serverData == null) {
                ModSync.LOGGER.warn("[ModSync] Could not extract ServerData, skipping pre-sync");
                return;
            }

            // Cancel the ConnectScreen (and therefore the Forge handshake)
            event.setCanceled(true);
            isPresyncing = true;
            ModSync.LOGGER.info("[ModSync] Intercepted connect to '{}', starting pre-sync", serverData.ip);

            // Show our pre-sync screen instead
            net.minecraft.client.gui.screens.Screen lastScreen = event.getCurrentScreen();
            Minecraft.getInstance().execute(
                () -> Minecraft.getInstance().setScreen(new PreSyncScreen(serverData, lastScreen))
            );
            return;
        }

        // --- Suppress DisconnectedScreen from the orphaned connect() thread ---
        // When we cancelled ConnectScreen's screen change, connect() still ran in the background.
        // Its handleDisconnect() will try to show DisconnectedScreen — we suppress it.
        if (isPresyncing && event.getScreen() instanceof DisconnectedScreen) {
            event.setCanceled(true);
            ModSync.LOGGER.debug("[ModSync] Suppressed DisconnectedScreen during pre-sync");
        }
    }

    /**
     * Extracts the ServerData from a ConnectScreen instance.
     * First tries Minecraft.getCurrentServer() (set before ConnectScreen opens),
     * then falls back to reflection scanning all fields.
     */
    static ServerData extractServerData(ConnectScreen screen) {
        // Minecraft stores the current server before ConnectScreen is shown
        ServerData current = Minecraft.getInstance().getCurrentServer();
        if (current != null) return current;

        // Fallback: scan ConnectScreen fields by type
        for (Field field : ConnectScreen.class.getDeclaredFields()) {
            if (ServerData.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    return (ServerData) field.get(screen);
                } catch (Exception e) {
                    ModSync.LOGGER.error("[ModSync] Reflection error on ConnectScreen: {}", e.getMessage());
                }
            }
        }
        return null;
    }
}
