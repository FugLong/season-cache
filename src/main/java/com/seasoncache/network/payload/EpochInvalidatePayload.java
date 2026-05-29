package com.seasoncache.network.payload;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record EpochInvalidatePayload(Identifier dimensionId, int epoch) implements CustomPacketPayload {
    public static final StreamCodec<FriendlyByteBuf, EpochInvalidatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(EpochInvalidatePayload::write, EpochInvalidatePayload::new);
    public static final CustomPacketPayload.Type<EpochInvalidatePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(SeasonCacheMod.MOD_ID, "epoch_invalidate"));

    public EpochInvalidatePayload(FriendlyByteBuf buf) {
        this(buf.readIdentifier(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeIdentifier(this.dimensionId);
        buf.writeVarInt(this.epoch);
    }

    @Override
    public Type<EpochInvalidatePayload> type() {
        return TYPE;
    }
}
