package com.samton23.modsync.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModSyncConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue HTTP_PORT;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue DISABLE_EXTRA_MODS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("ModSync Configuration").push("general");

        ENABLED = builder
            .comment("Enable or disable the ModSync system entirely")
            .define("enabled", true);

        HTTP_PORT = builder
            .comment("HTTP port used to serve mod files to clients",
                     "Make sure this port is open in your firewall/router")
            .defineInRange("httpPort", 8765, 1024, 65535);

        DISABLE_EXTRA_MODS = builder
            .comment(
                "If true, client mods NOT present in clientmodpack/ will be renamed to .jar.disabled.",
                "Mods marked as client-only (displayTest=IGNORE_SERVER_VERSION in their mods.toml) are ALWAYS kept.",
                "Default: false — clients may have extra mods freely (recommended for most servers)."
            )
            .define("disableExtraMods", false);

        builder.pop();
        SPEC = builder.build();
    }
}
