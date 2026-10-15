package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 两种托管载体的真实资产入口；只准备同一网络根的候选，不持有第二份工作状态。 */
public final class ManagedBeeAccess {
	public record Change(NetworkCheckpoint next, int moved) { }
	private final ServerLevel level;
	private final NetworkSavedData authority;
	private final NetworkCheckpoint root;
	private final OwnedMachineRecord record;
	private final BlockEntity source;
	private final MachineWorkService.Access combined;
	private final NetworkCoreBlockEntity core;
	private final long epoch;
	private ManagedBeeAccess(ServerLevel level, NetworkSavedData authority, OwnedMachineRecord record,
			BlockEntity source, MachineWorkService.Access combined, NetworkCoreBlockEntity core) {
		this.level = level; this.authority = authority; root = authority.checkpoint(); this.record = record;
		this.source = source; this.combined = combined; this.core = core; epoch = core.topology().epoch();
	}
	public static ManagedBeeAccess capture(ServerLevel level, NetworkSavedData authority, OwnedMachineRecord record) {
		if (record == null || !record.beeReady() || !level.getServer().isSameThread() || authority.checkpoint().ownedMachines().get(record.claim().member()) != record) return null;
		var origin = authority.identity().origin();
		var pos = new BlockPos(origin.x(), origin.y(), origin.z());
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null || !(chunk.getBlockEntity(pos) instanceof NetworkCoreBlockEntity core)
				|| core.ownership().readyAuthority() != authority || core.topology() == null || !core.topology().valid()) return null;
		var directory = NetworkPersistence.directory(level.getServer());
		if (record.combined() != null) {
			var machine = MachineNetworkAccess.member(level, authority, directory, record);
			if (machine == null) return null;
			var access = MachineWorkService.access(machine).orElse(null);
			return access == null || access.work() != record.combined() ? null : new ManagedBeeAccess(level, authority, record, machine, access, core);
		}
		var hive = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class);
		return hive == null ? null : new ManagedBeeAccess(level, authority, record, hive, null, core);
	}
	public static boolean activate(ServerLevel level, NetworkSavedData authority, OwnedMachineRecord record) {
		if (record == null || record.workLayout().beeSlots() == 0) return false;
		if (record.beeReady()) return capture(level, authority, record) != null;
		var directory = NetworkPersistence.directory(level.getServer());
		return record.claim().machine().equals(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.MACHINE)
				? CombinedNetworkService.activate(level, authority, directory, record)
				: new NetworkBeeService(authority, directory).activate(level, record.claim().member(), authority.checkpoint().revision(), 0);
	}
	public OwnedMachineRecord record() { return record; }
	public boolean current() {
		if (authority.checkpoint() != root || core.ownership().readyAuthority() != authority
				|| core.topology() == null || core.topology().epoch() != epoch) return false;
		return combined != null ? combined.current()
				: ManagedProductionAccess.member(level, authority, NetworkPersistence.directory(level.getServer()), record, TileEntityMekApiary.class) == source;
	}
	public boolean canFunction() { return combined != null || ((TileEntityMekApiary) source).canFunction(); }
	public StaticApiaryAdapter.Profile profile() {
		if (combined != null) return MachineUpgradeProfiles.apiary(record.combined().upgrades());
		var capacity = StaticApiaryAdapter.upgradeCapacity((TileEntityMekApiary) source, record.assets());
		return new StaticApiaryAdapter.Profile(capacity.timeFactor(), capacity.energyPerTick(), capacity.productivity(), capacity.combBlock());
	}
	public NetworkCheckpoint insert(int slot, CompoundTag contents) {
		checkSlot(slot);
		if (combined == null) return root.exchangeBee(StaticApiaryAdapter.insertCaged(level, (TileEntityMekApiary) source, record, slot, contents, root.policyRevision()));
		var bee = StaticApiaryAdapter.caged(level, profile(), slot, record.workLayout().beeSlots(), contents, root.policyRevision());
		return root.exchangeCombined(record.claim().member(), record.combined().insertBee(slot, bee.original(), bee.plan()));
	}
	public NetworkCheckpoint extract(int slot, UUID bee) {
		checkSlot(slot);
		return combined == null ? root.exchangeBee(BeeRosterChange.extract(record.bees(), slot, bee))
				: root.exchangeCombined(record.claim().member(), record.combined().extractBee(slot, bee));
	}
	public Change feeding(int slot, FeedingItem item, int amount, boolean insert) {
		checkSlot(slot);
		if (combined != null) {
			var change = insert ? record.combined().depositFeeding(slot, item, amount) : record.combined().withdrawFeeding(slot, amount);
			return new Change(root.exchangeCombined(record.claim().member(), change), (int) change.moved());
		}
		var state = record.bees().feeding();
		var plan = insert ? state.deposit(slot, item, amount) : state.withdraw(slot, amount);
		return new Change(plan.moved() == 0 ? root : root.withOwnership(record.withBees(record.bees().withFeeding(plan.apply(state)))), plan.moved());
	}
	public NetworkCheckpoint feedingDisabled(int slot, boolean disabled) {
		checkSlot(slot);
		if (record.feedingSlots().get(slot).disabled() == disabled) return root;
		return combined == null ? root.withOwnership(record.withBees(record.bees().withFeeding(record.bees().feeding().disabled(slot, disabled).apply(record.bees().feeding()))))
				: root.exchangeCombined(record.claim().member(), record.combined().feedingDisabled(slot, disabled));
	}
	public NetworkCheckpoint beeEnabled(int slot, UUID bee, boolean enabled) {
		checkSlot(slot);
		var next = combined == null ? record.bees().withBeeEnabled(slot, bee, enabled) : null;
		return combined == null ? next == record.bees() ? root : root.withOwnership(record.withBees(next))
				: root.exchangeCombined(record.claim().member(), record.combined().beeEnabled(slot, bee, enabled));
	}
	private void checkSlot(int slot) {
		if (slot < 0 || slot >= record.workLayout().beeSlots()) throw new IllegalArgumentException("Invalid managed bee slot");
	}
}
