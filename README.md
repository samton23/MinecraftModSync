# ModSync — Minecraft Forge 1.20.1

A Forge mod that automatically synchronizes client mods with the server modpack.

## How it works

1. **Server**: Install the mod. Place your modpack JARs into the `clientmodpack/` folder (auto-created on first start).
2. **Client**: Install the mod. When connecting to the server, your mods folder is automatically synced:
   - Missing mods are downloaded from the server.
   - Outdated mods (different version) are replaced.
   - Extra mods are renamed to `.jar.disabled`.
3. A sync screen shows download progress. After sync, click **Restart Minecraft** to apply changes.

## Configuration

Config file: `config/modsync-common.toml`

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Enable/disable ModSync |
| `httpPort` | `8765` | Port for mod file serving. Must be open in firewall. |

## Requirements

- Minecraft Forge **1.20.1-47.2.0+**
- The mod must be installed on **both server and client**
- Port `8765` (or your configured port) must be accessible from clients

## Building

```bash
./gradlew build
```

Output JAR: `build/libs/modsync-1.0.0.jar`

## Security

- Path traversal attacks are blocked (files only served from `clientmodpack/`)
- Only mods from the currently connected server are downloaded
- The mod's own JAR is excluded from sync comparison

## License

MIT
