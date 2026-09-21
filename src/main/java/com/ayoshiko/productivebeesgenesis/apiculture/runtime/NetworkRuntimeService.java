package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 全服共享工作预算；等待索引仅持有位置，不保活核心、区块或世界能力。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class NetworkRuntimeService {
	private static final Map<MinecraftServer, FairDueQueue<GlobalPos>> SESSIONS = new ConcurrentHashMap<>();
	public static void watch(NetworkCoreBlockEntity core, boolean wake) {
		if (!(core.getLevel() instanceof ServerLevel level) || !core.hasProductionSession() || core.network() == null) return;
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime registration belongs to the server thread");
		var queue = SESSIONS.computeIfAbsent(level.getServer(), ignored -> new FairDueQueue<>());
		var position = GlobalPos.of(level.dimension(), core.getBlockPos()); long now = level.getServer().overworld().getGameTime();
		if (wake) queue.wake(position, now); else queue.offer(position, now);
	}
	public static void remove(ServerLevel level, NetworkCoreBlockEntity core) {
		var queue = SESSIONS.get(level.getServer()); if (queue != null) queue.remove(GlobalPos.of(level.dimension(), core.getBlockPos()));
	}
	@SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
		var server = event.getServer(); var queue = SESSIONS.get(server); if (queue == null) return;
		long now = server.overworld().getGameTime();
		queue.runDue(now, ModConfig.SERVER.beeNetwork.runtimeSteps.get(), ModConfig.SERVER.beeNetwork.runtimeMicros.get() * 1000L, position -> {
			var level = server.getLevel(position.dimension());
			if (level == null || !level.hasChunk(position.pos().getX() >> 4, position.pos().getZ() >> 4)
					|| !(level.getBlockEntity(position.pos()) instanceof NetworkCoreBlockEntity core) || core.isRemoved() || !core.hasProductionSession()) return Long.MAX_VALUE;
			try { return core.runtime().step(core, now); }
			catch (RuntimeException failure) {
				core.runtime().failed(); com.mojang.logging.LogUtils.getLogger().error("Bee network runtime at {} paused for retry", position, failure); return now + 200;
			}
		});
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { SESSIONS.remove(event.getServer()); RuntimeProductPolicies.clear(event.getServer()); }
	private NetworkRuntimeService() { }
}
