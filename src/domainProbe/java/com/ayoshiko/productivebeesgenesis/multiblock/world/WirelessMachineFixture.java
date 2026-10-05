package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

public final class WirelessMachineFixture {
	private static MachineProbeFixture structure;
	public static void place(ServerPlayer owner) {
		structure = MachineProbeFixture.place(owner.serverLevel(), CombinedApiaryDefinition.DEFINITION.candidates().getFirst(), new BlockPos(28, 100, 8), Direction.NORTH, owner.getUUID());
	}
	public static MachineControllerEntity controller() { return structure.core(); }
	public static MachinePartEntity internalCore() {
		var local = structure.template().features().entrySet().stream().filter(e -> e.getValue().roles().contains(StructureRole.CORE)).findFirst().orElseThrow().getKey();
		return (MachinePartEntity) structure.core().getLevel().getBlockEntity(structure.world(local));
	}
	public static boolean ready() { return MachineWorkService.access(structure.core()).isPresent(); }
	public static void checkFood(int count) { check(structure.core().assets.work().feeding().getFirst().count() == count, "Wireless machine changed wrong food account"); }
	public static void breakStructure() { var part = internalCore(); part.getLevel().setBlockAndUpdate(part.getBlockPos(), Blocks.AIR.defaultBlockState()); }
	private WirelessMachineFixture() { }
}
