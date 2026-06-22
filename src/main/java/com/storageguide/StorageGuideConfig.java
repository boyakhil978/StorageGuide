package com.storageguide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class StorageGuideConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    int version = 4;
    boolean sloppinessDetector = false;
    boolean forceClientsToUseMod = false;
    int sloppinessCooldownSeconds = 30;
    StoredPos topLeft;
    StoredPos bottomRight;
    List<StorageCell> cells = new ArrayList<>();
    List<SloppinessRecord> sloppinessHistory = new ArrayList<>();

    public static StorageGuideConfig load(Path path) {
        if (!Files.exists(path)) {
            StorageGuideConfig blank = new StorageGuideConfig();
            blank.save(path);
            return blank;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            StorageGuideConfig loaded = GSON.fromJson(reader, StorageGuideConfig.class);
            if (loaded == null) {
                return new StorageGuideConfig();
            }
            if (loaded.cells == null) {
                loaded.cells = new ArrayList<>();
            }
            if (loaded.sloppinessHistory == null) {
                loaded.sloppinessHistory = new ArrayList<>();
            }
            loaded.sloppinessCooldownSeconds = clampCooldown(loaded.sloppinessCooldownSeconds);
            loaded.version = 4;
            loaded.cells.forEach(StorageCell::migrate);
            return loaded;
        } catch (IOException | RuntimeException ex) {
            return new StorageGuideConfig();
        }
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save StorageGuide config", ex);
        }
    }

    public boolean hasGrid() {
        return topLeft != null && bottomRight != null && !cells.isEmpty();
    }

    public boolean sloppinessDetector() {
        return sloppinessDetector;
    }

    public void setSloppinessDetector(boolean enabled) {
        this.sloppinessDetector = enabled;
    }

    public boolean forceClientsToUseMod() {
        return forceClientsToUseMod;
    }

    public void setForceClientsToUseMod(boolean enabled) {
        this.forceClientsToUseMod = enabled;
    }

    public int sloppinessCooldownSeconds() {
        return clampCooldown(sloppinessCooldownSeconds);
    }

    public void setSloppinessCooldownSeconds(int seconds) {
        sloppinessCooldownSeconds = clampCooldown(seconds);
    }

    public List<SloppinessRecord> sloppinessHistory() {
        if (sloppinessHistory == null) {
            sloppinessHistory = new ArrayList<>();
        }
        return List.copyOf(sloppinessHistory);
    }

    public void addSloppinessRecord(SloppinessRecord record) {
        if (sloppinessHistory == null) {
            sloppinessHistory = new ArrayList<>();
        }
        sloppinessHistory.add(record);
    }

    private static int clampCooldown(int seconds) {
        return Math.clamp(seconds <= 0 ? 30 : seconds, 1, 3600);
    }

    public void rebuild(BlockPos topLeft, BlockPos bottomRight) {
        GridPlane plane = GridPlane.from(topLeft, bottomRight)
                .orElseThrow(() -> new IllegalArgumentException("Corners must share one vertical X/Y or Z/Y plane"));
        this.topLeft = StoredPos.from(topLeft);
        this.bottomRight = StoredPos.from(bottomRight);
        this.cells = plane.createBlankCells();
    }

    public Optional<StorageCell> findCellForItem(String itemId) {
        return cells.stream()
                .filter(cell -> cell.containsItem(itemId))
                .findFirst();
    }

    public Optional<StorageCell> findCellContaining(BlockPos pos) {
        return cells.stream()
                .filter(cell -> cell.contains(pos))
                .findFirst();
    }

    public Optional<StorageCell> findCellById(String id) {
        return cells.stream()
                .filter(cell -> cell.id.equals(id))
                .findFirst();
    }

    public List<StorageGuideNetworking.CellDto> toDtos() {
        return cells.stream()
                .map(cell -> new StorageGuideNetworking.CellDto(cell.id, cell.origin.toBlockPos(), cell.itemIds()))
                .toList();
    }

    public record StoredPos(int x, int y, int z) {
        public static StoredPos from(BlockPos pos) {
            return new StoredPos(pos.getX(), pos.getY(), pos.getZ());
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    public record SloppinessRecord(
            String playerId,
            String playerName,
            long timestamp,
            String itemId,
            String cellId,
            StoredPos chestPos
    ) {
        public static SloppinessRecord create(
                UUID playerId,
                String playerName,
                long timestamp,
                String itemId,
                String cellId,
                BlockPos chestPos
        ) {
            return new SloppinessRecord(
                    playerId.toString(),
                    playerName,
                    timestamp,
                    itemId,
                    cellId,
                    StoredPos.from(chestPos)
            );
        }
    }

    public static final class StorageCell {
        String id;
        StoredPos origin;
        String plane;
        int width = 1;
        int height = 1;
        String itemId;
        List<String> itemIds = new ArrayList<>();
        boolean sloppinessExcluded = false;

        public String id() {
            return id;
        }

        public BlockPos origin() {
            return origin.toBlockPos();
        }

        void migrate() {
            if (itemIds == null) {
                itemIds = new ArrayList<>();
            }
            if (itemId != null && !itemId.isBlank() && !itemIds.contains(itemId)) {
                itemIds.add(itemId);
            }
            itemId = null;
        }

        public List<String> itemIds() {
            migrate();
            return List.copyOf(itemIds);
        }

        public boolean containsItem(String itemId) {
            migrate();
            return itemIds.contains(itemId);
        }

        public void setItemIds(List<String> itemIds) {
            this.itemIds = itemIds == null ? new ArrayList<>() : itemIds.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .distinct()
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            this.itemId = null;
        }

        public boolean sloppinessExcluded() {
            return sloppinessExcluded;
        }

        public void setSloppinessExcluded(boolean excluded) {
            sloppinessExcluded = excluded;
        }

        boolean contains(BlockPos pos) {
            BlockPos originPos = origin.toBlockPos();
            return switch (plane) {
                case "XY" -> pos.getZ() == originPos.getZ()
                        && pos.getX() == originPos.getX()
                        && pos.getY() == originPos.getY();
                case "YZ" -> pos.getX() == originPos.getX()
                        && pos.getZ() == originPos.getZ()
                        && pos.getY() == originPos.getY();
                default -> false;
            };
        }
    }

    private record GridPlane(String plane, FixedAxis fixedAxis, int fixed, int minA, int maxA, int minY, int maxY) {
        static Optional<GridPlane> from(BlockPos first, BlockPos second) {
            List<GridPlane> candidates = new ArrayList<>();
            if (first.getZ() == second.getZ()) {
                candidates.add(new GridPlane("XY", FixedAxis.Z, first.getZ(),
                        Math.min(first.getX(), second.getX()), Math.max(first.getX(), second.getX()),
                        Math.min(first.getY(), second.getY()), Math.max(first.getY(), second.getY())));
            }
            if (first.getX() == second.getX()) {
                candidates.add(new GridPlane("YZ", FixedAxis.X, first.getX(),
                        Math.min(first.getZ(), second.getZ()), Math.max(first.getZ(), second.getZ()),
                        Math.min(first.getY(), second.getY()), Math.max(first.getY(), second.getY())));
            }

            return candidates.stream()
                    .max(Comparator.comparingInt(GridPlane::area));
        }

        List<StorageCell> createBlankCells() {
            List<StorageCell> generated = new ArrayList<>();
            int columns = maxA - minA + 1;
            int rows = maxY - minY + 1;

            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    StorageCell cell = new StorageCell();
                    cell.id = "r" + row + "_c" + column;
                    cell.plane = plane;
                    cell.width = 1;
                    cell.height = 1;
                    cell.origin = toStoredPos(minA + column, minY + row);
                    generated.add(cell);
                }
            }

            return generated;
        }

        private StoredPos toStoredPos(int a, int y) {
            return switch (fixedAxis) {
                case X -> new StoredPos(fixed, y, a);
                case Z -> new StoredPos(a, y, fixed);
            };
        }

        private int area() {
            return (maxA - minA + 1) * (maxY - minY + 1);
        }
    }

    private enum FixedAxis {
        X,
        Z
    }
}
