package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeJob;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;

/** 夹具只布置、观察和操作正式开关／FE 口；恢复后的工作不调用领域执行器。 */
final class AutomaticRestartFixture {
	enum Kind { BEE, CENTRIFUGE, CHAIN }
	enum Mode { RUNNING, PAUSED, STARVED }
	final Kind kind;
	final Mode mode;
	final BlockPos pos;
	NetworkCoreBlockEntity core;
	TileEntityMekApiary hive;
	TileEntityMekCentrifuge centrifuge;
	NetworkSavedData data;
	NetworkCheckpoint saved, previous;
	boolean joined, ready, finished;
	int hiveTicker, centrifugeTicker, completedBees, completedJobs, maintenanceTicks;
	long expectedEnergy, workFe;
	private final Map<ProductKey, ProductAmount> expected = new ConcurrentHashMap<>();
	AutomaticRestartFixture(Kind kind, Mode mode, BlockPos pos) { this.kind = kind; this.mode = mode; this.pos = pos; }
	void create(ServerLevel level) {
		var owner = UUID.randomUUID(); level.setBlockAndUpdate(pos, NetworkContent.CORE.get().defaultBlockState());
		core = (NetworkCoreBlockEntity) level.getBlockEntity(pos); core.initializeOwner(owner);
		if (kind != Kind.CENTRIFUGE) {
			level.setBlockAndUpdate(pos.west(), ModBlocks.MEK_APIARY.get().defaultBlockState()); hive = (TileEntityMekApiary) level.getBlockEntity(pos.west());
			hive.setOwnerUUID(owner); hive.setControlType(RedstoneControl.HIGH); hive.setFeederConversionEnabled(false);
			var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
			hive.getBeeSlot(0).setBeeData(bee); hive.getBeeSlot(0).setBaseMinOccupationTicks(5);
			hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
		}
		if (kind != Kind.BEE) {
			level.setBlockAndUpdate(pos.east(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); centrifuge = (TileEntityMekCentrifuge) level.getBlockEntity(pos.east());
			centrifuge.setOwnerUUID(owner); centrifuge.setControlType(RedstoneControl.HIGH);
			require(centrifuge.installPbUpgradeBulk(PbUpgradeType.TIME, 1) == 1, "Cannot install restart fixture upgrade");
		}
	}
	void start(ServerLevel level) {
		data = NetworkPersistence.directory(level.getServer()).loadExisting(core.network()).ready();
		if (kind == Kind.CENTRIFUGE) {
			var comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get()); comb.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
			var current = data.checkpoint(); var ledger = new LedgerCheckpoint(current.ledger().revision() + 1,
					Map.of(ProductKeyCodec.item(comb, level.registryAccess()), ProductAmount.of(1)), current.ledger().transactions());
			data.publish(new NetworkCheckpoint(current.identity(), current.revision() + 1, current.policyRevision(), ledger, current.transfers(), current.discoveries(),
					current.members(), current.lanes(), current.scheduler(), current.energy()).restoredOwnership(current.ownedMachines()));
		}
		long perTick = hive != null ? hive.energyContainer().getEnergyPerTick() : centrifuge.energyContainer().getEnergyPerTick();
		charge(level, mode == Mode.STARVED ? Math.toIntExact((perTick + AutomaticRestartProbe.MAINTENANCE) * 3) : 1_000_000);
		redstone(RedstoneControl.DISABLED); require(core.setProductionRunning(true), "Restart fixture automatic start failed");
	}
	boolean prepareShutdown() {
		var current = data.checkpoint(); var bee = bee(current); var job = job(current);
		int progress = kind == Kind.CENTRIFUGE ? job == null ? 0 : job.progress() : bee == null ? 0 : bee.progress();
		if (progress < 2) return false;
		long cost = kind == Kind.CENTRIFUGE ? job.plan().energyPerTick(job.operations()) : bee.plan().energyPerTick();
		int cycle = kind == Kind.CENTRIFUGE ? job.plan().cycleTicks() : bee.plan().cycleTicks();
		require(progress < cycle, "Writer failed to stop in partial paid work");
		if (mode == Mode.STARVED && current.energy().stored() >= cost + AutomaticRestartProbe.MAINTENANCE) return false;
		redstone(RedstoneControl.HIGH);
		if (mode == Mode.PAUSED) require(core.setProductionRunning(false), "Writer pause failed");
		ready = true; return true;
	}
	void load(ServerLevel level, CompoundTag tag) {
		core = (NetworkCoreBlockEntity) level.getBlockEntity(pos);
		if (kind != Kind.CENTRIFUGE) hive = (TileEntityMekApiary) level.getBlockEntity(pos.west());
		if (kind != Kind.BEE) centrifuge = (TileEntityMekCentrifuge) level.getBlockEntity(pos.east());
		require(core != null && (kind == Kind.CENTRIFUGE || hive != null) && (kind == Kind.BEE || centrifuge != null), "Missing persisted automatic structure");
		saved = NetworkCheckpointCodec.forRegistries(level.registryAccess()).decode(tag.getCompound("saved"));
		require(saved.identity().equals(core.network()), "Automatic world points at another authority");
		require(core.hasProductionSession() && core.productionRunning() == (mode != Mode.PAUSED), "Automatic intent did not survive restart");
		if (hive != null) hiveTicker = hive.ticker; if (centrifuge != null) centrifugeTicker = centrifuge.ticker;
	}
	void restored(ServerLevel level) {
		data = NetworkPersistence.directory(level.getServer()).loadExisting(core.network()).ready();
		require(saved.equals(data.checkpoint()), "Automatic restart changed paid checkpoint before release: " + kind + "/" + mode);
		previous = saved; expected.putAll(saved.ledger().balances()); expectedEnergy = saved.energy().stored();
		require((hive == null || hive.ticker == hiveTicker && new MachineAssetStore(hive).empty())
				&& (centrifuge == null || centrifuge.ticker == centrifugeTicker && new MachineAssetStore(centrifuge).empty()), "Recovery ran a physical member before authority became ready");
		ready = true;
	}
	void observe() {
		var current = data.checkpoint(); boolean progressed = false;
		if (hive != null) {
			require(hive.ticker == hiveTicker && new MachineAssetStore(hive).empty(), "Restart resumed a physical apiary");
			var before = bee(previous); var after = bee(current);
			require(before != null && after != null && before.id().equals(after.id()) && before.plan().equals(after.plan()), "Restart changed bee identity or pinned plan");
			int delta = Math.floorMod(after.progress() - before.progress(), after.plan().cycleTicks());
			require(delta <= 1, "Restart gave a bee catch-up credit");
			if (delta != 0) {
				progressed = true; long cost = after.plan().energyPerTick(); workFe += cost; expectedEnergy -= cost;
				if (after.progress() == 0) {
					require(after.plan().productivity() == 0, "Restart oracle requires normal bee productivity");
					completedBees++; add(after.plan().output(), ProductAmount.of(after.plan().count())); hive.setControlType(RedstoneControl.HIGH);
				}
			}
		}
		if (centrifuge != null) {
			require(centrifuge.ticker == centrifugeTicker && new MachineAssetStore(centrifuge).empty(), "Restart resumed a physical centrifuge");
			var before = job(previous); var after = job(current);
			if (before != null && after != null) require(before.id().equals(after.id()) && before.seed() == after.seed() && before.plan().equals(after.plan()), "Restart replaced paid centrifuge work");
			if (after != null && before == null) subtract(after.plan().input(), after.heldInputs());
			var active = after != null ? after : before;
			if (active != null) {
				int delta = (after == null ? active.plan().cycleTicks() : after.progress()) - (before == null ? 0 : before.progress());
				require(delta >= 0 && delta <= 1, "Restart gave a centrifuge catch-up credit");
				if (delta != 0) { progressed = true; long cost = AutomaticRestartOracle.energyPerTick(active); workFe += cost; expectedEnergy -= cost; }
				if (after == null) { completedJobs++; AutomaticRestartOracle.outputs(active, this::add); }
			}
		}
		if (progressed) { maintenanceTicks++; expectedEnergy -= AutomaticRestartProbe.MAINTENANCE; }
		require(current.energy().stored() == expectedEnergy, "Restart FE or maintenance mismatch: " + kind + "/" + mode + " expected=" + expectedEnergy + " actual=" + current.energy().stored());
		require(current.ledger().balances().equals(expected), "Restart item/fluid conservation failed: " + kind + "/" + mode);
		previous = current;
		if (!finished && (kind == Kind.CENTRIFUGE || completedBees == 1) && (kind == Kind.BEE || completedJobs == 1)) {
			require((hive == null || bee(current).drained()) && job(current) == null, "Restart left paid outputs unsettled");
			require(core.setProductionRunning(false), "Cannot stop completed restart fixture"); finished = true;
		}
	}
	void charge(ServerLevel level, int amount) {
		var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
		require(port != null && port.receiveEnergy(amount, false) == amount, "Restart FE input failed"); if (ready) expectedEnergy += amount;
	}
	void redstone(RedstoneControl control) { if (hive != null) hive.setControlType(control); if (centrifuge != null) centrifuge.setControlType(control); }
	CompoundTag manifest() {
		var tag = new CompoundTag(); tag.putString("kind", kind.name()); tag.putString("mode", mode.name());
		tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ()); tag.put("saved", NetworkCheckpointCodec.encode(saved)); return tag;
	}
	private void add(ProductKey key, ProductAmount amount) { if (!amount.isZero()) expected.merge(key, amount, ProductAmount::add); }
	private void subtract(ProductKey key, ProductAmount amount) {
		var remaining = expected.getOrDefault(key, ProductAmount.ZERO).subtract(amount);
		if (remaining.isZero()) expected.remove(key); else expected.put(key, remaining);
	}
	private static BeeRecord bee(NetworkCheckpoint checkpoint) {
		for (var record : checkpoint.ownedMachines().values()) if (record.bees() != null) return record.bees().bee(0); return null;
	}
	private static CentrifugeJob job(NetworkCheckpoint checkpoint) {
		for (var record : checkpoint.ownedMachines().values()) if (record.centrifuge() != null) return record.centrifuge().jobs().get(0); return null;
	}
	static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
