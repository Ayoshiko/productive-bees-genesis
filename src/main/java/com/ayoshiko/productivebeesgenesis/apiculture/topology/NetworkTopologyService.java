package com.ayoshiko.productivebeesgenesis.apiculture.topology;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.common.lib.security.ISecurityTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 每服一个公平扫描队列；只跟踪有核心的区块，事件失效不遍历机器或强制加载区块。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class NetworkTopologyService {
	private record ChunkKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int x, int z) { }
	private static final class ChunkState { long epoch, auditTick; int cores; ChunkState(long epoch) { this.epoch = epoch; } }
	private static final class Session {
		final Map<ChunkKey, ChunkState> chunks = new ConcurrentHashMap<>();
		final Set<GlobalPos> cores = ConcurrentHashMap.newKeySet();
		final Set<NetworkCoreBlockEntity> queued = ConcurrentHashMap.newKeySet();
		final ArrayDeque<NetworkCoreBlockEntity> waiting = new ArrayDeque<>();
		long sequence;
		NetworkCoreBlockEntity active;
		TopologyScan scan;
		long scanEpoch;
	}
	private static final Map<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
	private NetworkTopologyService() { }
	private static ChunkKey key(ServerLevel level, BlockPos pos) { return new ChunkKey(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4); }
	public static void watch(NetworkCoreBlockEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level)) return;
		var session = SESSIONS.computeIfAbsent(level.getServer(), ignored -> new Session());
		var key = key(level, core.getBlockPos()); var chunk = session.chunks.computeIfAbsent(key, ignored -> new ChunkState(++session.sequence));
		if (session.cores.add(GlobalPos.of(level.dimension(), core.getBlockPos()))) { chunk.cores++; chunk.epoch = ++session.sequence; }
		long now = level.getGameTime();
		if (now - chunk.auditTick >= 200) { chunk.auditTick = now; chunk.epoch = ++session.sequence; }
		if (core.topology() == null && session.active != core && session.queued.add(core)) session.waiting.addLast(core);
	}
	public static long epoch(ServerLevel level, BlockPos pos) {
		var session = SESSIONS.get(level.getServer()); var chunk = session == null ? null : session.chunks.get(key(level, pos));
		return chunk == null ? -1 : chunk.epoch;
	}
	public static void dirty(ServerLevel level, BlockPos pos) {
		if (!level.getServer().isSameThread()) { var stable = pos.immutable(); level.getServer().execute(() -> dirty(level, stable)); return; }
		var session = SESSIONS.get(level.getServer()); if (session == null) return;
		var chunk = session.chunks.get(key(level, pos)); if (chunk != null) chunk.epoch = ++session.sequence;
	}
	public static void remove(ServerLevel level, NetworkCoreBlockEntity core) {
		var session = SESSIONS.get(level.getServer()); if (session == null) return;
		var key = key(level, core.getBlockPos()); var chunk = session.chunks.get(key);
		if (chunk != null && session.cores.remove(GlobalPos.of(level.dimension(), core.getBlockPos()))) {
			chunk.epoch = ++session.sequence; if (--chunk.cores == 0) session.chunks.remove(key);
		}
	}
	private static TopologyScan.Node node(ServerLevel level, BlockPos pos) {
		if (level.isOutsideBuildHeight(pos) || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
		var tile = level.getBlockEntity(pos); if (tile == null || tile.isRemoved()) return null;
		if (tile instanceof NetworkCoreBlockEntity core) return new TopologyScan.Node(pos, core.owner(), true, core.closedFaces(), 0, 0);
		if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).getNamespace().equals("productivebeesgenesis")) return null;
		var owner = tile instanceof ISecurityTile security ? security.getOwnerUUID() : null;
		int faces = tile.getPersistentData().getInt("pbgNetworkClosedFaces") & 63;
		if (tile instanceof TileEntityMekApiary hive) return new TopologyScan.Node(pos, owner, false, faces, hive.getBeeSlotCount(), 0);
		if (tile instanceof PbRecipeContext machine) return new TopologyScan.Node(pos, owner, false, faces, 0, machine.processes());
		return null;
	}
	@SubscribeEvent public static void neighbors(BlockEvent.NeighborNotifyEvent event) {
		if (event.getLevel() instanceof ServerLevel level) dirty(level, event.getPos());
	}
	@SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) { chunkChanged(event); }
	@SubscribeEvent public static void chunkUnloaded(ChunkEvent.Unload event) { chunkChanged(event); }
	private static void chunkChanged(ChunkEvent event) {
		if (event.getLevel() instanceof ServerLevel level) {
			var pos = event.getChunk().getPos(); level.getServer().execute(() -> dirty(level, new BlockPos(pos.getMinBlockX(), 0, pos.getMinBlockZ())));
		}
	}
	public static boolean step(MinecraftServer server) {
		var session = SESSIONS.get(server); if (session == null || !ModConfig.SERVER.beeNetwork.enabled.get()) return false;
		if (session.active == null) {
			session.active = session.waiting.pollFirst(); if (session.active == null) return false;
			session.queued.remove(session.active);
		}
		var core = session.active;
		if (core.isRemoved() || !(core.getLevel() instanceof ServerLevel level) || core.owner() == null
				|| !level.hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || level.getBlockEntity(core.getBlockPos()) != core) { clear(session); return true; }
		long epoch = epoch(level, core.getBlockPos());
		if (session.scan != null && session.scanEpoch != epoch) {
			clear(session); if (session.queued.add(core)) session.waiting.addLast(core); return true;
		}
		if (session.scan == null) { session.scanEpoch = epoch; session.scan = new TopologyScan(core.getBlockPos(), core.owner(), epoch, pos -> node(level, pos)); }
		if (session.scan.step(epoch)) { core.publishTopology(session.scan.finish()); clear(session); }
		return true;
	}
	private static void clear(Session session) { session.active = null; session.scan = null; }
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) { SESSIONS.remove(event.getServer()); }
}
