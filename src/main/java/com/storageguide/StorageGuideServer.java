package com.storageguide;

import com.storageguide.mixin.CompoundContainerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
    private static final long CLIENT_HANDSHAKE_GRACE_MS = 5_000L;

    private static StorageGuideConfig config = new StorageGuideConfig();
    private static Path configPath;
    private static MinecraftServer activeServer;
    private static Map<BlockPos, StorageGuideConfig.StorageCell> cellsByPosition = Map.of();
    private static final Map<UUID, Long> lastPlayerSloppinessAnnouncement = new HashMap<>();
    private static final Map<BlockPos, Long> lastChestSloppinessAnnouncement = new HashMap<>();
    private static final Map<UUID, ClientVersion> clientVersions = new HashMap<>();
    private static final Map<UUID, Long> clientHandshakeDeadlines = new HashMap<>();

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
        clientHandshakeDeadlines.clear();
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
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_OPERATOR_SETTINGS, (payload, context) ->
                context.server().execute(() -> openOperatorSettings(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS, (payload, context) ->
                context.server().execute(() -> updateOperatorSettings(context.player(), payload.sloppinessDetector())));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS_V2, (payload, context) ->
                context.server().execute(() -> updateOperatorSettings(
                        context.player(),
                        payload.sloppinessDetector(),
                        payload.forceClientsToUseMod(),
                        payload.sloppinessCooldownSeconds()
                )));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.EDIT_CELL_V2, (payload, context) ->
                context.server().execute(() -> editCell(
                        context.player(),
                        payload.cellId(),
                        payload.itemIds(),
                        payload.sloppinessExcluded()
                )));
        ServerPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.REQUEST_SLOPPINESS_HISTORY, (payload, context) ->
                context.server().execute(() -> sendSloppinessHistory(context.player())));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (config.forceClientsToUseMod()) {
                clientHandshakeDeadlines.put(handler.player.getUUID(), System.currentTimeMillis() + CLIENT_HANDSHAKE_GRACE_MS);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                clearClientSession(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> enforceRequiredClients());
    }

    private static void receiveClientHello(ServerPlayer player, String version, int protocolVersion) {
        clientVersions.put(player.getUUID(), new ClientVersion(version, protocolVersion));
        clientHandshakeDeadlines.remove(player.getUUID());
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
    }

    private static void editCell(ServerPlayer player, String cellId, java.util.List<String> itemIds) {
        editCell(player, cellId, itemIds, null);
    }

    private static void editCell(
            ServerPlayer player,
            String cellId,
            java.util.List<String> itemIds,
            Boolean sloppinessExcluded
    ) {
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
        if (sloppinessExcluded != null) {
            cell.get().setSloppinessExcluded(sloppinessExcluded);
        }
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
        StorageGuideNetworking.CellDto dto =
                new StorageGuideNetworking.CellDto(found.id(), found.origin(), found.itemIds());
        if (ServerPlayNetworking.canSend(player, StorageGuideNetworking.OPEN_EDITOR_V2)) {
            ServerPlayNetworking.send(player, new StorageGuideNetworking.OpenEditorV2Payload(
                    dto,
                    found.sloppinessExcluded()
            ));
        } else {
            sendIfSupported(player, new StorageGuideNetworking.OpenEditorPayload(dto));
        }
    }

    public static void openOperatorSettings(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, StorageGuideNetworking.OPEN_OPERATOR_SETTINGS)) {
            message(player, "Your StorageGuide client does not support the operator settings menu.");
            return;
        }

        sendOperatorSettings(
                player,
                canEdit(player),
                canEdit(player) ? "" : "Operator permission is required to change server settings."
        );
    }

    private static void updateOperatorSettings(ServerPlayer player, boolean sloppinessDetector) {
        updateOperatorSettings(
                player,
                sloppinessDetector,
                config.forceClientsToUseMod(),
                config.sloppinessCooldownSeconds()
        );
    }

    private static void updateOperatorSettings(
            ServerPlayer player,
            boolean sloppinessDetector,
            boolean forceClientsToUseMod,
            int sloppinessCooldownSeconds
    ) {
        if (!canEdit(player)) {
            sendOperatorSettings(player, false, "Operator permission is required to change server settings.");
            return;
        }

        config.setSloppinessDetector(sloppinessDetector);
        config.setForceClientsToUseMod(forceClientsToUseMod);
        config.setSloppinessCooldownSeconds(sloppinessCooldownSeconds);
        save();
        refreshClientEnforcement();
        sendOperatorSettings(player, true, "Operator settings saved.");
    }

    private static void sendOperatorSettings(ServerPlayer player, boolean canEdit, String statusMessage) {
        if (ServerPlayNetworking.canSend(player, StorageGuideNetworking.OPEN_OPERATOR_SETTINGS_V2)) {
            ServerPlayNetworking.send(player, new StorageGuideNetworking.OpenOperatorSettingsV2Payload(
                    canEdit,
                    config.sloppinessDetector(),
                    config.forceClientsToUseMod(),
                    config.sloppinessCooldownSeconds(),
                    statusMessage
            ));
        } else if (ServerPlayNetworking.canSend(player, StorageGuideNetworking.OPEN_OPERATOR_SETTINGS)) {
            ServerPlayNetworking.send(player, new StorageGuideNetworking.OpenOperatorSettingsPayload(
                    canEdit,
                    config.sloppinessDetector(),
                    statusMessage
            ));
        }
    }

    public static void sendSloppinessHistory(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, StorageGuideNetworking.OPEN_SLOPPINESS_HISTORY)) {
            message(player, "Your StorageGuide client does not support the sloppiness history menu.");
            return;
        }

        List<StorageGuideNetworking.SloppinessHistoryDto> entries = config.sloppinessHistory().stream()
                .sorted(java.util.Comparator
                        .comparing(StorageGuideConfig.SloppinessRecord::playerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StorageGuideConfig.SloppinessRecord::timestamp, java.util.Comparator.reverseOrder()))
                .map(record -> new StorageGuideNetworking.SloppinessHistoryDto(
                        record.playerName(),
                        record.timestamp(),
                        record.itemId(),
                        record.cellId(),
                        record.chestPos().toBlockPos()
                ))
                .toList();
        ServerPlayNetworking.send(player, new StorageGuideNetworking.OpenSloppinessHistoryPayload(entries));
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

    public static ContainerSnapshot captureContainerState(AbstractContainerMenu menu, Player player) {
        if (!(player instanceof ServerPlayer) || !(menu instanceof ChestMenu chestMenu)) {
            return null;
        }
        if (!config.sloppinessDetector() || !config.hasGrid() || cellsByPosition.isEmpty()) {
            return null;
        }

        return new ContainerSnapshot(copyContainerItems(chestMenu.getContainer()));
    }

    public static void afterContainerClick(AbstractContainerMenu menu, Player player, ContainerSnapshot before) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(menu instanceof ChestMenu chestMenu)) {
            return;
        }
        if (!config.sloppinessDetector() || !config.hasGrid() || cellsByPosition.isEmpty()) {
            return;
        }

        Container container = chestMenu.getContainer();
        List<RelevantChest> relevantChests = findRelevantChests(container);
        if (relevantChests.isEmpty()) {
            return;
        }

        List<ItemStack> beforeItems = before == null ? List.of() : before.items();
        List<ItemStack> afterItems = copyContainerItems(container);
        Optional<ItemStack> incompatible = findNewIncompatibleItem(beforeItems, afterItems, relevantChests);
        if (incompatible.isPresent()) {
            RelevantChest chest = relevantChests.getFirst();
            recordSloppiness(serverPlayer, chest, incompatible.get());
            announceSloppiness(serverPlayer, chest.pos());
        }
    }

    private static Optional<ItemStack> findNewIncompatibleItem(
            List<ItemStack> beforeItems,
            List<ItemStack> afterItems,
            List<RelevantChest> relevantChests
    ) {
        List<ItemStack> checked = new ArrayList<>();
        for (ItemStack stack : afterItems) {
            if (stack.isEmpty() || isAllowedInChests(stack, relevantChests) || containsMatchingStack(checked, stack)) {
                continue;
            }

            checked.add(stack);
            if (countMatchingItems(afterItems, stack) > countMatchingItems(beforeItems, stack)) {
                return Optional.of(stack.copy());
            }
        }
        return Optional.empty();
    }

    private static boolean isAllowedInChests(ItemStack stack, List<RelevantChest> relevantChests) {
        Optional<StorageGuideConfig.StorageCell> targetCell;
        if (isShulkerBox(stack)) {
            List<ItemStack> contents = shulkerContents(stack);
            targetCell = contents.isEmpty()
                    ? config.findCellForItem(itemId(stack))
                    : findCellForShulkerContents(contents);
        } else {
            targetCell = config.findCellForItem(itemId(stack));
        }

        return targetCell
                .map(target -> relevantChests.stream().anyMatch(chest -> chest.cell().id().equals(target.id())))
                .orElse(false);
    }

    private static int countMatchingItems(List<ItemStack> items, ItemStack target) {
        return items.stream()
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, target))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static boolean containsMatchingStack(List<ItemStack> items, ItemStack target) {
        return items.stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, target));
    }

    private static List<ItemStack> copyContainerItems(Container container) {
        List<ItemStack> items = new ArrayList<>(container.getContainerSize());
        for (ItemStack stack : container) {
            items.add(stack.copy());
        }
        return List.copyOf(items);
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

    private static List<RelevantChest> findRelevantChests(Container container) {
        List<RelevantChest> relevant = new ArrayList<>();
        Set<String> seenCellIds = new HashSet<>();
        for (ChestBlockEntity chest : chestBlockEntities(container)) {
            StorageGuideConfig.StorageCell cell = cellsByPosition.get(chest.getBlockPos());
            if (cell != null && cell.sloppinessExcluded()) {
                return List.of();
            }
            if (cell != null && seenCellIds.add(cell.id())) {
                relevant.add(new RelevantChest(chest.getBlockPos(), cell));
            }
        }
        return List.copyOf(relevant);
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
        long cooldownMs = config.sloppinessCooldownSeconds() * 1_000L;
        Long playerLast = lastPlayerSloppinessAnnouncement.get(player.getUUID());
        Long chestLast = lastChestSloppinessAnnouncement.get(chestPos);
        if ((playerLast != null && now - playerLast < cooldownMs)
                || (chestLast != null && now - chestLast < cooldownMs)) {
            return;
        }

        lastPlayerSloppinessAnnouncement.put(player.getUUID(), now);
        lastChestSloppinessAnnouncement.put(chestPos, now);
        if (activeServer != null) {
            activeServer.getPlayerList().broadcastSystemMessage(Component.literal("Sloppiness detected " + player.getScoreboardName() + "!"), false);
        }
    }

    private static void recordSloppiness(ServerPlayer player, RelevantChest chest, ItemStack stack) {
        config.addSloppinessRecord(StorageGuideConfig.SloppinessRecord.create(
                player.getUUID(),
                player.getScoreboardName(),
                System.currentTimeMillis(),
                itemId(stack),
                chest.cell().id(),
                chest.pos()
        ));
        save();
    }

    private static void clearClientSession(UUID playerId) {
        clientVersions.remove(playerId);
        clientHandshakeDeadlines.remove(playerId);
    }

    private static void refreshClientEnforcement() {
        clientHandshakeDeadlines.clear();
        if (!config.forceClientsToUseMod() || activeServer == null) {
            return;
        }

        long deadline = System.currentTimeMillis() + CLIENT_HANDSHAKE_GRACE_MS;
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            if (!clientVersions.containsKey(player.getUUID())) {
                clientHandshakeDeadlines.put(player.getUUID(), deadline);
            }
        }
    }

    private static void enforceRequiredClients() {
        if (!config.forceClientsToUseMod() || activeServer == null || clientHandshakeDeadlines.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<UUID> expired = clientHandshakeDeadlines.entrySet().stream()
                .filter(entry -> now >= entry.getValue())
                .map(Map.Entry::getKey)
                .toList();
        for (UUID playerId : expired) {
            ServerPlayer player = activeServer.getPlayerList().getPlayer(playerId);
            if (player != null && !clientVersions.containsKey(playerId)) {
                player.connection.disconnect(Component.literal(
                        "This server requires the StorageGuide mod. Install a compatible StorageGuide client to join."
                ));
            }
            clientHandshakeDeadlines.remove(playerId);
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

    public record ContainerSnapshot(List<ItemStack> items) {
    }
}
