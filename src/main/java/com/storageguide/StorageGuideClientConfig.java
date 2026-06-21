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
    public static final int DEFAULT_HIGHLIGHT_COLOR = 0xFFD11A;
    public static final int DEFAULT_FOUND_HOTBAR_COLOR = 0x55FF55;
    public static final int DEFAULT_MISSING_HOTBAR_COLOR = 0xFF5555;

    boolean debugMode = false;
    boolean hotbarStatusEnabled = true;
    int highlightColor = DEFAULT_HIGHLIGHT_COLOR;
    int foundHotbarColor = DEFAULT_FOUND_HOTBAR_COLOR;
    int missingHotbarColor = DEFAULT_MISSING_HOTBAR_COLOR;

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

    public boolean hotbarStatusEnabled() {
        return hotbarStatusEnabled;
    }

    public int highlightColor() {
        return sanitizeColor(highlightColor);
    }

    public int foundHotbarColor() {
        return sanitizeColor(foundHotbarColor);
    }

    public int missingHotbarColor() {
        return sanitizeColor(missingHotbarColor);
    }

    public void update(boolean hotbarStatusEnabled, int highlightColor, int foundHotbarColor, int missingHotbarColor) {
        this.hotbarStatusEnabled = hotbarStatusEnabled;
        this.highlightColor = sanitizeColor(highlightColor);
        this.foundHotbarColor = sanitizeColor(foundHotbarColor);
        this.missingHotbarColor = sanitizeColor(missingHotbarColor);
        save();
    }

    public void reset() {
        update(true, DEFAULT_HIGHLIGHT_COLOR, DEFAULT_FOUND_HOTBAR_COLOR, DEFAULT_MISSING_HOTBAR_COLOR);
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save StorageGuide client config", ex);
        }
    }

    private static int sanitizeColor(int color) {
        return color & 0xFFFFFF;
    }
}
