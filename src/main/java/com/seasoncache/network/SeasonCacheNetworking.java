package com.seasoncache.network;

import com.seasoncache.SeasonCacheMod;
import com.seasoncache.network.payload.ChunkStatesPayload;
import com.seasoncache.network.payload.EpochInvalidatePayload;
import com.seasoncache.network.payload.SnapshotBeginPayload;
import com.seasoncache.network.payload.SnapshotEndPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SeasonCacheNetworking {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private SeasonCacheNetworking() {
    }

    public static void registerPayloadTypes() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        PayloadTypeRegistry.playS2C().register(SnapshotBeginPayload.ID, SnapshotBeginPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkStatesPayload.ID, ChunkStatesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SnapshotEndPayload.ID, SnapshotEndPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(EpochInvalidatePayload.ID, EpochInvalidatePayload.CODEC);

        SeasonCacheMod.LOGGER.info("Season Cache networking registered.");
    }
}
