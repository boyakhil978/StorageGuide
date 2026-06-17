package com.storageguide;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class StorageGuideMod implements ModInitializer {
    public static final String MOD_ID = "storageguide";

    @Override
    public void onInitialize() {
        StorageGuideNetworking.registerPayloads();
        StorageGuideServer.registerReceivers();
        ServerLifecycleEvents.SERVER_STARTING.register(StorageGuideServer::load);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> StorageGuideServer.clear());
    }
}
