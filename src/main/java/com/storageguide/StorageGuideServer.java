package com.storageguide;

import com.storageguide.mixin.CompoundContainerAccessor;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class StorageGuideServer {
    private static final long SLOPPINESS_COOLDOWN_MS = 30_000L;

    private static StorageGuideConfig config = new StorageGuideConfig();
    private static Path configPath;
    private static MinecraftServer activeServer;
    private static Map<BlockPos, StorageGuideConfig.StorageCell> cellsByPosition = Map.of();
    private static final Map<UUID, Long> lastPlayerSloppinessAnnouncement = new HashMap<>();
    private static final Map<BlockPos, Long> lastChestSloppinessAnnouncement = new HashMap<>();
    private static final Map<UUID, ClientVersion> clientVersions = new HashMap<>();

    private StorageGuideServer() {
    }

    public static void load(MinecraftServer server) {
        activeServer = server;
        configPath = server.getFile("config").resolve("storageguide.json");
        config = StorageGuideConfig.load(configPath);
        config.save(configPath);
        rebuildCaches();
    }

    public static void clear() {
        config = new StorageGuideConfig();
        configPath = null;
        activeServer = null;
        cellsByPosition = Map.of();
        lastPlayerSloppinessAnnouncement.clear();
        lastChestSloppinessAnnouncement.clear();
        clientVersions.clear();
    }

    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_STATE, (payload, context) ->
                context.server().execute(() -> sendState(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.BEGIN_SELECT, (payload, context) ->
                context.server().execute(() -> beginSelect(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.SET_GRID, (payload, context) ->
                context.server().execute(() -> setGrid(context.player(), payload.first(), payload.second())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.LOCATE_HELD, (payload, context) ->
                context.server().execute(() -> locateItem(context.player(), payload.itemId())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.LOCATE_HELD_V2, (payload, context) ->
                context.server().execute(() -> locateHeld(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.LOCATE_ITEM, (payload, context) ->
                context.server().execute(() -> locateItem(context.player(), payload.itemId())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.CLIENT_HELLO, (payload, context) ->
                context.server().execute(() -> receiveClientHello(
                        context.player(),
                        payload.version(),
                        payload.protocolVersion()
                )));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_FIND, (payload, context) ->
                context.server().execute(() -> sendFindMenu(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_EDIT_CELL, (payload, context) ->
                context.server().execute(() -> openEditor(context.player(), payload.pos())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.EDIT_CELL, (payload, context) ->
                context.server().execute(() -> editCell(context.player(), payload.cellId(), payload.itemIds())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                clientVersions.remove(handler.player.getUUID()));
    }

    private static void receiveClientHello(ServerPlayer player, String version, int protocolVersion) {
        clientVersions.put(player.getUUID(), new ClientVersion(version, protocolVersion));
        if (ServerPlayNetworking.canSend(player, StorageGuideNetworking.SERVER_HELLO)) {
            ServerPlayNetworking.send(player, new StorageGuideNetworking.ServerHelloPayload(
                    StorageGuideMod.version(),
                    StorageGuideMod.PROTOCOL_VERSION
            ));
        }
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
            rebuildCaches();
            sendStateToAll();
            message(player, "StorageGuide server grid saved with " + config.cells.size() + " cells.");
        } catch (IllegalArgumentException ex) {
            message(player, "StorageGuide corners must share one vertical X/Y or Z/Y plane.");
        }
    }

    private static void locateHeld(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            stack = player.getOffhandItem();
        }
        if (stack.isEmpty()) {
            message(player, "Hold an item to locate it.");
            return;
        }

        if (!isShulkerBox(stack)) {
            locateItem(player, itemId(stack));
            return;
        }

        List<ItemStack> contents = shulkerContents(stack);
        if (contents.isEmpty()) {
            locateItem(player, itemId(stack));
            return;
        }

        Optional<StorageGuideConfig.StorageCell> cell = findCellForShulkerContents(contents);
        if (cell.isEmpty()) {
            sendIfSupported(player, new StorageGuideNetworking.HighlightPayload(Optional.empty(), ""));
            message(player, "The shulker box has items incompatible with any chest configuration");
            return;
        }

        sendIfSupported(player, new StorageGuideNetworking.HighlightPayload(
                Optional.of(cell.get().origin()),
                "StorageGuide locating shulker box contents."
        ));
    }

    private static void locateItem(ServerPlayer player, String itemId) {
        String normalized = normalizeItemId(itemId);
        if (normalized.isBlank()) {
            message(player, "Hold an item to locate it.");
            return;
        }

        Optional<StorageGuideConfig.StorageCell> cell = config.findCellForItem(normalized);
        if (cell.isEmpty()) {
            sendIfSupported(player, new StorageGuideNetworking.HighlightPayload(Optional.empty(), "StorageGuide has no cell for " + normalized + "."));
            return;
        }

        sendIfSupported(player, new StorageGuideNetworking.HighlightPayload(Optional.of(cell.get().origin()), "StorageGuide locating " + normalized + "."));
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
        rebuildCaches();
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
        sendIfSupported(player, new StorageGuideNetworking.OpenEditorPayload(
                new StorageGuideNetworking.CellDto(found.id(), found.origin(), found.itemIds())
        ));
    }

    private static void sendState(ServerPlayer player) {
        sendIfSupported(player, new StorageGuideNetworking.StatePayload(config.hasGrid(), canEdit(player), config.toDtos()));
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
        sendIfSupported(player, new StorageGuideNetworking.MessagePayload(message));
    }

    private static void sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void save() {
        if (configPath != null) {
            config.save(configPath);
        }
    }

    public static boolean sloppinessDetectorEnabled() {
        return config.sloppinessDetector();
    }

    public static void setSloppinessDetector(boolean enabled) {
        config.setSloppinessDetector(enabled);
        save();
    }

    public static void afterContainerClick(AbstractContainerMenu menu, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(menu instanceof ChestMenu chestMenu)) {
            return;
        }
        if (!config.sloppinessDetector() || !config.hasGrid() || cellsByPosition.isEmpty()) {
            return;
        }

        Container container = chestMenu.getContainer();
        RelevantChest relevantChest = findRelevantChest(container).orElse(null);
        if (relevantChest == null || relevantChest.cell().itemIds().isEmpty()) {
            return;
        }

        Set<String> allowedItems = new HashSet<>(relevantChest.cell().itemIds());
        for (ItemStack stack : container) {
            if (stack.isEmpty()) {
                continue;
            }

            if (!isAllowedInCell(stack, relevantChest.cell(), allowedItems)) {
                announceSloppiness(serverPlayer, relevantChest.pos());
                return;
            }
        }
    }

    private static boolean isAllowedInCell(
            ItemStack stack,
            StorageGuideConfig.StorageCell cell,
            Set<String> allowedItems
    ) {
        if (!isShulkerBox(stack)) {
            return allowedItems.contains(itemId(stack));
        }

        List<ItemStack> contents = shulkerContents(stack);
        if (contents.isEmpty()) {
            return allowedItems.contains(itemId(stack));
        }

        return findCellForShulkerContents(contents)
                .map(found -> found.id().equals(cell.id()))
                .orElse(false);
    }

    private static Optional<StorageGuideConfig.StorageCell> findCellForShulkerContents(List<ItemStack> contents) {
        StorageGuideConfig.StorageCell target = null;
        for (ItemStack containedStack : contents) {
            Optional<StorageGuideConfig.StorageCell> cell = config.findCellForItem(itemId(containedStack));
            if (cell.isEmpty()) {
                return Optional.empty();
            }
            if (target == null) {
                target = cell.get();
            } else if (!target.id().equals(cell.get().id())) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(target);
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static List<ItemStack> shulkerContents(ItemStack stack) {
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return contents.nonEmptyItemCopyStream().toList();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Optional<RelevantChest> findRelevantChest(Container container) {
        for (ChestBlockEntity chest : chestBlockEntities(container)) {
            StorageGuideConfig.StorageCell cell = cellsByPosition.get(chest.getBlockPos());
            if (cell != null) {
                return Optional.of(new RelevantChest(chest.getBlockPos(), cell));
            }
        }
        return Optional.empty();
    }

    private static List<ChestBlockEntity> chestBlockEntities(Container container) {
        List<ChestBlockEntity> chests = new ArrayList<>();
        if (container instanceof ChestBlockEntity chest) {
            chests.add(chest);
        } else if (container instanceof CompoundContainer compound) {
            CompoundContainerAccessor accessor = (CompoundContainerAccessor) compound;
            chests.addAll(chestBlockEntities(accessor.storageguide$container1()));
            chests.addAll(chestBlockEntities(accessor.storageguide$container2()));
        }
        return chests;
    }

    private static void announceSloppiness(ServerPlayer player, BlockPos chestPos) {
        long now = System.currentTimeMillis();
        Long playerLast = lastPlayerSloppinessAnnouncement.get(player.getUUID());
        Long chestLast = lastChestSloppinessAnnouncement.get(chestPos);
        if ((playerLast != null && now - playerLast < SLOPPINESS_COOLDOWN_MS)
                || (chestLast != null && now - chestLast < SLOPPINESS_COOLDOWN_MS)) {
            return;
        }

        lastPlayerSloppinessAnnouncement.put(player.getUUID(), now);
        lastChestSloppinessAnnouncement.put(chestPos, now);
        if (activeServer != null) {
            activeServer.getPlayerList().broadcastSystemMessage(Component.literal("Sloppiness detected " + player.getScoreboardName() + "!"), false);
        }
    }

    private static void rebuildCaches() {
        Map<BlockPos, StorageGuideConfig.StorageCell> cells = new HashMap<>();
        for (StorageGuideConfig.StorageCell cell : config.cells) {
            cells.put(cell.origin(), cell);
        }
        cellsByPosition = Map.copyOf(cells);
    }

    private record RelevantChest(BlockPos pos, StorageGuideConfig.StorageCell cell) {
    }

    private record ClientVersion(String version, int protocolVersion) {
    }
}
