package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbApiaryUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.config.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.MemberUpgradeService.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.MemberUpgradeService.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实核心菜单交换、独立蜂箱能力对照和付费结果恢复；只存在于开发源集。 */
final class ApiaryProductivityProbe {
	private static final List<PbUpgradeType> TYPES = List.of(PbUpgradeType.PRODUCTIVITY, PbUpgradeType.PRODUCTIVITY_2, PbUpgradeType.PRODUCTIVITY_3, PbUpgradeType.PRODUCTIVITY_4, PbUpgradeType.BLOCK);
	private final NetworkSavedData data;
	private final UUID member;
	private final ServerPlayer player;
	private final NetworkCoreMenu menu;
	private final TileEntityMekApiary hive, reference;
	private final NetworkBeeService service;
	private int exchanges, cycles;
	private long boundaryNanos, continuationNanos;
	private ApiaryProductivityProbe(NetworkSavedData data, UUID member, ServerPlayer player, NetworkCoreMenu menu,
			TileEntityMekApiary hive, TileEntityMekApiary reference) {
		this.data = data; this.member = member; this.player = player; this.menu = menu; this.hive = hive; this.reference = reference;
		service = new NetworkBeeService(data, NetworkPersistence.directory(player.serverLevel().getServer()));
	}
	static void run(NetworkSavedData data, UUID member, ServerPlayer player, NetworkCoreMenu menu,
			TileEntityMekApiary hive, TileEntityMekApiary reference, JsonObject report) throws Exception {
		var probe = new ApiaryProductivityProbe(data, member, player, menu, hive, reference);
		probe.tiers(); probe.legacy(); probe.boundaries(); probe.blockBoundaries(); probe.mappingCases(report);
		report.addProperty("apiaryProductivityTypes", TYPES.size());
		report.addProperty("apiaryProductivityExchanges", probe.exchanges);
		report.addProperty("apiaryProductivityCycles", probe.cycles);
		report.addProperty("apiaryProductivityPhysicalAndConservation", true);
		report.addProperty("apiaryProductivityOldCycleConfigAndReturnGuard", true);
		report.addProperty("apiaryBlockOldKeysAndCombinedUpgrades", true);
		report.addProperty("apiaryOutputBoundaryMaxNanos", probe.boundaryNanos);
		report.addProperty("apiaryOutputContinuationMaxNanos", probe.continuationNanos);
	}
	private BeeMemberState state() { return data.checkpoint().ownedMachines().get(member).bees(); }
	private BeeRecord bee() { return state().bee(0); }
	private BeeWorkExecutor.Cycle desired() { return StaticApiaryAdapter.cycle(hive, data.checkpoint().ownedMachines().get(member), bee()); }
	private void source(PbUpgradeType type) { player.getInventory().items.set(20, PbUpgradeInventorySlot.getRepresentativeStack(type).copyWithCount(64)); }
	private void exchange(PbUpgradeType type, MemberUpgradeService.Action action, int count, boolean simulate,
			MemberUpgradeService.Status expected, int moved, long revision, int slot) {
		var before = data.checkpoint(); var oldBees = state(); var inventory = player.getInventory().save(new ListTag());
		int total = player.getInventory().getItem(slot).getCount() + PbApiaryUpgradeCounts.read(before.ownedMachines().get(member).assets().copy().getCompound("extra")).getOrDefault(type, 0);
		var result = menu.exchangePbUpgrade(player, member, revision, type, slot, count, action, simulate); exchanges++;
		require(result.status() == expected && result.moved() == moved, "Productivity exchange failed: " + type + "/" + action + " " + result);
		require(oldBees.bees().equals(state().bees()) && oldBees.feeding() == state().feeding() && before.energy() == data.checkpoint().energy()
				&& before.ledger() == data.checkpoint().ledger(), "Productivity exchange changed paid work, feeding or FE");
		for (var record : before.ownedMachines().values()) if (!record.claim().member().equals(member))
			require(record == data.checkpoint().ownedMachines().get(record.claim().member()), "Productivity affected another member");
		if (simulate || moved == 0) require(before == data.checkpoint() && inventory.equals(player.getInventory().save(new ListTag())), "Rejected productivity request mutated assets");
		else require(total == player.getInventory().getItem(slot).getCount() + PbApiaryUpgradeCounts.read(data.checkpoint().ownedMachines().get(member).assets().copy().getCompound("extra")).getOrDefault(type, 0), "Productivity item conservation failed");
	}
	private void exchange(PbUpgradeType type, MemberUpgradeService.Action action, int count) {
		exchange(type, action, count, false, MOVED, count, state().revision(), 20);
	}
	private void physical(PbUpgradeType type, int count) {
		int old = reference.getPbUpgradeCount(type);
		if (count > old) require(reference.installPbUpgradeBulk(type, count - old) == count - old, "Reference productivity install failed");
		while (reference.getPbUpgradeCount(type) > count) {
			reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
			require(reference.extractPbUpgradeByType(type), "Reference productivity removal failed");
		}
		reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
		reference.getApiaryUpgradeHandler().invalidateUpgradeCache();
		var current = desired();
		require(current.productionMultiplier() == reference.getApiaryUpgradeHandler().getProductivityMultiplier(), "Sealed multiplier differs from physical apiary");
		require(current.energyPerTick() == reference.energyContainer().getEnergyPerTick(), "Productivity changed energy price");
		var source = ProductKeyCodec.item(bee().plan().sourceOutput(), 1, player.registryAccess());
		var outputs = reference.getApiaryUpgradeHandler().hasCombBlockUpgrade()
				? new CombBlockConverter().convertCombsToBlocks(List.of(source)) : List.of(source);
		require(current.output().equals(ProductKeyCodec.item(outputs.getFirst(), player.registryAccess())), "Sealed key differs from independent physical conversion");
	}
	private void tiers() {
		for (var type : TYPES) {
			source(type); int limit = hive.getPbUpgradeLimit(type); long stale = state().revision();
			require(limit > 0 && limit < 64, "Unexpected productivity limit");
			exchange(type, INSTALL, 1, true, MOVED, 1, stale, 20);
			for (int count = 1; count <= limit; count++) { exchange(type, INSTALL, 1); physical(type, count); round(); }
			exchange(type, INSTALL, 1, false, STALE, 0, stale, 20);
			exchange(type, INSTALL, 1, false, LIMIT, 0, state().revision(), 20);
			var named = PbUpgradeInventorySlot.getRepresentativeStack(type).copy();
			named.set(DataComponents.CUSTOM_NAME, Component.literal("productivity-components")); player.getInventory().items.set(21, named);
			exchange(type, INSTALL, 1, false, UNSUPPORTED, 0, state().revision(), 21);
			exchange(type, REMOVE, 1, false, NO_SPACE, 0, state().revision(), 21);
			exchange(type, REMOVE, limit, true, MOVED, limit, state().revision(), 20);
			exchange(type, REMOVE, limit); physical(type, 0); round();
		}
	}
	private void legacy() {
		var config = ModConfig.SERVER; var preset = config.balancePreset.get();
		boolean exclusive = config.productivityUpgradeTiersExclusive.get(); int limit = config.apiaryPbUpgradeProductivityMaxCount.get();
		try {
			config.balancePreset.set(BalancePreset.CUSTOM); config.productivityUpgradeTiersExclusive.set(false);
			config.apiaryPbUpgradeProductivityMaxCount.set(8); BalanceConfig.refresh(false);
			source(TYPES.get(0)); exchange(TYPES.get(0), INSTALL, 6); physical(TYPES.get(0), 6);
			source(TYPES.get(1)); exchange(TYPES.get(1), INSTALL, 1); physical(TYPES.get(1), 1); round();
			config.apiaryPbUpgradeProductivityMaxCount.set(4); config.productivityUpgradeTiersExclusive.set(true); BalanceConfig.refresh(false);
			exchange(TYPES.get(1), INSTALL, 1, false, CONFLICT, 0, state().revision(), 20);
			exchange(TYPES.get(1), REMOVE, 1); physical(TYPES.get(1), 0);
			source(TYPES.get(0)); exchange(TYPES.get(0), INSTALL, 1, false, LIMIT, 0, state().revision(), 20);
			player.getInventory().items.set(20, ItemStack.EMPTY); exchange(TYPES.get(0), REMOVE, 6); physical(TYPES.get(0), 0); round();
		} finally {
			config.balancePreset.set(preset); config.productivityUpgradeTiersExclusive.set(exclusive);
			config.apiaryPbUpgradeProductivityMaxCount.set(limit); BalanceConfig.refresh(false);
		}
	}
	private void work(int ticks, int budget, boolean simulate) {
		var bee = bee(); long started = System.nanoTime();
		require(service.advance(player.serverLevel(), member, 0, bee.revision(), bee.plan().recipeRevision(),
				bee.plan().capabilityRevision(), ticks, budget, simulate) == BeeWorkExecutor.Status.READY, "Productivity cycle failed");
		long elapsed = System.nanoTime() - started;
		if (ticks > 0 && bee.progress() == 0 && bee.drained()) boundaryNanos = Math.max(boundaryNanos, elapsed);
		else continuationNanos = Math.max(continuationNanos, elapsed);
	}
	private void settle() {
		long revision = bee().revision();
		require(service.settle(player.serverLevel(), member, 0, revision), "Productivity result not settled");
		require(!service.settle(player.serverLevel(), member, 0, revision), "Productivity result settled twice");
	}
	private static long expected(BeeRecord bee, float multiplier) {
		var random = new SplittableRandom(bee.random().seed());
		for (long i = 0; i < bee.random().cursor(); i++) random.nextDouble();
		long rolls = (long) Math.floor(multiplier) + (random.nextDouble() < multiplier - Math.floor(multiplier) ? 1 : 0);
		require(bee.plan().count() == 1 && bee.plan().productivity() == 0, "Unexpected production oracle bee");
		return rolls;
	}
	private void round() {
		var old = bee(); var cycle = desired(); var before = data.checkpoint(); long amount = expected(old, cycle.productionMultiplier());
		work(cycle.cycleTicks(), 1, true); require(before == data.checkpoint(), "Simulated productivity published");
		work(cycle.cycleTicks(), 1, false);
		require(cycle.matches(bee().plan()) && bee().frozen().exact().equals(java.math.BigInteger.valueOf(amount)), "Production differs from independent per-cycle model");
		require(data.checkpoint().energy().stored() == before.energy().stored() - (long) cycle.cycleTicks() * cycle.energyPerTick(), "Wrong productivity FE");
		require(bee().random().cursor() == old.random().cursor() + 1, "Productivity reset random cursor");
		settle();
		var expectedLedger = new HashMap<>(before.ledger().balances());
		expectedLedger.merge(cycle.output(), ProductAmount.of(amount), ProductAmount::add);
		require(data.checkpoint().ledger().balances().equals(expectedLedger), "Converted output changed count, key or another balance"); cycles++;
	}
	private void boundaries() throws Exception {
		var beta = TYPES.get(1); source(beta); exchange(beta, INSTALL, 1); physical(beta, 1);
		work(1, 1, false); var old = bee(); require(old.progress() > 0 && old.plan().productionMultiplier() == 2.5f, "Expected fractional active cycle");
		require(!StaticApiaryAdapter.returnReady(hive, data.checkpoint().ownedMachines().get(member)), "Fractional partial cycle escaped random ownership");
		exchange(beta, INSTALL, 1); physical(beta, 2); require(bee() == old, "Upgrade rewrote active cycle");
		work(old.plan().cycleTicks() - old.progress(), 0, false); require(bee().pendingCycles() == 1 && bee().plan() == old.plan(), "Old paid cycle lost");
		var folder = Path.of("results"); Files.createDirectories(folder);
		var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", NetworkCheckpointCodec.encode(data.checkpoint()));
		NbtIo.writeCompressed(root, folder.resolve("apiary-productivity.dat"));
		var metadata = new JsonObject(); metadata.addProperty("writerPid", ProcessHandle.current().pid()); metadata.addProperty("member", member.toString());
		Files.writeString(folder.resolve("apiary-productivity.json"), metadata.toString());
		long energy = data.checkpoint().energy().stored(); work(0, 1, false);
		require(bee().frozen().exact().longValueExact() == expected(old, 2.5f) && data.checkpoint().energy().stored() == energy, "Old output rerolled or recharged");
		settle();
		var config = cy.jdkdigital.productivebees.ProductiveBeesConfig.UPGRADES.productivityMultiplier2; double original = config.get();
		try {
			work(1, 1, false); old = bee();
			config.set(2.25D); reference.getApiaryUpgradeHandler().invalidateUpgradeCache();
			require(!StaticApiaryAdapter.returnReady(hive, data.checkpoint().ownedMachines().get(member)), "Changed configured multiplier returned mid-cycle");
			work(old.plan().cycleTicks() - old.progress(), 1, false);
			require(bee().plan() == old.plan() && bee().frozen().exact().longValueExact() == expected(old, 4), "Config changed old output"); settle();
			physical(beta, 2); round();
		} finally { config.set(original); reference.getApiaryUpgradeHandler().invalidateUpgradeCache(); }
		exchange(beta, REMOVE, 2); physical(beta, 0); round();
	}
	private void blockBoundaries() throws Exception {
		var block = PbUpgradeType.BLOCK; var omega = PbUpgradeType.PRODUCTIVITY_4;
		work(1, 0, false); var comb = bee();
		source(block); exchange(block, INSTALL, 1); physical(block, 1);
		require(!StaticApiaryAdapter.returnReady(hive, data.checkpoint().ownedMachines().get(member)), "Changed output key returned a partial comb cycle");
		work(comb.plan().cycleTicks() - comb.progress(), 0, false); saveBlock("install");
		long energy = data.checkpoint().energy().stored(); work(0, 1, false);
		require(bee().plan() == comb.plan() && bee().frozen().exact().longValueExact() == 1 && data.checkpoint().energy().stored() == energy, "Block install changed paid comb"); settle();
		round(); work(1, 0, false); var converted = bee();
		require(!converted.plan().output().equals(converted.plan().sourceOutput()), "Block upgrade did not convert iron comb");
		exchange(block, REMOVE, 1); physical(block, 0);
		require(!StaticApiaryAdapter.returnReady(hive, data.checkpoint().ownedMachines().get(member)), "Removed conversion returned old partial block cycle");
		work(converted.plan().cycleTicks() - converted.progress(), 0, false); saveBlock("remove");
		energy = data.checkpoint().energy().stored(); work(0, 1, false);
		require(bee().plan() == converted.plan() && bee().frozen().exact().longValueExact() == 1 && data.checkpoint().energy().stored() == energy, "Block removal changed paid block"); settle(); round();
		source(omega); exchange(omega, INSTALL, 1); physical(omega, 1); round();
		var omegaCycle = desired(); source(block); exchange(block, INSTALL, 1); physical(block, 1);
		require(omegaCycle.equals(desired()), "Omega plus block converted or multiplied twice"); round();
		player.getInventory().items.set(20, ItemStack.EMPTY); exchange(omega, REMOVE, 1); physical(omega, 0); round();
		player.getInventory().items.set(20, ItemStack.EMPTY); exchange(block, REMOVE, 1); physical(block, 0); round();
	}
	private void saveBlock(String phase) throws Exception {
		var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion()); root.put("data", NetworkCheckpointCodec.encode(data.checkpoint()));
		NbtIo.writeCompressed(root, Path.of("results", "apiary-block-" + phase + ".dat"));
		var metadata = new JsonObject(); metadata.addProperty("writerPid", ProcessHandle.current().pid()); metadata.addProperty("member", member.toString());
		Files.writeString(Path.of("results", "apiary-block.json"), metadata.toString());
	}
	private void mappingCases(JsonObject report) {
		var registry = player.registryAccess(); var converter = new CombBlockConverter(); int verified = 0;
		var inputs = new ArrayList<ItemStack>();
		for (String id : List.of("minecraft:honeycomb", "productivebees:honeycomb_ghostly", "productivebees:honeycomb_milky", "productivebees:honeycomb_powdery", "minecraft:iron_ingot", "minecraft:honeycomb_block")) {
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
			require(item != net.minecraft.world.item.Items.AIR, "Missing conversion fixture: " + id); inputs.add(new ItemStack(item, 23));
		}
		for (String type : List.of("iron", "gold")) {
			var stack = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get(), 23);
			stack.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), net.minecraft.resources.ResourceLocation.parse("productivebees:" + type));
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("source-only")); inputs.add(stack);
		}
		for (var input : inputs) {
			var source = ProductKeyCodec.item(input, registry); var snapshot = input.copy();
			var actual = StaticApiaryAdapter.output(source, true, registry); var physical = converter.convertCombsToBlocks(List.of(input.copy())).getFirst();
			require(physical.getCount() == 23 && actual.equals(ProductKeyCodec.item(physical, registry)), "Mapping changed complete components or count");
			require(StaticApiaryAdapter.output(source, false, registry).equals(source) && ItemStack.matches(snapshot, input), "Mapping mutated source or disabled result");
			if (input.is(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get())) {
				var converted = ProductKeyCodec.item(actual, 23, registry);
				require(!converted.has(DataComponents.CUSTOM_NAME) && Objects.equals(input.get(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get()), converted.get(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get())), "Conversion copied unrelated components or lost bee type");
			}
			verified++;
		}
		report.addProperty("apiaryBlockMappingCases", verified);
	}
	static void prepareReturn(NetworkSavedData data, UUID member, ServerPlayer player, NetworkCoreMenu menu, TileEntityMekApiary hive) {
		var probe = new ApiaryProductivityProbe(data, member, player, menu, hive, null);
		var old = probe.bee(); probe.work(old.plan().cycleTicks() - old.progress(), 1, false); probe.settle();
		probe.source(PbUpgradeType.PRODUCTIVITY_3); probe.exchange(PbUpgradeType.PRODUCTIVITY_3, INSTALL, 1);
		probe.source(PbUpgradeType.BLOCK); probe.exchange(PbUpgradeType.BLOCK, INSTALL, 1);
		probe.work(1, 1, false);
		require(probe.bee().plan().productionMultiplier() == 3 && StaticApiaryAdapter.returnReady(hive, data.checkpoint().ownedMachines().get(member)), "Matching integer partial cycle cannot return");
		require(!probe.bee().plan().output().equals(probe.bee().plan().sourceOutput()), "Matching converted partial cycle was not tested");
	}

}
