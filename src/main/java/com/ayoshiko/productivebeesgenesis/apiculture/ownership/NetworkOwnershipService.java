package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 全服共享交接预算，不持有区块或 BE；卸载后队列位置自然淘汰。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class NetworkOwnershipService {
	private static final class Session {
		final ArrayDeque<GlobalPos> queue = new ArrayDeque<>();
		final Set<GlobalPos> queued = ConcurrentHashMap.newKeySet();
	}
	private static final Map<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
	public static void watch(NetworkCoreBlockEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level) || core.network() == null) return;
		var session = SESSIONS.computeIfAbsent(level.getServer(), ignored -> new Session());
		var pos = GlobalPos.of(level.dimension(), core.getBlockPos()); if (session.queued.add(pos)) session.queue.addLast(pos);
	}
	public static boolean step(MinecraftServer server) {
		var session = SESSIONS.get(server); if (session == null || session.queue.isEmpty()) return false;
		var pos = session.queue.removeFirst(); session.queued.remove(pos); var level = server.getLevel(pos.dimension());
		if (level != null && level.hasChunk(pos.pos().getX() >> 4, pos.pos().getZ() >> 4)
				&& level.getBlockEntity(pos.pos()) instanceof NetworkCoreBlockEntity core && !core.isRemoved()) core.ownership().advance();
		return true;
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { SESSIONS.remove(event.getServer()); }
	private NetworkOwnershipService() { }
}
