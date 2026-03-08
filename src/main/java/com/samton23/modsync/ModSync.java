package com.samton23.modsync;

import com.mojang.logging.LogUtils;
import com.samton23.modsync.config.ModSyncConfig;
import com.samton23.modsync.network.ModSyncChannel;
import com.samton23.modsync.server.ModpackHttpServer;
import com.samton23.modsync.server.ModpackManager;
import com.samton23.modsync.server.ServerEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ModSync.MOD_ID)
public class ModSync {

    public static final String MOD_ID = "modsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    private ModpackHttpServer httpServer;

    public ModSync() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ModSyncConfig.SPEC, "modsync-common.toml");

        ModSyncChannel.register();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[ModSync] Server starting, initializing modpack manager...");
        ModpackManager.INSTANCE.init(event.getServer());

        int port = ModSyncConfig.HTTP_PORT.get();
        httpServer = new ModpackHttpServer(ModpackManager.INSTANCE, port);
        httpServer.start();
        LOGGER.info("[ModSync] HTTP server started on port {}", port);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (httpServer != null) {
            httpServer.stop();
            LOGGER.info("[ModSync] HTTP server stopped.");
        }
    }
}
