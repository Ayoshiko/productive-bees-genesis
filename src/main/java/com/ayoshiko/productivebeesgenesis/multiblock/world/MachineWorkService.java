package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineCapacity;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** 正式独立工作入口；每次读取和提交都核对当前 BE、结构绑定、单机资产锚点及运行模式。 */
public final class MachineWorkService {
	/** 一次服务访问的凭据；重扫、卸载、替换或任何资产提交后均失效。 */
	public static final class Access {
		private final MachineControllerEntity core;
		private final com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory.Binding binding;
		private final MachineAssets assets;
		private final CombinedMachineWork work;
		private Access(MachineControllerEntity core) {
			this.core = core; binding = core.handle.binding().orElseThrow(); assets = core.assets; work = assets.work();
		}
		public CombinedMachineWork work() { return work; }
		private boolean current() {
			return active(core) && core.assets == assets && assets.work() == work && core.handle.binding().orElse(null) == binding;
		}
	}
	public static Optional<Access> access(MachineControllerEntity core) { return active(core) ? Optional.of(new Access(core)) : Optional.empty(); }
	static String dataName(UUID machine) { return "pbg_machine_" + machine; }
	static void attach(MachineControllerEntity core) {
		var level = (ServerLevel) core.getLevel(); var server = level.getServer();
		if (!server.isSameThread()) throw new IllegalStateException("Attach machine assets on the server thread");
		var name = dataName(core.machineId());
		var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name + ".dat");
		var storage = server.overworld().getDataStorage();
		var factory = new SavedData.Factory<MachineAssets>(() -> { throw new IllegalStateException("Machine assets require explicit creation"); }, MachineAssets::load, null);
		var assets = storage.get(factory, name);
		if (assets == null) {
			// 原生 get 在读取失败时也返回 null；只有确认文件不存在且控制器从未引用资产，才允许创建。
			if (core.assetReferenced || !Files.notExists(file)) throw new IllegalStateException("Missing or unreadable referenced machine assets: " + name);
			assets = new MachineAssets(core.ownerId(), level.dimension().location(), core.getBlockPos(),
					CombinedMachineCapacity.empty(core.machineId(), core.generation()));
			storage.set(name, assets);
			// 首次空资产真实落盘后才发布引用，失败不开放物料入口。
			assets.save(file.toFile(), level.registryAccess());
		}
		if (!assets.matches(core.machineId(), core.generation(), core.ownerId(), level.dimension().location(), core.getBlockPos()))
			throw new IllegalStateException("Machine asset identity conflict or invalid checkpoint: " + name + " " + assets.failure());
		core.assets = assets;
		if (!assets.available()) throw new IllegalStateException("Machine assets quarantined: " + assets.failure());
		if (!core.assetReferenced) { core.assetReferenced = true; core.setChanged(); }
	}
	public static Optional<CombinedMachineWork> view(MachineControllerEntity core) {
		return access(core).map(Access::work);
	}
	static boolean active(MachineControllerEntity core) {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || core.isRemoved()
				|| !core.readyIdentity() || !core.assetReferenced || core.assets == null || !core.assets.available() || !core.formed()) return false;
		// Level.getBlockEntity 会经 getChunk 续 UNKNOWN 票据；轮询必须使用不取区块的快照入口。
		var chunk = level.getChunkSource().getChunkNow(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4);
		return chunk != null && chunk.getBlockEntity(core.getBlockPos()) == core
				&& core.assets.matches(core.machineId(), core.generation(), core.ownerId(), level.dimension().location(), core.getBlockPos());
	}
	/** 只接受内核生成的候选；模拟调用仅计算候选，不调用本方法。 */
	static boolean commit(Access access, CombinedMachineWork.Change change) {
		if (!access.current()) return false;
		var after = change.apply(access.work); access.assets.commit(access.work, after); return after != access.work;
	}
	static void step(MachineControllerEntity core) {
		var access = access(core).orElse(null); if (access == null) return;
		var level = (ServerLevel) core.getLevel(); var before = access.work(); var next = before;
		try {
			var environment = new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering());
			for (var bee : before.bees()) {
				// M04c 才接真实喂食与新周期能力；这里仅处理已经付款的采样和交付，不伪造花朵条件。
				if (bee.pendingCycles() > 0) {
					var context = new BeeWorkExecutor.Context(true, false, false, bee.plan().recipeRevision(), bee.plan().capabilityRevision(), environment);
					next = next.advanceBee(bee.slot(), bee.revision(), context, 0, 8, null).apply(next);
				}
				var output = next.bee(bee.slot()).plan().output();
				next = next.settleBee(bee.slot(), Math.min(64, ProductKeyCodec.item(output, 1, level.registryAccess()).getMaxStackSize())).apply(next);
			}
			for (int lane = 0; lane < before.lanes(); lane++) {
				// 输入已归此作业；推进沿用冻结的旧能力，并且每台机器每个真实 tick 至多获准一次。
				next = next.advanceCentrifuge(lane, 1, true).apply(next);
				next = next.freezeCentrifuge(lane).apply(next);
				next = next.settleCentrifuge(lane, key -> Math.min(64, ProductKeyCodec.item(key, 1, level.registryAccess()).getMaxStackSize())).apply(next);
			}
			if (access.current()) access.assets.commit(before, next);
		} catch (RuntimeException failure) {
			// 私有候选尚未提交；保留全部原资产，隔离一次并记录根因。
			core.assets.quarantine(failure); MachineWorldService.workFailed(core);
			LogUtils.getLogger().error("Machine work quarantined at {} {}", level.dimension().location(), core.getBlockPos(), failure);
		}
	}
	private MachineWorkService() { }
}
