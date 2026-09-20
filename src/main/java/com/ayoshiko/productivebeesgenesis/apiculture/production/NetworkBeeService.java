package com.ayoshiko.productivebeesgenesis.apiculture.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 显式开发入口；D16 之前不接服务器 ticker。每次提交重新校验加载、身份和预期版本。 */
public final class NetworkBeeService {
	private final NetworkSavedData authority;
	private final NetworkDirectory directory;
	public NetworkBeeService(NetworkSavedData authority, NetworkDirectory directory) { this.authority = authority; this.directory = directory; }
	public boolean activate(ServerLevel level, UUID member, long expectedCheckpoint, long capabilityRevision) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (current.revision() != expectedCheckpoint || record == null || record.bees() != null) return false;
		var hive = member(level, record); if (hive == null) return false;
		var state = StaticApiaryAdapter.compile(level, hive, record, current.policyRevision(), capabilityRevision);
		authority.publish(current.withOwnership(record.withBees(state))); directory.requestSave(authority); return true;
	}
	public BeeWorkExecutor.Status advance(ServerLevel level, UUID member, int slot, long beeRevision,
			long recipeRevision, long capabilityRevision, int ticks, int samplingBudget, boolean simulate) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.bees() == null) return BeeWorkExecutor.Status.DISABLED;
		var hive = member(level, record); if (hive == null) return BeeWorkExecutor.Status.UNLOADED;
		if (ticks > 0 && (!StaticApiaryAdapter.currentPlan(level, hive, record.bees().bee(slot)) || recipeRevision != current.policyRevision())) return BeeWorkExecutor.Status.STALE_PLAN;
		boolean flower = record.bees().feeding() != null && com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter.flower(
				record.bees().feeding(), slot, net.minecraft.resources.ResourceLocation.parse(record.bees().bee(slot).plan().beeType()), level.registryAccess());
		var context = new BeeWorkExecutor.Context(true, hive.canFunction(), flower,
				recipeRevision, capabilityRevision, new BeeWorkConditions.Environment(level.dimensionType().hasFixedTime(), level.isNight(), level.isRaining(), level.isThundering()));
		var result = BeeWorkExecutor.advance(record.bees(), slot, beeRevision, context, ticks, samplingBudget);
		if (!simulate && result.status() == BeeWorkExecutor.Status.READY) {
			authority.publish(current.withOwnership(record.withBees(result.candidate()))); directory.requestSave(authority);
		}
		return result.status();
	}
	public boolean settle(ServerLevel level, UUID member, int slot, long beeRevision) {
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || member(level, record) == null) return false;
		var next = current.settleBee(member, slot, beeRevision);
		if (next == current) return false;
		authority.publish(next); directory.requestSave(authority); return true;
	}
	TileEntityMekApiary member(ServerLevel level, OwnedMachineRecord record) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Bee production belongs to the server thread");
		var coreOrigin = authority.identity().origin();
		if (!coreOrigin.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(coreOrigin.x() >> 4, coreOrigin.z() >> 4)) return null;
		var coreTile = level.getBlockEntity(new BlockPos(coreOrigin.x(), coreOrigin.y(), coreOrigin.z()));
		if (!(coreTile instanceof com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity core) || core.isRemoved()
				|| !core.validNetworkReference() || !authority.identity().equals(core.network()) || !authority.identity().ownerId().equals(core.owner())
				|| !authority.identity().controllerId().equals(core.controller())) return null;
		var topology = core.topology();
		var position = new BlockPos(record.claim().origin().x(), record.claim().origin().y(), record.claim().origin().z());
		if (topology == null || !topology.valid() || topology.members().stream().noneMatch(node -> node.position().equals(position))) return null;
		if (record.phase() != OwnedMachineRecord.Phase.OWNED || !record.claim().equals(directory.claimAt(record.claim().origin()))) return null;
		var origin = record.claim().origin();
		if (!origin.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(origin.x() >> 4, origin.z() >> 4)) return null;
		var tile = level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z()));
		if (!(tile instanceof TileEntityMekApiary hive) || hive.getClass() != TileEntityMekApiary.class) return null;
		var endpoint = new BlockEntityOwnershipEndpoint(hive); endpoint.validate(authority.identity(), record.claim());
		return endpoint.matches(authority.identity(), record.claim(), MemberBinding.Mode.MANAGED) && endpoint.empty() ? hive : null;
	}
}
