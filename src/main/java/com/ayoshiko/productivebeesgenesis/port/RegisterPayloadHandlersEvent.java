package com.ayoshiko.productivebeesgenesis.port;
import com.ayoshiko.productivebeesgenesis.network.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import java.util.function.BiConsumer;
public final class RegisterPayloadHandlersEvent {
    public Registrar registrar(String version) { return new Registrar(); }
    public static final class Registrar {
        public <T extends CustomPacketPayload> void playToServer(CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf,T> codec, BiConsumer<T,ForgePayloadContext> handler) { register(type,codec,handler,NetworkDirection.PLAY_TO_SERVER); }
        public <T extends CustomPacketPayload> void playToClient(CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf,T> codec, BiConsumer<T,ForgePayloadContext> handler) { register(type,codec,handler,NetworkDirection.PLAY_TO_CLIENT); }
        @SuppressWarnings("unchecked") private <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf,T> codec, BiConsumer<T,ForgePayloadContext> handler, NetworkDirection dir) {
            // Nested record names correspond to their declared wire type.
            try {
                String path=type.id().getPath();
                Class<?> owner=path.equals("resource_catalog") ? com.ayoshiko.productivebeesgenesis.resource.ResourceCatalogSync.class : path.startsWith("resource_sides") ? com.ayoshiko.productivebeesgenesis.resource.ResourceSideConfigSync.class : com.ayoshiko.productivebeesgenesis.resource.ResourceMenuSync.class;
                for (Class<?> nested:owner.getDeclaredClasses()) {
                    if (!CustomPacketPayload.class.isAssignableFrom(nested)) continue;
                    if (nested.getField("TYPE").get(null)==type) { ForgeNetwork.register((Class<T>)nested,(value,buffer)->codec.encode(buffer,value),codec::decode,handler,dir); return; }
                }
                throw new IllegalStateException("Unregistered Forge payload: "+type.id());
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
}
