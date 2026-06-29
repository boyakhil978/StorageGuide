package com.storageguide;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class StorageGuideMod implements ModInitializer {
    public static final String MOD_ID = "storageguide";
    public static final int PROTOCOL_VERSION = 4;

    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static int compareVersions(String left, String right) {
        int[] leftParts = versionParts(left);
        int[] rightParts = versionParts(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? leftParts[i] : 0;
            int rightPart = i < rightParts.length ? rightParts[i] : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private static int[] versionParts(String version) {
        if (version == null || version.isBlank() || "unknown".equalsIgnoreCase(version)) {
            return new int[0];
        }

        String[] rawParts = version.split("[^0-9]+");
        return java.util.Arrays.stream(rawParts)
                .filter(part -> !part.isBlank())
                .mapToInt(part -> {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException ex) {
                        return 0;
                    }
                })
                .toArray();
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
