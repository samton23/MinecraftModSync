package com.samton23.modsync.client;

import com.samton23.modsync.ModSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;

/**
 * Adds a "Sync Mods" button to the multiplayer server list screen.
 *
 * The button is only active when a server is selected.
 * Clicking it opens PreSyncScreen which fetches the manifest via HTTP,
 * downloads missing/outdated mods, and then lets the user restart or connect.
 */
@Mod.EventBusSubscriber(modid = ModSync.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class MultiplayerSyncHandler {

    private static Button syncButton;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen multiplayerScreen)) return;

        // Position: right of the "Add Server" button (width/2 + 4, height - 52, 100x20)
        // Our button sits at width/2 + 108, height - 52 with width 100
        int x = multiplayerScreen.width / 2 + 108;
        int y = multiplayerScreen.height - 52;

        syncButton = Button.builder(
            Component.literal("Sync Mods"),
            btn -> onSyncClicked(multiplayerScreen)
        ).pos(x, y).size(100, 20).build();

        syncButton.active = false; // disabled until a server is selected

        event.addListener(syncButton);
        ModSync.LOGGER.debug("[ModSync] Added 'Sync Mods' button to multiplayer screen");
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen multiplayerScreen)) return;
        if (syncButton == null) return;

        // Enable button only when a server row is selected
        ServerData selected = getSelectedServer(multiplayerScreen);
        syncButton.active = (selected != null);
    }

    // -------------------------------------------------------------------------

    private static void onSyncClicked(JoinMultiplayerScreen screen) {
        ServerData serverData = getSelectedServer(screen);
        if (serverData == null) {
            ModSync.LOGGER.warn("[ModSync] Sync clicked but no server selected");
            return;
        }

        ModSync.LOGGER.info("[ModSync] Manual sync requested for server: {}", serverData.ip);

        // Open PreSyncScreen — when done it either restarts or connects normally
        Minecraft.getInstance().setScreen(new PreSyncScreen(serverData, screen));
    }

    /**
     * Extracts the currently selected ServerData from JoinMultiplayerScreen
     * by finding the ServerSelectionList field via reflection, then the
     * ServerData field inside the selected entry.
     */
    static ServerData getSelectedServer(JoinMultiplayerScreen screen) {
        try {
            // Find the ServerSelectionList field in JoinMultiplayerScreen
            ServerSelectionList list = findField(screen, ServerSelectionList.class);
            if (list == null) return null;

            ServerSelectionList.Entry selected = list.getSelected();
            if (selected == null) return null;

            // ServerData is in ServerSelectionList.NormalEntry
            return findField(selected, ServerData.class);

        } catch (Exception e) {
            ModSync.LOGGER.error("[ModSync] Could not read selected server: {}", e.getMessage());
            return null;
        }
    }

    /** Scans all declared fields (including superclasses) for the first field of the given type. */
    @SuppressWarnings("unchecked")
    private static <T> T findField(Object obj, Class<T> type) throws IllegalAccessException {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                if (type.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (T) field.get(obj);
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}
