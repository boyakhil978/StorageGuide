package com.storageguide;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StorageGuideNetworking {
    private StorageGuideNetworking() {
    }

    public static final CustomPacketPayload.Type<RequestStatePayload> REQUEST_STATE = type("request_state");
    public static final CustomPacketPayload.Type<BeginSelectPayload> BEGIN_SELECT = type("begin_select");
    public static final CustomPacketPayload.Type<SetGridPayload> SET_GRID = type("set_grid");
    public static final CustomPacketPayload.Type<LocateHeldPayload> LOCATE_HELD = type("locate_held");
    public static final CustomPacketPayload.Type<OpenFindPayload> OPEN_FIND = type("open_find");
    public static final CustomPacketPayload.Type<RequestEditCellPayload> REQUEST_EDIT_CELL = type("request_edit_cell");
    public static final CustomPacketPayload.Type<EditCellPayload> EDIT_CELL = type("edit_cell");
    public static final CustomPacketPayload.Type<StatePayload> STATE = type("state");
    public static final CustomPacketPayload.Type<HighlightPayload> HIGHLIGHT = type("highlight");
    public static final CustomPacketPayload.Type<OpenEditorPayload> OPEN_EDITOR = type("open_editor");
    public static final CustomPacketPayload.Type<MessagePayload> MESSAGE = type("message");

    public static void registerPayloads() {
        PayloadTypeRegistry.serverboundPlay().register(REQUEST_STATE, RequestStatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BEGIN_SELECT, BeginSelectPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SET_GRID, SetGridPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LOCATE_HELD, LocateHeldPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OPEN_FIND, OpenFindPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(REQUEST_EDIT_CELL, RequestEditCellPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EDIT_CELL, EditCellPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(STATE, StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HIGHLIGHT, HighlightPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OPEN_EDITOR, OpenEditorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MESSAGE, MessagePayload.CODEC);
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(StorageGuideMod.MOD_ID, path));
    }

    public record RequestStatePayload() implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, RequestStatePayload> CODEC = StreamCodec.unit(new RequestStatePayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return REQUEST_STATE;
        }
    }

    public record BeginSelectPayload() implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, BeginSelectPayload> CODEC = StreamCodec.unit(new BeginSelectPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return BEGIN_SELECT;
        }
    }

    public record SetGridPayload(BlockPos first, BlockPos second) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, SetGridPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetGridPayload::first,
                BlockPos.STREAM_CODEC, SetGridPayload::second,
                SetGridPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SET_GRID;
        }
    }

    public record LocateHeldPayload(String itemId) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, LocateHeldPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LocateHeldPayload::itemId,
                LocateHeldPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return LOCATE_HELD;
        }
    }

    public record OpenFindPayload() implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, OpenFindPayload> CODEC = StreamCodec.unit(new OpenFindPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return OPEN_FIND;
        }
    }

    public record RequestEditCellPayload(BlockPos pos) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, RequestEditCellPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, RequestEditCellPayload::pos,
                RequestEditCellPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return REQUEST_EDIT_CELL;
        }
    }

    public record EditCellPayload(String cellId, List<String> itemIds) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, EditCellPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, EditCellPayload::cellId,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8, 1024), EditCellPayload::itemIds,
                EditCellPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return EDIT_CELL;
        }
    }

    public record CellDto(String id, BlockPos pos, List<String> itemIds) {
        static final StreamCodec<ByteBuf, CellDto> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CellDto::id,
                BlockPos.STREAM_CODEC, CellDto::pos,
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8, 1024), CellDto::itemIds,
                CellDto::new
        );
    }

    public record StatePayload(boolean hasGrid, boolean canEdit, List<CellDto> cells) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, StatePayload::hasGrid,
                ByteBufCodecs.BOOL, StatePayload::canEdit,
                ByteBufCodecs.collection(ArrayList::new, CellDto.CODEC, 8192), StatePayload::cells,
                StatePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return STATE;
        }
    }

    public record HighlightPayload(Optional<BlockPos> pos, String message) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, HighlightPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(BlockPos.STREAM_CODEC), HighlightPayload::pos,
                ByteBufCodecs.STRING_UTF8, HighlightPayload::message,
                HighlightPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return HIGHLIGHT;
        }
    }

    public record OpenEditorPayload(CellDto cell) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, OpenEditorPayload> CODEC = StreamCodec.composite(
                CellDto.CODEC, OpenEditorPayload::cell,
                OpenEditorPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return OPEN_EDITOR;
        }
    }

    public record MessagePayload(String message) implements CustomPacketPayload {
        static final StreamCodec<RegistryFriendlyByteBuf, MessagePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, MessagePayload::message,
                MessagePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return MESSAGE;
        }
    }
}
