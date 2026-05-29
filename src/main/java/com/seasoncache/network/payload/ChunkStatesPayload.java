package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ChunkStatesPayload(Identifier dimensionId, int epoch, long[] packedChunkStates) implements CustomPacketPayload {
    public static final StreamCodec<FriendlyByteBuf, ChunkStatesPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ChunkStatesPayload::write, ChunkStatesPayload::new);
    public static final CustomPacketPayload.Type<ChunkStatesPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SeasonCacheMod.MOD_ID, "chunk_states"));

    public ChunkStatesPayload(FriendlyByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt(), readPackedChunkStates(buf));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
        buf.writeVarInt(this.packedChunkStates.length);
        for (long packed : this.packedChunkStates) {
            buf.writeLong(packed);
        }
    }

    private static long[] readPackedChunkStates(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readLong();
        }
        return values;
    }

    @Override
    public Type<ChunkStatesPayload> type() {
        return TYPE;
    }
}
