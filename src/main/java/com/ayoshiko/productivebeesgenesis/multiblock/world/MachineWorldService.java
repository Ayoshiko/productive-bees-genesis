package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.validation.StructureScan;
import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** 一个全服队列；每次 step 至多推进一个扫描格，唯一调度入口为 NetworkTickService。 */
public final class MachineWorldService {
	private record ScanJob(StructureScan scan, MachineDirectory.Binding auditing) {
		void cancel() { scan.cancel(); }
	}
	private static final class Session {
		final Map<ServerLevel, MachineDirectory> directories = new ConcurrentHashMap<>();
		final Set<MachineControllerEntity> watched = ConcurrentHashMap.newKeySet(), queued = ConcurrentHashMap.newKeySet();
		final ArrayDeque<MachineControllerEntity> waiting = new ArrayDeque<>(), audit = new ArrayDeque<>();
		final Map<MachineControllerEntity, ScanJob> scans = new ConcurrentHashMap<>();
		boolean auditTurn;
	}
	private static final Map<MinecraftServer, Session> SESSIONS = new ConcurrentHashMap<>();
	public static boolean watches(Level world) {
		if (!(world instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
		var session = SESSIONS.get(level.getServer());
		var directory = session == null ? null : session.directories.get(level);
		return directory != null && directory.size() != 0;
	}
	public static void blockChanged(Level world, BlockPos pos, BlockState before, BlockState after) {
		if (before == after || before.getBlock() == after.getBlock()
				&& (!before.hasProperty(MachinePartBlock.FACING) || before.getValue(MachinePartBlock.FACING) == after.getValue(MachinePartBlock.FACING))) return;
		changed(world, pos);
	}
	public static boolean active(Level level, MachineDirectory.Binding binding) {
		if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return false;
		var session = SESSIONS.get(server.getServer()); var directory = session == null ? null : session.directories.get(server);
		if (directory == null || !directory.active(binding)) return false;
		// FULL 票据可能先降级，实际 Unload 事件因邻区依赖滞后；访问本身也必须关门。
		for (var chunk : binding.handle().chunks()) if (!server.hasChunk(chunk.x, chunk.z)) {
			invalidateHandle(session, server, binding.handle(), MachineDirectory.State.SUSPENDED);
			return false;
		}
		return true;
	}
	public static int tracked(MinecraftServer server) { var session = SESSIONS.get(server); return session == null ? 0 : session.watched.size(); }
	public static void watch(MachineControllerEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level) || core.isRemoved()) return;
		if (!level.getServer().isSameThread()) { level.getServer().execute(() -> watch(core)); return; }
		core.publishState();
		if (!core.readyIdentity() || !level.hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || level.getBlockEntity(core.getBlockPos()) != core) return;
		var session = SESSIONS.computeIfAbsent(level.getServer(), ignored -> new Session());
		if (session.watched.contains(core)) return;
		var directory = session.directories.computeIfAbsent(level, ignored -> new MachineDirectory(CombinedApiaryDefinition.DEFINITION));
		try { core.handle = directory.attach(core.machineId(), core.generation(), core.getBlockPos(), core.getBlockState().getValue(MachinePartBlock.FACING)); }
		catch (RuntimeException failure) { core.registrationFailed = true; core.publishState(); LogUtils.getLogger().warn("Machine registration failed at {}", core.getBlockPos(), failure); return; }
		session.watched.add(core); session.audit.addLast(core); enqueue(session, core);
		// 重复 UUID 会使原有控制器也失效，显示只是投影；运行资格已在目录中同步撤销。
		for (var other : directory.sameIdentity(core.handle)) sync(level, other);
	}
	public static void request(MachineControllerEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level)) return;
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Request machine validation on the server thread");
		core.registrationFailed = false;
		watch(core);
		if (core.handle == null) return;
		var session = SESSIONS.get(level.getServer()); invalidate(session, core, MachineDirectory.State.REBUILDING);
	}
	private static void enqueue(Session session, MachineControllerEntity core) { if (session.queued.add(core)) session.waiting.addLast(core); }
	private static void invalidate(Session session, MachineControllerEntity core, MachineDirectory.State state) {
		var scan = session.scans.remove(core); if (scan != null) scan.cancel();
		var directory = session.directories.get(core.getLevel());
		if (directory == null || core.handle == null) return;
		directory.invalidate(core.handle, state); core.publishState();
		if (core.status() != MachineDirectory.State.RECOVERY) enqueue(session, core);
	}
	public static void changed(Level world, BlockPos pos) {
		if (!(world instanceof ServerLevel level)) return;
		if (!level.getServer().isSameThread()) { var stable = pos.immutable(); level.getServer().execute(() -> changed(level, stable)); return; }
		var session = SESSIONS.get(level.getServer()); var directory = session == null ? null : session.directories.get(level);
		if (directory == null) return;
		for (var handle : directory.affectedAt(pos)) invalidateHandle(session, level, handle, MachineDirectory.State.REBUILDING);
	}
	static void chunkChanged(ServerLevel level, ChunkPos chunk, boolean unloading) {
		var session = SESSIONS.get(level.getServer()); var directory = session == null ? null : session.directories.get(level);
		if (directory == null) return;
		for (var handle : directory.affectedChunk(chunk)) invalidateHandle(session, level, handle,
				unloading ? MachineDirectory.State.SUSPENDED : MachineDirectory.State.REBUILDING);
	}
	private static void invalidateHandle(Session session, ServerLevel level, MachineDirectory.Handle handle, MachineDirectory.State state) {
		// 卸载区块不通过 getBlockEntity 强制取回；目录凭据仍立即失效。
		if (level.hasChunk(handle.controller().getX() >> 4, handle.controller().getZ() >> 4) && level.getBlockEntity(handle.controller()) instanceof MachineControllerEntity core && core.handle == handle) {
			invalidate(session, core, state);
		} else session.directories.get(level).invalidate(handle, state);
	}
	public static boolean step(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Advance machine validation on the server thread");
		var session = SESSIONS.get(server); if (session == null) return false;
		// 审计与扫描交替消费工作单位；持续排队不能饿死低频补漏。
		if (session.waiting.isEmpty() || (session.auditTurn = !session.auditTurn)) {
			var core = session.audit.pollFirst(); if (core == null) return false;
			session.audit.addLast(core);
			if (server.getTickCount() - core.auditedAt >= 200 && !session.queued.contains(core)
					&& !session.scans.containsKey(core) && core.status() != MachineDirectory.State.RECOVERY) {
				core.auditedAt = server.getTickCount(); beginAudit(session, core); return true;
			}
			return !session.waiting.isEmpty();
		}
		var core = session.waiting.pollFirst();
		session.queued.remove(core);
		if (!(core.getLevel() instanceof ServerLevel level) || core.isRemoved() || core.handle == null
				|| !level.hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4) || level.getBlockEntity(core.getBlockPos()) != core) { remove(core); return true; }
		if (core.handle.facing() != core.getBlockState().getValue(MachinePartBlock.FACING)) { remove(core); watch(core); return true; }
		var directory = session.directories.get(level);
		try {
			var job = session.scans.get(core);
			if (job == null) {
				if (!directory.beginValidation(core.handle)) return true;
				core.publishState();
				job = new ScanJob(new StructureScan(CombinedApiaryDefinition.DEFINITION, core.handle.stamp(), core.getBlockPos(), core.handle.facing()), null);
				session.scans.put(core, job);
			}
			var scan = job.scan();
			var step = scan.advance(1, new StructureWorldAccess(level, core.handle));
			if (step.status() == StructureScan.Status.SCANNING) { enqueue(session, core); return true; }
			session.scans.remove(core); core.auditedAt = server.getTickCount();
			if (job.auditing() != null) {
				// 只读复核不能重发绑定，也不能让一个过期作业复活旧资格。
				if (step.status() != StructureScan.Status.MATCHED || !directory.active(job.auditing())
						|| scan.readyMatch(core.handle.stamp()).isEmpty()) {
					invalidate(session, core, step.status() == StructureScan.Status.FAILED ? MachineDirectory.State.RECOVERY
							: step.status() == StructureScan.Status.SUSPENDED ? MachineDirectory.State.SUSPENDED : MachineDirectory.State.REBUILDING);
				}
			} else if (step.status() == StructureScan.Status.MATCHED) {
				var match = scan.readyMatch(core.handle.stamp()).orElseThrow(); var controller = core;
				directory.form(core.handle, match, binding -> bindParts(level, controller, match, binding));
				var affected = ConcurrentHashMap.<MachineDirectory.Handle>newKeySet();
				for (var key : core.handle.candidates().sections()) affected.addAll(directory.affectedChunk(new ChunkPos(key.x(), key.z())));
				for (var entry : affected) sync(level, entry);
			} else {
				directory.invalidate(core.handle, switch (step.status()) {
					case SUSPENDED -> MachineDirectory.State.SUSPENDED;
					case INVALID -> MachineDirectory.State.UNFORMED;
					case STALE, CANCELLED -> MachineDirectory.State.REBUILDING;
					default -> MachineDirectory.State.RECOVERY;
				});
			}
			scan.failure().ifPresent(error -> LogUtils.getLogger().warn("Machine structure scan failed at {}", level.dimension().location(), error));
			if (core.handle.failure().isPresent()) LogUtils.getLogger().warn("Machine formation failed at {}", core.getBlockPos(), core.handle.failure().get());
			core.publishState();
		} catch (RuntimeException failure) {
			session.scans.remove(core); directory.invalidate(core.handle, MachineDirectory.State.RECOVERY); core.publishState();
			LogUtils.getLogger().warn("Machine validation failed at {}", core.getBlockPos(), failure);
		}
		return true;
	}
	private static void beginAudit(Session session, MachineControllerEntity core) {
		try {
			var binding = core.handle.binding().orElse(null);
			if (binding == null || !active(core.getLevel(), binding)) {
				invalidate(session, core, MachineDirectory.State.REBUILDING); return;
			}
			var definition = CombinedApiaryDefinition.DEFINITION;
			var template = definition.candidates().stream().filter(candidate -> candidate.variant().equals(binding.variant())).findFirst().orElseThrow();
			// 已形成的唯一模板逐格复核；事件失效或审计失败后才重新遍历全部候选。
			var scan = new StructureScan(new StructureDefinition(definition.id(), definition.layoutVersion(), List.of(template)),
					binding.stamp(), core.getBlockPos(), core.handle.facing());
			session.scans.put(core, new ScanJob(scan, binding)); enqueue(session, core);
		} catch (RuntimeException failure) {
			invalidate(session, core, MachineDirectory.State.RECOVERY);
			LogUtils.getLogger().warn("Cannot start machine audit at {}", core.getBlockPos(), failure);
		}
	}
	private static void bindParts(ServerLevel level, MachineControllerEntity core, StructureScan.Match match, MachineDirectory.Binding binding) {
		var transform = match.template().geometry().at(core.getBlockPos(), match.facing());
		for (var local : match.template().features().keySet()) {
			var pos = transform.toWorld(local);
			if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) throw new IllegalStateException("Part chunk unloaded during formation");
			var tile = level.getBlockEntity(pos);
			if (pos.equals(core.getBlockPos())) { if (tile != core) throw new IllegalStateException("Controller replaced during formation"); }
			else if (tile instanceof MachinePartEntity part && !part.isRemoved()) part.bind(binding);
			else throw new IllegalStateException("Missing machine part entity");
		}
	}
	private static void sync(ServerLevel level, MachineDirectory.Handle handle) {
		if (level.hasChunk(handle.controller().getX() >> 4, handle.controller().getZ() >> 4) && level.getBlockEntity(handle.controller()) instanceof MachineControllerEntity core) core.publishState();
	}
	public static void remove(MachineControllerEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level)) return;
		var session = SESSIONS.get(level.getServer()); if (session == null) return;
		var directory = session.directories.get(level); var removed = core.handle;
		var peers = directory != null && removed != null ? directory.sameIdentity(removed) : List.<MachineDirectory.Handle>of();
		if (directory != null && removed != null) directory.remove(removed);
		core.handle = null; session.watched.remove(core); session.queued.remove(core); session.waiting.remove(core); session.audit.remove(core);
		var scan = session.scans.remove(core); if (scan != null) scan.cancel();
		for (var peer : peers) if (peer != removed && directory.current(peer)) {
			if (peer.state() == MachineDirectory.State.REBUILDING) invalidateHandle(session, level, peer, MachineDirectory.State.REBUILDING);
			else sync(level, peer);
		}
	}
	static void unload(ServerLevel level) {
		var session = SESSIONS.get(level.getServer()); if (session == null) return;
		for (var core : List.copyOf(session.watched)) if (core.getLevel() == level) remove(core);
		var directory = session.directories.remove(level); if (directory != null) directory.clear();
	}
	static void stop(MinecraftServer server) {
		var session = SESSIONS.remove(server); if (session == null) return;
		session.directories.values().forEach(MachineDirectory::clear); session.watched.forEach(core -> core.handle = null);
	}
	private MachineWorldService() { }
}
