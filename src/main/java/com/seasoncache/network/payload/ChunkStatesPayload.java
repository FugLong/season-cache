package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ChunkStatesPayload(Identifier dimensionId, int epoch, long[] packedChunkStates) implements CustomPayload {
    public static final CustomPayload.Id<ChunkStatesPayload> ID = new CustomPayload.Id<>(Identifier.of(SeasonCacheMod.MOD_ID, "chunk_states"));
    public static final PacketCodec<PacketByteBuf, ChunkStatesPayload> CODEC = CustomPayload.codecOf(ChunkStatesPayload::write, ChunkStatesPayload::new);

    public ChunkStatesPayload(PacketByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt(), readPackedChunkStates(buf));
    }

    private void write(PacketByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
        buf.writeVarInt(this.packedChunkStates.length);
        for (long packedChunkState : this.packedChunkStates) {
            buf.writeLong(packedChunkState);
        }
    }

    private static long[] readPackedChunkStates(PacketByteBuf buf) {
        int size = buf.readVarInt();
        long[] packed = new long[size];
        for (int i = 0; i < size; i++) {
            packed[i] = buf.readLong();
        }
        return packed;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
