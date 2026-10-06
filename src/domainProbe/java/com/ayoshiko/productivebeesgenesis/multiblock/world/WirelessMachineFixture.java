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
	public static void seedWorkspace(ServerPlayer player) {
		var core = controller(); var before = core.assets.work(); var after = before.receiveEnergy(12345).apply(before);
		var iron = com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT), player.registryAccess());
		var water = com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.fluid(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1), player.registryAccess());
		after = after.insert(iron, 3, 64).apply(after); after = after.insert(water, 1000, 64).apply(after); core.assets.commit(before, after);
		player.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
		player.getInventory().setItem(1, mekanism.common.util.UpgradeUtils.getStack(mekanism.api.Upgrade.SPEED, 2));
		player.containerMenu.broadcastChanges();
	}
	public static void checkWorkspace(ServerPlayer player, int stage) {
		var work = controller().assets.work();
		check(work.energy() == 12345 && work.buffer().items().stream().mapToLong(c -> c.count()).sum() == 3 && work.buffer().fluids().stream().mapToLong(c -> c.count()).sum() == 1000, "Read-only workspace changed machine assets");
		check(work.upgrades().count(0) == (stage == 752 ? 1 : 0), "Workspace upgrade round trip changed wrong slot");
		check(player.getInventory().countItem(net.minecraft.world.item.Items.OAK_LOG) == (stage == 754 ? 1 : 2), "Workspace material custody changed");
	}
	public static void breakStructure() { var part = internalCore(); part.getLevel().setBlockAndUpdate(part.getBlockPos(), Blocks.AIR.defaultBlockState()); }
	private WirelessMachineFixture() { }
}
