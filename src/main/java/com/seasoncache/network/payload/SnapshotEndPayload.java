package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SnapshotEndPayload(Identifier dimensionId, int epoch) implements CustomPayload {
    public static final CustomPayload.Id<SnapshotEndPayload> ID = new CustomPayload.Id<>(Identifier.of(SeasonCacheMod.MOD_ID, "snapshot_end"));
    public static final PacketCodec<PacketByteBuf, SnapshotEndPayload> CODEC = CustomPayload.codecOf(SnapshotEndPayload::write, SnapshotEndPayload::new);

    public SnapshotEndPayload(PacketByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt());
    }

    private void write(PacketByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
