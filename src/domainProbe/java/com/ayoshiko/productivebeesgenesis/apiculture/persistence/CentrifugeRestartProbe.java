package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import java.nio.file.*;
import java.util.*;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.LevelResource;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CentrifugeRestartFixture.require;

/** 两次独立专服进程读取同一测试世界副本；包含真实核心、成员、目录及域文件。 */
public final class CentrifugeRestartProbe {
	private static final List<CentrifugeRestartFixture> fixtures = new ArrayList<>();
	private static final Map<UUID, NetworkCheckpoint> shutdown = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean reading, changedRecipes;
	private static int started, cursor;
	private static Collection<RecipeHolder<?>> originalRecipes;
	private static double oldTimeBonus, oldProductivity;
	private static long originalEpoch;
	private static final UUID JVM = UUID.randomUUID();
	private static long producerPid;
	public static void start(MinecraftServer server) throws Exception {
		reading = "read".equals(System.getProperty("pbg.centrifuge.mode")); started = server.getTickCount();
		ModConfig.SERVER.beeNetwork.enabled.set(true);
		var level = server.overworld(); level.setChunkForced(3, 0, true); level.getChunk(3, 0);
		if (reading) {
			var root = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap());
			require(!JVM.equals(root.getUUID("producerJvm")), "Restart reused the writer JVM"); producerPid = root.getLong("producerPid");
			for (var raw : root.getList("fixtures", Tag.TAG_COMPOUND)) {
				var tag = (CompoundTag) raw;
				var fixture = new CentrifugeRestartFixture(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
						CentrifugeRestartFixture.Stage.valueOf(tag.getString("stage")), tag.getBoolean("block"));
				fixture.load(level, tag); fixtures.add(fixture);
			}
			require(fixtures.size() == 16, "Incomplete centrifuge restart matrix");
		} else {
			for (var stage : CentrifugeRestartFixture.Stage.values()) for (boolean block : new boolean[]{false, true}) {
				int index = fixtures.size();
				var fixture = new CentrifugeRestartFixture(new BlockPos(48 + index % 4 * 3, 100 + index / 4 * 3, 4), stage, block);
				fixture.create(level); fixtures.add(fixture);
			}
		}
	}
	public static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		require(server.getTickCount() - started < 3000, "Centrifuge restart timed out");
		if (fixtures.stream().allMatch(f -> f.finished)) {
			if (!reading) {
				var root = new CompoundTag(); var list = new ListTag(); fixtures.forEach(f -> list.add(f.manifest())); root.put("fixtures", list);
				root.putUUID("producerJvm", JVM); root.putLong("producerPid", ProcessHandle.current().pid());
				NbtIo.writeCompressed(root, manifest(server));
			}
			for (var fixture : fixtures) shutdown.put(fixture.core.network().networkId(), fixture.data.checkpoint());
			restoreRecipes(server);
			report.addProperty(reading ? "centrifugeWorldRestartStagesVerified" : "centrifugeWorldShutdownStagesPrepared", fixtures.size());
			report.addProperty("centrifugeWorldRestartIsNewJvm", reading);
			report.addProperty("ae2Loaded", net.neoforged.fml.ModList.get().isLoaded("ae2"));
			report.addProperty("producerPid", reading ? producerPid : ProcessHandle.current().pid());
			report.addProperty("currentPid", ProcessHandle.current().pid());
			report.addProperty("sharedEnergyStages", fixtures.stream().filter(f -> f.saved.ownedMachines().get(f.member).centrifuge().networkPowered()).count());
			report.addProperty("passed", true); return true;
		}
		var fixture = fixtures.get(cursor++ % fixtures.size());
		if (fixture.finished) return false;
		var core = fixture.core;
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, fixture.stage + ": " + core.ownership().failure());
		if (core.topology() == null || core.ownership().busy()) return false;
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		if (!reading) {
			if (!fixture.started) { require(core.ownership().command(true), "Restart fixture takeover rejected"); fixture.started = true; return false; }
			if (core.ownership().status() == CoreOwnershipController.Status.MANAGED) fixture.prepare(level, directory);
			return false;
		}
		if (!fixture.started && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			fixture.data = directory.loadExisting(core.network()).ready();
			require(fixture.saved.equals(fixture.data.checkpoint()), "World restart changed checkpoint at " + fixture.stage);
			require(new MachineAssetStore(fixture.tile).empty(), "Restart revived physical inventory");
			var policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, fixture.saved.policyRevision()).snapshot());
			var service = new NetworkCentrifugeService(fixture.data, directory, policy);
			if (!changedRecipes) changeRecipes(server);
			var state = fixture.data.checkpoint().ownedMachines().get(fixture.member).centrifuge();
			if (!state.drained()) {
				var job = state.jobs().get(0);
				// 周期中途补料不扩大已预约批次；新增实物量纳入最终守恒预期。
				if (fixture.stage == CentrifugeRestartFixture.Stage.PARTIAL) {
					fixture.creditFixtureInput(job.plan().input(), 9);
					require(fixture.data.checkpoint().ownedMachines().get(fixture.member).centrifuge().jobs().get(0).equals(job), "Refill changed pinned work");
					var expanded = fixture.data.checkpoint();
					fixture.expected = fixture.finishCandidate(expanded);
					var tileTag = fixture.tile.saveWithFullMetadata(level.registryAccess()); var pos = fixture.tile.getBlockPos();
					var replacement = (com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge) fixture.tile.getType().create(pos, fixture.tile.getBlockState());
					level.removeBlockEntity(pos);
					require(!service.work(level, fixture.member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 4096, false)
							&& fixture.data.checkpoint() == expanded, "Missing member advanced or lost owned work");
					replacement.loadWithComponents(tileTag, level.registryAccess()); level.setBlockEntity(replacement); fixture.tile = replacement;
					var broken = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, pos, replacement.getBlockState(),
							net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level));
					ManagedMemberEvents.broken(broken); require(broken.isCanceled(), "In-flight member could be broken");
				}
				fixture.tile.setControlType(RedstoneControl.HIGH);
				require(!service.work(level, fixture.member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 4096, false), "Restart ignored pause");
				fixture.tile.setControlType(RedstoneControl.DISABLED);
				if (fixture.stage == CentrifugeRestartFixture.Stage.STARVED) {
					require(!service.work(level, fixture.member, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 4096, false)
							&& !core.ownership().command(false), "Starved work restarted for free or was returned");
					require(fixture.saved.equals(fixture.data.checkpoint()), "Unfunded restart changed assets"); fixture.finished = true; return false;
				}
				if (!job.paid()) fixture.work(level, service, NetworkCentrifugeService.Action.ADVANCE, Integer.MAX_VALUE);
				if (!job.sampled()) fixture.work(level, service, NetworkCentrifugeService.Action.FREEZE, 0);
				fixture.work(level, service, NetworkCentrifugeService.Action.SETTLE, 0);
			}
			var result = fixture.data.checkpoint();
			require(result.energy().equals(fixture.expected.energy()) && result.ledger().balances().equals(fixture.expected.ledger().balances())
					&& result.ownedMachines().get(fixture.member).centrifuge().equals(fixture.expected.ownedMachines().get(fixture.member).centrifuge()),
					"Recovered work changed quantities or energy at " + fixture.stage);
			fixture.tile.setControlType(RedstoneControl.HIGH);
			require(core.ownership().command(false), "Recovered completed work cannot return"); fixture.started = true; return false;
		}
		if (fixture.started && core.ownership().status() == CoreOwnershipController.Status.STANDALONE) {
			require(fixture.data.checkpoint().energy().equals(fixture.expected.energy()), "Returning a member changed the shared balance");
			var returned = new MachineAssetStore(fixture.tile).capture(level.registryAccess()); var original = fixture.original.copy();
			original.putLong("energy", fixture.expected.ownedMachines().get(fixture.member).centrifuge().energy());
			require(returned.equals(new AssetImage(original)), "Restart return duplicated or lost sealed old outputs");
			require(fixture.data.checkpoint().ownedMachines().get(fixture.member).assets().isEmpty() && !MemberBinding.isolated(fixture.tile), "Return retained executable authority");
			fixture.finished = true;
		}
		return false;
	}
	private static void changeRecipes(MinecraftServer server) {
		var manager = server.getRecipeManager(); originalRecipes = List.copyOf(manager.getRecipes());
		oldTimeBonus = ProductiveBeesConfig.UPGRADES.timeBonus.get(); oldProductivity = ProductiveBeesConfig.UPGRADES.productivityMultiplier.get();
		ProductiveBeesConfig.UPGRADES.timeBonus.set(oldTimeBonus + 1); ProductiveBeesConfig.UPGRADES.productivityMultiplier.set(oldProductivity + 4);
		manager.replaceRecipes(originalRecipes.stream().filter(recipe -> recipe.value().getType() != ModRecipeTypes.CENTRIFUGE_TYPE.get()).toList());
		CentrifugeRecipeIndex.rebuild(manager); originalEpoch = ProductiveBeesGenesis.RECIPE_VERSION.getAndIncrement(); changedRecipes = true;
	}
	public static void restoreRecipes(MinecraftServer server) {
		if (!changedRecipes) return;
		server.getRecipeManager().replaceRecipes(originalRecipes); CentrifugeRecipeIndex.rebuild(server.getRecipeManager());
		ProductiveBeesConfig.UPGRADES.timeBonus.set(oldTimeBonus); ProductiveBeesConfig.UPGRADES.productivityMultiplier.set(oldProductivity);
		ProductiveBeesGenesis.RECIPE_VERSION.set(originalEpoch); changedRecipes = false;
	}
	public static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		var folder = server.getWorldPath(LevelResource.ROOT).resolve("data");
		for (var entry : shutdown.entrySet()) {
			var tag = NbtIo.readCompressed(folder.resolve("productivebeesgenesis_network_" + entry.getKey() + ".dat"), NbtAccounter.unlimitedHeap());
			require(NetworkCheckpointCodec.encode(entry.getValue()).equals(tag.getCompound("data")), "Shutdown did not save final centrifuge authority");
		}
		require(shutdown.size() == 16, "Shutdown matrix was incomplete");
		report.addProperty("normalShutdownCheckpointSaved", true);
	}
	private static Path manifest(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("centrifuge-probe.dat"); }
	private CentrifugeRestartProbe() { }
}
