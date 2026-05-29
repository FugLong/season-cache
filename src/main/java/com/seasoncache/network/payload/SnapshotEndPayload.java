package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SnapshotEndPayload(Identifier dimensionId, int epoch) implements CustomPacketPayload {
    public static final StreamCodec<FriendlyByteBuf, SnapshotEndPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SnapshotEndPayload::write, SnapshotEndPayload::new);
    public static final CustomPacketPayload.Type<SnapshotEndPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SeasonCacheMod.MOD_ID, "snapshot_end"));

    public SnapshotEndPayload(FriendlyByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
    }

    @Override
    public Type<SnapshotEndPayload> type() {
        return TYPE;
    }
}
