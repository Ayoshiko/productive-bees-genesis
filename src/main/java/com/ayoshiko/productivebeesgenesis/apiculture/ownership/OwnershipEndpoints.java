package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineOwnershipEndpoint;
import java.util.UUID;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 实际载体适配集中在交接边界，状态机不辨认物理机继承层级。 */
public final class OwnershipEndpoints {
	public static OwnershipEndpoint of(BlockEntity tile) {
		if (tile instanceof MachineControllerEntity machine) return new MachineOwnershipEndpoint(machine);
		if (tile instanceof TileEntityMekanism machine && MachineAssetStore.supports(machine)) return new BlockEntityOwnershipEndpoint(machine);
		throw new IllegalArgumentException("No verified ownership adapter for " + tile.getBlockState());
	}
	public static boolean bound(BlockEntity tile) {
		return tile instanceof MachineControllerEntity machine ? new MachineOwnershipEndpoint(machine).bound() : tile.getPersistentData().contains(MemberBinding.TAG);
	}
	public static UUID memberId(BlockEntity tile) { return tile instanceof MachineControllerEntity machine ? machine.machineId() : UUID.randomUUID(); }
	private OwnershipEndpoints() { }
}
