package com.seasoncache.client;

import com.seasoncache.network.SeasonCacheNetworking;
import com.seasoncache.network.payload.ChunkStatesPayload;
import com.seasoncache.network.payload.EpochInvalidatePayload;
import com.seasoncache.network.payload.SnapshotBeginPayload;
import com.seasoncache.network.payload.SnapshotEndPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class SeasonCacheClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SeasonCacheNetworking.registerPayloadTypes();

        ClientPlayNetworking.registerGlobalReceiver(SnapshotBeginPayload.TYPE, (payload, context) ->
                SeasonCacheClientState.beginSnapshot(payload.dimensionId(), payload.epoch()));

        ClientPlayNetworking.registerGlobalReceiver(ChunkStatesPayload.TYPE, (payload, context) ->
                SeasonCacheClientState.applyChunkBatch(payload.dimensionId(), payload.epoch(), payload.packedChunkStates()));

        ClientPlayNetworking.registerGlobalReceiver(SnapshotEndPayload.TYPE, (payload, context) ->
                SeasonCacheClientState.endSnapshot(payload.dimensionId(), payload.epoch()));

        ClientPlayNetworking.registerGlobalReceiver(EpochInvalidatePayload.TYPE, (payload, context) ->
                SeasonCacheClientState.applyInvalidate(payload.dimensionId(), payload.epoch()));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SeasonCacheClientState.resetAll());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SeasonCacheClientState.resetAll());
    }
}
