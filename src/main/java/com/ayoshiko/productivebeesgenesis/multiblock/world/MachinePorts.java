package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineCapacity;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** 朝外的三类端口；缓存能力绑定一个结构凭据，每次操作取得新的权威根。 */
final class MachinePorts {
	static void register(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, MachineContent.PART_TILE.get(), (part, side) -> {
			var endpoint = endpoint(part, side);
			return endpoint != null && endpoint.role == StructureRole.ENERGY_PORT ? new Energy(endpoint) : null;
		});
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, MachineContent.PART_TILE.get(), (part, side) -> {
			var endpoint = endpoint(part, side); return endpoint != null && endpoint.material() ? new Items(endpoint) : null;
		});
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, MachineContent.PART_TILE.get(), (part, side) -> {
			var endpoint = endpoint(part, side); return endpoint != null && endpoint.material() ? new Fluids(endpoint) : null;
		});
	}
	private static Endpoint endpoint(MachinePartEntity part, Direction side) {
		if (side == null || !(part.getBlockState().getBlock() instanceof MachineContent.RoleBlock block)
				|| side != part.getBlockState().getValue(MachinePartBlock.FACING) || part.binding() == null) return null;
		var endpoint = new Endpoint(part, part.binding(), block.role(), side);
		return endpoint.access() == null ? null : endpoint;
	}
	private static final class Endpoint {
		final MachinePartEntity part;
		final MachineDirectory.Binding binding;
		final StructureRole role;
		final Direction face;
		boolean reportedFailure;
		Endpoint(MachinePartEntity part, MachineDirectory.Binding binding, StructureRole role, Direction face) {
			this.part = part; this.binding = binding; this.role = role; this.face = face;
		}
		boolean material() { return role == StructureRole.INPUT_PORT || role == StructureRole.OUTPUT_PORT; }
		boolean input() { return role == StructureRole.INPUT_PORT; }
		ServerLevel level() { return (ServerLevel) part.getLevel(); }
		MachineWorkService.Access access() {
			if (!(part.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || part.isRemoved()
					|| !part.references(binding) || part.getBlockState().getValue(MachinePartBlock.FACING) != face
					|| !(part.getBlockState().getBlock() instanceof MachineContent.RoleBlock current) || current.role() != role
					|| !MachineWorldService.active(level, binding)) return null;
			var partPos = part.getBlockPos(); var partChunk = level.getChunkSource().getChunkNow(partPos.getX() >> 4, partPos.getZ() >> 4);
			if (partChunk == null || partChunk.getBlockEntity(partPos) != part) return null;
			var corePos = binding.handle().controller(); var chunk = level.getChunkSource().getChunkNow(corePos.getX() >> 4, corePos.getZ() >> 4);
			if (chunk == null || !(chunk.getBlockEntity(corePos) instanceof MachineControllerEntity core) || core.handle != binding.handle()) return null;
			return MachineWorkService.access(core).orElse(null);
		}
		boolean allowed(ProductKey key) {
			if (key == null) return false;
			var policy = RuntimeProductPolicies.peek(level());
			// 静态目录按完整准入规则判断；新动态发现须由生产适配器证明，端口不能自行注册。
			return policy != null && policy.descriptors(key).stream().anyMatch(descriptor -> descriptor.accepts(key));
		}
		ProductKey itemKey(ItemStack stack) {
			try { return ProductKeyCodec.item(stack, level().registryAccess()); }
			catch (RuntimeException invalid) { report(invalid); return null; }
		}
		ProductKey fluidKey(FluidStack stack) {
			try { return ProductKeyCodec.fluid(stack, level().registryAccess()); }
			catch (RuntimeException invalid) { report(invalid); return null; }
		}
		ItemStack item(ProductKey key, int count) {
			try { return ProductKeyCodec.item(key, count, level().registryAccess()); }
			catch (RuntimeException failure) { report(failure); return ItemStack.EMPTY; }
		}
		FluidStack fluid(ProductKey key, int count) {
			try { return ProductKeyCodec.fluid(key, count, level().registryAccess()); }
			catch (RuntimeException failure) { report(failure); return FluidStack.EMPTY; }
		}
		private void report(RuntimeException failure) {
			if (!reportedFailure) { reportedFailure = true; LogUtils.getLogger().warn("Cannot encode/decode machine port asset at {}", part.getBlockPos(), failure); }
		}
		long transfer(MachineWorkService.Access access, CombinedMachineWork.Change change, boolean simulate) {
			return change.moved() > 0 && access.current() && (simulate || MachineWorkService.commit(access, change)) ? change.moved() : 0;
		}
	}
	private record Energy(Endpoint endpoint) implements IEnergyStorage {
		@Override public int receiveEnergy(int offered, boolean simulate) {
			var access = endpoint.access(); if (access == null || offered <= 0) return 0;
			return (int) endpoint.transfer(access, access.work().receiveEnergy(offered), simulate);
		}
		@Override public int extractEnergy(int amount, boolean simulate) { return 0; }
		@Override public int getEnergyStored() { var access = endpoint.access(); return access == null ? 0 : (int) Math.min(Integer.MAX_VALUE, access.work().energy()); }
		@Override public int getMaxEnergyStored() { var access = endpoint.access(); return access == null ? 0 : (int) Math.min(Integer.MAX_VALUE, access.work().energyCapacity()); }
		@Override public boolean canExtract() { return false; }
		@Override public boolean canReceive() { return endpoint.access() != null; }
	}
	private record Items(Endpoint endpoint) implements IItemHandler {
		@Override public int getSlots() { return endpoint.access() == null ? 0 : CombinedMachineCapacity.ITEM_SLOTS; }
		@Override public ItemStack getStackInSlot(int slot) {
			var access = endpoint.access(); if (access == null || !validSlot(slot)) return ItemStack.EMPTY;
			var cell = access.work().buffer().items().get(slot); if (cell.key() == null) return ItemStack.EMPTY;
			var stack = endpoint.item(cell.key(), (int) cell.count()); return access.current() ? stack : ItemStack.EMPTY;
		}
		@Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			if (!endpoint.input() || stack.isEmpty() || !validSlot(slot)) return stack;
			var access = endpoint.access(); if (access == null) return stack;
			var key = endpoint.itemKey(stack); if (!endpoint.allowed(key)) return stack;
			var change = access.work().insertItem(slot, key, stack.getCount(), Math.min(64, stack.getMaxStackSize()));
			int moved = (int) endpoint.transfer(access, change, simulate);
			return moved == 0 ? stack : stack.copyWithCount(stack.getCount() - moved);
		}
		@Override public ItemStack extractItem(int slot, int requested, boolean simulate) {
			if (endpoint.input() || requested <= 0 || !validSlot(slot)) return ItemStack.EMPTY;
			var access = endpoint.access(); if (access == null) return ItemStack.EMPTY;
			var cell = access.work().buffer().items().get(slot); if (cell.key() == null) return ItemStack.EMPTY;
			var change = access.work().extractItem(slot, Math.min(requested, 64));
			var stack = endpoint.item(cell.key(), (int) change.moved());
			return !stack.isEmpty() && endpoint.transfer(access, change, simulate) == stack.getCount() ? stack : ItemStack.EMPTY;
		}
		@Override public int getSlotLimit(int slot) { return validSlot(slot) && endpoint.access() != null ? 64 : 0; }
		@Override public boolean isItemValid(int slot, ItemStack stack) {
			if (!endpoint.input() || !validSlot(slot) || stack.isEmpty() || endpoint.access() == null) return false;
			return endpoint.allowed(endpoint.itemKey(stack));
		}
		private static boolean validSlot(int slot) { return slot >= 0 && slot < CombinedMachineCapacity.ITEM_SLOTS; }
	}
	private record Fluids(Endpoint endpoint) implements IFluidHandler {
		@Override public int getTanks() { return endpoint.access() == null ? 0 : CombinedMachineCapacity.FLUID_TANKS; }
		@Override public FluidStack getFluidInTank(int tank) {
			var access = endpoint.access(); if (access == null || !validTank(tank)) return FluidStack.EMPTY;
			var cell = access.work().buffer().fluids().get(tank); if (cell.key() == null) return FluidStack.EMPTY;
			var fluid = endpoint.fluid(cell.key(), (int) cell.count()); return access.current() ? fluid : FluidStack.EMPTY;
		}
		@Override public int getTankCapacity(int tank) { return validTank(tank) && endpoint.access() != null ? CombinedMachineCapacity.TANK_CAPACITY : 0; }
		@Override public boolean isFluidValid(int tank, FluidStack fluid) {
			return endpoint.input() && validTank(tank) && !fluid.isEmpty() && endpoint.access() != null && endpoint.allowed(endpoint.fluidKey(fluid));
		}
		@Override public int fill(FluidStack fluid, FluidAction action) {
			if (!endpoint.input() || fluid.isEmpty()) return 0;
			var access = endpoint.access(); if (access == null) return 0;
			var key = endpoint.fluidKey(fluid); if (!endpoint.allowed(key)) return 0;
			return (int) endpoint.transfer(access, access.work().insert(key, fluid.getAmount(), 1), action.simulate());
		}
		@Override public FluidStack drain(FluidStack requested, FluidAction action) {
			if (endpoint.input() || requested.isEmpty()) return FluidStack.EMPTY;
			var access = endpoint.access(); if (access == null) return FluidStack.EMPTY;
			var key = endpoint.fluidKey(requested); return key == null ? FluidStack.EMPTY : drain(access, key, requested.getAmount(), action);
		}
		@Override public FluidStack drain(int requested, FluidAction action) {
			if (endpoint.input() || requested <= 0) return FluidStack.EMPTY;
			var access = endpoint.access(); if (access == null) return FluidStack.EMPTY;
			for (var cell : access.work().buffer().fluids()) if (cell.key() != null) return drain(access, cell.key(), requested, action);
			return FluidStack.EMPTY;
		}
		private FluidStack drain(MachineWorkService.Access access, ProductKey key, int requested, FluidAction action) {
			var change = access.work().extract(key, requested); if (change.moved() == 0) return FluidStack.EMPTY;
			var fluid = endpoint.fluid(key, (int) change.moved());
			return !fluid.isEmpty() && endpoint.transfer(access, change, action.simulate()) == fluid.getAmount() ? fluid : FluidStack.EMPTY;
		}
		private static boolean validTank(int tank) { return tank >= 0 && tank < CombinedMachineCapacity.FLUID_TANKS; }
	}
	private MachinePorts() { }
}
