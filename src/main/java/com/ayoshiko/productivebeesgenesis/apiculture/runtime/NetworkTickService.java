package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.NetworkOwnershipService;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.topology.NetworkTopologyService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 唯一网络后台调度入口；世界保存和正常停服的耐久等待不属于可延期工作。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class NetworkTickService {
	public enum Service { PERSISTENCE, TOPOLOGY, OWNERSHIP, PRODUCTION }
	private static final class Session {
		final FairServiceBudget budget = new FairServiceBudget(4);
		final int[] limits = new int[4];
		final long[] times = new long[4];
	}
	private static final Map<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
	@SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
		var server = event.getServer(); var session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
		var config = ModConfig.SERVER.beeNetwork;
		session.limits[0] = 32; session.limits[1] = config.topologyNodes.get(); session.limits[2] = 4; session.limits[3] = config.runtimeSteps.get();
		session.times[0] = 2_000_000; session.times[1] = config.topologyMicros.get() * 1000L;
		session.times[2] = 2_000_000; session.times[3] = config.runtimeMicros.get() * 1000L;
		session.budget.run(server.getTickCount(), config.totalSteps.get(), config.totalMicros.get() * 1000L, session.limits, session.times,
				service -> switch (service) {
					case 0 -> NetworkPersistence.step(server);
					case 1 -> NetworkTopologyService.step(server);
					case 2 -> NetworkOwnershipService.step(server);
					case 3 -> NetworkRuntimeService.step(server);
					default -> throw new IllegalArgumentException("Unknown network service");
				});
	}
	/** 固定大小的本 tick 计数，供开发探针读取；不累计历史样本或世界引用。 */
	public static FairServiceBudget budget(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Read network metrics on the server thread");
		var session = SESSIONS.get(server); return session == null ? null : session.budget;
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { SESSIONS.remove(event.getServer()); }
	private NetworkTickService() { }
}
