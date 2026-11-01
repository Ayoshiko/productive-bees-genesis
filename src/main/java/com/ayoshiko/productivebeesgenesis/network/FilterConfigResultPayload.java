package com.ayoshiko.productivebeesgenesis.network;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
public record FilterConfigResultPayload(UUID request, boolean success, String message, String snapshot) {
    public static Consumer<FilterConfigResultPayload> receiver = result -> {};
    public static FilterConfigResultPayload decode(FriendlyByteBuf b) { return new FilterConfigResultPayload(b.readUUID(), b.readBoolean(), b.readUtf(512), b.readUtf(BeeDesignerProtocol.MAX_BYTES)); }
    public void encode(FriendlyByteBuf b) { BeeDesignerProtocol.checkSize(snapshot); b.writeUUID(request); b.writeBoolean(success); b.writeUtf(message,512); b.writeUtf(snapshot,BeeDesignerProtocol.MAX_BYTES); }
}
