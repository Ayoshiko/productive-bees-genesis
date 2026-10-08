package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** 容器能力只在单件副本上执行；发布前检查全部流体的组件与净变化。 */
final class FluidContainerPlan {
	private static final int MAX_TANKS = 32;
	record Change(ItemStack container, FluidStack fluid) {
		Change { container = container.copy(); fluid = fluid.copy(); }
		@Override public ItemStack container() { return container.copy(); }
		@Override public FluidStack fluid() { return fluid.copy(); }
	}
	static FluidStack content(ItemStack item) {
		var contents = snapshot(item.copyWithCount(1));
		return contents.isEmpty() ? FluidStack.EMPTY : contents.getFirst().copy();
	}
	static Change prepare(ItemStack original, FluidStack requested, boolean fill) {
		if (original.isEmpty() || requested.isEmpty()) return null;
		var copy = original.copyWithCount(1); var beforeItem = copy.copy();
		var before = snapshot(copy);
		var handler = copy.getCapability(Capabilities.FluidHandler.ITEM);
		if (handler == null) return null;
		int possible;
		if (fill) possible = handler.fill(requested.copy(), IFluidHandler.FluidAction.SIMULATE);
		else {
			var simulated = handler.drain(requested.copy(), IFluidHandler.FluidAction.SIMULATE);
			if (!simulated.isEmpty() && !same(simulated, requested)) throw new IllegalStateException("Container simulated a different fluid");
			possible = simulated.getAmount();
		}
		if (possible < 0 || possible > requested.getAmount() || !ItemStack.matches(copy, beforeItem)
				|| !ItemStack.matches(handler.getContainer(), beforeItem)) throw new IllegalStateException("Invalid container simulation");
		if (possible == 0) return null;
		int actual;
		if (fill) actual = handler.fill(requested.copyWithAmount(possible), IFluidHandler.FluidAction.EXECUTE);
		else {
			var drained = handler.drain(requested.copyWithAmount(possible), IFluidHandler.FluidAction.EXECUTE);
			if (!drained.isEmpty() && !same(drained, requested)) throw new IllegalStateException("Container drained a different fluid");
			actual = drained.getAmount();
		}
		var result = handler.getContainer().copy();
		if (result.getCount() != 1 || actual < 0 || actual > possible) throw new IllegalStateException("Invalid container result");
		var after = snapshot(result);
		long delta = amount(after, requested) - amount(before, requested);
		if (delta != (fill ? actual : -actual)) throw new IllegalStateException("Container fluid delta differs from its return value");
		for (var fluid : before) if (!same(fluid, requested) && amount(after, fluid) != amount(before, fluid)) throw new IllegalStateException("Container changed another fluid");
		for (var fluid : after) if (!same(fluid, requested) && amount(after, fluid) != amount(before, fluid)) throw new IllegalStateException("Container created another fluid");
		return actual == 0 ? null : new Change(result, requested.copyWithAmount(actual));
	}
	private static List<FluidStack> snapshot(ItemStack item) {
		var handler = item.getCapability(Capabilities.FluidHandler.ITEM); var result = new ArrayList<FluidStack>();
		if (handler == null) return result;
		int tanks = handler.getTanks();
		if (tanks < 0 || tanks > MAX_TANKS) throw new IllegalArgumentException("Container tank inspection budget exceeded");
		for (int tank = 0; tank < tanks; tank++) { var fluid = handler.getFluidInTank(tank); if (!fluid.isEmpty()) result.add(fluid.copy()); }
		return result;
	}
	private static long amount(List<FluidStack> fluids, FluidStack key) {
		long result = 0; for (var fluid : fluids) if (same(fluid, key)) result += fluid.getAmount(); return result;
	}
	static boolean same(FluidStack first, FluidStack second) { return FluidStack.isSameFluidSameComponents(first, second); }
	private FluidContainerPlan() { }
}
