package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeContainerRegistrar;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.attachments.containers.fluid.AttachedFluids;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 从保存的流体栈迁移到目标罐布局，不用源物品的创建器重建已拆卸机器。 */
final class MachineCraftingFluidTransfer {

	private MachineCraftingFluidTransfer() {
	}

	static boolean transfer(List<ItemStack> inputs, ItemStack result) {
		AttachedFluids layout = ContainerType.FLUID.createNewAttachment(result);
		if (result.getItem() instanceof ItemBlockMekCentrifuge centrifuge) {
			layout = new AttachedFluids(new ArrayList<>(Collections.nCopies(
					MekCentrifugeContainerRegistrar.getCraftingFluidTankCount(centrifuge), FluidStack.EMPTY)));
		}
		result.set(ContainerType.FLUID.getComponentType(), layout);
		List<IExtendedFluidTank> target = ContainerType.FLUID.createHandler(result).getContainers();
		for (ItemStack input : inputs) {
			List<FluidStack> source = ContainerType.FLUID.getOrEmpty(input).containers();
			for (int index = 0; index < source.size(); index++) {
				FluidStack remaining = source.get(index);
				if (remaining.isEmpty()) {
					continue;
				}
				if (index < target.size()) {
					remaining = mergeIntoTank(target.get(index), remaining);
				}
				for (int tank = 0; tank < target.size() && !remaining.isEmpty(); tank++) {
					if (tank != index) {
						remaining = mergeIntoTank(target.get(tank), remaining);
					}
				}
				if (!remaining.isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	private static FluidStack mergeIntoTank(IExtendedFluidTank tank, FluidStack stack) {
		FluidStack stored = tank.getFluid();
		if (!stored.isEmpty() && !FluidStack.isSameFluidSameComponents(stored, stack)) {
			return stack;
		}
		int moved = Math.min(Math.max(0, tank.getCapacity() - stored.getAmount()), stack.getAmount());
		if (moved == 0) {
			return stack;
		}
		tank.setStack(stack.copyWithAmount(stored.getAmount() + moved));
		return stack.copyWithAmount(stack.getAmount() - moved);
	}
}
