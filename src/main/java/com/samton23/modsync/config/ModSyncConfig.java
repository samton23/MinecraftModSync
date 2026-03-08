package com.samton23.modsync.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModSyncConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue HTTP_PORT;
    public static final ForgeConfigSpec.BooleanValue ENABLED;

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

        builder.pop();
        SPEC = builder.build();
    }
}
