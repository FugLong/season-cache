package com.seasoncache.core;

import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.core.store.ChunkSeasonStore;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegionPrecacheBuilder {
    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private final SeasonCacheConfig config;
    private final ChunkSeasonStore store;
    private final ArrayDeque<Path> pending = new ArrayDeque<>();

    private RuntimeTypes.BudgetProfile activeProfile = RuntimeTypes.BudgetProfile.LOW;
    private int totalFiles;
    private int processedFiles;
    private boolean active;

    public RegionPrecacheBuilder(SeasonCacheConfig config, ChunkSeasonStore store) {
        this.config = config;
        this.store = store;
    }

    public boolean isActive() { return this.active; }
    public int totalFiles() { return this.totalFiles; }
    public int processedFiles() { return this.processedFiles; }
    public RuntimeTypes.BudgetProfile activeProfile() { return this.activeProfile; }

    public void start(ServerWorld world, RuntimeTypes.BudgetProfile profile) {
        this.pending.clear();
        this.processedFiles = 0;
        this.totalFiles = 0;
        this.activeProfile = profile;

        Path regionDir = getRegionDirectory(world);
        if (!Files.isDirectory(regionDir)) {
            this.active = false;
            return;
        }

        try {
            List<Path> files = Files.list(regionDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.naturalOrder())
                    .toList();

            this.pending.addAll(files);
            this.totalFiles = files.size();
            this.active = !files.isEmpty();
        } catch (Exception e) {
            this.active = false;
        }
    }

    public void tick(ServerWorld world) {
        if (!this.active) return;

        RuntimeTypes.Budget budget = this.config.budgetFor(this.activeProfile);

        for (int i = 0; i < budget.regionsPerTick() && !this.pending.isEmpty(); i++) {
            Path next = this.pending.removeFirst();
            scanRegionHeader(world, next);
            this.processedFiles++;
        }

        if (this.pending.isEmpty()) {
            this.active = false;
            this.store.flushDirty();
        }
    }

    private void scanRegionHeader(ServerWorld world, Path regionFile) {
        Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) return;

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        byte[] header = new byte[4096];

        try (InputStream in = Files.newInputStream(regionFile)) {
            if (in.read(header) < 4096) return;
        } catch (Exception e) {
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int chunkCount = 0;

        for (int index = 0; index < 1024; index++) {
            int location = buffer.getInt(index * 4);
            if (location == 0) continue;
            chunkCount++;
        }

        // Record how many chunks actually exist in this region so the store can
        // promote it to "fully clean" once all of them have been reconciled.
        if (chunkCount > 0) {
            this.store.setKnownChunkCount(world, regionX, regionZ, chunkCount);
        }
    }

    /**
     * Resolves the Anvil region directory for the given world.
     * Overworld uses {@code <root>/region/}; Nether and End use their legacy paths;
     * custom dimensions use the 1.16+ convention.
     */
    private static Path getRegionDirectory(ServerWorld world) {
        Path root = world.getServer().getSavePath(WorldSavePath.ROOT);
        RegistryKey<World> key = world.getRegistryKey();

        if (key == World.OVERWORLD) {
            return root.resolve("region");
        } else if (key == World.NETHER) {
            return root.resolve("DIM-1").resolve("region");
        } else if (key == World.END) {
            return root.resolve("DIM1").resolve("region");
        } else {
            Identifier id = key.getValue();
            return root.resolve("dimensions")
                       .resolve(id.getNamespace())
                       .resolve(id.getPath())
                       .resolve("region");
        }
    }
}
