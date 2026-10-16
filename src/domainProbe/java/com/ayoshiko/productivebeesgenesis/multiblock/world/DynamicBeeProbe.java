package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** 已校准的真实服务器入口；独立源集不进入发布产物。 */
public final class DynamicBeeProbe {
	private static final BlockPos POS = new BlockPos(4, 130, 4);
	private static final boolean MYRIAD = Boolean.getBoolean("pbg.dynamic.myriad");
	private static final boolean EFFECTS = Boolean.getBoolean("pbg.dynamic.effects");
	private static final int BEE_COUNT = MYRIAD || EFFECTS ? 2 : 4;
	private static final String[] TYPES = {"wanna", "lumber_bee", "quarry_bee", "dye_bee"};
	private static final TileEntityMekApiary[] HIVES = new TileEntityMekApiary[BEE_COUNT];
	private static NetworkCoreBlockEntity core;
	private static NetworkSavedData data;
	private static MachineProbeFixture machine;
	private static int phase, started, oldCycle;
	private static boolean oldEnabled;
	private static CompoundTag checkpoint;
	private static final long[] standaloneCycles = new long[BEE_COUNT];
	static ItemStack flower(int index) {
		if (EFFECTS) return new ItemStack(Items.OBSIDIAN);
		if (MYRIAD) return new ItemStack(cy.jdkdigital.productivebees.init.ModItems.HONEY_TREAT.get());
		if (index == 0) {
			var amber = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("productivebees:amber")));
			var entity = new CompoundTag();
			entity.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.IRON_GOLEM).toString());
			amber.set(DataComponents.ENTITY_DATA, CustomData.of(entity));
			return amber;
		}
		return new ItemStack(switch (index) { case 1 -> Items.OAK_LOG; case 2 -> Items.STONE; default -> Items.POPPY; });
	}
	static CompoundTag bee(int index, boolean cage) {
		var tag = new CompoundTag();
		boolean configurable = MYRIAD || EFFECTS || index == 0;
		tag.putString(cage ? "entity" : "id", configurable ? "productivebees:configurable_bee" : "productivebees:" + TYPES[index]);
		if (EFFECTS) tag.putString("type", "productivebees:obsidian");
		else if (MYRIAD) tag.putString("type", "productivebees:myriadcreations");
		else if (index == 0) tag.putString("type", "productivebees:wanna");
		tag.putUUID("UUID", UUID.randomUUID());
		var genes = new CompoundTag();
		genes.putString("bee_behavior", "behavior.metaturnal");
		genes.putString("bee_weather_tolerance", "weather_tolerance.any");
		genes.putString("bee_productivity", "productivity.normal");
		var attachments = new CompoundTag();
		attachments.put("productivebees:attributes_handler", genes);
		tag.put("neoforge:attachments", attachments);
		return tag;
	}
	public static void start(MinecraftServer server) throws Exception {
		started = server.getTickCount();
		if ("read".equals(System.getProperty("pbg.dynamic.worldMode"))) {
			DynamicBeeRecoveryProbe.read(server);
			phase = 8;
			return;
		}
		if (System.getProperty("pbg.dynamic.restartSource") != null) {
			phase = 9;
			return;
		}
		oldCycle = ModConfig.SERVER.apiaryProcessingTime.get();
		oldEnabled = ModConfig.SERVER.beeNetwork.enabled.get();
		ModConfig.SERVER.apiaryProcessingTime.set(4);
		ModConfig.SERVER.beeNetwork.enabled.set(true);
		if (EFFECTS) cy.jdkdigital.productivebees.ProductiveBeesConfig.UPGRADES.samplerChance.set(1.0);
		var level = server.overworld();
		var owner = UUID.randomUUID();
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
		core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
		core.initializeOwner(owner);
		var directions = List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
		for (int index = 0; index < BEE_COUNT; index++) {
			String typeId = "productivebees:" + TYPES[index];
			if (MYRIAD) typeId = "productivebees:myriadcreations";
			if (EFFECTS) typeId = "productivebees:obsidian";
			var type = ResourceLocation.parse(typeId);
			require((MYRIAD || EFFECTS || index == 0 ? cy.jdkdigital.productivebees.setup.BeeReloadListener.INSTANCE.getData(type) != null : BuiltInRegistries.ENTITY_TYPE.containsKey(type))
					&& (EFFECTS || DynamicBeeAdapter.flower(type, flower(index))), "Invalid dynamic fixture " + type);
			var pos = POS.relative(directions.get(index));
			level.setBlockAndUpdate(pos, ModBlocks.MEK_APIARY.get().defaultBlockState());
			var hive = (TileEntityMekApiary) level.getBlockEntity(pos);
			HIVES[index] = hive;
			hive.setOwnerUUID(owner);
			hive.setControlType(RedstoneControl.HIGH);
			hive.setDirectEjectEnabled(false);
			hive.setDirectAeOutputEnabled(false);
			hive.setDirectContainerOutputEnabled(false);
			hive.setCentrifugePriorityEnabled(false);
			hive.setFeederConversionEnabled(false);
			if (MYRIAD && index == 1) require(hive.installPbUpgrade(PbUpgradeType.BLOCK), "Myriad block fixture installation failed");
			if (EFFECTS) {
				for (var upgrade : effectUpgrades(index == 0))
					require(hive.installPbUpgrade(upgrade), "Effect fixture upgrade rejected: " + upgrade);
			}
			hive.getBeeSlot(0).setBeeData(bee(index, false));
			hive.getFeederSlots().getFirst().setStack(flower(index));
			hive.energyContainer().setEnergy(hive.energyContainer().getMaxEnergy());
		}
		machine = MachineProbeFixture.place(level, CombinedApiaryDefinition.DEFINITION.candidates().getFirst(),
				new BlockPos(1012, 128, 1012), Direction.NORTH, owner);
	}
	public static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		var level = server.overworld();
		if (phase == 8) return DynamicBeeRecoveryProbe.advance(server, report);
		if (phase == 9) {
			var raw = NbtIo.readCompressed(Path.of(System.getProperty("pbg.dynamic.restartSource")), NbtAccounter.unlimitedHeap());
			require(raw.getLong("writer") != ProcessHandle.current().pid(), "Restart reused writer JVM");
			var codec = new NetworkCheckpointCodec(key -> ProductKeyCodec.validatePersisted(key, level.registryAccess()));
			var restored = drain(codec.decode(raw.getCompound("ready")));
			require(restored.ledger().balances().equals(codec.decode(raw.getCompound("done")).ledger().balances()),
					"New JVM changed frozen dynamic result");
			require(restored.ownedMachines().values().stream().allMatch(record -> record.bees().drained()), "Restart did not drain");
			report.addProperty("newJvmFrozenPoolResume", true);
			return true;
		}
		require(server.getTickCount() - started < 1200, "Dynamic probe timed out in phase " + phase);
		require(level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(10)).isEmpty()
				&& level.getEntitiesOfClass(ItemEntity.class, new AABB(machine.pos()).inflate(20)).isEmpty(), "Dynamic production spawned drops");
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var directory = NetworkPersistence.directory(server);
		if (phase == 0) {
			if (core.topology() == null || !core.topology().valid() || !machine.core().formed() || RuntimeProductPolicies.peek(level) == null) return false;
			if (MYRIAD && !MyriadBeeTypeCache.isWarmupComplete()) return false;
			require(core.ownership().command(true), "Dynamic takeover rejected");
			phase = 1;
			return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1) {
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			data = directory.loadExisting(core.network()).ready();
			var service = new NetworkBeeService(data, directory);
			for (var original : List.copyOf(data.checkpoint().ownedMachines().values())) {
				var member = original.claim().member();
				if (original.bees() == null) require(service.activate(level, member, data.checkpoint().revision(), 0), "Dynamic activation rejected");
				var record = data.checkpoint().ownedMachines().get(member);
				var hive = (TileEntityMekApiary) level.getBlockEntity(new BlockPos(record.claim().origin().x(), record.claim().origin().y(), record.claim().origin().z()));
				var value = record.bees().bee(0);
				long energy = record.bees().energy();
				hive.setControlType(RedstoneControl.DISABLED);
				int ticks = value.plan().cycleTicks();
				var before = data.checkpoint();
				require(service.advance(level, member, 0, value.revision(), value.plan().recipeRevision(), 0, ticks, 1, true) == BeeWorkExecutor.Status.READY
						&& before == data.checkpoint(), "Dynamic simulate published or generated");
				require(service.advance(level, member, 0, value.revision(), value.plan().recipeRevision(), 0, ticks, 1, false) == BeeWorkExecutor.Status.READY, "Dynamic event rejected");
				var paid = data.checkpoint().ownedMachines().get(member).bees();
				require(paid.bee(0).generation().phase() == BeeGeneration.Phase.READY, "Generation became unknown");
				require(energy - paid.energy() == (long) ticks * paid.bee(0).plan().energyPerTick(), "Dynamic event FE mismatch");
				if (!EFFECTS) require(!paid.bee(0).generation().pool().isEmpty(), "Calibrated dynamic source produced no candidate");
				require(new MachineAssetStore(hive).empty(), "Managed dynamic bee changed physical inventory");
				hive.setControlType(RedstoneControl.HIGH);
				checkPool(level, paid.bee(0));
			}
			checkpoint = new CompoundTag();
			checkpoint.putLong("writer", ProcessHandle.current().pid());
			checkpoint.put("ready", NetworkCheckpointCodec.encode(data.checkpoint()));
			var expected = drain(data.checkpoint());
			for (var original : List.copyOf(data.checkpoint().ownedMachines().values())) {
				var member = original.claim().member();
				for (int step = 0; !data.checkpoint().ownedMachines().get(member).bees().drained(); step++) {
					require(step < 20, "Live dynamic event did not settle");
					var value = data.checkpoint().ownedMachines().get(member).bees().bee(0);
				if (value.deliverable()) require(service.settle(level, member, 0, value.revision()), "Frozen delivery failed");
					else require(service.advance(level, member, 0, value.revision(), 0, 0, 0, 1, false) == BeeWorkExecutor.Status.READY, "Paid continuation failed");
				}
			}
			require(expected.ledger().balances().equals(data.checkpoint().ledger().balances()), "Live and restored dynamic outputs differ");
			checkpoint.put("done", NetworkCheckpointCodec.encode(data.checkpoint()));
			Files.createDirectories(Path.of("results"));
			NbtIo.writeCompressed(checkpoint, Path.of("results/dynamic-checkpoint.dat"));
			if (EFFECTS) verifyEffects(level, data.checkpoint().ledger().balances(), BEE_COUNT);
			else if (!MYRIAD) verifySources(level, data.checkpoint(), report);
			String managedField = MYRIAD ? "myriadNormalAndBlockManagedProductionAndExactFE" : "fourNativeBeesManagedProductionAndExactFE";
			if (EFFECTS) managedField = "beeEffectsManagedProductionAndExactFE";
			report.addProperty(managedField, true);
			report.addProperty("simulateUnchangedAndPaidPoolDelivery", true);
			seedMachine(level);
			require(core.ownership().command(false), "Settled dynamic return rejected");
			phase = 2;
			return false;
		}
		if (phase == 2) {
			require(machine.core().assets.available(), "Standalone machine lost authority: " + machine.core().assets.failure());
			var work = machine.core().assets.work();
			for (var value : work.bees()) if (value.random().cursor() >= 1 && value.enabled()) {
				var before = machine.core().assets.work();
				machine.core().assets.commit(before, before.beeEnabled(value.slot(), value.id(), false).apply(before));
			}
			work = machine.core().assets.work();
			if (work.bees().stream().anyMatch(value -> value.random().cursor() < 1 || !value.drained())) return false;
			require(work.buffer().items().stream().anyMatch(cell -> cell.key() != null), "Standalone dynamic ticker made no items");
			var details = new MachineMenuDetails();
			details.capture(work, machine.core().handle.binding().orElseThrow(), level.getGameTime());
			require(details.value(MachineMenuDetails.Field.PENDING_BEES) == 0, "Settled machine details show pending work");
			if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
			for (var hive : HIVES) require(!hive.getBeeSlot(0).isEmpty() && hive.getFeederSlots().getFirst().getCount() == 1, "Return lost bee or flower");
			if (EFFECTS) {
				var amounts = new HashMap<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey, ProductAmount>();
				for (var cell : work.buffer().items()) if (cell.key() != null)
					amounts.merge(cell.key(), ProductAmount.of(cell.count()), ProductAmount::add);
				verifyEffects(level, amounts, work.bees().stream().mapToLong(value -> value.random().cursor()).sum());
				for (int index = 0; index < HIVES.length; index++) for (var upgrade : effectUpgrades(index == 0))
					require(HIVES[index].getPbUpgradeCount(upgrade) == 1, "Returned hive lost upgrade " + upgrade);
			}
			String standaloneField = MYRIAD ? "standaloneMyriadTickerAndCageInput" : "standaloneFourNativeBeeTickerAndCageInput";
			if (EFFECTS) standaloneField = "standaloneBeeEffectsTickerAndCageInput";
			report.addProperty(standaloneField, true);
			report.addProperty("normalReturnPreservesBeesFlowersAndNoDrops", true);
			for (int index = 0; index < BEE_COUNT; index++) standaloneCycles[index] = work.bee(index).random().cursor();
			var port = machine.world(new BlockPos(1, 1, 0));
			require(port.getX() >> 4 == port.north().getX() >> 4
					&& port.getZ() >> 4 == port.north().getZ() >> 4, "Network fixture crosses the single-chunk boundary");
			level.setBlockAndUpdate(port, MachineContent.block(StructureRole.NETWORK_PORT).defaultBlockState().setValue(MachinePartBlock.FACING, Direction.NORTH));
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
				level.setChunkForced((port.north().getX() >> 4) + dx, (port.north().getZ() >> 4) + dz, true);
			level.setBlockAndUpdate(port.north(), NetworkContent.CORE.get().defaultBlockState());
			core = (NetworkCoreBlockEntity) level.getBlockEntity(port.north());
			core.initializeOwner(machine.core().ownerId());
			phase = 3;
			return false;
		}
		if (phase == 3) {
			report.addProperty("combinedFormed", machine.core().formed());
			report.addProperty("combinedTopology", String.valueOf(core.topology()));
			if (!machine.core().formed() || core.topology() == null || !core.topology().valid() || !core.topology().hasMember(machine.pos())) return false;
			require(core.ownership().command(true), "Combined dynamic takeover rejected");
			phase = 4;
			return false;
		}
		if (phase == 4) {
			data = core.ownership().readyAuthority();
			if (data == null) return false;
			var record = data.checkpoint().ownedMachines().get(machine.core().machineId());
			if (record == null) return false;
			if (record.combined() == null) {
				require(CombinedNetworkService.activate(level, data, directory, record), "Combined dynamic activation failed");
			}
			for (int index = 0; index < BEE_COUNT; index++) {
				var root = data.checkpoint();
				var work = root.ownedMachines().get(machine.core().machineId()).combined();
				data.publish(root.exchangeCombined(work.machine(), work.beeEnabled(index, work.bee(index).id(), true)));
			}
			require(core.setProductionRunning(true), "Combined dynamic scheduler did not start");
			phase = 5;
			return false;
		}
		if (phase == 5) {
			if (MYRIAD || EFFECTS) core.energyPort().receiveEnergy(Integer.MAX_VALUE, false);
			var root = data.checkpoint();
			var work = root.ownedMachines().get(machine.core().machineId()).combined();
			require(machine.core().assets.empty() && !MachineWorkService.active(machine.core()), "Managed machine retained independent producer");
			for (var value : work.bees()) if (value.random().cursor() > standaloneCycles[value.slot()] && value.enabled()) {
				root = data.checkpoint();
				var live = root.ownedMachines().get(work.machine()).combined();
				data.publish(root.exchangeCombined(work.machine(), live.beeEnabled(value.slot(), value.id(), false)));
			}
			work = data.checkpoint().ownedMachines().get(work.machine()).combined();
			for (var value : work.bees()) if (value.random().cursor() <= standaloneCycles[value.slot()] || !value.drained()) return false;
			require(!data.checkpoint().ledger().balances().isEmpty(), "Combined dynamic scheduler produced no network balance");
			if (!work.drained()) return false;
			if (EFFECTS) {
				long cycles = 0;
				for (var value : work.bees()) cycles += value.random().cursor() - standaloneCycles[value.slot()];
				verifyEffects(level, data.checkpoint().ledger().balances(), cycles);
			}
			require(core.setProductionRunning(false), "Combined dynamic pause rejected");
			require(core.ownership().command(false), "Combined dynamic return rejected: " + core.ownership().failure());
			phase = 6;
			return false;
		}
		if (phase == 6) {
			if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
			require(machine.core().assets.available() && machine.core().assets.work().bees().size() == BEE_COUNT, "Combined dynamic return lost bees");
			String combinedField = MYRIAD ? "managedCombinedMyriadTickerAndReturn" : "managedCombinedFourBeeTickerAndReturn";
			if (EFFECTS) combinedField = "managedCombinedBeeEffectsTickerAndReturn";
			report.addProperty(combinedField, true);
			if ("write".equals(System.getProperty("pbg.dynamic.worldMode"))) {
				DynamicBeeRecoveryProbe.prepare(server, POS, core);
				phase = 8;
				return false;
			}
			ModConfig.SERVER.apiaryProcessingTime.set(oldCycle);
			ModConfig.SERVER.beeNetwork.enabled.set(oldEnabled);
			return true;
		}
		return false;
	}
	private static void checkPool(ServerLevel level, BeeRecord bee) {
		if (EFFECTS) {
			var effects = bee.generation().effects();
			require(bee.plan().generator() == BeeGenerator.STATIC && effects.geneChance() == 1
					&& effects.essence() && effects.discardByproducts() && effects.conversions().size() == 1,
					"Fixed effects were not frozen with the paid event");
			boolean typeOnly = effects.genes().size() == 1;
			require(typeOnly || effects.genes().size() == 24, "Gene template matrix is incomplete");
			for (var key : effects.genes()) {
				var gene = ProductKeyCodec.item(key, 1, level.registryAccess()).get(cy.jdkdigital.productivebees.init.ModDataComponents.GENE_GROUP.get());
				require(gene != null && (typeOnly ? gene.attribute() == cy.jdkdigital.productivebees.util.GeneAttribute.TYPE
						&& gene.value().equals("productivebees:obsidian") && gene.purity() == 100
						: gene.purity() >= 1 && gene.purity() <= 4), "Gene components differ from the installed plugins");
			}
			return;
		}
		if (MYRIAD) {
			var snapshot = MyriadBeeTypeCache.snapshot();
			var variants = bee.plan().generatedCombBlock() ? snapshot.combBlockVariants() : snapshot.honeycombVariants();
			require(bee.generation().pool().size() <= 3, "Myriad event exceeded type budget");
			for (var drop : bee.generation().pool()) {
				var stack = ProductKeyCodec.item(drop.key(), 1, level.registryAccess());
				require(variants.values().stream().flatMap(List::stream).anyMatch(template -> ItemStack.isSameItemSameComponents(stack, template)),
						"Myriad generated a foreign complete template");
				require(RuntimeProductPolicies.peek(level).accepts(drop.key()), "Myriad generated key was not admitted");
			}
			return;
		}
		for (var drop : bee.generation().pool()) {
			var stack = ProductKeyCodec.item(drop.key(), drop.count(), level.registryAccess());
			require(RuntimeProductPolicies.peek(level).accepts(drop.key()), "Generated complete key not admitted");
			if (bee.plan().generator() == BeeGenerator.WANNA_BEE) require(
					stack.is(Items.IRON_INGOT) && stack.getCount() >= 3 && stack.getCount() <= 5
					|| stack.is(Items.POPPY) && stack.getCount() <= 2, "Iron golem loot differs from source table");
			else {
				Item expected = switch (bee.plan().beeType()) {
					case "productivebees:lumber_bee" -> Items.OAK_LOG;
					case "productivebees:quarry_bee" -> Items.STONE;
					default -> Items.RED_DYE;
				};
				require(stack.is(expected) && stack.getCount() == 1, "Dynamic flower mapped incorrectly");
			}
		}
	}
	private static NetworkCheckpoint drain(NetworkCheckpoint root) {
		for (var entry : List.copyOf(root.ownedMachines().values())) {
			var member = entry.claim().member();
			for (int step = 0; !root.ownedMachines().get(member).bees().drained(); step++) {
				require(step < 20, "Frozen pool failed to drain");
				var state = root.ownedMachines().get(member).bees();
				var value = state.bee(0);
				if (value.deliverable()) root = root.settleBee(member, 0, value.revision());
				else {
					var context = new BeeWorkExecutor.Context(true, false, false, 0, 0, new BeeWorkConditions.Environment(false, false, false, false));
					root = root.applyBeeWork(member, BeeWorkExecutor.advance(state, 0, value.revision(), context, 0, 1));
				}
			}
		}
		return root;
	}
	private static void verifySources(ServerLevel level, NetworkCheckpoint root, JsonObject report) {
		var bee = root.ownedMachines().values().stream().map(record -> record.bees().bee(0))
				.filter(value -> value.plan().beeType().equals("productivebees:lumber_bee")).findFirst().orElseThrow();
		var oak = StaticFeedingAdapter.fromStack(new ItemStack(Items.OAK_LOG), level.registryAccess());
		var birch = StaticFeedingAdapter.fromStack(new ItemStack(Items.BIRCH_LOG), level.registryAccess());
		var stone = StaticFeedingAdapter.fromStack(new ItemStack(Items.STONE), level.registryAccess());
		var slots = List.of(new FeedingSlotStore.Slot(oak, 1, false, 0),
				new FeedingSlotStore.Slot(birch, 1, true, 0), new FeedingSlotStore.Slot(stone, 1, false, 2));
		var context = new BeeWorkExecutor.Context(true, true, true, bee.plan().recipeRevision(), 0, new BeeWorkConditions.Environment(false, false, false, false));
		var captured = DynamicBeeAdapter.prepare(level, bee, slots, bee.plan().cycleTicks(), null, context);
		require(captured.generationSource().copy().getList("items", Tag.TAG_COMPOUND).size() == 1, "Disabled or foreign-group flower captured");
		require(!StaticFeedingAdapter.flower(List.of(new FeedingSlotStore.Slot(oak, 1, true, 0)), 0,
				ResourceLocation.parse(bee.plan().beeType()), level.registryAccess()), "Disabled flower kept work eligible");
		var shared = List.of(new FeedingSlotStore.Slot(oak, 1, false, 0), new FeedingSlotStore.Slot(birch, 1, false, 0));
		var source = DynamicBeeAdapter.prepare(level, bee, shared, bee.plan().cycleTicks(), null, context).generationSource().copy();
		source.putBoolean("allFlowers", true);
		var paid = BeeWorkExecutor.advanceBee(bee, bee.revision(), context.withGenerationSource(new AssetImage(source)),
				bee.plan().cycleTicks(), 1, Long.MAX_VALUE, null).candidate();
		var generated = DynamicBeeAdapter.generate(level, POS, paid);
		require(generated.phase() == BeeGeneration.Phase.READY && generated.pool().size() == 2, "All flowers lost a mapped source");
		report.addProperty("snapshotGroupsDisabledAndAllFlowers", true);
	}
	private static void seedMachine(ServerLevel level) {
		var controller = machine.core();
		var player = FakePlayerFactory.get(level, new GameProfile(controller.ownerId(), "DynamicOwner"));
		new PlayerInventorySyncProbe(player);
		player.setPos(machine.pos().getCenter());
		var menu = new MachineMenu(87, player.getInventory(), controller, UUID.randomUUID());
		player.containerMenu = menu;
		if (EFFECTS) for (var type : effectUpgrades(true)) {
			int upgrade = MachineUpgrades.slot(true, type);
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(upgrade));
			require(MachineExchange.exchange(menu, player, MachineExchange.Action.UPGRADE_IN, upgrade, 0, 1, false).moved() == 1,
					"Machine effect upgrade rejected: " + type);
		}
		if (MYRIAD) {
			int upgrade = MachineUpgrades.slot(true, PbUpgradeType.BLOCK);
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(upgrade));
			require(MachineExchange.exchange(menu, player, MachineExchange.Action.UPGRADE_IN, upgrade, 0, 1, false).moved() == 1,
					"Myriad machine block installation rejected");
		}
		for (int index = 0; index < BEE_COUNT; index++) {
			var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get());
			cage.set(DataComponents.CUSTOM_DATA, CustomData.of(bee(index, true)));
			player.getInventory().items.set(0, cage);
			require(MachineExchange.exchange(menu, player, MachineExchange.Action.CAGE_IN, index, 0, 1, false).moved() == 1, "Native dynamic cage rejected");
			player.getInventory().items.set(0, flower(index));
			require(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_IN, index, 0, 1, false).moved() == 1, "Dynamic flower exchange rejected");
		}
		var before = controller.assets.work();
		controller.assets.commit(before, before.receiveEnergy(Long.MAX_VALUE).apply(before));
	}
	private static List<PbUpgradeType> effectUpgrades(boolean fullType) {
		var types = new ArrayList<>(List.of(PbUpgradeType.GENE_SAMPLER, PbUpgradeType.ESSENCE_CONVERSION, PbUpgradeType.USELESS_BYPRODUCT));
		if (fullType) {
			types.add(PbUpgradeType.GENE_TYPE_ONLY);
			types.add(PbUpgradeType.GENE_FULL_PURITY);
		}
		return types;
	}
	private static void verifyEffects(ServerLevel level, Map<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey, ProductAmount> amounts, long cycles) {
		long obsidian = 0;
		long shards = 0;
		long genes = 0;
		for (var entry : amounts.entrySet()) {
			require(RuntimeProductPolicies.peek(level).accepts(entry.getKey()), "Produced effect key was not admitted");
			require(entry.getKey().kind() == com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey.Kind.ITEM, "Discard left a fluid output");
			var stack = ProductKeyCodec.item(entry.getKey(), 1, level.registryAccess());
			if (stack.is(Items.OBSIDIAN)) obsidian += entry.getValue().longSaturated();
			else if (entry.getKey().id().toString().equals("productivebees:obsidian_shard")) shards += entry.getValue().longSaturated();
			else if (entry.getKey().id().toString().equals("productivebees:gene")) {
				require(stack.get(cy.jdkdigital.productivebees.init.ModDataComponents.GENE_GROUP.get()) != null, "Gene lost its complete component");
				genes += entry.getValue().longSaturated();
			} else throw new IllegalStateException("Unexpected effect output " + entry.getKey());
		}
		require(obsidian == cycles * 2 && shards == cycles * 2 && genes == cycles,
				"Effect conservation mismatch: cycles=" + cycles + " obsidian=" + obsidian + " shards=" + shards + " genes=" + genes);
	}
	private DynamicBeeProbe() { }
	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
