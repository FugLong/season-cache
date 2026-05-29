package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SnapshotBeginPayload(Identifier dimensionId, int epoch) implements CustomPacketPayload {
    public static final StreamCodec<FriendlyByteBuf, SnapshotBeginPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SnapshotBeginPayload::write, SnapshotBeginPayload::new);
    public static final CustomPacketPayload.Type<SnapshotBeginPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SeasonCacheMod.MOD_ID, "snapshot_begin"));

    public SnapshotBeginPayload(FriendlyByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
    }

    @Override
    public Type<SnapshotBeginPayload> type() {
        return TYPE;
    }
}
