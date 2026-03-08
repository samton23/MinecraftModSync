package com.samton23.modsync.server;

import com.google.gson.Gson;
import com.samton23.modsync.ModSync;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP server that serves the mod manifest and mod files.
 *
 * Endpoints:
 *   GET /manifest        -> JSON array of ModManifestEntry
 *   GET /mods/{filename} -> raw JAR bytes
 */
public class ModpackHttpServer {

    private static final Gson GSON = new Gson();

    private final ModpackManager manager;
    private final int port;
    private HttpServer server;

    public ModpackHttpServer(ModpackManager manager, int port) {
        this.manager = manager;
        this.port = port;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/manifest", this::handleManifest);
            server.createContext("/mods/", this::handleModFile);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException e) {
            ModSync.LOGGER.error("[ModSync] Failed to start HTTP server on port {}: {}", port, e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        // Refresh manifest on each request so admin can hot-reload
        manager.refresh();
        String json = GSON.toJson(manager.getManifest());
        byte[] bytes = json.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleModFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        // Extract filename from /mods/{filename}
        String path = exchange.getRequestURI().getPath();
        String filename = path.substring("/mods/".length());

        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\")) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        File file = manager.getModFile(filename);
        if (file == null) {
            ModSync.LOGGER.warn("[ModSync] Requested mod not found: {}", filename);
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.sendResponseHeaders(200, file.length());
        try (OutputStream os = exchange.getResponseBody()) {
            Files.copy(file.toPath(), os);
        }
        ModSync.LOGGER.debug("[ModSync] Served mod file: {}", filename);
    }
}
