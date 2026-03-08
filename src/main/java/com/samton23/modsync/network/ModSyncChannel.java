package com.samton23.modsync.network;

import com.samton23.modsync.ModSync;
import com.samton23.modsync.client.ClientEventHandler;
import com.samton23.modsync.network.packets.ModManifestPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class ModSyncChannel {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ModSync.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(
            nextId++,
            ModManifestPacket.class,
            ModManifestPacket::encode,
            ModManifestPacket::decode,
            (packet, contextSupplier) -> {
                var ctx = contextSupplier.get();
                ctx.enqueueWork(() -> ClientEventHandler.handleManifest(packet, ctx));
                ctx.setPacketHandled(true);
            },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendToPlayer(ModManifestPacket packet, net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), packet);
        }
    }
}
