package com.storageguide;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.nio.file.Path;
import java.util.Optional;

public final class StorageGuideServer {
    private static StorageGuideConfig config = new StorageGuideConfig();
    private static Path configPath;
    private static MinecraftServer activeServer;

    private StorageGuideServer() {
    }

    public static void load(MinecraftServer server) {
        activeServer = server;
        configPath = server.getFile("config").resolve("storageguide.json");
        config = StorageGuideConfig.load(configPath);
    }

    public static void clear() {
        config = new StorageGuideConfig();
        configPath = null;
        activeServer = null;
    }

    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_STATE, (payload, context) ->
                context.server().execute(() -> sendState(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.BEGIN_SELECT, (payload, context) ->
                context.server().execute(() -> beginSelect(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.SET_GRID, (payload, context) ->
                context.server().execute(() -> setGrid(context.player(), payload.first(), payload.second())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.LOCATE_HELD, (payload, context) ->
                context.server().execute(() -> locate(context.player(), payload.itemId())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_FIND, (payload, context) ->
                context.server().execute(() -> sendFindMenu(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_EDIT_CELL, (payload, context) ->
                context.server().execute(() -> openEditor(context.player(), payload.pos())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.EDIT_CELL, (payload, context) ->
                context.server().execute(() -> editCell(context.player(), payload.cellId(), payload.itemIds())));
    }

    private static void beginSelect(ServerPlayer player) {
        if (!canEdit(player)) {
            message(player, "StorageGuide editing requires operator permission.");
            return;
        }

        if (!config.hasGrid()) {
            message(player, "Select two vertical wall corners with the StorageGuide select key.");
            sendState(player);
            return;
        }

        sendState(player);
        message(player, "StorageGuide grid highlighted. Look at a highlighted block and press the select key to edit it.");
    }

    private static void setGrid(ServerPlayer player, BlockPos first, BlockPos second) {
        if (!canEdit(player)) {
            message(player, "StorageGuide editing requires operator permission.");
            return;
        }

        try {
            config.rebuild(first, second);
            save();
            sendStateToAll();
            message(player, "StorageGuide server grid saved with " + config.cells.size() + " cells.");
        } catch (IllegalArgumentException ex) {
            message(player, "StorageGuide corners must share one vertical X/Y or Z/Y plane.");
        }
    }

    private static void locate(ServerPlayer player, String itemId) {
        String normalized = normalizeItemId(itemId);
        if (normalized.isBlank()) {
            message(player, "Hold an item to locate it.");
            return;
        }

        Optional<StorageGuideConfig.StorageCell> cell = config.findCellForItem(normalized);
        if (cell.isEmpty()) {
            ServerPlayNetworking.send(player, new StorageGuideNetworking.HighlightPayload(Optional.empty(), "StorageGuide has no cell for " + normalized + "."));
            return;
        }

        ServerPlayNetworking.send(player, new StorageGuideNetworking.HighlightPayload(Optional.of(cell.get().origin()), "StorageGuide locating " + normalized + "."));
    }

    private static void sendFindMenu(ServerPlayer player) {
        sendState(player);
        message(player, "StorageGuide find menu synced from the server.");
    }

    private static void editCell(ServerPlayer player, String cellId, java.util.List<String> itemIds) {
        if (!canEdit(player)) {
            message(player, "StorageGuide editing requires operator permission.");
            return;
        }

        Optional<StorageGuideConfig.StorageCell> cell = config.findCellById(cellId);
        if (cell.isEmpty()) {
            message(player, "StorageGuide cell no longer exists.");
            return;
        }

        java.util.List<String> normalized = itemIds == null ? java.util.List.of() : itemIds.stream()
                .map(StorageGuideServer::normalizeItemId)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();

        for (String itemId : normalized) {
            if (Identifier.tryParse(itemId) == null) {
                message(player, "StorageGuide item ids must look like emerald or minecraft:emerald.");
                return;
            }
            if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemId))) {
                message(player, "StorageGuide does not know item id " + itemId + ".");
                return;
            }
        }

        for (StorageGuideConfig.StorageCell existing : config.cells) {
            if (!existing.id().equals(cellId)) {
                java.util.List<String> remaining = existing.itemIds().stream()
                        .filter(existingItem -> !normalized.contains(existingItem))
                        .toList();
                existing.setItemIds(remaining);
            }
        }

        cell.get().setItemIds(normalized);
        save();
        sendStateToAll();
        message(player, normalized.isEmpty() ? "StorageGuide cell cleared." : "StorageGuide cell assigned " + normalized.size() + " item(s).");
    }

    public static void openEditor(ServerPlayer player, BlockPos pos) {
        if (!canEdit(player)) {
            message(player, "StorageGuide editing requires operator permission.");
            return;
        }

        Optional<StorageGuideConfig.StorageCell> cell = config.findCellContaining(pos);
        if (cell.isEmpty()) {
            message(player, "Look at a StorageGuide grid cell to edit it.");
            return;
        }

        StorageGuideConfig.StorageCell found = cell.get();
        ServerPlayNetworking.send(player, new StorageGuideNetworking.OpenEditorPayload(
                new StorageGuideNetworking.CellDto(found.id(), found.origin(), found.itemIds())
        ));
    }

    private static void sendState(ServerPlayer player) {
        ServerPlayNetworking.send(player, new StorageGuideNetworking.StatePayload(config.hasGrid(), canEdit(player), config.toDtos()));
    }

    private static void sendStateToAll() {
        if (activeServer == null) {
            return;
        }
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            sendState(player);
        }
    }

    private static String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }

        String trimmed = itemId.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static boolean canEdit(ServerPlayer player) {
        if (activeServer == null) {
            return false;
        }

        NameAndId nameAndId = new NameAndId(player.getGameProfile());
        return activeServer.isSingleplayerOwner(nameAndId)
                || activeServer.getPlayerList().isOp(nameAndId);
    }

    private static void message(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        ServerPlayNetworking.send(player, new StorageGuideNetworking.MessagePayload(message));
    }

    private static void save() {
        if (configPath != null) {
            config.save(configPath);
        }
    }
}
