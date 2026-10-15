package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.ayoshiko.productivebeesgenesis.mek.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticRestartFixture.require;

/** 四档普通工厂共用正式所有权、ticker 和菜单；保管资产不混入网络产物核算。 */
public final class FactoryCentrifugeProbe {
	private static final List<Fixture> fixtures = new ArrayList<>();
	private static boolean reading;
	private static int started;
	private static long writerPid;
	private static com.ayoshiko.productivebeesgenesis.multiblock.world.FactoryMixedProbe mixed;
	private static NetworkCheckpoint mixedSaved;
	private static int clientStarted;
	public static volatile boolean clientReady, clientVerified, clientSaved;
	public static volatile BlockPos clientPos;
	public static volatile String clientMachine;
	private static List<String> factoryMachines() {
		if (Boolean.getBoolean("pbg.factoryCentrifuge.extra")) return CentrifugeWorkLayout.EXTRA_MACHINES;
		return Boolean.getBoolean("pbg.factoryCentrifuge.evolved") ? CentrifugeWorkLayout.EVOLVED_MACHINES
				: CentrifugeWorkLayout.ORDINARY_MACHINES.subList(1, CentrifugeWorkLayout.ORDINARY_MACHINES.size());
	}
	private static Path manifest(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("automatic-probe.dat"); }
	public static void start(MinecraftServer server) throws Exception {
		reading = "read".equals(System.getProperty("pbg.automatic.mode")); started = server.getTickCount();
		ModConfig.SERVER.beeNetwork.enabled.set(!reading); ModConfig.SERVER.beeNetwork.maintenanceFe.set(0L);
		ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(true);
		var level = server.overworld();
		if (reading) {
			var tag = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap()); writerPid = tag.getLong("writerPid");
			require(tag.getBoolean("evolvedFactories") == Boolean.getBoolean("pbg.factoryCentrifuge.evolved"), "Reader factory family differs from writer");
			require(tag.getBoolean("extraFactories") == Boolean.getBoolean("pbg.factoryCentrifuge.extra"), "Reader ME family differs from writer");
			require(writerPid != ProcessHandle.current().pid(), "Factory reader reused writer JVM");
			for (var raw : tag.getList("fixtures", 10)) {
				var value = (CompoundTag) raw; var fixture = new Fixture(BlockPos.of(value.getLong("position")), value.getString("machine"));
				require(factoryMachines().contains(fixture.machine), "Writer contains a foreign factory family");
				fixture.force(level); fixture.bind(level); fixture.original = new AssetImage(value.getCompound("original"));
				fixture.saved = NetworkCheckpointCodec.forRegistries(level.registryAccess()).decode(value.getCompound("saved"));
				fixture.member = value.getUUID("member"); fixtures.add(fixture);
			}
		} else {
			writerPid = ProcessHandle.current().pid();
			var machines = factoryMachines();
			for (var machine : server.isDedicatedServer() ? machines : List.of(machines.getLast())) {
				var fixture = new Fixture(new BlockPos(440 + fixtures.size() * 32, 160, 8), machine);
				fixture.create(level); fixtures.add(fixture);
			}
		}
	}
	public static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		int elapsed = server.getTickCount() - started;
		require(clientReady ? server.getTickCount() - clientStarted < 3600 : elapsed < (mixed == null ? 2400 : 4200), "Factory probe timeout: " + fixtures.stream().map(f -> f.machine + "/" + f.stage + "/" + f.core.ownership().status() + "/" + f.core.runtime().status()).toList());
		if (elapsed % 200 == 0) com.mojang.logging.LogUtils.getLogger().info("FACTORY_CENTRIFUGE_PROGRESS {}", fixtures.stream().map(f -> f.stage + "/" + f.core.runtime().status()).toList());
		var level = server.overworld();
		for (var fixture : fixtures) fixture.step(level);
		if (fixtures.stream().anyMatch(f -> f.stage != 9)) return false;
		if (!server.isDedicatedServer() && !clientVerified) {
			if (!clientReady) {
				var fixture = fixtures.getFirst();
				clientPos = fixture.pos;
				clientMachine = fixture.machine;
				level.setBlockAndUpdate(clientPos.south(), NetworkContent.CENTRIFUGE_TERMINAL.get().defaultBlockState());
				var player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(level, clientPos.getX() + 0.5, clientPos.getY() + 1.5, clientPos.getZ() + 2.5, 180, 30);
				player.getAbilities().mayfly = true; player.getAbilities().flying = true; player.onUpdateAbilities();
				var stack = Arrays.stream(Upgrade.values()).filter(u -> u.name().equals("STACK")).findFirst().orElseThrow();
				player.getInventory().items.set(0, mekanism.common.util.UpgradeUtils.getStack(stack, 1)); player.containerMenu.broadcastChanges();
				clientStarted = server.getTickCount(); clientReady = true;
			}
			return false;
		}
		if (reading) {
			if (mixed == null) mixed = new com.ayoshiko.productivebeesgenesis.multiblock.world.FactoryMixedProbe(level, factoryMachines().getLast());
			if (!mixed.step(level)) return false;
			mixedSaved = mixed.checkpoint();
		}
		ModConfig.SERVER.beeNetwork.enabled.set(false);
		var rows = new JsonArray(); var list = new ListTag();
		for (var f : fixtures) {
			f.saved = f.data.checkpoint(); var row = new JsonObject(); row.addProperty("machine", f.machine);
			row.addProperty("lanes", f.lanes); row.addProperty("completedProcesses", f.completed.size()); rows.add(row);
			var tag = new CompoundTag(); tag.putLong("position", f.pos.asLong()); tag.putString("machine", f.machine);
			tag.put("original", f.original.copy()); tag.put("saved", NetworkCheckpointCodec.encode(f.saved)); tag.putUUID("member", f.member); list.add(tag);
		}
		if (!reading) {
			var tag = new CompoundTag();
			tag.putLong("writerPid", writerPid);
			tag.putBoolean("evolvedFactories", Boolean.getBoolean("pbg.factoryCentrifuge.evolved"));
			tag.putBoolean("extraFactories", Boolean.getBoolean("pbg.factoryCentrifuge.extra"));
			tag.put("fixtures", list);
			NbtIo.writeCompressed(tag, manifest(server));
		}
		report.add("centrifugeFactories", rows); report.addProperty("factoryCentrifuge", true);
		report.addProperty("evolvedFactories", Boolean.getBoolean("pbg.factoryCentrifuge.evolved"));
		report.addProperty("extraFactories", Boolean.getBoolean("pbg.factoryCentrifuge.extra"));
		report.addProperty("automaticRestartIsNewJvm", reading); report.addProperty("producerPid", writerPid);
		report.addProperty("allLanesPaidAndRestored", reading); report.addProperty("sealedAssetsAndReturn", reading);
		report.addProperty("upgradesSimulationAndStale", reading); report.addProperty("noItemEntities", true);
		report.addProperty("mixedFourCarriersProducedAndReturned", reading);
		report.addProperty("ae2Loaded", net.neoforged.fml.ModList.get().isLoaded("ae2")); report.addProperty("passed", true); return true;
	}
	public static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		require(fixtures.size() == (server.isDedicatedServer() ? factoryMachines().size() : 1) && fixtures.stream().allMatch(f -> f.stage == 9), "Incomplete factory shutdown");
		for (var f : fixtures) {
			var file = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + f.core.network().networkId() + ".dat");
			require(NetworkCheckpointCodec.encode(f.saved).equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data")), "Factory shutdown checkpoint mismatch");
		}
		if (mixed != null) {
			require(mixedSaved != null, "Mixed checkpoint was not sealed before shutdown");
			var file = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + mixedSaved.identity().networkId() + ".dat");
			require(NetworkCheckpointCodec.encode(mixedSaved).equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data")), "Mixed shutdown checkpoint mismatch");
		}
		report.addProperty("normalShutdownCheckpointSaved", true);
		clientSaved = true;
	}
	private static ItemStack comb() {
		var stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
		stack.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron")); return stack;
	}
	/** 两种夹具显式准备公共原料，不把迁移保管区算入公共余额。 */
	public static void supplyComb(NetworkSavedData data, ServerLevel level, int count) {
		var current = data.checkpoint(); var amounts = new HashMap<>(current.ledger().balances());
		amounts.merge(ProductKeyCodec.item(comb(), level.registryAccess()), ProductAmount.of(count), ProductAmount::add);
		var ledger = new LedgerCheckpoint(current.ledger().revision() + 1, amounts, current.ledger().transactions());
		data.publish(new NetworkCheckpoint(current.identity(), current.revision() + 1, current.policyRevision(), ledger, current.transfers(), current.discoveries(),
				current.members(), current.lanes(), current.scheduler(), current.energy()).restoredOwnership(current.ownedMachines()));
	}
	private static final class Fixture {
		final BlockPos pos;
		final String machine;
		final int lanes;
		NetworkCoreBlockEntity core;
		TileEntityMekanism factory;
		PbRecipeContext context;
		MachineAssetSection assets;
		TileEntityMekApiary hive;
		NetworkSavedData data;
		AssetImage original;
		NetworkCheckpoint saved, previous;
		UUID member;
		int stage;
		final Set<Integer> completed = new HashSet<>();
		final Map<ProductKey, ProductAmount> expected = new HashMap<>();
		long expectedEnergy;
		Fixture(BlockPos pos, String machine) { this.pos = pos; this.machine = machine; lanes = CentrifugeWorkLayout.lanes(machine); }
		void force(ServerLevel level) { for (int dx = -1; dx <= 1; dx++) level.setChunkForced((pos.getX() >> 4) + dx, pos.getZ() >> 4, true); level.getChunkAt(pos); }
		void bind(ServerLevel level) {
			core = (NetworkCoreBlockEntity) level.getBlockEntity(pos);
			factory = (TileEntityMekanism) level.getBlockEntity(pos.east());
			context = (PbRecipeContext) factory;
			assets = ((MachineAssetSection.Provider) factory).ownershipAssets();
			hive = (TileEntityMekApiary) level.getBlockEntity(pos.west());
			require(core != null && factory != null && hive != null && context.processes() == lanes, "Missing real factory layout");
		}
		void create(ServerLevel level) throws ReflectiveOperationException {
			force(level); var owner = level.getServer().isDedicatedServer() ? UUID.randomUUID() : level.getServer().getSingleplayerProfile().getId();
			level.setBlockAndUpdate(pos, NetworkContent.CORE.get().defaultBlockState());
			level.setBlockAndUpdate(pos.east(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse(machine)).defaultBlockState());
			String hiveMachine = CentrifugeWorkLayout.EVOLVED_MACHINES.contains(machine)
					? "productivebeesgenesis:ultimate_mek_apiary_factory" : machine.replace("centrifuge", "apiary");
			level.setBlockAndUpdate(pos.west(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse(hiveMachine)).defaultBlockState());
			bind(level);
			core.initializeOwner(owner); factory.setOwnerUUID(owner); hive.setOwnerUUID(owner);
			factory.setControlType(RedstoneControl.HIGH); hive.setControlType(RedstoneControl.HIGH);
			factory.getComponent().addUpgrades(Upgrade.ENERGY, 4); factory.getComponent().addUpgrades(Upgrade.SPEED, 2);
			var upgradeOutput = (mekanism.api.inventory.IInventorySlot) factory.getClass().getMethod("getPbUpgradeOutputSlot").invoke(factory);
			upgradeOutput.setStack(com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot.getRepresentativeStack(com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.TIME_2));
			for (int i = 0; i < lanes; i++) {
				context.inputSlot(i).setStack(comb().copyWithCount(i + 2));
				context.primaryOutputSlot(i).setStack(new ItemStack(Items.IRON_INGOT, i + 1));
				context.secondaryOutputSlot(i).setStack(new ItemStack(Items.GOLD_INGOT, i + 1));
				context.tertiaryOutputSlot(i).setStack(new ItemStack(Items.DIAMOND, i + 1));
			}
			for (var fluid : List.of(new FluidStack(Fluids.WATER, 1234), new FluidStack(Fluids.LAVA, 2345))) context.fluidOutputTankForInsert(fluid).setStack(fluid);
			var extra = assets.capture(level.registryAccess());
			var pending = new ListTag();
			var job = new CompoundTag();
			job.putInt("process", lanes - 1); var items = new ListTag(); var item = (CompoundTag) new ItemStack(Items.EMERALD).save(level.registryAccess());
			item.putInt("productivebeesgenesis_count", 19); items.add(item); job.put("items", items);
			job.put("fluid", new FluidStack(Fluids.WATER, 1).save(level.registryAccess())); job.putLong("fluid_amount", 3_000_000_000L); pending.add(job);
			extra.put("productivebeesgenesis_pb_committed_pending", pending);
			assets.restore(extra, level.registryAccess());
			var store = new MachineAssetStore(factory); original = store.capture(level.registryAccess()); store.validate(original, level);
			var partial = extra.copy(); var progress = new int[lanes]; progress[lanes - 1] = 1; partial.putIntArray("nativeProgress", progress);
			assets.restore(partial, level.registryAccess());
			require(!store.prepared() && !OwnershipEndpoints.bound(factory), "Legacy partial cycle accepted");
			assets.restore(extra, level.registryAccess());
			require(original.equals(store.capture(level.registryAccess())), "Refusal changed physical assets");
			var excessive = original.copy(); var fluids = new ListTag();
			for (int i = 0; i <= lanes; i++) {
				var fluid = new FluidStack(Fluids.WATER, 1000); var component = new CompoundTag(); component.putInt("variant", i);
				fluid.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(component));
				var value = new CompoundTag(); value.put("fluidStack", fluid.save(level.registryAccess())); fluids.add(value);
			}
			excessive.getCompound("extra").getCompound(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS).put("tanks", fluids);
			boolean refused = false; try { store.validate(new AssetImage(excessive), level); } catch (IllegalArgumentException expected) { refused = true; }
			require(refused, "Over-capacity multi-fluid image accepted");
			require(original.equals(store.capture(level.registryAccess())), "Capacity preflight changed source");
			factory.setChanged();
		}
		OwnedMachineRecord record() { return data.checkpoint().ownedMachines().get(member); }
		void step(ServerLevel level) {
			require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
			require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(4)).isEmpty(), "Factory emitted world item");
			if (stage == 9 || core.topology() == null || !core.topology().valid() || core.ownership().busy()) return;
			if (stage == 0) {
				if (!reading) { require(core.ownership().command(true), "Factory join refused"); stage = 1; return; }
				data = core.ownership().readyAuthority(); if (data == null || core.ownership().status() != CoreOwnershipController.Status.MANAGED) return;
				require(saved.equals(data.checkpoint()), "Cross-JVM factory work changed before resume");
				previous = data.checkpoint(); expected.putAll(previous.ledger().balances()); expectedEnergy = previous.energy().stored();
				ModConfig.SERVER.beeNetwork.enabled.set(true); factory.setControlType(RedstoneControl.DISABLED);
				require(core.setProductionRunning(true), "Factory resume failed"); stage = 3; return;
			}
			if (stage == 1) {
				data = core.ownership().readyAuthority(); if (data == null || core.ownership().status() != CoreOwnershipController.Status.MANAGED) return;
				member = data.checkpoint().ownedMachines().values().stream().filter(r -> r.claim().machine().equals(machine)).findFirst().orElseThrow().claim().member();
				require(new MachineAssetStore(factory).empty(), "Factory source not fully cleared");
				supplyComb(data, level, lanes);
				var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
				require(energy != null && energy.receiveEnergy(1_000_000, false) == 1_000_000, "Factory core energy rejected");
				factory.setControlType(RedstoneControl.DISABLED); require(core.setProductionRunning(true), "Factory production refused"); stage = 2; return;
			}
			if (stage == 2) {
				var state = record().centrifuge();
				if (state == null || state.jobs().size() != lanes || state.jobs().values().stream().anyMatch(j -> j.progress() < 2 || j.paid())) return;
				require(state.laneCount() == lanes && state.jobs().get(lanes - 1) != null, "Last real process was omitted");
				factory.setControlType(RedstoneControl.HIGH); require(core.setProductionRunning(false), "Factory writer pause failed");
				stage = 9; return;
			}
			if (stage == 3) {
				observe(); if (completed.size() != lanes) return;
				require(record().centrifuge().drained(), "Factory work not settled"); factory.setControlType(RedstoneControl.HIGH);
				require(core.setProductionRunning(false), "Factory stop failed"); stage = 4;
			}
			if (stage == 4) { management(level); require(core.ownership().command(false), "Factory return refused"); stage = 5; return; }
			if (stage == 5) {
				if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return;
				require(original.equals(new MachineAssetStore(factory).capture(level.registryAccess())), "Factory input/output/fluid/paid remainder or upgrades changed on return");
				require(!OwnershipEndpoints.bound(factory) && data.checkpoint().ownedMachines().values().stream().allMatch(r -> r.phase() == OwnedMachineRecord.Phase.RETURNED), "Factory return receipt incomplete");
				stage = 9;
			}
		}
		void observe() {
			var current = data.checkpoint(); var old = previous.ownedMachines().get(member).centrifuge(); var next = record().centrifuge();
			for (var entry : old.jobs().entrySet()) {
				var job = entry.getValue(); var now = next.jobs().get(entry.getKey());
				int delta = (now == null ? job.plan().cycleTicks() : now.progress()) - job.progress();
				require(delta >= 0 && delta <= 1, "Factory received unearned catch-up progress");
				expectedEnergy -= delta * AutomaticRestartOracle.energyPerTick(job);
				if (now == null) {
					AutomaticRestartOracle.outputs(job, (key, amount) -> expected.merge(key, amount, ProductAmount::add)); completed.add(entry.getKey());
				}
			}
			require(expectedEnergy == current.energy().stored(), "Factory fee differs from lane progress");
			require(expected.equals(current.ledger().balances()), "Factory output differs from frozen results");
			previous = current;
		}
		void management(ServerLevel level) {
			var player = new ServerPlayer(level.getServer(), level, new GameProfile(core.owner(), "CentrifugeManager"), ClientInformation.createDefault());
			new PlayerInventorySyncProbe(player); player.setPos(pos.getCenter());
			var menu = (NetworkCoreMenu) core.createMenu(95, player.getInventory(), player); player.containerMenu = menu;
			require(menu != null && menu.stillValid(player), "Factory menu invalid");
			for (var upgrade : Upgrade.values()) if (upgrade == Upgrade.SPEED || upgrade == Upgrade.ENERGY || upgrade.name().equals("STACK")) {
				player.getInventory().items.set(0, mekanism.common.util.UpgradeUtils.getStack(upgrade, 1));
				var before = data.checkpoint(); long revision = record().centrifuge().revision();
				var profile = StaticCentrifugeAdapter.profile(level, factory, record());
				require(menu.exchangeUpgrade(player, member, revision, upgrade, 0, 1, MemberUpgradeService.Action.INSTALL, true).moved() == 1 && before == data.checkpoint(), "Factory upgrade simulation changed assets");
				require(menu.exchangeUpgrade(player, member, revision, upgrade, 0, 1, MemberUpgradeService.Action.INSTALL, false).moved() == 1, "Factory upgrade install refused");
				if (upgrade.name().equals("STACK")) {
					require(StaticCentrifugeAdapter.profile(level, factory, record()).parallel() == profile.parallel() * 2, "STACK did not double per-lane capacity");
					int limit = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
					try { ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.set(1); require(StaticCentrifugeAdapter.profile(level, factory, record()).parallel() == 1, "Factory ignored configured operation limit"); }
					finally { ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.set(limit); }
				}
				require(menu.exchangeUpgrade(player, member, revision, upgrade, 0, 1, MemberUpgradeService.Action.INSTALL, false).status() == MemberUpgradeService.Status.STALE, "Factory stale upgrade accepted");
				require(menu.exchangeUpgrade(player, member, record().centrifuge().revision(), upgrade, 0, 1, MemberUpgradeService.Action.REMOVE, false).moved() == 1, "Factory upgrade return failed");
			}
			var page = menu.querySelections(player, NetworkSelectionSession.Kind.UPGRADES, 0);
			require(page.rows().stream().anyMatch(r -> r instanceof NetworkSelectionSession.MemberRow m && m.claim().member().equals(member)), "Factory missing from upgrade index");
			player.closeContainer();
		}
	}
	private FactoryCentrifugeProbe() { }
}
