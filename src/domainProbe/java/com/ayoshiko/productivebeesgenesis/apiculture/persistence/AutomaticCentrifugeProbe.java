package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.Upgrade;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;

/** 正式 ticker 驱动异构双离心机及蜂箱串联；仅在启动前注入测试库存／规则。 */
public final class AutomaticCentrifugeProbe {
	private static final long MAINTENANCE = 7;
	private static long previousMaintenance;
	private static final ReservePolicy NONE = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
	private static final class Fixture {
		final BlockPos pos; final boolean chain;
		NetworkCoreBlockEntity core;
		NetworkSavedData data;
		TileEntityMekApiary hive;
		UUID hiveId;
		final List<TileEntityMekCentrifuge> tiles = new ArrayList<>();
		final Map<TileEntityMekCentrifuge, Integer> tickers = new ConcurrentHashMap<>();
		final Set<UUID> seenJobs = ConcurrentHashMap.newKeySet();
		final Map<UUID, Integer> lastProgress = new ConcurrentHashMap<>();
		Map<UUID, CentrifugeJob> previousJobs = Map.of();
		int previousBeeProgress, maintenanceTicks;
		final Map<ProductKey, ProductAmount> expected = new ConcurrentHashMap<>();
		long charged, plannedCost; int hiveTicker;
		boolean joined, beeStopped;
		Fixture(int x, boolean chain) { pos = new BlockPos(x, 154, 4); this.chain = chain; }
		void create(ServerLevel level) {
			level.setChunkForced(pos.getX() >> 4, 0, true); var owner = UUID.randomUUID();
			level.setBlockAndUpdate(pos, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(pos); core.initializeOwner(owner);
			for (int i = 0; i < (chain ? 1 : 2); i++) {
				var at = i == 0 ? pos.east() : pos.west(); level.setBlockAndUpdate(at, ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
				var tile = (TileEntityMekCentrifuge) level.getBlockEntity(at); tiles.add(tile); tile.setOwnerUUID(owner); tile.setControlType(RedstoneControl.HIGH);
				if (i == 1) { tile.getComponent().addUpgrades(Upgrade.SPEED, 2); tile.getComponent().addUpgrades(Upgrade.ENERGY, 1); require(tile.installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 1) == 1, "Upgrade fixture failed"); }
				require(tile.installPbUpgradeBulk(PbUpgradeType.TIME, 1) == 1, "Time fixture failed");
			}
			if (chain) {
				level.setBlockAndUpdate(pos.west(), ModBlocks.MEK_APIARY.get().defaultBlockState()); hive = (TileEntityMekApiary) level.getBlockEntity(pos.west());
				hive.setOwnerUUID(owner); hive.setControlType(RedstoneControl.HIGH); hive.setFeederConversionEnabled(false);
				var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
				var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
				var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
				hive.getBeeSlot(0).setBeeData(bee); hive.getBeeSlot(0).setBaseMinOccupationTicks(5); hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
			}
		}
		void start(ServerLevel level, NetworkDirectory directory) {
			data = directory.loadExisting(core.network()).ready();
			for (var record : data.checkpoint().ownedMachines().values()) if (record.claim().machine().endsWith("mek_apiary")) hiveId = record.claim().member();
			for (var tile : tiles) { tickers.put(tile, tile.ticker); tile.setControlType(RedstoneControl.DISABLED); }
			if (hive != null) { hiveTicker = hive.ticker; hive.setControlType(RedstoneControl.DISABLED); }
			charge(level, chain ? 3_000_000 : 200); require(core.setProductionRunning(true), "Automatic start rejected");
		}
		void charge(ServerLevel level, int amount) {
			var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
			require(port != null && port.receiveEnergy(amount, false) == amount, "Automatic core power input failed"); charged += amount;
		}
		void observe() {
			boolean progressed = false;
			var currentJobs = new ConcurrentHashMap<UUID, CentrifugeJob>();
			for (var tile : tiles) require(tile.ticker == tickers.get(tile) && new MachineAssetStore(tile).empty(), "Automatic centrifuge executed a physical ticker or inventory");
			if (hive != null) {
				require(hive.ticker == hiveTicker && new MachineAssetStore(hive).empty(), "Automatic chain executed physical bee work");
				var state = data.checkpoint().ownedMachines().get(hiveId).bees();
				if (state != null) { progressed = state.bee(0).progress() != previousBeeProgress; previousBeeProgress = state.bee(0).progress(); }
				if (state != null && !beeStopped && state.bee(0).revision() > 0 && state.bee(0).progress() == 0 && state.bee(0).drained()) {
					var bee = state.bee(0); hive.setControlType(RedstoneControl.HIGH); beeStopped = true;
					plannedCost += Math.multiplyExact(bee.plan().cycleTicks(), bee.plan().energyPerTick());
				}
			}
			for (var record : data.checkpoint().ownedMachines().values()) if (record.centrifuge() != null) for (var job : record.centrifuge().jobs().values()) {
				int previous = lastProgress.getOrDefault(job.id(), 0); require(job.progress() >= previous && job.progress() - previous <= 1, "Automatic centrifuge advanced more than one real tick");
				progressed |= job.progress() > previous; currentJobs.put(job.id(), job);
				lastProgress.put(job.id(), job.progress()); if (!seenJobs.add(job.id())) continue;
				require(job.plan().cycleTicks() > 1, "Fixture needs an observable multi-tick job");
				plannedCost += Math.multiplyExact(job.plan().cycleTicks(), job.plan().energyPerTick(job.operations()));
				job.plan().sample(job.operations(), job.seed()).forEach((key, amount) -> expected.merge(key, amount, ProductAmount::add));
			}
			for (var job : previousJobs.values()) if (!currentJobs.containsKey(job.id()) && !job.paid()) {
				require(job.progress() == job.plan().cycleTicks() - 1, "An unfinished job disappeared"); progressed = true;
			}
			previousJobs = currentJobs;
			if (progressed) maintenanceTicks++;
		}
		boolean drained() { return data.checkpoint().ownedMachines().values().stream().allMatch(record -> record.centrifuge() == null || record.centrifuge().drained()); }
		ProductAmount balance(ProductKey key) { return data.checkpoint().ledger().balances().getOrDefault(key, ProductAmount.ZERO); }
	}
	private static final Fixture RULES = new Fixture(104, false), CHAIN = new Fixture(136, true);
	private static final List<Fixture> FIXTURES = List.of(RULES, CHAIN);
	private static ProductKey a, b, block;
	private static int phase, started, until;
	private static NetworkCheckpoint paused;
	private static SchedulerCheckpoint configured;
	public static void start(MinecraftServer server) {
		previousMaintenance = com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.maintenanceFe.get();
		com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.maintenanceFe.set(MAINTENANCE);
		started = server.getTickCount(); for (var fixture : FIXTURES) fixture.create(server.overworld());
		a = comb(server.overworld(), false, "a"); b = comb(server.overworld(), false, "b"); block = comb(server.overworld(), true, null);
	}
	public static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 7) return true;
		require(server.getTickCount() - started < 2200, "Automatic centrifuge timeout " + phase + " " + RULES.core.runtime().status() + " " + CHAIN.core.runtime().status());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		for (var fixture : FIXTURES) require(fixture.core.ownership().status() != CoreOwnershipController.Status.RECOVERY, fixture.core.ownership().failure());
		if (phase == 0) {
			for (var f : FIXTURES) {
				if (!f.joined && f.core.topology() != null) { require(f.core.ownership().command(true), "Automatic takeover failed"); f.joined = true; }
				if (f.core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			}
			RULES.data = directory.loadExisting(RULES.core.network()).ready();
			var global = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a), ReserveLimit.floor(ProductAmount.of(6)), new ProductMatcher(ProductMatcher.Mode.EXACT, a), ReserveLimit.NONE));
			var local = new ReservePolicy.Layer(ReserveLimit.NONE, Map.of(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a), ReserveLimit.floor(ProductAmount.of(15))));
			var combRule = new ProcessingRule("combs", 0, true, 10, 2, 2, new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, a)), new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, global, local));
			var exactRule = new ProcessingRule("exact", 0, true, 20, 1, 1, new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.EXACT, a)), combRule.reserves());
			var beeRule = new ProcessingRule("bee", 0, true, 5, 1, 2, new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.BEE_TYPE, a)), combRule.reserves());
			var blockRule = new ProcessingRule("blocks", 0, true, 0, 1, 1, new ProcessingRule.Tag(ProductKey.Kind.ITEM, ResourceLocation.parse("c:storage_blocks/honeycombs")), new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, new ReservePolicy.Layer(ReserveLimit.floor(ProductAmount.of(1)), Map.of()), ReservePolicy.Layer.NONE));
			var missing = new ProcessingRule("missing", 0, true, 100, 1, 1, new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.EXACT, comb(level, false, "missing"))), NONE);
			configured = new SchedulerCheckpoint(List.of(blockRule, missing, combRule, exactRule, beeRule), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0);
			publishFixture(RULES.data, new LedgerCheckpoint(1, Map.of(a, ProductAmount.of(10), b, ProductAmount.of(10), block, ProductAmount.of(4)), List.of()), configured);
			for (var f : FIXTURES) f.start(level, directory); phase = 1; return false;
		}
		if (phase < 6) for (var f : FIXTURES) f.observe();
		if (phase == 1) {
			if (RULES.seenJobs.isEmpty() || RULES.drained()) return false;
			long stored = RULES.data.checkpoint().energy().stored();
			for (var record : RULES.data.checkpoint().ownedMachines().values()) if (record.centrifuge() != null)
				for (var job : record.centrifuge().jobs().values()) if (job.paid() || job.plan().energyPerTick(job.operations()) + MAINTENANCE <= stored) return false;
			paused = RULES.data.checkpoint(); until = server.getTickCount() + 12; phase = 2; return false;
		}
		if (phase == 2) {
			require(RULES.data.checkpoint().ownedMachines().equals(paused.ownedMachines()) && RULES.data.checkpoint().energy().equals(paused.energy()), "Unfunded automatic job advanced");
			if (server.getTickCount() < until) return false;
			require(RULES.core.setProductionRunning(false), "Automatic pause rejected"); RULES.charge(level, 3_000_000);
			paused = RULES.data.checkpoint(); until = server.getTickCount() + 12; phase = 3; return false;
		}
		if (phase == 3) {
			require(RULES.data.checkpoint() == paused, "Paused automatic job consumed inputs or energy"); if (server.getTickCount() < until) return false;
			RULES.core.setProductionRunning(true); var old = RULES.core; var tag = old.saveWithFullMetadata(server.registryAccess());
			var replacement = new NetworkCoreBlockEntity(RULES.pos, old.getBlockState()); replacement.loadWithComponents(tag, server.registryAccess());
			level.removeBlockEntity(RULES.pos); level.setBlockEntity(replacement); RULES.core = replacement;
			require(!old.setProductionRunning(true) && replacement.productionRunning(), "Automatic reload accepted an old host or lost intent"); phase = 4; return false;
		}
		if (phase == 4) {
			if (!RULES.drained() || !CHAIN.drained() || CHAIN.seenJobs.isEmpty() || !CHAIN.beeStopped) return false;
			if (!RULES.balance(a).add(RULES.balance(b)).equals(ProductAmount.of(15)) || !RULES.balance(block).equals(ProductAmount.of(1))) return false;
			for (var f : FIXTURES) {
				var expected = new ConcurrentHashMap<>(f.expected);
				if (!f.chain) { expected.put(a, f.balance(a)); expected.put(b, f.balance(b)); expected.put(block, ProductAmount.of(1)); }
				require(f.data.checkpoint().ledger().balances().equals(expected), "Automatic sampled item/fluid amounts differ");
				require(f.data.checkpoint().energy().stored() == f.charged - f.plannedCost - MAINTENANCE * f.maintenanceTicks, "Automatic exact FE debit differs including per-tick maintenance");
			}
			var goal = new ProcessingRule("goal", 0, true, 1000, 1, 1, new ProcessingRule.Goal(a, ProductAmount.of(100), ProductAmount.of(200)), NONE);
			publishFixture(RULES.data, RULES.data.checkpoint().ledger(), new SchedulerCheckpoint(List.of(goal), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0));
			paused = RULES.data.checkpoint(); until = server.getTickCount() + 25; phase = 5; return false;
		}
		if (phase == 5) {
			require(RULES.data.checkpoint() == paused, "Unsupported goal silently consumed protected inputs");
			if (server.getTickCount() < until) return false;
			for (var f : FIXTURES) { f.core.setProductionRunning(false); for (var tile : f.tiles) tile.setControlType(RedstoneControl.HIGH); require(f.core.ownership().command(false), "Automatic return rejected"); }
			phase = 6; return false;
		}
		if (phase == 6) {
			for (var f : FIXTURES) if (f.core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
			for (var f : FIXTURES) {
				for (var tile : f.tiles) require(tile.energyContainer().getEnergy() == 0, "Automatic return duplicated shared FE");
				level.removeBlock(f.pos.east(), false); level.removeBlock(f.pos.west(), false); level.removeBlock(f.pos, false); level.setChunkForced(f.pos.getX() >> 4, 0, false);
			}
			report.addProperty("automaticCentrifugeLayeredReservesTagsAndMissingPriority", true);
			report.addProperty("automaticCentrifugeStarvationPauseCoreReloadAndExactFees", true);
			report.addProperty("automaticBeeCentrifugeChainAndSeededItemFluidOutputs", true);
			report.addProperty("automaticCentrifugeJobsObserved", RULES.seenJobs.size() + CHAIN.seenJobs.size());
			report.addProperty("automaticRulesMaintenanceTicks", RULES.maintenanceTicks);
			report.addProperty("automaticChainMaintenanceTicks", CHAIN.maintenanceTicks);
			report.addProperty("automaticMaintenanceSharedPerTickAndAtomicWithWork", true);
			com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.beeNetwork.maintenanceFe.set(previousMaintenance);
			phase = 7; return true;
		}
		return false;
	}
	private static void publishFixture(NetworkSavedData data, LedgerCheckpoint ledger, SchedulerCheckpoint scheduler) {
		var old = data.checkpoint(); data.publish(new NetworkCheckpoint(old.identity(), old.revision() + 1, old.policyRevision(), ledger,
				old.transfers(), old.discoveries(), old.members(), old.lanes(), scheduler, old.energy()).restoredOwnership(old.ownedMachines()));
	}
	private static ProductKey comb(ServerLevel level, boolean block, String name) {
		var stack = new ItemStack(block ? cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_COMB_BLOCK.get() : cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
		stack.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
		if (name != null) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return ProductKeyCodec.item(stack, level.registryAccess());
	}
	private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private AutomaticCentrifugeProbe() { }
}
