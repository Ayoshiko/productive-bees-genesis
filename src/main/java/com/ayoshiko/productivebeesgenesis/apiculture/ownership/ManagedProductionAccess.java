package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 工作提交前的实时身份门控；不加载区块，不凭旧候选恢复生产资格。 */
public final class ManagedProductionAccess {
	public static <T extends TileEntityMekanism> T member(ServerLevel level, NetworkSavedData authority,
			NetworkDirectory directory, OwnedMachineRecord record, Class<T> type) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Production belongs to the server thread");
		var identity = authority.identity(); var origin = identity.origin();
		if (!origin.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(origin.x() >> 4, origin.z() >> 4)) return null;
		var coreTile = level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z()));
		if (!(coreTile instanceof NetworkCoreBlockEntity core) || core.isRemoved() || !core.validNetworkReference()
				|| !identity.equals(core.network()) || !identity.ownerId().equals(core.owner()) || !identity.controllerId().equals(core.controller())) return null;
		var topology = core.topology(); var location = record.claim().origin();
		var position = new BlockPos(location.x(), location.y(), location.z());
		if (topology == null || !topology.valid() || topology.members().stream().noneMatch(node -> node.position().equals(position))) return null;
		if (record.phase() != OwnedMachineRecord.Phase.OWNED || !record.claim().equals(directory.claimAt(location))
				|| !location.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(location.x() >> 4, location.z() >> 4)) return null;
		var tile = level.getBlockEntity(position);
		if (tile == null || tile.getClass() != type) return null;
		var member = type.cast(tile); var endpoint = new BlockEntityOwnershipEndpoint(member);
		endpoint.validate(identity, record.claim());
		return endpoint.matches(identity, record.claim(), MemberBinding.Mode.MANAGED) && endpoint.empty() ? member : null;
	}
	private ManagedProductionAccess() { }
}
