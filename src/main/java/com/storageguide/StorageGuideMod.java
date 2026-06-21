package com.storageguide;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class StorageGuideMod implements ModInitializer {
    public static final String MOD_ID = "storageguide";
    public static final int PROTOCOL_VERSION = 2;

    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public void onInitialize() {
        StorageGuideNetworking.registerPayloads();
        StorageGuideCommands.register();
        StorageGuideServer.registerReceivers();
        ServerLifecycleEvents.SERVER_STARTING.register(StorageGuideServer::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> StorageGuideServer.clear());
    }
}
