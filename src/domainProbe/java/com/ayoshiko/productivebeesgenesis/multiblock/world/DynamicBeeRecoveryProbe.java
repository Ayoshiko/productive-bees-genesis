package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGeneration;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGenerator;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkConditions;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiary.DynamicBeeAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** 保留真实宿主和网络根，在正常停服后的独立 JVM 中验证已付生成请求。 */
public final class DynamicBeeRecoveryProbe {
	private static final List<NetworkCoreBlockEntity> cores = new ArrayList<>();
	private static final List<NetworkCheckpoint> saved = new ArrayList<>();
	private static final List<NetworkCheckpoint> shutdown = new ArrayList<>();
	private static int phase;
	private static int started;
	private static int takeoverRetries;

	static void prepare(MinecraftServer server, BlockPos apiaryCore, NetworkCoreBlockEntity combinedCore) {
		cores.add((NetworkCoreBlockEntity) server.overworld().getBlockEntity(apiaryCore));
		cores.add(combinedCore);
		started = server.getTickCount();
		for (var core : cores) require(core.ownership().command(true), "Recovery fixture takeover rejected");
	}

	static void read(MinecraftServer server) throws Exception {
		started = server.getTickCount();
		ModConfig.SERVER.beeNetwork.enabled.set(false);
		var manifest = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap());
		require(manifest.getLong("writer") != ProcessHandle.current().pid(), "World recovery reused writer JVM");
		var level = server.overworld();
		for (var raw : manifest.getList("networks", Tag.TAG_COMPOUND)) {
			var tag = (CompoundTag) raw;
			var pos = BlockPos.of(tag.getLong("core"));
			level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
			var core = (NetworkCoreBlockEntity) level.getBlockEntity(pos);
			var checkpoint = NetworkCheckpointCodec.forRegistries(level.registryAccess()).decode(tag.getCompound("saved"));
			require(core != null && checkpoint.identity().equals(core.network()) && !core.productionRunning(),
					"Persisted core identity or pause changed");
			cores.add(core);
			saved.add(checkpoint);
		}
		require(cores.size() == 2, "Incomplete dynamic world fixture");
		phase = 2;
	}

	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		require(server.getTickCount() - started < 800, "Dynamic world recovery timeout at " + phase);
		var level = server.overworld();
		for (var core : cores) require(core.ownership().status() != com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController.Status.RECOVERY,
				core.ownership().failure());
		if (cores.stream().anyMatch(core -> core.ownership().busy() || core.ownership().readyAuthority() == null)) return false;
		if (phase == 0) {
			for (int index = 0; index < cores.size(); index++) {
				var core = cores.get(index);
				int expected = 1;
				if (index == 0) expected = Boolean.getBoolean("pbg.dynamic.myriad") || Boolean.getBoolean("pbg.dynamic.effects") ? 2 : 4;
				if (core.ownership().readyAuthority().checkpoint().ownedMachines().activeCount() == expected) continue;
				require(core.ownership().status() == com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController.Status.REJECTED
						&& core.ownership().failure().equals("Topology changed; completed transfers remain owned")
						&& takeoverRetries++ < 3, "Recovery fixture did not take over every member: " + core.ownership().failure());
				require(core.ownership().command(true), "Recovery fixture topology retry rejected");
				return false;
			}
			for (var core : cores) {
				require(core.setProductionRunning(false), "Cannot pause dynamic recovery fixture");
				prepareRequests(level, core.ownership().readyAuthority());
				saved.add(core.ownership().readyAuthority().checkpoint());
			}
			ModConfig.SERVER.beeNetwork.enabled.set(false);
			var manifest = new CompoundTag();
			manifest.putLong("writer", ProcessHandle.current().pid());
			var networks = new ListTag();
			for (int index = 0; index < cores.size(); index++) {
				var tag = new CompoundTag();
				tag.putLong("core", cores.get(index).getBlockPos().asLong());
				tag.put("saved", NetworkCheckpointCodec.encode(saved.get(index)));
				tag.put("prepared", NetworkCheckpointCodec.encode(saved.get(index)));
				networks.add(tag);
			}
			manifest.put("networks", networks);
			NbtIo.writeCompressed(manifest, manifest(server));
			report.addProperty("dynamicWorldPrepared", true);
			captureShutdown();
			return true;
		}
		if (phase == 2) {
			for (int index = 0; index < cores.size(); index++) {
				var data = cores.get(index).ownership().readyAuthority();
				require(drainReady(saved.get(index)).equals(drainReady(data.checkpoint())), "World load changed paid authority");
				verifyPhysical(level, data.checkpoint());
			}
			ModConfig.SERVER.beeNetwork.enabled.set(true);
			phase = 3;
			return false;
		}
		for (var core : cores) {
			var authority = core.ownership().readyAuthority();
			for (var record : authority.checkpoint().ownedMachines().activeValues())
				if (record.combined() != null && MachineNetworkAccess.member(level, authority, NetworkPersistence.directory(server), record) == null) return false;
		}
		for (int index = 0; index < cores.size(); index++) {
			var core = cores.get(index);
			var data = core.ownership().readyAuthority();
			var expected = drainReady(saved.get(index));
			for (var original : List.copyOf(data.checkpoint().ownedMachines().values())) {
				for (var source : bees(original)) {
					if (source.drained()) continue;
					var member = original.claim().member();
					if (source.generation().phase() != BeeGeneration.Phase.READY) {
						var before = data.checkpoint();
						if (original.combined() != null) {
							require(CombinedNetworkService.bee(level, data, NetworkPersistence.directory(server), original, source.slot(), false)
									== NetworkRuntime.Status.FAILED, "Unknown combined request resumed");
						} else {
							require(new NetworkBeeService(data, NetworkPersistence.directory(server)).advance(level, member, source.slot(),
									source.revision(), 0, 0, 0, 1, false) == BeeWorkExecutor.Status.GENERATION_UNKNOWN,
									"Unknown apiary request resumed");
						}
						require(before == data.checkpoint(), "Unknown request mutated assets, FE or random cursor");
						continue;
					}
					for (int step = 0; !bee(data.checkpoint().ownedMachines().get(member), source.slot()).drained(); step++) {
						require(step < 20, "Recovered READY result failed to settle");
						var record = data.checkpoint().ownedMachines().get(member);
						var live = bee(record, source.slot());
						if (record.combined() != null) {
							require(CombinedNetworkService.bee(level, data, NetworkPersistence.directory(server), record, live.slot(), false)
									== NetworkRuntime.Status.SETTLING, "Recovered combined delivery rejected");
						} else {
							var service = new NetworkBeeService(data, NetworkPersistence.directory(server));
							if (live.deliverable()) require(service.settle(level, member, live.slot(), live.revision()), "Recovered apiary settlement rejected");
							else require(service.advance(level, member, live.slot(), live.revision(), 0, 0, 0, 1, false)
									== BeeWorkExecutor.Status.READY, "Recovered apiary delivery rejected");
						}
					}
				}
			}
			require(expected.equals(data.checkpoint()), "Recovered result differs from frozen checkpoint continuation");
			require(!core.ownership().command(false), "Unknown request was returned without verification");
			require(expected.equals(data.checkpoint()), "Rejected return changed the authority");
			verifyPhysical(level, data.checkpoint());
		}
		report.addProperty("dynamicWorldRestored", true);
		report.addProperty("readyDeliveredWithoutNewFeeOrDraw", true);
		report.addProperty(Boolean.getBoolean("pbg.dynamic.myriad") || Boolean.getBoolean("pbg.dynamic.effects")
				? "startedPreservedAndReturnRejected" : "startedAndUnknownPreservedAndReturnRejected", true);
		captureShutdown();
		return true;
	}

	private static void prepareRequests(ServerLevel level, NetworkSavedData data) {
		var directory = NetworkPersistence.directory(level.getServer());
		int requestIndex = 0;
		for (var initial : List.copyOf(data.checkpoint().ownedMachines().values())) {
			if (initial.phase() != OwnedMachineRecord.Phase.OWNED) continue;
			var member = initial.claim().member();
			if (initial.bees() == null && initial.combined() == null) {
				var origin = initial.claim().origin();
				if (level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z())) instanceof TileEntityMekApiary)
					require(new NetworkBeeService(data, directory).activate(level, member, data.checkpoint().revision(), 0), "Recovery apiary activation failed");
				else require(CombinedNetworkService.activate(level, data, directory, initial), "Recovery combined activation failed");
			}
			for (var original : bees(data.checkpoint().ownedMachines().get(member))) {
				var root = data.checkpoint();
				var record = root.ownedMachines().get(member);
				if (!original.enabled()) {
					if (record.combined() != null)
						data.publish(root.exchangeCombined(member, record.combined().beeEnabled(original.slot(), original.id(), true)));
					else data.publish(root.withOwnership(record.withBees(record.bees().withBeeEnabled(original.slot(), original.id(), true))));
				}
				root = data.checkpoint();
				record = root.ownedMachines().get(member);
				var live = bee(record, original.slot());
				var context = new BeeWorkExecutor.Context(true, true, true, live.plan().recipeRevision(),
						live.plan().capabilityRevision(), new BeeWorkConditions.Environment(false, false, false, false));
				var flowers = record.combined() != null ? record.combined().feeding() : record.bees().feeding().slots();
				int ticks = live.plan().cycleTicks() - live.progress();
				context = DynamicBeeAdapter.prepare(level, live, flowers, ticks, null, context);
				if (record.combined() != null) {
					var result = BeeWorkExecutor.advanceBee(live, live.revision(), context, ticks, 1, root.energy().stored(), null);
					require(result.status() == BeeWorkExecutor.Status.READY, "Recovery combined payment rejected");
					data.publish(root.applyCombinedBee(member, result));
				} else {
					var state = record.bees();
					var result = BeeWorkExecutor.advance(state, live.slot(), live.revision(), context, ticks, 1,
							state.networkPowered() ? root.energy().stored() : state.energy());
					require(result.status() == BeeWorkExecutor.Status.READY, "Recovery apiary payment rejected");
					data.publish(root.applyBeeWork(member, result));
				}
				root = data.checkpoint();
				record = root.ownedMachines().get(member);
				live = bee(record, live.slot());
				require(live.generation().phase() == BeeGeneration.Phase.STARTED, "Expected paid generation window");
				boolean myriad = live.plan().generator() == BeeGenerator.MYRIAD;
				if ((myriad || Boolean.getBoolean("pbg.dynamic.effects")) && requestIndex++ % 2 == 1) continue;
				if (live.plan().beeType().equals("productivebees:lumber_bee")) continue;
				var origin = record.claim().origin();
				var resolved = live.plan().beeType().equals("productivebees:quarry_bee")
						? live.generation().unknown("Injected unconfirmed external generation")
						: DynamicBeeAdapter.generate(level, new BlockPos(origin.x(), origin.y(), origin.z()), live);
				if (record.combined() != null) data.publish(root.applyCombinedBee(member, BeeWorkExecutor.resolveGeneration(live, resolved)));
				else data.publish(root.applyBeeWork(member, BeeWorkExecutor.resolveGeneration(record.bees(), live.slot(), resolved)));
				RuntimeProductPolicies.recordGenerated(level, resolved);
			}
		}
	}

	private static NetworkCheckpoint drainReady(NetworkCheckpoint root) {
		for (var original : List.copyOf(root.ownedMachines().values())) for (var source : bees(original)) {
			if (source.drained()) continue;
			if (source.generation().phase() != BeeGeneration.Phase.READY) continue;
			var member = original.claim().member();
			for (int step = 0; !bee(root.ownedMachines().get(member), source.slot()).drained(); step++) {
				require(step < 20, "Frozen oracle did not drain");
				var record = root.ownedMachines().get(member);
				var live = bee(record, source.slot());
				var context = new BeeWorkExecutor.Context(true, false, false, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
				if (record.combined() != null) {
					root = live.deliverable() ? root.settleCombinedBee(member, live.slot(), live.revision())
							: root.applyCombinedBee(member, BeeWorkExecutor.advanceBee(live, live.revision(), context, 0, 1, root.energy().stored(), null));
				} else {
					root = live.deliverable() ? root.settleBee(member, live.slot(), live.revision())
							: root.applyBeeWork(member, BeeWorkExecutor.advance(record.bees(), live.slot(), live.revision(), context, 0, 1,
									record.bees().networkPowered() ? root.energy().stored() : record.bees().energy()));
				}
			}
		}
		return root;
	}

	private static List<BeeRecord> bees(OwnedMachineRecord record) {
		if (record.combined() != null) return record.combined().bees();
		return record.bees() == null ? List.of() : record.bees().bees();
	}
	private static BeeRecord bee(OwnedMachineRecord record, int slot) {
		return record.combined() != null ? record.combined().bee(slot) : record.bees().bee(slot);
	}
	private static void verifyPhysical(ServerLevel level, NetworkCheckpoint root) {
		for (var record : root.ownedMachines().activeValues()) {
			var origin = record.claim().origin();
			var pos = new BlockPos(origin.x(), origin.y(), origin.z());
			var tile = level.getBlockEntity(pos);
			if (tile instanceof TileEntityMekApiary hive) require(new MachineAssetStore(hive).empty(), "Recovered hive retained duplicate assets");
			else {
				require(tile instanceof MachineControllerEntity, "Recovered combined controller missing");
				var machine = (MachineControllerEntity) tile;
				require(machine.assets.empty() && !MachineWorkService.active(machine), "Recovered combined producer retained duplicate authority");
			}
			require(level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(8)).isEmpty(), "Recovery spawned item entities");
		}
	}
	private static void captureShutdown() {
		shutdown.clear();
		for (var core : cores) shutdown.add(core.ownership().readyAuthority().checkpoint());
	}
	public static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(shutdown.size() == 2, "Dynamic world shutdown did not reach its target window");
		var manifest = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap());
		for (var root : shutdown) {
			var file = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + root.identity().networkId() + ".dat");
			var persisted = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			var actual = NetworkCheckpointCodec.forRegistries(server.registryAccess()).decode(persisted.getCompound("data"));
			require(drainReady(root).equals(drainReady(actual)), "Shutdown changed frozen results, FE or unknown requests");
			for (var raw : manifest.getList("networks", Tag.TAG_COMPOUND)) {
				var entry = (CompoundTag) raw;
				if (BlockPos.of(entry.getLong("core")).equals(new BlockPos(root.identity().origin().x(), root.identity().origin().y(), root.identity().origin().z())))
					entry.put("saved", NetworkCheckpointCodec.encode(actual));
			}
		}
		NbtIo.writeCompressed(manifest, manifest(server));
		report.addProperty("dynamicWorldShutdownSaved", true);
	}
	private static Path manifest(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("dynamic-world-probe.dat");
	}
	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
	private DynamicBeeRecoveryProbe() { }
}
