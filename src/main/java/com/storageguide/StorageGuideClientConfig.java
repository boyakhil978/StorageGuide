package com.storageguide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StorageGuideClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("storageguide-client.json");

    boolean debugMode = false;

    public static StorageGuideClientConfig load() {
        if (!Files.exists(PATH)) {
            StorageGuideClientConfig config = new StorageGuideClientConfig();
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(PATH)) {
            StorageGuideClientConfig loaded = GSON.fromJson(reader, StorageGuideClientConfig.class);
            return loaded == null ? new StorageGuideClientConfig() : loaded;
        } catch (IOException | RuntimeException ex) {
            return new StorageGuideClientConfig();
        }
    }

    public boolean debugMode() {
        return debugMode;
    }

    private void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save StorageGuide client config", ex);
        }
    }
}
