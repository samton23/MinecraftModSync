package com.samton23.modsync.server;

/**
 * Represents a single mod entry in the server modpack manifest.
 */
public record ModManifestEntry(String filename, String md5, long size) {

    @Override
    public String toString() {
        return "ModManifestEntry{filename='" + filename + "', md5='" + md5 + "', size=" + size + "}";
    }
}
