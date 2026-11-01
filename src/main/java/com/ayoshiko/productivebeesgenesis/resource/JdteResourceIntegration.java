package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceHostsEvent;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostAdapter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** 常驻注册入口不链接 JDTE／JDT 类；实际生产桥只由已核对版本的 Mixin 实现。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class JdteResourceIntegration {
	private JdteResourceIntegration() {}

	public interface Host {
		boolean productivebeesgenesis$hasLiveResourceWork();
		boolean productivebeesgenesis$canAccessResources(ServerPlayer player);
	}

	@SubscribeEvent
	public static void register(RegisterResourceHostsEvent event) {
		if (ModList.get().isLoaded("jdte") && JdteResourceMixinContract.publicApiSupported()) {
			JdtePublicResourceBridge.register(event);
			return;
		}
		if (ModList.get().getModContainerById("jdte")
				.filter(mod -> mod.getModInfo().getVersion().toString().equals("0.5.8-forge-1.20.1")).isEmpty()) return;
		var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(new ResourceLocation("jdte:bio_factory"));
		if (type == null) return;
		event.register(type, new ResourceHostAdapter() {
			@Override public boolean ready(BlockEntity host) { return host instanceof Host; }
			@Override public boolean canAccess(ServerPlayer player, BlockEntity host) {
				return host instanceof Host bridge && bridge.productivebeesgenesis$canAccessResources(player);
			}
			@Override public boolean hasLiveWork(BlockEntity host) {
				return !(host instanceof Host bridge) || bridge.productivebeesgenesis$hasLiveResourceWork();
			}
		});
	}

	@SubscribeEvent
	public static void serverStarting(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
		if (ModList.get().isLoaded("jdte") && JdteResourceMixinContract.publicApiSupported()) JdtePublicResourceBridge.install();
	}
}
