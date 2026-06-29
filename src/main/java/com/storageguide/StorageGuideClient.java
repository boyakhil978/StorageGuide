package com.storageguide;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class StorageGuideClient implements ClientModInitializer {
    private final KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(StorageGuideMod.MOD_ID, StorageGuideMod.MOD_ID));
    private KeyMapping selectOrEditKey;
    private KeyMapping locateHeldKey;
    private KeyMapping findMenuKey;
    private static KeyMapping selectOrEditMapping;
    private static KeyMapping locateHeldMapping;
    private static KeyMapping findMenuMapping;

    private static boolean hasGrid;
    private static boolean canEdit;
    private static boolean requestedInitialState;
    private static boolean sentClientHello;
    private static StorageGuideClientConfig clientConfig;
    private static String serverVersion = "legacy or unavailable";
    private static int serverProtocolVersion = 1;
    private static List<StorageGuideNetworking.CellDto> cells = List.of();
    private static boolean editOverlayActive;
    private static BlockPos firstSelectionCorner;
    private static BlockPos activeHighlight;
    private static long activeHighlightUntilMs;
    private static long hotbarStatusUntilMs;
    private static int hotbarStatusColor;
    private static int hotbarStatusSlot = -1;
    private static final long HOTBAR_STATUS_DURATION_MS = 2_500L;
    private static final long HOTBAR_STATUS_FADE_MS = 600L;

    @Override
    public void onInitializeClient() {
        clientConfig = StorageGuideClientConfig.load();
        this.selectOrEditKey = registerKey("select_or_edit", GLFW.GLFW_KEY_LEFT_BRACKET);
        this.locateHeldKey = registerKey("locate_held_item", GLFW.GLFW_KEY_GRAVE_ACCENT);
        this.findMenuKey = registerKey("find_menu", GLFW.GLFW_KEY_O);
        selectOrEditMapping = this.selectOrEditKey;
        locateHeldMapping = this.locateHeldKey;
        findMenuMapping = this.findMenuKey;

        registerReceivers();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(this::extractHighlights);
    }

    private KeyMapping registerKey(String name, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + StorageGuideMod.MOD_ID + "." + name,
                InputConstants.Type.KEYSYM,
                defaultKey,
                this.category
        ));
    }

    private static Screen currentScreen(Minecraft client) {
        return client.gui.screen();
    }

    private static void showScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    private static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.STATE, (payload, context) ->
                context.client().execute(() -> {
                    hasGrid = payload.hasGrid();
                    canEdit = payload.canEdit();
                    cells = List.copyOf(payload.cells());
                    if (!hasGrid) {
                        editOverlayActive = false;
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.HIGHLIGHT, (payload, context) ->
                context.client().execute(() -> {
                    payload.pos().ifPresentOrElse(pos -> {
                        activeHighlight = pos;
                        activeHighlightUntilMs = System.currentTimeMillis() + 8000L;
                    }, () -> activeHighlight = null);
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_CLIENT_SETTINGS, (payload, context) ->
                context.client().execute(() -> openClientSettings(currentScreen(context.client()))));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_EDITOR, (payload, context) ->
                context.client().execute(() -> showScreen(context.client(), new CellEditorScreen(payload.cell()))));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_EDITOR_V2, (payload, context) ->
                context.client().execute(() -> showScreen(context.client(),
                        new CellEditorScreen(payload.cell(), payload.sloppinessExcluded())
                )));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.CELL_EDIT_STATUS, (payload, context) ->
                context.client().execute(() -> {
                    if (currentScreen(context.client()) instanceof CellEditorScreen editor) {
                        editor.setStatus(payload.cellId(), payload.success(), payload.message());
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.MESSAGE, (payload, context) -> {
        });
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.SERVER_HELLO, (payload, context) ->
                context.client().execute(() -> {
                    serverVersion = payload.version();
                    serverProtocolVersion = payload.protocolVersion();
                    if (StorageGuideMod.compareVersions(StorageGuideMod.version(), serverVersion) < 0) {
                        message(
                                context.client(),
                                "Your StorageGuide client is older than this server (client "
                                        + StorageGuideMod.version()
                                        + ", server "
                                        + serverVersion
                                        + "). Please upgrade StorageGuide for the best compatibility."
                        );
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_OPERATOR_SETTINGS, (payload, context) ->
                context.client().execute(() -> {
                    Screen current = currentScreen(context.client());
                    Screen parent = current instanceof OperatorSettingsScreen settings ? settings.parent : current;
                    showScreen(context.client(), new OperatorSettingsScreen(
                            parent,
                            payload.canEdit(),
                            payload.sloppinessDetector(),
                            payload.statusMessage()
                    ));
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_OPERATOR_SETTINGS_V2, (payload, context) ->
                context.client().execute(() -> {
                    Screen current = currentScreen(context.client());
                    Screen parent = current instanceof OperatorSettingsScreen settings ? settings.parent : current;
                    showScreen(context.client(), new OperatorSettingsScreen(
                            parent,
                            payload.canEdit(),
                            payload.sloppinessDetector(),
                            payload.forceClientsToUseMod(),
                            payload.sloppinessCooldownSeconds(),
                            payload.statusMessage()
                    ));
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_OPERATOR_SETTINGS_V3, (payload, context) ->
                context.client().execute(() -> {
                    Screen current = currentScreen(context.client());
                    Screen parent = current instanceof OperatorSettingsScreen settings ? settings.parent : current;
                    showScreen(context.client(), new OperatorSettingsScreen(
                            parent,
                            payload.canEdit(),
                            payload.bigBrotherEnabled(),
                            payload.forceClientsToUseMod(),
                            payload.announcementCooldownSeconds(),
                            payload.bigBrotherMessages(),
                            payload.statusMessage()
                    ));
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_SLOPPINESS_HISTORY, (payload, context) ->
                context.client().execute(() -> showScreen(context.client(),
                        new SloppinessHistoryScreen(currentScreen(context.client()), payload.entries())
                )));
    }

    private void onClientTick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            resetSessionState();
            return;
        }

        Screen currentScreen = currentScreen(client);
        if (currentScreen != null && !(currentScreen instanceof CellEditorScreen) && !(currentScreen instanceof FindItemScreen)) {
            clearEditState();
        }

        if (!requestedInitialState) {
            requestedInitialState = true;
            if (canSend(StorageGuideNetworking.REQUEST_STATE)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.RequestStatePayload());
            }
        }

        if (!sentClientHello && canSend(StorageGuideNetworking.CLIENT_HELLO)) {
            sentClientHello = true;
            ClientPlayNetworking.send(new StorageGuideNetworking.ClientHelloPayload(
                    StorageGuideMod.version(),
                    StorageGuideMod.PROTOCOL_VERSION
            ));
        }

        while (this.selectOrEditKey.consumeClick()) {
            handleSelectOrEdit(client);
        }

        while (this.locateHeldKey.consumeClick()) {
            heldItemId(client).ifPresentOrElse(
                    itemId -> {
                        showHotbarStatus(client);
                        locateHeldItem(client, itemId);
                    },
                    () -> message(client, "Hold an item to locate it.")
            );
        }

        while (this.findMenuKey.consumeClick()) {
            if (canSend(StorageGuideNetworking.OPEN_FIND)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.OpenFindPayload());
            }
            showScreen(client, new FindItemScreen());
        }

        if (System.currentTimeMillis() > activeHighlightUntilMs) {
            activeHighlight = null;
        }
    }

    private static void handleSelectOrEdit(Minecraft client) {
        if (!canEdit) {
            if (canSend(StorageGuideNetworking.BEGIN_SELECT)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.BeginSelectPayload());
            } else {
                message(client, "StorageGuide is not available on this server.");
            }
            return;
        }

        if (!hasGrid) {
            Optional<BlockPos> lookedAt = lookedAtBlock(client);
            if (lookedAt.isEmpty()) {
                return;
            }

            if (firstSelectionCorner == null) {
                firstSelectionCorner = lookedAt.get();
                activeHighlight = firstSelectionCorner;
                activeHighlightUntilMs = System.currentTimeMillis() + 30000L;
                message(client, "StorageGuide first corner selected. Press the select key on the opposite vertical corner.");
                return;
            }

            if (canSend(StorageGuideNetworking.SET_GRID)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.SetGridPayload(firstSelectionCorner, lookedAt.get()));
            }
            firstSelectionCorner = null;
            return;
        }

        if (!editOverlayActive) {
            editOverlayActive = true;
            message(client, "StorageGuide grid highlighted. Look at a cell and press the select key again to edit.");
            return;
        }

        Optional<BlockPos> lookedAt = lookedAtGridCell(client);
        if (lookedAt.isEmpty()) {
            message(client, "StorageGuide grid highlighted. Look at a cell and press the select key again to edit.");
            return;
        }

        if (canSend(StorageGuideNetworking.REQUEST_EDIT_CELL)) {
            ClientPlayNetworking.send(new StorageGuideNetworking.RequestEditCellPayload(lookedAt.get()));
        }
    }

    private static Optional<BlockPos> lookedAtBlock(Minecraft client) {
        Optional<BlockPos> lookedAt = lookedAtBlockSilently(client);
        if (lookedAt.isPresent()) {
            return lookedAt;
        }

        message(client, "StorageGuide needs you to look at a block.");
        return Optional.empty();
    }

    private static Optional<BlockPos> lookedAtBlockSilently(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return Optional.of(blockHit.getBlockPos());
        }

        return Optional.empty();
    }

    private static Optional<BlockPos> lookedAtGridCell(Minecraft client) {
        if (client.player == null || cells.isEmpty()) {
            return Optional.empty();
        }

        Vec3 start = client.player.getEyePosition();
        Vec3 end = start.add(client.player.getLookAngle().scale(client.player.blockInteractionRange()));
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (StorageGuideNetworking.CellDto cell : cells) {
            Optional<Vec3> intersection = new AABB(cell.pos()).inflate(0.01).clip(start, end);
            if (intersection.isEmpty()) {
                continue;
            }

            double distance = start.distanceToSqr(intersection.get());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = cell.pos();
            }
        }

        return Optional.ofNullable(closest);
    }

    private static void locateHeldItem(Minecraft client, String itemId) {
        if (canSend(StorageGuideNetworking.LOCATE_HELD_V2)) {
            ClientPlayNetworking.send(new StorageGuideNetworking.LocateHeldV2Payload());
        } else if (canSend(StorageGuideNetworking.LOCATE_HELD)) {
            ClientPlayNetworking.send(new StorageGuideNetworking.LocateHeldPayload(itemId));
        } else {
            message(client, "StorageGuide is not available on this server.");
        }
    }

    private static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    private static Optional<String> heldItemId(Minecraft client) {
        if (client.player == null) {
            return Optional.empty();
        }

        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) {
            stack = client.player.getOffhandItem();
        }
        if (stack.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static void message(Minecraft client, String message) {
        if (client.player != null && message != null && !message.isBlank()) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    private static void resetSessionState() {
        requestedInitialState = false;
        sentClientHello = false;
        serverVersion = "legacy or unavailable";
        serverProtocolVersion = 1;
        hasGrid = false;
        canEdit = false;
        cells = List.of();
        clearEditState();
        activeHighlight = null;
        clearHotbarStatus();
    }

    private static void clearEditState() {
        editOverlayActive = false;
        firstSelectionCorner = null;
    }

    private void extractHighlights(LevelExtractionContext context, HitResult hitResult) {
        List<HighlightState> renderStates = new ArrayList<>();
        if (editOverlayActive && canEdit && hasGrid) {
            for (StorageGuideNetworking.CellDto cell : cells) {
                renderStates.add(new HighlightState(cell.pos(), 0.1F, 0.85F, 1.0F, 0.12F, 0.45F));
            }
            lookedAtGridCell(Minecraft.getInstance())
                    .ifPresent(pos -> renderStates.add(new HighlightState(pos, 1.0F, 0.25F, 0.1F, 0.35F, 0.95F)));
        }
        if (!hasGrid && firstSelectionCorner != null) {
            lookedAtBlockSilently(Minecraft.getInstance())
                    .ifPresent(pos -> renderStates.add(new HighlightState(pos, 0.1F, 1.0F, 0.35F, 0.28F, 0.9F)));
        }
        if (activeHighlight != null) {
            int color = clientConfig.highlightColor();
            renderStates.add(new HighlightState(
                    activeHighlight,
                    ((color >> 16) & 0xFF) / 255.0F,
                    ((color >> 8) & 0xFF) / 255.0F,
                    (color & 0xFF) / 255.0F,
                    0.3F,
                    1.0F
            ));
        }

        for (HighlightState state : renderStates) {
            BlockPos pos = state.pos();
            int stroke = ARGB.colorFromFloat(state.strokeAlpha(), state.r(), state.g(), state.b());
            int fill = ARGB.colorFromFloat(state.fillAlpha(), state.r(), state.g(), state.b());
            Gizmos.cuboid(
                    new AABB(pos).inflate(0.01),
                    GizmoStyle.strokeAndFill(stroke, 2.0F, fill)
            ).setAlwaysOnTop();
        }
    }

    private record HighlightState(BlockPos pos, float r, float g, float b, float fillAlpha, float strokeAlpha) {
    }

    private static void showHotbarStatus(Minecraft client) {
        if (clientConfig == null
                || !clientConfig.hotbarStatusEnabled()
                || client.player == null
                || client.player.getMainHandItem().isEmpty()) {
            clearHotbarStatus();
            return;
        }

        hotbarStatusColor = itemHasConfiguredDestination(client.player.getMainHandItem())
                ? clientConfig.foundHotbarColor()
                : clientConfig.missingHotbarColor();
        hotbarStatusSlot = client.player.getInventory().getSelectedSlot();
        hotbarStatusUntilMs = System.currentTimeMillis() + HOTBAR_STATUS_DURATION_MS;
    }

    public static int hotbarSelectionTint() {
        if (!hotbarStatusActive(Minecraft.getInstance())) {
            return 0xFFFFFFFF;
        }

        long remaining = hotbarStatusUntilMs - System.currentTimeMillis();
        int subtleStatusColor = ARGB.srgbLerp(
                0.72F,
                0xFFFFFFFF,
                ARGB.opaque(hotbarStatusColor)
        );
        if (remaining >= HOTBAR_STATUS_FADE_MS) {
            return subtleStatusColor;
        }

        float restoreProgress = 1.0F - Math.max(0.0F, remaining / (float) HOTBAR_STATUS_FADE_MS);
        return ARGB.srgbLerp(restoreProgress, subtleStatusColor, 0xFFFFFFFF);
    }

    private static boolean hotbarStatusActive(Minecraft client) {
        if (clientConfig == null
                || !clientConfig.hotbarStatusEnabled()
                || client.gui.hud.isHidden()
                || client.player == null
                || client.gameMode == null
                || client.player.isSpectator()
                || client.player.getInventory().getSelectedSlot() != hotbarStatusSlot
                || client.player.getMainHandItem().isEmpty()
                || System.currentTimeMillis() >= hotbarStatusUntilMs) {
            clearHotbarStatus();
            return false;
        }
        return true;
    }

    private static void clearHotbarStatus() {
        hotbarStatusUntilMs = 0L;
        hotbarStatusSlot = -1;
    }

    private static boolean itemHasConfiguredDestination(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!isShulkerBox(stack)) {
            return findCellIdForItem(stack).isPresent();
        }

        List<ItemStack> contents = shulkerContents(stack);
        if (contents.isEmpty()) {
            return findCellIdForItem(stack).isPresent();
        }

        String targetCell = null;
        for (ItemStack contained : contents) {
            Optional<String> cellId = findCellIdForItem(contained);
            if (cellId.isEmpty()) {
                return false;
            }
            if (targetCell == null) {
                targetCell = cellId.get();
            } else if (!targetCell.equals(cellId.get())) {
                return false;
            }
        }
        return targetCell != null;
    }

    private static Optional<String> findCellIdForItem(ItemStack stack) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return cells.stream()
                .filter(cell -> cell.itemIds().contains(itemId))
                .map(StorageGuideNetworking.CellDto::id)
                .findFirst();
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static List<ItemStack> shulkerContents(ItemStack stack) {
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return contents.nonEmptyItemCopyStream().toList();
    }

    public static Screen createConfigScreen(Screen parent) {
        return new ClientSettingsScreen(parent);
    }

    public static void openClientSettings(Screen parent) {
        showScreen(Minecraft.getInstance(), createConfigScreen(parent));
    }

    private static final class ClientSettingsScreen extends Screen {
        private static final int PANEL_MAX_HEIGHT = 330;
        private static final int PANEL_MARGIN = 18;
        private static final int SCROLL_STEP = 24;
        private static final int HEADER_HEIGHT = 34;
        private static final int PANEL_BOTTOM_PADDING = 8;
        private final Screen parent;
        private boolean hotbarStatusEnabled;
        private int highlightColor;
        private int foundHotbarColor;
        private int missingHotbarColor;
        private String operatorStatusMessage = "";
        private KeyMapping listeningForKey;
        private int scrollOffset;

        private ClientSettingsScreen(Screen parent) {
            super(Component.literal("StorageGuide Settings"));
            this.parent = parent;
            loadDraft();
        }

        private void loadDraft() {
            if (clientConfig == null) {
                clientConfig = StorageGuideClientConfig.load();
            }
            this.hotbarStatusEnabled = clientConfig.hotbarStatusEnabled();
            this.highlightColor = clientConfig.highlightColor();
            this.foundHotbarColor = clientConfig.foundHotbarColor();
            this.missingHotbarColor = clientConfig.missingHotbarColor();
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = panelTop();
            this.scrollOffset = Math.min(this.scrollOffset, maxScroll());

            addScrollableWidget(withTooltip(CycleButton.onOffBuilder(this.hotbarStatusEnabled)
                            .create(left + 12, contentY(34), panelWidth - 24, 20, Component.literal("Hotbar item status"),
                                    (button, enabled) -> this.hotbarStatusEnabled = enabled),
                    "Briefly recolors the selected hotbar frame after using Locate Held Item."));

            addScrollableWidget(colorButton(
                    left + 12,
                    contentY(62),
                    panelWidth - 24,
                    "Located chest highlight",
                    this.highlightColor,
                    StorageGuideClientConfig.DEFAULT_HIGHLIGHT_COLOR,
                    color -> this.highlightColor = color
            ));
            addScrollableWidget(colorButton(
                    left + 12,
                    contentY(90),
                    panelWidth - 24,
                    "Found item hotbar",
                    this.foundHotbarColor,
                    StorageGuideClientConfig.DEFAULT_FOUND_HOTBAR_COLOR,
                    color -> this.foundHotbarColor = color
            ));
            addScrollableWidget(colorButton(
                    left + 12,
                    contentY(118),
                    panelWidth - 24,
                    "Missing item hotbar",
                    this.missingHotbarColor,
                    StorageGuideClientConfig.DEFAULT_MISSING_HOTBAR_COLOR,
                    color -> this.missingHotbarColor = color
            ));

            addScrollableWidget(keyButton(
                    left + 12,
                    contentY(150),
                    panelWidth - 24,
                    "Select/Edit grid",
                    selectOrEditMapping,
                    "Change the key used to create a grid, show the edit overlay, and open a cell editor."
            ));
            addScrollableWidget(keyButton(
                    left + 12,
                    contentY(174),
                    panelWidth - 24,
                    "Locate held item",
                    locateHeldMapping,
                    "Change the key used to locate the item in your hand."
            ));
            addScrollableWidget(keyButton(
                    left + 12,
                    contentY(198),
                    panelWidth - 24,
                    "Open item finder",
                    findMenuMapping,
                    "Change the key used to open the live item finder."
            ));

            addScrollableWidget(withTooltip(Button.builder(Component.literal("Operator Settings"), button -> {
                if (canSend(StorageGuideNetworking.REQUEST_OPERATOR_SETTINGS)) {
                    this.operatorStatusMessage = canEdit
                            ? "Loading operator settings..."
                            : "Checking operator permission...";
                    ClientPlayNetworking.send(new StorageGuideNetworking.RequestOperatorSettingsPayload());
                } else {
                    this.operatorStatusMessage = "This server does not support the operator settings menu.";
                }
            }).bounds(left + 12, contentY(230), panelWidth - 24, 20).build(),
                    "Open server-owned settings such as Big Brother, cooldowns, templates, and client requirements."));

            addScrollableWidget(withTooltip(Button.builder(Component.literal("Big Brother History"), button -> {
                if (canSend(StorageGuideNetworking.REQUEST_SLOPPINESS_HISTORY)) {
                    ClientPlayNetworking.send(new StorageGuideNetworking.RequestSloppinessHistoryPayload());
                } else {
                    this.operatorStatusMessage = "This server does not support the Big Brother history menu.";
                }
            }).bounds(left + 12, contentY(254), panelWidth - 24, 20).build(),
                    "Open the public list of Big Brother events grouped by player."));

            int footerY = contentY(296);
            addScrollableWidget(withTooltip(Button.builder(Component.literal("Reset"), button -> {
                this.hotbarStatusEnabled = true;
                this.highlightColor = StorageGuideClientConfig.DEFAULT_HIGHLIGHT_COLOR;
                this.foundHotbarColor = StorageGuideClientConfig.DEFAULT_FOUND_HOTBAR_COLOR;
                this.missingHotbarColor = StorageGuideClientConfig.DEFAULT_MISSING_HOTBAR_COLOR;
                this.rebuildWidgets();
            }).bounds(left + 12, footerY, 76, 20).build(),
                    "Restore StorageGuide's default client colors and hotbar indicator setting."));
            addScrollableWidget(withTooltip(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center - 38, footerY, 76, 20).build(),
                    "Discard unsaved color and hotbar-status changes."));
            addScrollableWidget(withTooltip(Button.builder(Component.literal("Save"), button -> {
                clientConfig.update(
                        this.hotbarStatusEnabled,
                        this.highlightColor,
                        this.foundHotbarColor,
                        this.missingHotbarColor
                );
                this.onClose();
            }).bounds(left + panelWidth - 88, footerY, 76, 20).build(),
                    "Save client-only StorageGuide display settings."));
        }

        private Button colorButton(
                int x,
                int y,
                int width,
                String label,
                int color,
                int defaultColor,
                Consumer<Integer> setter
        ) {
            return withTooltip(Button.builder(colorLabel(label, color), button ->
                    showScreen(this.minecraft, new ColorPickerScreen(
                            this,
                            label,
                            color,
                            defaultColor,
                            selected -> {
                                setter.accept(selected);
                                this.rebuildWidgets();
                            }
                    ))
            ).bounds(x, y, width, 20).build(),
                    "Change " + label.toLowerCase(Locale.ROOT) + " color.");
        }

        private Button keyButton(int x, int y, int width, String label, KeyMapping mapping, String tooltip) {
            String key = mapping == null ? "Unavailable" : mapping.getTranslatedKeyMessage().getString();
            Component message = Component.literal((mapping == this.listeningForKey ? "Press a key for " : label + ": ") + key);
            Button button = Button.builder(message, clicked -> {
                this.listeningForKey = mapping;
                this.operatorStatusMessage = "Press a key or mouse button for " + label + ". Esc cancels; Backspace clears.";
                this.rebuildWidgets();
            }).bounds(x, y, width, 20).build();
            button.active = mapping != null;
            return withTooltip(button, tooltip);
        }

        private <T extends AbstractWidget> T addScrollableWidget(T widget) {
            boolean inViewport = isInScrollViewport(widget.getY(), widget.getHeight());
            widget.visible = inViewport;
            widget.active = widget.active && inViewport;
            return this.addRenderableWidget(widget);
        }

        private int panelHeight() {
            return Math.min(PANEL_MAX_HEIGHT, Math.max(220, this.height - PANEL_MARGIN * 2));
        }

        private int panelTop() {
            return Math.max(PANEL_MARGIN, (this.height - panelHeight()) / 2);
        }

        private int contentY(int originalOffset) {
            return panelTop() + originalOffset - this.scrollOffset;
        }

        private int scrollViewportTop() {
            return panelTop() + HEADER_HEIGHT;
        }

        private int scrollViewportBottom() {
            return panelTop() + panelHeight() - PANEL_BOTTOM_PADDING;
        }

        private boolean isInScrollViewport(int y, int height) {
            return y >= scrollViewportTop() && y + height <= scrollViewportBottom();
        }

        private int maxScroll() {
            return Math.max(0, PANEL_MAX_HEIGHT - panelHeight());
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            int maxScroll = maxScroll();
            if (maxScroll <= 0) {
                return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
            if (scrollY > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - SCROLL_STEP);
                this.rebuildWidgets();
                return true;
            }
            if (scrollY < 0) {
                this.scrollOffset = Math.min(maxScroll, this.scrollOffset + SCROLL_STEP);
                this.rebuildWidgets();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (this.listeningForKey != null) {
                if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                    this.listeningForKey = null;
                    this.operatorStatusMessage = "Keybind change cancelled.";
                    this.rebuildWidgets();
                    return true;
                }

                InputConstants.Key key = event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE
                        ? InputConstants.UNKNOWN
                        : InputConstants.getKey(event);
                applyKeybind(key);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (this.listeningForKey != null) {
                applyKeybind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        private void applyKeybind(InputConstants.Key key) {
            this.listeningForKey.setKey(key);
            KeyMapping.resetMapping();
            if (this.minecraft != null) {
                this.minecraft.options.save();
            }
            this.operatorStatusMessage = "Keybind saved as " + this.listeningForKey.getTranslatedKeyMessage().getString() + ".";
            this.listeningForKey = null;
            this.rebuildWidgets();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = panelTop();
            int panelHeight = panelHeight();
            drawPanel(graphics, left, top, panelWidth, panelHeight);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
            graphics.centeredText(
                    this.font,
                    Component.literal("Colors are saved per client."),
                    this.width / 2,
                    top + 21,
                    0xFFAAAAAA
            );
            int statusY = contentY(280);
            if (!this.operatorStatusMessage.isBlank() && isInScrollViewport(statusY, 9)) {
                graphics.centeredText(
                        this.font,
                        Component.literal(this.operatorStatusMessage),
                        this.width / 2,
                        statusY,
                        0xFFFFAA55
                );
            }
            drawScrollbar(graphics, left + panelWidth - 8, scrollViewportTop(), scrollViewportBottom() - scrollViewportTop(), PANEL_MAX_HEIGHT, panelHeight, this.scrollOffset);
        }

        @Override
        public void onClose() {
            showScreen(this.minecraft, this.parent);
        }
    }

    private static final class ColorPickerScreen extends Screen {
        private final Screen parent;
        private final String label;
        private final int defaultColor;
        private final Consumer<Integer> onSave;
        private int color;

        private ColorPickerScreen(Screen parent, String label, int color, int defaultColor, Consumer<Integer> onSave) {
            super(Component.literal(label + " Color"));
            this.parent = parent;
            this.label = label;
            this.color = color & 0xFFFFFF;
            this.defaultColor = defaultColor & 0xFFFFFF;
            this.onSave = onSave;
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int top = Math.max(48, this.height / 2 - 88);

            this.addRenderableWidget(withTooltip(new ColorSlider(
                    center - 100, top, 200, "Red", red(this.color),
                    value -> this.color = (this.color & 0x00FFFF) | (value << 16)
            ), "Adjust the red channel for this StorageGuide color."));
            this.addRenderableWidget(withTooltip(new ColorSlider(
                    center - 100, top + 28, 200, "Green", green(this.color),
                    value -> this.color = (this.color & 0xFF00FF) | (value << 8)
            ), "Adjust the green channel for this StorageGuide color."));
            this.addRenderableWidget(withTooltip(new ColorSlider(
                    center - 100, top + 56, 200, "Blue", blue(this.color),
                    value -> this.color = (this.color & 0xFFFF00) | value
            ), "Adjust the blue channel for this StorageGuide color."));

            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Default"), button -> {
                this.color = this.defaultColor;
                this.rebuildWidgets();
            }).bounds(center - 100, top + 126, 62, 20).build(), "Restore this color to the StorageGuide default."));
            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center - 31, top + 126, 62, 20).build(), "Return without applying this color."));
            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Apply"), button -> {
                this.onSave.accept(this.color);
                this.onClose();
            }).bounds(center + 38, top + 126, 62, 20).build(), "Apply this color to the previous settings screen."));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            int center = this.width / 2;
            int top = Math.max(48, this.height / 2 - 88);
            graphics.centeredText(this.font, Component.literal(this.label), center, 22, 0xFFFFFFFF);
            graphics.fill(center - 100, top + 92, center + 100, top + 116, ARGB.opaque(this.color));
            graphics.outline(center - 100, top + 92, 200, 24, 0xFFFFFFFF);
            graphics.centeredText(this.font, Component.literal(hexColor(this.color)), center, top + 100, contrastText(this.color));
        }

        @Override
        public void onClose() {
            showScreen(this.minecraft, this.parent);
        }
    }

    private static final class ColorSlider extends AbstractSliderButton {
        private final String label;
        private final IntConsumer setter;

        private ColorSlider(int x, int y, int width, String label, int channel, IntConsumer setter) {
            super(x, y, width, 20, Component.empty(), channel / 255.0);
            this.label = label;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.label + ": " + channel()));
        }

        @Override
        protected void applyValue() {
            this.setter.accept(channel());
            updateMessage();
        }

        private int channel() {
            return Math.clamp((int) Math.round(this.value * 255.0), 0, 255);
        }
    }

    private static final class OperatorSettingsScreen extends Screen {
        private final Screen parent;
        private final boolean canEditSettings;
        private final String statusMessage;
        private boolean sloppinessDetector;
        private boolean forceClientsToUseMod;
        private int sloppinessCooldownSeconds;
        private List<String> bigBrotherMessages;
        private EditBox cooldownBox;
        private EditBox messagesBox;

        private OperatorSettingsScreen(
                Screen parent,
                boolean canEditSettings,
                boolean sloppinessDetector,
                String statusMessage
        ) {
            this(parent, canEditSettings, sloppinessDetector, false, 30, statusMessage);
        }

        private OperatorSettingsScreen(
                Screen parent,
                boolean canEditSettings,
                boolean sloppinessDetector,
                boolean forceClientsToUseMod,
                int sloppinessCooldownSeconds,
                String statusMessage
        ) {
            this(
                    parent,
                    canEditSettings,
                    sloppinessDetector,
                    forceClientsToUseMod,
                    sloppinessCooldownSeconds,
                    List.of("Big Brother caught {playername} slacking off."),
                    statusMessage
            );
        }

        private OperatorSettingsScreen(
                Screen parent,
                boolean canEditSettings,
                boolean sloppinessDetector,
                boolean forceClientsToUseMod,
                int sloppinessCooldownSeconds,
                List<String> bigBrotherMessages,
                String statusMessage
        ) {
            super(Component.literal("StorageGuide Operator Settings"));
            this.parent = parent;
            this.canEditSettings = canEditSettings;
            this.sloppinessDetector = sloppinessDetector;
            this.forceClientsToUseMod = forceClientsToUseMod;
            this.sloppinessCooldownSeconds = sloppinessCooldownSeconds;
            this.bigBrotherMessages = sanitizeBigBrotherMessages(bigBrotherMessages);
            this.statusMessage = statusMessage == null ? "" : statusMessage;
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(360, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(44, this.height / 2 - 116);
            CycleButton<Boolean> detectorButton = CycleButton.onOffBuilder(this.sloppinessDetector)
                    .create(left + 12, top + 28, panelWidth - 24, 20, Component.literal("Big Brother"),
                            (button, enabled) -> this.sloppinessDetector = enabled);
            detectorButton.active = this.canEditSettings;
            this.addRenderableWidget(withTooltip(detectorButton, "Enable or disable Big Brother misplaced-item monitoring on this server."));
            CycleButton<Boolean> forceClientButton = CycleButton.onOffBuilder(this.forceClientsToUseMod)
                    .create(left + 12, top + 56, panelWidth - 24, 20, Component.literal("Require StorageGuide clients"),
                            (button, enabled) -> this.forceClientsToUseMod = enabled);
            forceClientButton.active = this.canEditSettings;
            this.addRenderableWidget(withTooltip(forceClientButton, "Disconnect players who do not handshake with a compatible StorageGuide client."));

            this.cooldownBox = new EditBox(
                    this.font,
                    left + 12,
                    top + 100,
                    panelWidth - 24,
                    20,
                    Component.literal("Big Brother cooldown seconds")
            );
            this.cooldownBox.setValue(Integer.toString(this.sloppinessCooldownSeconds));
            this.cooldownBox.setHint(Component.literal("Cooldown seconds (1-3600)"));
            this.cooldownBox.setMaxLength(4);
            this.cooldownBox.setEditable(this.canEditSettings);
            this.addRenderableWidget(withTooltip(this.cooldownBox, "Minimum seconds before Big Brother announces again for the same player or chest."));

            this.messagesBox = new EditBox(
                    this.font,
                    left + 12,
                    top + 148,
                    panelWidth - 24,
                    20,
                    Component.literal("Big Brother message templates")
            );
            this.messagesBox.setValue(String.join(" | ", this.bigBrotherMessages));
            this.messagesBox.setHint(Component.literal("Use {playername}; separate options with |"));
            this.messagesBox.setMaxLength(512);
            this.messagesBox.setEditable(this.canEditSettings);
            this.addRenderableWidget(withTooltip(this.messagesBox, "Announcement templates. Use {playername}; separate random options with |."));

            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Back"), button -> this.onClose())
                    .bounds(left + 12, top + 194, 96, 20).build(), "Return to the previous screen."));
            Button saveButton = Button.builder(Component.literal("Save"), button -> {
                int cooldown = parseCooldown(this.cooldownBox.getValue(), this.sloppinessCooldownSeconds);
                List<String> messages = parseMessages(this.messagesBox.getValue());
                if (canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS_V3)) {
                    ClientPlayNetworking.send(new StorageGuideNetworking.UpdateOperatorSettingsV3Payload(
                            this.sloppinessDetector,
                            this.forceClientsToUseMod,
                            cooldown,
                            messages
                    ));
                } else if (canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS_V2)) {
                    ClientPlayNetworking.send(new StorageGuideNetworking.UpdateOperatorSettingsV2Payload(
                            this.sloppinessDetector,
                            this.forceClientsToUseMod,
                            cooldown
                    ));
                } else if (canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS)) {
                    ClientPlayNetworking.send(new StorageGuideNetworking.UpdateOperatorSettingsPayload(
                            this.sloppinessDetector
                    ));
                }
            }).bounds(left + panelWidth - 108, top + 194, 96, 20).build();
            saveButton.active = this.canEditSettings
                    && (canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS_V3)
                    || canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS_V2)
                    || canSend(StorageGuideNetworking.UPDATE_OPERATOR_SETTINGS));
            this.addRenderableWidget(withTooltip(saveButton, "Save these server-owned operator settings."));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(360, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(44, this.height / 2 - 116);
            drawPanel(graphics, left, top, panelWidth, 226);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, Component.literal("Big Brother Settings"), this.width / 2, top + 8, 0xFFFFFFFF);
            graphics.centeredText(
                    this.font,
                    Component.literal(this.canEditSettings
                            ? "These settings are stored on the server."
                            : "You can view these settings, but only operators can change them."),
                    this.width / 2,
                    top + 20,
                    0xFFAAAAAA
            );
            graphics.text(this.font, Component.literal("Announcement cooldown"), left + 12, top + 88, 0xFFDDDDDD);
            graphics.text(this.font, Component.literal("Message templates"), left + 12, top + 136, 0xFFDDDDDD);
            graphics.text(this.font, Component.literal("Separate random options with |. Use {playername}."), left + 12, top + 172, 0xFFAAAAAA);
            if (!this.statusMessage.isBlank()) {
                graphics.centeredText(
                        this.font,
                        Component.literal(this.statusMessage),
                        this.width / 2,
                        top + 220,
                        this.canEditSettings ? 0xFF55FF55 : 0xFFFFAA55
                );
            }
        }

        private static int parseCooldown(String value, int fallback) {
            try {
                return Math.clamp(Integer.parseInt(value), 1, 3600);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private static List<String> parseMessages(String value) {
            return sanitizeBigBrotherMessages(List.of(value.split("\\|")));
        }

        private static List<String> sanitizeBigBrotherMessages(List<String> messages) {
            List<String> sanitized = messages == null ? List.of() : messages.stream()
                    .filter(message -> message != null && !message.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(16)
                    .toList();
            return sanitized.isEmpty()
                    ? List.of("Big Brother caught {playername} slacking off.")
                    : sanitized;
        }

        @Override
        public void onClose() {
            showScreen(this.minecraft, this.parent);
        }
    }

    private static Component colorLabel(String label, int color) {
        return Component.literal(label + ": " + hexColor(color));
    }

    private static String hexColor(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private static int contrastText(int color) {
        int luminance = red(color) * 299 + green(color) * 587 + blue(color) * 114;
        return luminance >= 128_000 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xD0181A20);
        graphics.outline(x, y, width, height, 0x80FFFFFF);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 24, 0x502D8CFF);
    }

    private static void drawScrollbar(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int height,
            int totalItems,
            int visibleItems,
            int scrollOffset
    ) {
        if (totalItems <= visibleItems || visibleItems <= 0) {
            return;
        }

        graphics.fill(x, y, x + 4, y + height, 0x60303038);
        int thumbHeight = Math.max(18, height * visibleItems / totalItems);
        int maxScroll = Math.max(1, totalItems - visibleItems);
        int availableTravel = Math.max(1, height - thumbHeight);
        int thumbY = y + (int) Math.round(availableTravel * (scrollOffset / (double) maxScroll));
        graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xCC8AB4FF);
    }

    private static <T extends AbstractWidget> T withTooltip(T widget, String tooltip) {
        widget.setTooltip(Tooltip.create(Component.literal(tooltip)));
        widget.setTooltipDelay(Duration.ofMillis(250));
        return widget;
    }

    private static void renderItemIcon(GuiGraphicsExtractor graphics, String itemId, int x, int y) {
        Identifier id = Identifier.tryParse(normalizeClientItemId(itemId));
        if (id == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item != null) {
            graphics.item(new ItemStack(item), x, y);
        }
    }

    private static String posLabel(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static final class SloppinessHistoryScreen extends Screen {
        private static final int VISIBLE_PLAYERS = 7;
        private static final DateTimeFormatter TIME_FORMAT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

        private final Screen parent;
        private final List<StorageGuideNetworking.SloppinessHistoryDto> entries;
        private final List<PlayerHistoryGroup> groups;
        private final List<Button> playerButtons = new ArrayList<>();
        private int scrollOffset;

        private SloppinessHistoryScreen(
                Screen parent,
                List<StorageGuideNetworking.SloppinessHistoryDto> entries
        ) {
            super(Component.literal("StorageGuide Big Brother History"));
            this.parent = parent;
            this.entries = entries.stream()
                    .sorted(Comparator
                            .comparing(StorageGuideNetworking.SloppinessHistoryDto::playerName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(StorageGuideNetworking.SloppinessHistoryDto::timestamp, Comparator.reverseOrder()))
                    .toList();
            this.groups = groupHistoryByPlayer(this.entries);
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(360, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(40, this.height / 2 - 112);
            this.playerButtons.clear();

            for (int i = 0; i < VISIBLE_PLAYERS; i++) {
                int rowIndex = i;
                Button row = Button.builder(Component.empty(), button -> openVisiblePlayer(rowIndex))
                        .bounds(left + 12, top + 38 + i * 23, panelWidth - 24, 21).build();
                row.active = false;
                this.playerButtons.add(row);
                this.addRenderableWidget(row);
            }

            this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose())
                    .bounds(center - 48, top + 38 + VISIBLE_PLAYERS * 23 + 8, 96, 20).build());
            refreshEntries();
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 1);
                refreshEntries();
                return true;
            }
            if (scrollY < 0) {
                this.scrollOffset = Math.min(maxScroll(), this.scrollOffset + 1);
                refreshEntries();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        private void refreshEntries() {
            this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
            for (int i = 0; i < this.playerButtons.size(); i++) {
                int index = this.scrollOffset + i;
                Button row = this.playerButtons.get(i);
                if (index >= this.groups.size()) {
                    row.visible = false;
                    continue;
                }

                PlayerHistoryGroup group = this.groups.get(index);
                row.visible = true;
                row.active = true;
                row.setMessage(Component.literal(group.playerName() + " • " + group.entries().size() + " instance(s)"));
            }
        }

        private void openVisiblePlayer(int rowIndex) {
            int index = this.scrollOffset + rowIndex;
            if (index >= 0 && index < this.groups.size()) {
                showScreen(this.minecraft, new BigBrotherPlayerHistoryScreen(this, this.groups.get(index)));
            }
        }

        private int maxScroll() {
            return Math.max(0, this.groups.size() - VISIBLE_PLAYERS);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(360, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(40, this.height / 2 - 112);
            drawPanel(graphics, left, top, panelWidth, 226);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
            graphics.centeredText(
                    this.font,
                    Component.literal(this.entries.size() + " caught instance(s) across " + this.groups.size() + " player(s)"),
                    this.width / 2,
                    top + 22,
                    0xFFAAAAAA
            );
            if (this.entries.isEmpty()) {
                graphics.centeredText(
                        this.font,
                        Component.literal("Big Brother has not caught anyone yet."),
                        this.width / 2,
                        this.height / 2,
                        0xFFAAAAAA
                );
            } else if (this.groups.size() > VISIBLE_PLAYERS) {
                graphics.centeredText(
                        this.font,
                        Component.literal("Scroll to browse players"),
                        this.width / 2,
                        top + 38 + VISIBLE_PLAYERS * 23 + 32,
                        0xFFAAAAAA
                );
            }
            drawScrollbar(graphics, left + panelWidth - 8, top + 38, VISIBLE_PLAYERS * 23 - 2, this.groups.size(), VISIBLE_PLAYERS, this.scrollOffset);
        }

        @Override
        public void onClose() {
            showScreen(this.minecraft, this.parent);
        }
    }

    private static final class BigBrotherPlayerHistoryScreen extends Screen {
        private static final int VISIBLE_ENTRIES = 7;

        private final Screen parent;
        private final PlayerHistoryGroup group;
        private final List<Button> detailButtons = new ArrayList<>();
        private int scrollOffset;

        private BigBrotherPlayerHistoryScreen(Screen parent, PlayerHistoryGroup group) {
            super(Component.literal("Big Brother: " + group.playerName()));
            this.parent = parent;
            this.group = group;
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(420, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(40, this.height / 2 - 112);
            this.detailButtons.clear();

            for (int i = 0; i < VISIBLE_ENTRIES; i++) {
                Button row = Button.builder(Component.empty(), button -> {
                }).bounds(left + 12, top + 38 + i * 23, panelWidth - 24, 21).build();
                row.active = false;
                this.detailButtons.add(row);
                this.addRenderableWidget(row);
            }

            this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose())
                    .bounds(center - 48, top + 38 + VISIBLE_ENTRIES * 23 + 8, 96, 20).build());
            refreshEntries();
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 1);
                refreshEntries();
                return true;
            }
            if (scrollY < 0) {
                this.scrollOffset = Math.min(maxScroll(), this.scrollOffset + 1);
                refreshEntries();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        private void refreshEntries() {
            this.scrollOffset = Math.min(this.scrollOffset, maxScroll());
            for (int i = 0; i < this.detailButtons.size(); i++) {
                int index = this.scrollOffset + i;
                Button row = this.detailButtons.get(i);
                if (index >= this.group.entries().size()) {
                    row.visible = false;
                    continue;
                }

                StorageGuideNetworking.SloppinessHistoryDto entry = this.group.entries().get(index);
                row.visible = true;
                row.setMessage(Component.literal(
                        cleanItemName(itemPath(entry.itemId()))
                                + " • " + SloppinessHistoryScreen.TIME_FORMAT.format(Instant.ofEpochMilli(entry.timestamp()))
                                + " • " + posLabel(entry.chestPos())
                ));
            }
        }

        private int maxScroll() {
            return Math.max(0, this.group.entries().size() - VISIBLE_ENTRIES);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(420, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(40, this.height / 2 - 112);
            drawPanel(graphics, left, top, panelWidth, 226);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
            graphics.centeredText(
                    this.font,
                    Component.literal(this.group.entries().size() + " instance(s), newest first"),
                    this.width / 2,
                    top + 22,
                    0xFFAAAAAA
            );
            for (int i = 0; i < this.detailButtons.size(); i++) {
                int index = this.scrollOffset + i;
                if (index < this.group.entries().size()) {
                    Button button = this.detailButtons.get(i);
                    renderItemIcon(graphics, this.group.entries().get(index).itemId(), button.getX() + 3, button.getY() + 2);
                }
            }
            drawScrollbar(graphics, left + panelWidth - 8, top + 38, VISIBLE_ENTRIES * 23 - 2, this.group.entries().size(), VISIBLE_ENTRIES, this.scrollOffset);
        }

        @Override
        public void onClose() {
            showScreen(this.minecraft, this.parent);
        }
    }

    private record PlayerHistoryGroup(String playerName, List<StorageGuideNetworking.SloppinessHistoryDto> entries) {
    }

    private static List<PlayerHistoryGroup> groupHistoryByPlayer(List<StorageGuideNetworking.SloppinessHistoryDto> entries) {
        Map<String, List<StorageGuideNetworking.SloppinessHistoryDto>> grouped = new LinkedHashMap<>();
        for (StorageGuideNetworking.SloppinessHistoryDto entry : entries) {
            grouped.computeIfAbsent(entry.playerName(), ignored -> new ArrayList<>()).add(entry);
        }
        return grouped.entrySet().stream()
                .map(entry -> new PlayerHistoryGroup(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(StorageGuideNetworking.SloppinessHistoryDto::timestamp).reversed())
                                .toList()
                ))
                .sorted(Comparator
                        .comparingInt((PlayerHistoryGroup group) -> group.entries().size()).reversed()
                        .thenComparing(PlayerHistoryGroup::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static final class CellEditorScreen extends Screen {
        private static final int VISIBLE_ITEMS = 5;

        private final StorageGuideNetworking.CellDto cell;
        private final List<ItemOption> itemOptions = availableItemOptions();
        private final List<Button> itemButtons = new ArrayList<>();
        private Set<String> originalItemIds;
        private EditBox searchBox;
        private Set<String> selectedItemIds;
        private boolean originalSloppinessExcluded;
        private boolean sloppinessExcluded;
        private int scrollOffset;
        private String statusMessage = "Select items, then press Save.";
        private int statusColor = 0xFFAAAAAA;

        private CellEditorScreen(StorageGuideNetworking.CellDto cell) {
            this(cell, false);
        }

        private CellEditorScreen(StorageGuideNetworking.CellDto cell, boolean sloppinessExcluded) {
            super(Component.literal("Edit Chest Assignment"));
            this.cell = cell;
            this.originalSloppinessExcluded = sloppinessExcluded;
            this.sloppinessExcluded = sloppinessExcluded;
            this.selectedItemIds = cell.itemIds().stream()
                    .map(StorageGuideClient::normalizeClientItemId)
                    .filter(item -> !item.isBlank())
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
            this.originalItemIds = new LinkedHashSet<>(this.selectedItemIds);
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(16, this.height / 2 - 120);
            this.itemButtons.clear();
            this.searchBox = new EditBox(this.font, left + 12, top + 30, panelWidth - 24, 20, Component.literal("Search item"));
            this.searchBox.setMaxLength(64);
            this.searchBox.setHint(Component.literal("emerald"));
            this.searchBox.setResponder(value -> {
                this.scrollOffset = 0;
                refreshItems();
            });
            this.addRenderableWidget(this.searchBox);

            for (int i = 0; i < VISIBLE_ITEMS; i++) {
                int row = i;
                Button button = Button.builder(Component.empty(), clicked -> selectVisibleItem(row))
                        .bounds(left + 12, top + 58 + i * 23, panelWidth - 24, 21).build();
                this.itemButtons.add(button);
                this.addRenderableWidget(button);
            }

            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Clear"), button -> {
                this.selectedItemIds.clear();
                this.statusMessage = changeSummary("Unsaved");
                this.statusColor = 0xFFFFD166;
                refreshItems();
            }).bounds(left + 12, top + 58 + VISIBLE_ITEMS * 23 + 6, 76, 20).build(),
                    "Remove all item assignments from this draft. Press Save to apply."));
            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center - 38, top + 58 + VISIBLE_ITEMS * 23 + 6, 76, 20).build(),
                    "Close without saving this draft."));
            this.addRenderableWidget(withTooltip(Button.builder(Component.literal("Save"), button -> saveCellNow())
                    .bounds(left + panelWidth - 88, top + 58 + VISIBLE_ITEMS * 23 + 6, 76, 20).build(),
                    "Save the selected assignments and Big Brother exclusion state."));
            this.addRenderableWidget(withTooltip(CycleButton.onOffBuilder(this.sloppinessExcluded)
                    .create(left + 12, top + 88 + VISIBLE_ITEMS * 23, panelWidth - 24, 20,
                            Component.literal("Exclude from Big Brother"),
                            (button, excluded) -> {
                                this.sloppinessExcluded = excluded;
                                this.statusMessage = changeSummary("Unsaved");
                                this.statusColor = 0xFFFFD166;
                            }),
                    "Prevent this chest from creating Big Brother records without changing its item assignments."));
            this.setInitialFocus(this.searchBox);
            refreshItems();
        }

        private void saveCell(List<String> itemIds) {
            this.statusMessage = changeSummary("Saving");
            this.statusColor = 0xFFFFD166;
            if (canSend(StorageGuideNetworking.EDIT_CELL_V2)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.EditCellV2Payload(
                        cell.id(),
                        itemIds,
                        this.sloppinessExcluded
                ));
            } else {
                ClientPlayNetworking.send(new StorageGuideNetworking.EditCellPayload(cell.id(), itemIds));
            }
        }

        private void saveCellNow() {
            saveCell(List.copyOf(this.selectedItemIds));
        }

        private void setStatus(String cellId, boolean success, String message) {
            if (!this.cell.id().equals(cellId)) {
                return;
            }
            if (success) {
                this.statusMessage = changeSummary("Saved");
                this.originalItemIds = new LinkedHashSet<>(this.selectedItemIds);
                this.originalSloppinessExcluded = this.sloppinessExcluded;
            } else {
                this.statusMessage = message == null || message.isBlank() ? "Could not save." : message;
            }
            this.statusColor = success ? 0xFF55FF88 : 0xFFFF7777;
        }

        private String changeSummary(String prefix) {
            long assigned = this.selectedItemIds.stream()
                    .filter(item -> !this.originalItemIds.contains(item))
                    .count();
            long removed = this.originalItemIds.stream()
                    .filter(item -> !this.selectedItemIds.contains(item))
                    .count();
            boolean exclusionChanged = this.sloppinessExcluded != this.originalSloppinessExcluded;

            List<String> parts = new ArrayList<>();
            if (assigned > 0) {
                parts.add("assigned " + assigned + " item(s) to chest");
            }
            if (removed > 0) {
                parts.add("removed " + removed + " item(s) from chest");
            }
            if (exclusionChanged) {
                parts.add(this.sloppinessExcluded ? "excluded from Big Brother" : "included in Big Brother");
            }
            if (parts.isEmpty()) {
                return "No changes to save.";
            }
            return prefix + ": " + String.join(", ", parts) + ".";
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }

        @Override
        public void onClose() {
            clearEditState();
            showScreen(Minecraft.getInstance(), null);
        }

        private void refreshItems() {
            List<ItemOption> filtered = filteredItems();
            for (int i = 0; i < itemButtons.size(); i++) {
                int index = scrollOffset + i;
                Button button = itemButtons.get(i);
                if (index >= filtered.size()) {
                    button.visible = false;
                    button.active = false;
                    continue;
                }

                ItemOption option = filtered.get(index);
                button.visible = true;
                button.active = true;
                boolean selected = selectedItemIds.contains(option.id());
                button.setMessage(Component.literal("      " + (selected ? "Selected • " : "") + option.displayName()));
            }
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 1);
                refreshItems();
                return true;
            }
            if (scrollY < 0) {
                this.scrollOffset = Math.min(Math.max(0, filteredItems().size() - VISIBLE_ITEMS), this.scrollOffset + 1);
                refreshItems();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(16, this.height / 2 - 120);
            drawPanel(graphics, left, top, panelWidth, 58 + VISIBLE_ITEMS * 23 + 104);
            List<ItemOption> filtered = filteredItems();
            for (int i = 0; i < this.itemButtons.size(); i++) {
                int index = this.scrollOffset + i;
                if (index < filtered.size() && this.selectedItemIds.contains(filtered.get(index).id())) {
                    Button button = this.itemButtons.get(i);
                    graphics.fill(button.getX() + 1, button.getY() + 1, button.getRight() - 1, button.getBottom() - 1, 0x5539C96B);
                }
            }
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, center, top + 8, 0xFFFFFFFF);
            graphics.centeredText(this.font, Component.literal(selectedItemIds.size() + " assigned item(s) • " + posLabel(this.cell.pos())), center, top + 20, 0xFFAAAAAA);
            for (int i = 0; i < this.itemButtons.size(); i++) {
                int index = this.scrollOffset + i;
                if (index < filtered.size()) {
                    Button button = this.itemButtons.get(i);
                    ItemOption option = filtered.get(index);
                    if (this.selectedItemIds.contains(option.id())) {
                        graphics.fill(button.getX() + 2, button.getY() + 2, button.getX() + 6, button.getBottom() - 2, 0xFF55FF88);
                        graphics.outline(button.getX(), button.getY(), button.getWidth(), button.getHeight(), 0xAA55FF88);
                    }
                    renderItemIcon(graphics, option.id(), button.getX() + 4, button.getY() + 2);
                }
            }
            drawScrollbar(graphics, left + panelWidth - 8, top + 58, VISIBLE_ITEMS * 23 - 2, filtered.size(), VISIBLE_ITEMS, this.scrollOffset);
            graphics.centeredText(this.font, Component.literal(this.statusMessage), center, top + 112 + VISIBLE_ITEMS * 23, this.statusColor);
        }

        private void selectVisibleItem(int row) {
            int index = scrollOffset + row;
            List<ItemOption> filtered = filteredItems();
            if (index >= filtered.size()) {
                return;
            }

            ItemOption option = filtered.get(index);
            if (!selectedItemIds.remove(option.id())) {
                selectedItemIds.add(option.id());
            }
            this.statusMessage = changeSummary("Unsaved");
            this.statusColor = 0xFFFFD166;
            refreshItems();
        }

        private List<ItemOption> filteredItems() {
            String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
            return this.itemOptions.stream()
                    .filter(option -> option.displayName().toLowerCase(Locale.ROOT).contains(query)
                            || option.id().toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator.comparing((ItemOption option) -> !selectedItemIds.contains(option.id()))
                            .thenComparing(ItemOption::displayName))
                    .limit(256)
                    .toList();
        }
    }

    private static final class FindItemScreen extends Screen {
        private static final int VISIBLE_ITEMS = 8;

        private final List<ItemOption> itemOptions = availableItemOptions();
        private final List<Button> itemButtons = new ArrayList<>();
        private EditBox searchBox;
        private int scrollOffset;

        private FindItemScreen() {
            super(Component.literal("StorageGuide Find Item"));
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(24, this.height / 2 - 122);
            this.itemButtons.clear();
            this.scrollOffset = 0;

            this.searchBox = new EditBox(this.font, left + 12, top + 32, panelWidth - 24, 20, Component.literal("Search items"));
            this.searchBox.setMaxLength(128);
            this.searchBox.setHint(Component.literal("Search items..."));
            this.searchBox.setResponder(value -> {
                this.scrollOffset = 0;
                refreshItems();
            });
            this.addRenderableWidget(this.searchBox);

            for (int i = 0; i < VISIBLE_ITEMS; i++) {
                int row = i;
                Button button = Button.builder(Component.empty(), clicked -> locateVisibleItem(row))
                        .bounds(left + 12, top + 60 + i * 23, panelWidth - 24, 21)
                        .build();
                this.itemButtons.add(button);
                this.addRenderableWidget(button);
            }

            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center - 48, top + 60 + VISIBLE_ITEMS * 23 + 6, 96, 20)
                    .build());
            this.setInitialFocus(this.searchBox);
            refreshItems();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                return locateVisibleItem(0);
            }
            return super.keyPressed(event);
        }

        private void refreshItems() {
            List<ItemOption> filtered = filteredItems();
            this.scrollOffset = Math.min(this.scrollOffset, maxScrollOffset(filtered.size()));

            for (int i = 0; i < this.itemButtons.size(); i++) {
                int index = this.scrollOffset + i;
                Button button = this.itemButtons.get(i);
                if (index >= filtered.size()) {
                    button.visible = false;
                    button.active = false;
                    continue;
                }

                ItemOption option = filtered.get(index);
                button.visible = true;
                button.active = true;
                button.setMessage(Component.literal("      " + option.displayName()));
            }

        }

        private boolean locateVisibleItem(int row) {
            List<ItemOption> filtered = filteredItems();
            int index = this.scrollOffset + row;
            if (index < 0 || index >= filtered.size()) {
                return false;
            }

            String itemId = filtered.get(index).id();
            if (canSend(StorageGuideNetworking.LOCATE_ITEM)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.LocateItemPayload(itemId));
            } else if (canSend(StorageGuideNetworking.LOCATE_HELD)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.LocateHeldPayload(itemId));
            } else {
                message(Minecraft.getInstance(), "StorageGuide is not available on this server.");
                return false;
            }
            this.onClose();
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 1);
                refreshItems();
                return true;
            }
            if (scrollY < 0) {
                this.scrollOffset = Math.min(maxScrollOffset(), this.scrollOffset + 1);
                refreshItems();
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int center = this.width / 2;
            int panelWidth = Math.min(340, this.width - 32);
            int left = center - panelWidth / 2;
            int top = Math.max(24, this.height / 2 - 122);
            drawPanel(graphics, left, top, panelWidth, 60 + VISIBLE_ITEMS * 23 + 40);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            graphics.centeredText(this.font, this.title, center, top + 8, 0xFFFFFFFF);
            graphics.centeredText(this.font, Component.literal("Type to search, Enter to locate, scroll to browse"), center, top + 21, 0xFFAAAAAA);
            List<ItemOption> filtered = filteredItems();
            if (this.searchBox != null && this.searchBox.getValue().isBlank()) {
                graphics.centeredText(this.font, Component.literal("Start typing an item name"), center, top + 98, 0xFFAAAAAA);
            }
            for (int i = 0; i < this.itemButtons.size(); i++) {
                int index = this.scrollOffset + i;
                if (index < filtered.size()) {
                    Button button = this.itemButtons.get(i);
                    renderItemIcon(graphics, filtered.get(index).id(), button.getX() + 4, button.getY() + 2);
                }
            }
            drawScrollbar(graphics, left + panelWidth - 8, top + 60, VISIBLE_ITEMS * 23 - 2, filtered.size(), VISIBLE_ITEMS, this.scrollOffset);
        }

        private List<ItemOption> filteredItems() {
            String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(Locale.ROOT);
            if (query.isBlank()) {
                return List.of();
            }

            return this.itemOptions.stream()
                    .filter(option -> option.displayName().toLowerCase(Locale.ROOT).contains(query)
                            || option.id().toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator
                            .comparing((ItemOption option) -> !option.displayName().toLowerCase(Locale.ROOT).startsWith(query))
                            .thenComparing(option -> !itemPath(option.id()).startsWith(query))
                            .thenComparing(ItemOption::displayName))
                    .limit(256)
                    .toList();
        }

        private int maxScrollOffset() {
            return maxScrollOffset(filteredItems().size());
        }

        private static int maxScrollOffset(int itemCount) {
            return Math.max(0, itemCount - VISIBLE_ITEMS);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }

        @Override
        public void onClose() {
            showScreen(Minecraft.getInstance(), null);
        }
    }

    private record ItemOption(String id, String displayName) {
        static ItemOption fromIdentifier(Identifier id) {
            return new ItemOption(id.toString(), itemDisplayName(id));
        }
    }

    private static List<ItemOption> availableItemOptions() {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return List.of();
        }

        CreativeModeTabs.tryRebuildTabContents(
                client.getConnection().enabledFeatures(),
                false,
                client.getConnection().registryAccess()
        );

        Map<String, ItemOption> options = new LinkedHashMap<>();
        CreativeModeTabs.tabs().stream()
                .flatMap(tab -> tab.getDisplayItems().stream())
                .filter(stack -> !stack.isEmpty())
                .forEach(stack -> {
                    Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    options.putIfAbsent(id.toString(), ItemOption.fromIdentifier(id));
                });

        return options.values().stream()
                .sorted(Comparator.comparing(ItemOption::displayName))
                .toList();
    }

    private static String normalizeClientItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        String trimmed = itemId.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static String itemDisplayName(Identifier id) {
        String name = cleanItemName(id.getPath());
        if ("minecraft".equals(id.getNamespace())) {
            return name;
        }
        return cleanItemName(id.getNamespace()) + " / " + name;
    }

    private static String itemPath(String itemId) {
        int separator = itemId.indexOf(':');
        return (separator >= 0 ? itemId.substring(separator + 1) : itemId).toLowerCase(Locale.ROOT);
    }

    private static String cleanItemName(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalize = true;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '_' || character == '-') {
                result.append(' ');
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

}
