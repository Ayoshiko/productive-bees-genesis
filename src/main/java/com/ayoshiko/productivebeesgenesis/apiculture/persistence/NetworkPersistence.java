package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 所有维度共享一个保存队列；工作线程仅编码不可变 checkpoint 并流式写盘。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class NetworkPersistence {
	private record Session(NetworkDirectory directory, ThreadPoolExecutor writer) { }
	private static final Map<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
	private NetworkPersistence() { }
	public static NetworkDirectory directory(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Open network storage on the server thread");
		return SESSIONS.computeIfAbsent(server, current -> {
			// 完成回执可能先于线程重新等待任务；一个交接槽避免停服连写时误报拒绝。
			var writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), action -> {
				Thread thread = new Thread(action, "pbg-network-checkpoints"); thread.setDaemon(true); return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
			return new Session(new NetworkDirectory(current.getWorldPath(LevelResource.ROOT).resolve("data"),
					current.registryAccess(), writer, current.overworld().getDataStorage()), writer);
		}).directory();
	}
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		var session = SESSIONS.get(event.getServer());
		if (session != null) session.directory().tick();
	}
	@SubscribeEvent public static void stopping(ServerStoppingEvent event) {
		var session = SESSIONS.remove(event.getServer());
		if (session == null) return;
		try { session.directory().flush(); }
		catch (IOException failure) { LogUtils.getLogger().error("Bee network checkpoint flush failed during shutdown; previous files retained", failure); }
		finally { session.writer().shutdown(); }
	}
}
