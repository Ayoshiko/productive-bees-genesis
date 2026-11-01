package com.ayoshiko.productivebeesgenesis.port;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.*;
import net.minecraft.core.Direction;
@Mod.EventBusSubscriber(modid="productivebeesgenesis")
public final class ForgeResourceHostState {
    public static final Capability<ResourceHiveHost> CAP=CapabilityManager.get(new CapabilityToken<>(){});
    private static final java.util.Map<BlockEntity,java.lang.ref.WeakReference<Provider>> HOSTS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static Provider provider(BlockEntity host){var reference=HOSTS.get(host);return reference==null?null:reference.get();}
    public static ResourceHiveHost existing(BlockEntity host) { Provider p=provider(host); return p==null || !p.active ? null : p.state; }
    public static ResourceHiveHost get(BlockEntity host) { Provider p=provider(host);if(p==null)throw new IllegalStateException("Host lacks resource capability");p.active=true;return p.state; }
    public static void set(BlockEntity host,ResourceHiveHost state) { Provider p=provider(host); if(p==null) throw new IllegalStateException("Host lacks resource capability"); p.state=state; p.active=true; p.value.invalidate(); p.value=LazyOptional.of(()->p.state); host.setChanged(); }
    @SubscribeEvent public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        // Third-party host adapters register at server start; attach a dormant
        // reference to all block entities, but ResourceHiveHost.supports still
        // strictly controls production and account binding.
        Provider p=new Provider(event.getObject()); HOSTS.put(event.getObject(),new java.lang.ref.WeakReference<>(p));
        event.addCapability(new ResourceLocation("productivebeesgenesis:resource_host"),p);
        event.addListener(()->p.value.invalidate());
    }
    private static final class Provider implements ICapabilitySerializable<CompoundTag> {
        final java.lang.ref.WeakReference<BlockEntity> host;
        Provider(BlockEntity host){this.host=new java.lang.ref.WeakReference<>(host);}
        boolean active; ResourceHiveHost state=new ResourceHiveHost(); LazyOptional<ResourceHiveHost> value=LazyOptional.of(()->state);
        public <T> LazyOptional<T> getCapability(Capability<T> cap,Direction side) { if(cap==CAP)return value.cast();var tile=host.get();return tile==null?LazyOptional.empty():ForgeBlockCapabilities.view(tile,cap,side); }
        public CompoundTag serializeNBT() { return active ? state.serializeNBT() : new CompoundTag(); }
        public void deserializeNBT(CompoundTag data) { if(!data.isEmpty()){active=true;state.deserializeNBT(data);} }
    }
}
