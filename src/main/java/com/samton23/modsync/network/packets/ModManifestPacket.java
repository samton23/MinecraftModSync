package com.samton23.modsync.network.packets;

import com.samton23.modsync.server.ModManifestEntry;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet sent from server → client on login.
 * Contains the full list of mods in clientmodpack/ plus the HTTP port.
 */
public record ModManifestPacket(List<ModManifestEntry> entries, int httpPort) {

    public static void encode(ModManifestPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.httpPort());
        buf.writeInt(packet.entries().size());
        for (ModManifestEntry entry : packet.entries()) {
            buf.writeUtf(entry.filename(), 256);
            buf.writeUtf(entry.md5(), 64);
            buf.writeLong(entry.size());
        }
    }

    public static ModManifestPacket decode(FriendlyByteBuf buf) {
        int httpPort = buf.readInt();
        int count = buf.readInt();
        List<ModManifestEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String filename = buf.readUtf(256);
            String md5 = buf.readUtf(64);
            long size = buf.readLong();
            entries.add(new ModManifestEntry(filename, md5, size));
        }
        return new ModManifestPacket(entries, httpPort);
    }
}
