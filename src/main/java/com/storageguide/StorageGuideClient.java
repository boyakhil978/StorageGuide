package com.storageguide;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
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

public final class StorageGuideClient implements ClientModInitializer {
    private final KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(StorageGuideMod.MOD_ID, StorageGuideMod.MOD_ID));
    private KeyMapping selectOrEditKey;
    private KeyMapping locateHeldKey;
    private KeyMapping findMenuKey;

    private static boolean hasGrid;
    private static boolean canEdit;
    private static boolean requestedInitialState;
    private static boolean sentClientHello;
    private static String serverVersion = "legacy or unavailable";
    private static int serverProtocolVersion = 1;
    private static List<StorageGuideNetworking.CellDto> cells = List.of();
    private static boolean editOverlayActive;
    private static BlockPos firstSelectionCorner;
    private static BlockPos activeHighlight;
    private static long activeHighlightUntilMs;

    @Override
    public void onInitializeClient() {
        this.selectOrEditKey = registerKey("select_or_edit", GLFW.GLFW_KEY_LEFT_BRACKET);
        this.locateHeldKey = registerKey("locate_held_item", GLFW.GLFW_KEY_GRAVE_ACCENT);
        this.findMenuKey = registerKey("find_menu", GLFW.GLFW_KEY_O);

        registerReceivers();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LevelRenderEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(this::extractHighlights);
    }

    private KeyMapping registerKey(String name, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + StorageGuideMod.MOD_ID + "." + name,
                InputConstants.Type.KEYSYM,
                defaultKey,
                this.category
        ));
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
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_EDITOR, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new CellEditorScreen(payload.cell()))));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.MESSAGE, (payload, context) -> {
        });
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.SERVER_HELLO, (payload, context) ->
                context.client().execute(() -> {
                    serverVersion = payload.version();
                    serverProtocolVersion = payload.protocolVersion();
                }));
    }

    private void onClientTick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            resetSessionState();
            return;
        }

        if (client.screen != null && !(client.screen instanceof CellEditorScreen) && !(client.screen instanceof FindItemScreen)) {
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
                    itemId -> locateHeldItem(client, itemId),
                    () -> message(client, "Hold an item to locate it.")
            );
        }

        while (this.findMenuKey.consumeClick()) {
            if (canSend(StorageGuideNetworking.OPEN_FIND)) {
                ClientPlayNetworking.send(new StorageGuideNetworking.OpenFindPayload());
            }
            client.setScreen(new FindItemScreen());
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
            renderStates.add(new HighlightState(activeHighlight, 1.0F, 0.82F, 0.1F, 0.3F, 1.0F));
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

    private static final class CellEditorScreen extends Screen {
        private static final int VISIBLE_ITEMS = 8;

        private final StorageGuideNetworking.CellDto cell;
        private final List<ItemOption> itemOptions = availableItemOptions();
        private final List<Button> itemButtons = new ArrayList<>();
        private EditBox searchBox;
        private Set<String> selectedItemIds;
        private int scrollOffset;

        private CellEditorScreen(StorageGuideNetworking.CellDto cell) {
            super(Component.literal("StorageGuide Cell " + cell.id()));
            this.cell = cell;
            this.selectedItemIds = cell.itemIds().stream()
                    .map(StorageGuideClient::normalizeClientItemId)
                    .filter(item -> !item.isBlank())
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int top = Math.max(24, this.height / 2 - 112);
            this.searchBox = new EditBox(this.font, center - 120, top, 240, 20, Component.literal("Search item"));
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
                        .bounds(center - 120, top + 28 + i * 22, 240, 20).build();
                this.itemButtons.add(button);
                this.addRenderableWidget(button);
            }

            this.addRenderableWidget(Button.builder(Component.literal("Up"), button -> {
                this.scrollOffset = Math.max(0, this.scrollOffset - VISIBLE_ITEMS);
                refreshItems();
            }).bounds(center - 120, top + 28 + VISIBLE_ITEMS * 22, 52, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Down"), button -> {
                this.scrollOffset = Math.min(Math.max(0, filteredItems().size() - VISIBLE_ITEMS), this.scrollOffset + VISIBLE_ITEMS);
                refreshItems();
            }).bounds(center - 62, top + 28 + VISIBLE_ITEMS * 22, 58, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
                ClientPlayNetworking.send(new StorageGuideNetworking.EditCellPayload(cell.id(), List.copyOf(this.selectedItemIds)));
                this.onClose();
            }).bounds(center + 4, top + 28 + VISIBLE_ITEMS * 22, 56, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
                ClientPlayNetworking.send(new StorageGuideNetworking.EditCellPayload(cell.id(), List.of()));
                this.onClose();
            }).bounds(center + 66, top + 28 + VISIBLE_ITEMS * 22, 54, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center - 40, top + 56 + VISIBLE_ITEMS * 22, 80, 20).build());
            this.setInitialFocus(this.searchBox);
            refreshItems();
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return true;
        }

        @Override
        public void onClose() {
            clearEditState();
            Minecraft.getInstance().setScreen(null);
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
                button.setMessage(Component.literal((selectedItemIds.contains(option.id()) ? "[x] " : "[ ] ") + option.displayName()));
            }
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
        private Button upButton;
        private Button downButton;
        private int scrollOffset;

        private FindItemScreen() {
            super(Component.literal("StorageGuide Find Item"));
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int top = Math.max(24, this.height / 2 - 112);
            this.itemButtons.clear();
            this.scrollOffset = 0;

            this.searchBox = new EditBox(this.font, center - 120, top, 240, 20, Component.literal("Search items"));
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
                        .bounds(center - 120, top + 28 + i * 22, 240, 20)
                        .build();
                this.itemButtons.add(button);
                this.addRenderableWidget(button);
            }

            this.upButton = Button.builder(Component.literal("Up"), button -> {
                this.scrollOffset = Math.max(0, this.scrollOffset - VISIBLE_ITEMS);
                refreshItems();
            }).bounds(center - 120, top + 28 + VISIBLE_ITEMS * 22, 58, 20).build();
            this.addRenderableWidget(this.upButton);

            this.downButton = Button.builder(Component.literal("Down"), button -> {
                this.scrollOffset = Math.min(maxScrollOffset(), this.scrollOffset + VISIBLE_ITEMS);
                refreshItems();
            }).bounds(center - 56, top + 28 + VISIBLE_ITEMS * 22, 58, 20).build();
            this.addRenderableWidget(this.downButton);

            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center + 42, top + 28 + VISIBLE_ITEMS * 22, 78, 20)
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
                button.setMessage(Component.literal(option.displayName()));
            }

            if (this.upButton != null) {
                this.upButton.active = this.scrollOffset > 0;
            }
            if (this.downButton != null) {
                this.downButton.active = this.scrollOffset < maxScrollOffset(filtered.size());
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
            Minecraft.getInstance().setScreen(null);
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
