package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** writer 正常保存世界，reader 在另一 JVM 的正式加载入口恢复身份后重新形成。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineRestartProbe {
	private static final BlockPos POSITION = new BlockPos(1008, 128, 1008), BAD_POSITION = new BlockPos(1088, 128, 1008);
	private static final JsonObject report = new JsonObject();
	private static MachineProbeFixture fixture, bad;
	private static CompoundTag marker, shutdownWork;
	private static int workUntil;
	private static int phase, started;
	private static String mode() { return System.getProperty("pbg.multiblock.mode", ""); }
	private static boolean enabled() { return mode().equals("write") || mode().equals("read"); }
	@SubscribeEvent public static void started(ServerStartedEvent event) {
		if (!enabled()) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			if (mode().equals("read")) marker = NbtIo.readCompressed(markerFile(server), NbtAccounter.unlimitedHeap());
			int variant = mode().equals("write") ? CombinedApiaryDefinition.DEFINITION.candidates().size() - 1
					: marker.contains("variant", net.minecraft.nbt.Tag.TAG_INT) ? marker.getInt("variant") : 2;
			var template = CombinedApiaryDefinition.DEFINITION.candidates().get(variant);
			if (mode().equals("write")) {
				fixture = MachineProbeFixture.place(level, template, POSITION, Direction.NORTH, UUID.randomUUID());
				bad = MachineProbeFixture.place(level, template, BAD_POSITION, Direction.NORTH, UUID.randomUUID());
				var broken = bad.core().saveWithFullMetadata(level.registryAccess()); broken.remove("owner");
				bad.core().loadWithComponents(broken, level.registryAccess()); bad.core().setChanged();
				marker = new CompoundTag(); marker.putLong("writerPid", ProcessHandle.current().pid());
				marker.putUUID("machine", fixture.core().machineId()); marker.putUUID("owner", fixture.core().ownerId());
				marker.putLong("generation", fixture.core().generation()); marker.putInt("variant", variant);
			} else {
				check(marker.getLong("writerPid") != ProcessHandle.current().pid(), "Restart reused writer JVM");
				// force 仅属于夹具，正式机器服务不持区块票据。
				for (var pos : new BlockPos[]{POSITION, BAD_POSITION}) {
					var shell = new MachineProbeFixture(template, pos, Direction.NORTH, null);
					MachineLifecycleProbe.force(level, shell, true);
				}
				fixture = new MachineProbeFixture(template, POSITION, Direction.NORTH, (MachineControllerEntity) level.getBlockEntity(POSITION));
				bad = new MachineProbeFixture(template, BAD_POSITION, Direction.NORTH, (MachineControllerEntity) level.getBlockEntity(BAD_POSITION));
				check(fixture.core() != null && bad.core() != null, "Saved controllers missing");
				check(!fixture.core().formed() && !bad.core().formed(), "NBT restored formed authority before scanning");
				report.addProperty("noFormedAuthorityBeforeFirstTick", true);
				report.addProperty("newJvm", true);
				check(MachineWorkService.view(fixture.core()).isEmpty(), "Unformed loaded controller exposed assets");
			}
			phase = 1;
		} catch (Exception failure) { fail(server, failure); }
	}
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!enabled() || phase == 0 || phase == 99) return;
		var server = event.getServer();
		try {
			if (started == 0) started = server.getTickCount();
			check(server.getTickCount() - started < 600, "Restart formation timeout");
			check(bad.core().status() == MachineDirectory.State.RECOVERY && !bad.core().readyIdentity(), "Malformed saved identity became usable");
			if (phase == 2) { verifyWork(server); return; }
			if (mode().equals("read") && !report.has("normalWorkCheckpointRestoredBeforeFormation")) {
				// 区块 BE 的正式 onLoad 可晚于 ServerStarted；等该入口完成，不手工调用生产服务注册。
				if (fixture.core().assets == null) return;
				check(!fixture.core().formed(), "Work checkpoint was not observed before formation");
				check(sameWork(CombinedWorkCodec.decode(marker.getCompound("work"), key -> {}, key -> 64, item -> {}), fixture.core().assets.work()), "Normal world load changed work checkpoint");
				report.addProperty("normalWorkCheckpointRestoredBeforeFormation", true);
			}
			if (!fixture.core().formed()) return;
			var core = fixture.core();
			check(core.machineId().equals(marker.getUUID("machine")) && core.ownerId().equals(marker.getUUID("owner"))
					&& core.generation() == marker.getLong("generation"), "Saved identity changed");
			check(MachineWorldService.tracked(server) == 1, "Duplicate ticker or malformed controller registered");
			for (var local : fixture.template().features().keySet()) if (!fixture.world(local).equals(POSITION)) {
				check(server.overworld().getBlockEntity(fixture.world(local)) instanceof MachinePartEntity part && part.bound(), "Restored part unbound");
			}
			report.addProperty("identityOwnerAndGenerationRetained", true);
			report.addProperty("formalQueueReformedAndPartsBound", true);
			report.addProperty("malformedIdentityRemainsIsolated", true);
			report.addProperty("singleRegisteredController", true);
			if (mode().equals("write")) {
				var before = core.assets.work(); core.assets.commit(before, seedWork(server, before));
				shutdownWork = CombinedWorkCodec.encode(core.assets.work()); marker.put("work", shutdownWork.copy());
				MachineWorldService.request(core);
				check(MachineWorkService.view(core).isEmpty(), "Rebuilding machine exposed assets");
				NbtIo.writeCompressed(marker, markerFile(server)); report.addProperty("passed", true); finish(server);
			} else { workUntil = server.getTickCount() + 6; phase = 2; }
		} catch (Exception failure) { fail(server, failure); }
	}

	/** Map 的编码列表顺序可跨 JVM 改变；核对全部权威字段与真实槽序，不把顺序当资产差异。 */
	private static boolean sameWork(CombinedMachineWork expected, CombinedMachineWork actual) {
		return expected.machine().equals(actual.machine()) && expected.generation() == actual.generation() && expected.revision() == actual.revision()
				&& expected.beeSlots() == actual.beeSlots() && expected.lanes() == actual.lanes()
				&& expected.energy() == actual.energy() && expected.energyCapacity() == actual.energyCapacity()
				&& expected.bees().equals(actual.bees()) && expected.feeding().equals(actual.feeding()) && expected.centrifuges().equals(actual.centrifuges())
				&& expected.buffer().tankCapacity() == actual.buffer().tankCapacity()
				&& expected.buffer().items().equals(actual.buffer().items()) && expected.buffer().fluids().equals(actual.buffer().fluids());
	}
	private static CombinedMachineWork seedWork(MinecraftServer server, CombinedMachineWork original) {
		var registries = server.registryAccess();
		var comb = ProductKeyCodec.item(new ItemStack(Items.HONEYCOMB), registries);
		var iron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), registries);
		var water = ProductKeyCodec.fluid(new FluidStack(Fluids.WATER, 1), registries);
		var source = new CompoundTag(); source.putInt("slot_index", 5); source.putInt("ticks_in_hive", 0);
		var entity = new CompoundTag(); entity.putString("type", "productivebees:iron"); source.put("entity_data", entity);
		var beePlan = new StaticBeePlan("productivebees:iron", "test:paid_bee", 0, 0, 20, 5, 0, false, BeeWorkConditions.Traits.DEFAULT, comb, 1);
		var bee = new BeeRecord(UUID.randomUUID(), original.machine(), 5, new AssetImage(source), beePlan, 0, 0, 2,
				ProductAmount.of(java.math.BigInteger.ONE.shiftLeft(70)), new BeeCycleRandom(17, 5));
		var small = new CentrifugeRecipePlan("test:held_input", 0, 0, comb, 4, 1, 3, 1, 0,
				List.of(new CentrifugeRecipePlan.Output(iron, 2, 2, 1)));
		var large = new CentrifugeRecipePlan("test:paid_outputs", 0, 0, comb, 2, 1, 3, 1, 0,
				List.of(new CentrifugeRecipePlan.Output(iron, 5000, 5000, 1), new CentrifugeRecipePlan.Output(water, 100_000, 100_000, 1)));
		var paid = new CentrifugeDelivery(new CentrifugeJob(UUID.randomUUID(), large, 1, 2, 29, null).freeze(), Map.of());
		var delivered = paid.deliver(original.buffer(), key -> 64);
		var work = new CombinedMachineWork(original.machine(), original.generation(), original.revision() + 1, original.beeSlots(), original.lanes(),
				1000, original.energyCapacity(), List.of(bee), Map.of(0, new CentrifugeDelivery(new CentrifugeJob(UUID.randomUUID(), small, 1, 1, 31, null), Map.of()),
				2, delivered.work()), delivered.buffer(), original.feeding());
		var food = com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter.fromStack(new ItemStack(Items.IRON_BLOCK), registries);
		return work.depositFeeding(5, food, 7).apply(work);
	}
	private static void verifyWork(MinecraftServer server) throws Exception {
		var core = fixture.core();
		MachineWorldService.stepWork(server); var once = core.assets.work();
		MachineWorldService.stepWork(server); check(core.assets.work() == once, "Repeated service call advanced the same real tick");
		if (server.getTickCount() < workUntil) return;
		var state = core.assets.work(); var initial = CombinedWorkCodec.decode(marker.getCompound("work"), key -> {}, key -> 64, item -> {});
		check(state.energy() == 991 && state.centrifuges().get(0).job().paid(), "Restored work did not pay only its remaining three ticks");
		check(state.bee(5).pendingCycles() == 0 && state.bee(5).random().cursor() == 7
				&& state.bee(5).frozen().equals(initial.bee(5).frozen().add(ProductAmount.of(2))), "Paid bee sampling changed across restart");
		check(state.centrifuges().get(2).equals(initial.centrifuges().get(2)), "Full buffer rerolled or redelivered paid outputs");
		check(state.feeding().equals(initial.feeding()) && state.feeding().get(5).count() == 7, "Restored work lost real feeding items");
		report.addProperty("singleTickWorkAndPaidRecovery", true);
		report.addProperty("sixSlotFeedingSurvivesRestartAndWork", true);
		rejectedFile(server, false); rejectedFile(server, true);
		report.addProperty("missingAndUnreadableFilesNeverRecreated", true);
		shutdownWork = CombinedWorkCodec.encode(state);
		core.assets.quarantine(new IllegalStateException("probe recovery")); MachineWorldService.workFailed(core);
		check(core.status() == MachineDirectory.State.RECOVERY && !core.formed() && MachineWorkService.view(core).isEmpty(), "Recovery retained work authority or invalid visual state");
		report.addProperty("recoveryQuarantineRevokesAuthority", true);
		report.addProperty("passed", true); finish(server);
	}
	private static void rejectedFile(MinecraftServer server, boolean corrupt) throws Exception {
		var level = server.overworld(); var id = UUID.randomUUID();
		var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MachineWorkService.dataName(id) + ".dat");
		byte[] broken = {1, 2, 3, 4};
		if (corrupt) Files.write(file, broken);
		level.removeBlock(BAD_POSITION, false);
		level.setBlockAndUpdate(BAD_POSITION, MachineContent.block(com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.CONTROLLER).defaultBlockState());
		var candidate = (MachineControllerEntity) level.getBlockEntity(BAD_POSITION);
		var tag = fixture.core().saveWithFullMetadata(level.registryAccess()); tag.putUUID("machine", id);
		candidate.loadWithComponents(tag, level.registryAccess());
		check(candidate.status() == MachineDirectory.State.RECOVERY && MachineWorkService.view(candidate).isEmpty(), "Invalid referenced asset file became available");
		level.getDataStorage().save();
		check(corrupt ? java.util.Arrays.equals(broken, Files.readAllBytes(file)) : Files.notExists(file), "Failed asset load overwrote or recreated a file");
		level.removeBlock(BAD_POSITION, false);
	}
	private static Path markerFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("multiblock-probe.dat"); }
	private static void fail(MinecraftServer server, Exception failure) {
		report.addProperty("passed", false); report.addProperty("failure", failure.toString());
		com.mojang.logging.LogUtils.getLogger().error("MACHINE_RESTART_PROBE_FAILED", failure); finish(server);
	}
	private static void finish(MinecraftServer server) {
		phase = 99; report.addProperty("mode", mode()); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		report.addProperty("pid", ProcessHandle.current().pid()); report.addProperty("elapsedTicks", server.getTickCount() - started);
		write(); server.halt(false);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (enabled()) {
			try {
				if (shutdownWork != null) {
					var file = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(MachineWorkService.dataName(fixture.core().machineId()) + ".dat");
					check(shutdownWork.equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").getCompound("work")), "Shutdown did not persist the final asset root");
					if (mode().equals("read")) check("RECOVERY".equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").getString("mode")), "Recovery mode did not persist");
					report.addProperty("normalAssetCheckpointSaved", true);
				}
				report.addProperty("normalShutdown", true);
			} catch (Exception failure) { report.addProperty("passed", false); report.addProperty("failure", failure.toString()); }
			write(); fixture = bad = null; marker = shutdownWork = null;
		}
	}
	private static void write() {
		try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
		catch (Exception failure) { throw new IllegalStateException("Cannot write restart report", failure); }
	}
	private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineRestartProbe() { }
}
