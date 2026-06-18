package com.storageguide;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

public final class StorageGuideClient implements ClientModInitializer {
    private static final RenderPipeline HIGHLIGHT_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(StorageGuideMod.MOD_ID, "pipeline/highlight_box"))
            .withDepthStencilState(Optional.empty())
            .build());
    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private final KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(StorageGuideMod.MOD_ID, StorageGuideMod.MOD_ID));
    private KeyMapping selectOrEditKey;
    private KeyMapping locateHeldKey;
    private KeyMapping findMenuKey;
    private BufferBuilder buffer;
    private MappableRingBuffer vertexBuffer;

    private static StorageGuideClientConfig clientConfig;
    private static boolean hasGrid;
    private static boolean canEdit;
    private static boolean requestedInitialState;
    private static List<StorageGuideNetworking.CellDto> cells = List.of();
    private static boolean editOverlayActive;
    private static BlockPos firstSelectionCorner;
    private static BlockPos activeHighlight;
    private static long activeHighlightUntilMs;

    @Override
    public void onInitializeClient() {
        clientConfig = StorageGuideClientConfig.load();
        this.selectOrEditKey = registerKey("select_or_edit", GLFW.GLFW_KEY_LEFT_BRACKET);
        this.locateHeldKey = registerKey("locate_held_item", GLFW.GLFW_KEY_P);
        this.findMenuKey = registerKey("find_menu", GLFW.GLFW_KEY_O);

        registerReceivers();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::renderHighlights);
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
                    debugMessage(context.client(), payload.message());
                }));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.OPEN_EDITOR, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new CellEditorScreen(payload.cell()))));
        ClientPlayNetworking.registerGlobalReceiver(StorageGuideNetworking.MESSAGE, (payload, context) ->
                context.client().execute(() -> debugMessage(context.client(), payload.message())));
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
            ClientPlayNetworking.send(new StorageGuideNetworking.RequestStatePayload());
        }

        while (this.selectOrEditKey.consumeClick()) {
            handleSelectOrEdit(client);
        }

        while (this.locateHeldKey.consumeClick()) {
            heldItemId(client).ifPresentOrElse(
                    itemId -> ClientPlayNetworking.send(new StorageGuideNetworking.LocateHeldPayload(itemId)),
                    () -> message(client, "Hold an item to locate it.")
            );
        }

        while (this.findMenuKey.consumeClick()) {
            ClientPlayNetworking.send(new StorageGuideNetworking.OpenFindPayload());
            client.setScreen(new FindItemScreen());
        }

        if (System.currentTimeMillis() > activeHighlightUntilMs) {
            activeHighlight = null;
        }
    }

    private static void handleSelectOrEdit(Minecraft client) {
        if (!canEdit) {
            ClientPlayNetworking.send(new StorageGuideNetworking.BeginSelectPayload());
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

            ClientPlayNetworking.send(new StorageGuideNetworking.SetGridPayload(firstSelectionCorner, lookedAt.get()));
            firstSelectionCorner = null;
            return;
        }

        if (!editOverlayActive) {
            editOverlayActive = true;
            message(client, "StorageGuide grid highlighted. Look at a cell and press the select key again to edit.");
            return;
        }

        Optional<BlockPos> lookedAt = lookedAtBlock(client);
        if (lookedAt.isEmpty()) {
            message(client, "StorageGuide grid highlighted. Look at a cell and press the select key again to edit.");
            return;
        }

        ClientPlayNetworking.send(new StorageGuideNetworking.RequestEditCellPayload(lookedAt.get()));
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

    private static void debugMessage(Minecraft client, String message) {
        if (clientConfig != null && clientConfig.debugMode()) {
            message(client, message);
        }
    }

    private static void resetSessionState() {
        requestedInitialState = false;
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

    private void renderHighlights(LevelRenderContext context) {
        List<HighlightState> renderStates = new ArrayList<>();
        if (editOverlayActive && canEdit && hasGrid) {
            for (StorageGuideNetworking.CellDto cell : cells) {
                renderStates.add(new HighlightState(cell.pos(), 0.1F, 0.85F, 1.0F, 0.12F));
            }
            lookedAtBlockSilently(Minecraft.getInstance())
                    .filter(StorageGuideClient::isGridCell)
                    .ifPresent(pos -> renderStates.add(new HighlightState(pos, 1.0F, 0.25F, 0.1F, 0.45F)));
        }
        if (!hasGrid && firstSelectionCorner != null) {
            lookedAtBlockSilently(Minecraft.getInstance())
                    .ifPresent(pos -> renderStates.add(new HighlightState(pos, 0.1F, 1.0F, 0.35F, 0.35F)));
        }
        if (activeHighlight != null) {
            renderStates.add(new HighlightState(activeHighlight, 1.0F, 0.82F, 0.1F, 0.35F));
        }
        if (renderStates.isEmpty()) {
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        if (this.buffer == null) {
            this.buffer = new BufferBuilder(ALLOCATOR, HIGHLIGHT_PIPELINE.getVertexFormatMode(), HIGHLIGHT_PIPELINE.getVertexFormat());
        }

        for (HighlightState state : renderStates) {
            BlockPos pos = state.pos();
            renderFilledBox(matrices.last().pose(), this.buffer,
                    pos.getX() - 0.01F, pos.getY() - 0.01F, pos.getZ() - 0.01F,
                    pos.getX() + 1.01F, pos.getY() + 1.01F, pos.getZ() + 1.01F,
                    state.r(), state.g(), state.b(), state.a());
        }

        matrices.popPose();
        this.drawHighlight(Minecraft.getInstance(), HIGHLIGHT_PIPELINE);
    }

    private static void renderFilledBox(Matrix4fc positionMatrix, BufferBuilder buffer, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float red, float green, float blue, float alpha) {
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
    }

    private void drawHighlight(Minecraft client, RenderPipeline pipeline) {
        MeshData builtBuffer = this.buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();
        GpuBuffer vertices = this.upload(drawParameters, format, builtBuffer);

        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);
        this.vertexBuffer.rotate();
        this.buffer = null;
    }

    private GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();
        if (this.vertexBuffer == null || this.vertexBuffer.size() < vertexBufferSize) {
            if (this.vertexBuffer != null) {
                this.vertexBuffer.close();
            }
            this.vertexBuffer = new MappableRingBuffer(() -> StorageGuideMod.MOD_ID + " highlight pipeline", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(this.vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return this.vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer, MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> StorageGuideMod.MOD_ID + " highlight rendering", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(), client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    private static boolean isGridCell(BlockPos pos) {
        return cells.stream().anyMatch(cell -> cell.pos().equals(pos));
    }

    private record HighlightState(BlockPos pos, float r, float g, float b, float a) {
    }

    private static final class CellEditorScreen extends Screen {
        private static final int VISIBLE_ITEMS = 8;
        private static final List<ItemOption> ITEM_OPTIONS = BuiltInRegistries.ITEM.keySet().stream()
                .map(ItemOption::fromIdentifier)
                .sorted(Comparator.comparing(ItemOption::displayName))
                .toList();

        private final StorageGuideNetworking.CellDto cell;
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
            String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
            return ITEM_OPTIONS.stream()
                    .filter(option -> option.displayName().contains(query) || option.id().contains(query))
                    .sorted(Comparator.comparing((ItemOption option) -> !selectedItemIds.contains(option.id()))
                            .thenComparing(ItemOption::displayName))
                    .limit(256)
                    .toList();
        }
    }

    private static final class FindItemScreen extends Screen {
        private EditBox itemIdBox;

        private FindItemScreen() {
            super(Component.literal("StorageGuide Find Item"));
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            this.itemIdBox = new EditBox(this.font, center - 120, this.height / 2 - 20, 240, 20, Component.literal("Item id"));
            this.itemIdBox.setMaxLength(128);
            this.itemIdBox.setHint(Component.literal("minecraft:stone"));
            heldItemId(Minecraft.getInstance()).ifPresent(this.itemIdBox::setValue);
            this.addRenderableWidget(this.itemIdBox);
            this.addRenderableWidget(Button.builder(Component.literal("Locate"), button -> locate())
                    .bounds(center - 80, this.height / 2 + 10, 75, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.onClose())
                    .bounds(center + 5, this.height / 2 + 10, 75, 20).build());
            this.setInitialFocus(this.itemIdBox);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                locate();
                return true;
            }
            return super.keyPressed(event);
        }

        private void locate() {
            ClientPlayNetworking.send(new StorageGuideNetworking.LocateHeldPayload(normalizeClientItemId(this.itemIdBox.getValue())));
            this.onClose();
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

    private static String normalizeClientItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        String trimmed = itemId.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static String itemDisplayName(Identifier id) {
        if ("minecraft".equals(id.getNamespace())) {
            return id.getPath();
        }
        return id.getNamespace() + "/" + id.getPath();
    }

}
