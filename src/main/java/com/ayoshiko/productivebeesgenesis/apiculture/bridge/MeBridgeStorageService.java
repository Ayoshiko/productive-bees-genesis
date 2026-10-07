package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import com.ayoshiko.productivebeesgenesis.apiculture.runtime.FairDueQueue;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 桥只登记位置，键投影与资格编译在全服网络预算内轮转。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MeBridgeStorageService {
	private static final Map<MinecraftServer, FairDueQueue<GlobalPos>> QUEUES = new HashMap<>();
	static void watch(MeBridgeBlockEntity bridge) {
		var level = (ServerLevel) bridge.getLevel();
		QUEUES.computeIfAbsent(level.getServer(), ignored -> new FairDueQueue<>()).offer(GlobalPos.of(level.dimension(), bridge.getBlockPos()), level.getServer().overworld().getGameTime());
	}
	static void remove(MeBridgeBlockEntity bridge) {
		if (bridge.getLevel() instanceof ServerLevel level) {
			var queue = QUEUES.get(level.getServer()); if (queue != null) queue.remove(GlobalPos.of(level.dimension(), bridge.getBlockPos()));
		}
	}

	/** 同步撤销时失效相邻桥缓存，不能等下一 tick 才隐藏暂停库存。 */
	public static void hostChanged(net.minecraft.world.level.block.entity.BlockEntity host) {
		if (!(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
		for (var side : net.minecraft.core.Direction.values()) {
			var pos = host.getBlockPos().relative(side);
			var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk != null && chunk.getBlockEntity(pos) instanceof MeBridgeBlockEntity bridge && bridge.link() != null) {
				try { bridge.link().tick(); } catch (RuntimeException | LinkageError error) { bridge.isolate(error); }
			}
		}
	}
	public static boolean step(MinecraftServer server) {
		var queue = QUEUES.get(server); if (queue == null) return false;
		var pos = queue.poll(server.overworld().getGameTime()); if (pos == null) return false;
		var level = server.getLevel(pos.dimension());
		if (level != null && level.getChunkSource().getChunkNow(pos.pos().getX() >> 4, pos.pos().getZ() >> 4) != null
				&& level.getBlockEntity(pos.pos()) instanceof MeBridgeBlockEntity bridge && MeBridgeTarget.live(bridge) && bridge.automation() && bridge.link() != null)
			bridge.link().storageStep();
		return true;
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { QUEUES.remove(event.getServer()); }
	private MeBridgeStorageService() { }
}
