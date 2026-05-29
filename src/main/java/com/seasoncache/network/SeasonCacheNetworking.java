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

        PayloadTypeRegistry.clientboundPlay().register(SnapshotBeginPayload.TYPE, SnapshotBeginPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChunkStatesPayload.TYPE, ChunkStatesPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnapshotEndPayload.TYPE, SnapshotEndPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EpochInvalidatePayload.TYPE, EpochInvalidatePayload.STREAM_CODEC);

        SeasonCacheMod.LOGGER.info("Season Cache networking registered.");
    }
}
