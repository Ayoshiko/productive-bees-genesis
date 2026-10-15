package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.topology.TopologyScan;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 连接端口只贡献控制器身份；结构、目录、接管收据和当前核心同时有效才开放托管访问。 */
public final class MachineNetworkAccess {
	public record Access(MachineControllerEntity machine, NetworkSavedData data, NetworkDirectory directory, OwnedMachineRecord record, long topologyEpoch) { }
	public static TopologyScan.Node node(ServerLevel level, BlockEntity tile) {
		if (!(tile instanceof MachinePartEntity part) || !(part.getBlockState().getBlock() instanceof MachineContent.RoleBlock block)
				|| block.role() != StructureRole.NETWORK_PORT || !part.bound()) return null;
		var binding = part.binding(); var pos = binding.handle().controller();
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null || !(chunk.getBlockEntity(pos) instanceof MachineControllerEntity core) || core.handle != binding.handle()
				|| !core.readyIdentity() || core.assets == null || !core.assets.readable()) return null;
		var facing = part.getBlockState().getValue(MachinePartBlock.FACING);
		if (facing != binding.handle().facing()) return null;
		return new TopologyScan.Node(part.getBlockPos(), core.ownerId(), false, 63 & ~(1 << facing.ordinal()), 6, 3, pos);
	}
	public static MachineControllerEntity member(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, OwnedMachineRecord record) {
		if (!level.getServer().isSameThread() || !data.active() || record.phase() != OwnedMachineRecord.Phase.OWNED) return null;
		var identity = data.identity(); var origin = identity.origin(); var location = record.claim().origin();
		if (!origin.dimension().equals(level.dimension().location().toString()) || !location.dimension().equals(origin.dimension())
				|| !record.claim().equals(directory.claimAt(location))) return null;
		var corePos = new BlockPos(origin.x(), origin.y(), origin.z()); var memberPos = new BlockPos(location.x(), location.y(), location.z());
		var coreChunk = level.getChunkSource().getChunkNow(corePos.getX() >> 4, corePos.getZ() >> 4);
		var memberChunk = level.getChunkSource().getChunkNow(memberPos.getX() >> 4, memberPos.getZ() >> 4);
		if (coreChunk == null || memberChunk == null || !(coreChunk.getBlockEntity(corePos) instanceof NetworkCoreBlockEntity network)
				|| network.isRemoved() || !network.validNetworkReference() || !identity.equals(network.network())
				|| network.ownership().readyAuthority() != data || network.topology() == null || !network.topology().valid() || !network.topology().hasMember(memberPos)
				|| !(memberChunk.getBlockEntity(memberPos) instanceof MachineControllerEntity machine) || !machine.formed()) return null;
		try {
			var endpoint = new MachineOwnershipEndpoint(machine); endpoint.validate(identity, record.claim());
			return endpoint.matches(identity, record.claim(), MemberBinding.Mode.MANAGED) && endpoint.empty() ? machine : null;
		} catch (IllegalArgumentException | IllegalStateException unavailable) { return null; }
	}
	public static Access access(MachineControllerEntity machine) {
		if (!(machine.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || machine.assets == null) return null;
		var binding = machine.assets.binding(); if (binding == null || binding.mode() != MemberBinding.Mode.MANAGED) return null;
		var origin = binding.network().origin();
		if (!origin.dimension().equals(level.dimension().location().toString())) return null;
		var pos = new BlockPos(origin.x(), origin.y(), origin.z()); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null || !(chunk.getBlockEntity(pos) instanceof NetworkCoreBlockEntity core)) return null;
		var data = core.ownership().readyAuthority(); if (data == null) return null;
		var record = data.checkpoint().ownedMachines().get(machine.machineId()); if (record == null || record.combined() == null) return null;
		var directory = NetworkPersistence.directory(level.getServer());
		return member(level, data, directory, record) == machine ? new Access(machine, data, directory, record, core.topology().epoch()) : null;
	}
	private MachineNetworkAccess() { }
}
