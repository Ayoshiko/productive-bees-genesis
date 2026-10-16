package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** 被动能力：一次至多一栈／一桶，不遍历账本，不调用外部接收器。 */
public final class PortCapabilities {
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, NetworkContent.PORT_TILE.get(),
                (port, side) -> side == null ? null : new Items(port));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, NetworkContent.PORT_TILE.get(),
                (port, side) -> side == null || port.kind() == PortState.Kind.SUPPLY ? null : new Fluids(port));
    }
    static long product(PortAccess access, PortAccess.Snapshot view, ProductKey key, long request, boolean insert, boolean simulate) {
        if (request <= 0) return 0;
        var level = (ServerLevel) access.port.getLevel();
        var filter = view.state().filters().get(key.kind() == ProductKey.Kind.FLUID ? PortState.FLUID : -1);
        if (filter != null && !filter.equals(key)) return 0;
        var policy = insert ? RuntimeProductPolicies.peek(level) : null;
        if (insert && (policy == null || !policy.accepts(key))) return 0;
        long amount = insert ? request : Math.min(request, view.root().ledger().available(key).longSaturated());
        if (amount == 0) return 0;
        if (simulate) return (!insert || RuntimeProductPolicies.peek(level) == policy) && access.commit(view, view.root(), true) ? amount : 0;
        var next = insert ? view.root().insertProduct(view.root().ledger().revision(), key, amount, policy)
                : view.root().extractProduct(view.root().ledger().revision(), key, amount);
        return next != view.root() && (!insert || RuntimeProductPolicies.peek(level) == policy) && access.commit(view, next, simulate) ? amount : 0;
    }
    static PortState.Stack stack(ItemStack stack, ServerLevel level) {
        return stack.isEmpty() ? PortState.Stack.EMPTY : new PortState.Stack(ProductKeyCodec.item(stack, level.registryAccess()), stack.getCount());
    }
    static ItemStack item(PortState.Stack stack, ServerLevel level) {
        return stack.empty() ? ItemStack.EMPTY : ProductKeyCodec.item(stack.key(), stack.count(), level.registryAccess());
    }
    static int supply(PortAccess access, PortAccess.Snapshot view, int slot, ItemStack offered, int requested, boolean insert, boolean simulate) {
        var current = view.state().supplies().get(slot); var level = (ServerLevel) access.port.getLevel();
        PortState.Stack next; int amount;
        if (insert) {
            if (offered.isEmpty()) return 0;
            var key = ProductKeyCodec.item(offered, level.registryAccess());
            if (!current.empty() && !current.key().equals(key)) return 0;
            amount = Math.min(Math.min(requested, offered.getCount()), Math.min(64, offered.getMaxStackSize()) - current.count());
            if (amount <= 0) return 0;
            next = new PortState.Stack(key, current.count() + amount);
        } else {
            if (current.empty()) return 0;
            int limit = ProductKeyCodec.item(current.key(), 1, level.registryAccess()).getMaxStackSize();
            amount = Math.min(Math.min(requested, Math.min(64, limit)), current.count()); if (amount <= 0) return 0;
            next = amount == current.count() ? PortState.Stack.EMPTY : new PortState.Stack(current.key(), current.count() - amount);
        }
        if (simulate) return access.commit(view, view.root(), true) ? amount : 0;
        var candidate = view.root().withPort(view.state(), view.state().stack(slot, next));
        if (!access.commit(view, candidate, simulate)) return 0;
        if (!simulate) PortWorkService.watch(access.port);
        return amount;
    }
    public static final class Items implements IItemHandler {
        private final PortAccess access;
        public Items(NetworkPortBlockEntity port) { access = new PortAccess(port); }
        @Override public int getSlots() { return PortState.SLOTS; }
        private boolean slot(int slot) { return slot >= 0 && slot < getSlots(); }
        @Override public int getSlotLimit(int slot) { return slot(slot) ? 64 : 0; }
        @Override public ItemStack getStackInSlot(int slot) {
            var view = access.get(true); if (!slot(slot) || view == null) return ItemStack.EMPTY;
            var level = (ServerLevel) access.port.getLevel();
            if (view.state().kind() == PortState.Kind.SUPPLY) return item(view.state().supplies().get(slot), level);
            if (view.state().kind() == PortState.Kind.INPUT) return ItemStack.EMPTY;
            var key = view.state().filters().get(slot); if (key == null) return ItemStack.EMPTY;
            var unit = ProductKeyCodec.item(key, 1, level.registryAccess());
            int count = (int) Math.min(Math.min(64, unit.getMaxStackSize()), view.root().ledger().available(key).longSaturated());
            return count == 0 ? ItemStack.EMPTY : unit.copyWithCount(count);
        }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            var view = access.get(true); if (!slot(slot) || view == null || stack.isEmpty() || view.state().kind() == PortState.Kind.OUTPUT) return false;
            if (view.state().kind() == PortState.Kind.SUPPLY) return true;
            var key = ProductKeyCodec.item(stack, access.port.getLevel().registryAccess()); var filter = view.state().filters().get(slot);
            var policy = RuntimeProductPolicies.peek((ServerLevel) access.port.getLevel());
            return (filter == null || filter.equals(key)) && policy != null && policy.accepts(key);
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            var view = access.get(true); if (!slot(slot) || view == null || stack.isEmpty() || view.state().kind() == PortState.Kind.OUTPUT) return stack;
            int offered = Math.min(Math.min(64, stack.getMaxStackSize()), stack.getCount()); int accepted;
            if (view.state().kind() == PortState.Kind.SUPPLY) accepted = supply(access, view, slot, stack, offered, true, simulate);
            else {
                var key = ProductKeyCodec.item(stack, access.port.getLevel().registryAccess()); var filter = view.state().filters().get(slot);
                if (filter != null && !filter.equals(key)) return stack;
                accepted = (int) product(access, view, key, offered, true, simulate);
            }
            return accepted == 0 ? stack : stack.copyWithCount(stack.getCount() - accepted);
        }
        @Override public ItemStack extractItem(int slot, int requested, boolean simulate) {
            var view = access.get(true); if (!slot(slot) || view == null || requested <= 0 || view.state().kind() == PortState.Kind.INPUT) return ItemStack.EMPTY;
            var projection = getStackInSlot(slot); if (projection.isEmpty()) return ItemStack.EMPTY;
            int amount = Math.min(requested, projection.getCount());
            int moved = view.state().kind() == PortState.Kind.SUPPLY ? supply(access, view, slot, ItemStack.EMPTY, amount, false, simulate)
                    : (int) product(access, view, view.state().filters().get(slot), amount, false, simulate);
            return moved == 0 ? ItemStack.EMPTY : projection.copyWithCount(moved);
        }
    }
    public static final class Fluids implements IFluidHandler {
        private final PortAccess access;
        public Fluids(NetworkPortBlockEntity port) { access = new PortAccess(port); }
        @Override public int getTanks() { return 1; }
        @Override public int getTankCapacity(int tank) { return tank == 0 ? PortState.FLUID_LIMIT : 0; }
        @Override public FluidStack getFluidInTank(int tank) {
            var view = access.get(true);
            if (tank != 0 || view == null || view.state().kind() != PortState.Kind.OUTPUT) return FluidStack.EMPTY;
            var key = view.state().filters().get(PortState.FLUID); if (key == null) return FluidStack.EMPTY;
            int amount = (int) Math.min(PortState.FLUID_LIMIT, view.root().ledger().available(key).longSaturated());
            return amount == 0 ? FluidStack.EMPTY : ProductKeyCodec.fluid(key, amount, access.port.getLevel().registryAccess());
        }
        @Override public boolean isFluidValid(int tank, FluidStack fluid) {
            return tank == 0 && !fluid.isEmpty() && fill(fluid, FluidAction.SIMULATE) > 0;
        }
        @Override public int fill(FluidStack fluid, FluidAction action) {
            var view = access.get(true);
            if (view == null || fluid.isEmpty() || view.state().kind() != PortState.Kind.INPUT) return 0;
            var key = ProductKeyCodec.fluid(fluid, access.port.getLevel().registryAccess());
            return (int) product(access, view, key, Math.min(PortState.FLUID_LIMIT, fluid.getAmount()), true, action.simulate());
        }
        @Override public FluidStack drain(FluidStack requested, FluidAction action) {
            var stored = getFluidInTank(0);
            return requested.isEmpty() || !FluidStack.isSameFluidSameComponents(stored, requested) ? FluidStack.EMPTY : drain(requested.getAmount(), action);
        }
        @Override public FluidStack drain(int requested, FluidAction action) {
            var view = access.get(true); var stored = getFluidInTank(0);
            if (view == null || requested <= 0 || stored.isEmpty() || view.state().kind() != PortState.Kind.OUTPUT) return FluidStack.EMPTY;
            int moved = (int) product(access, view, view.state().filters().get(PortState.FLUID),
                    Math.min(requested, stored.getAmount()), false, action.simulate());
            return moved == 0 ? FluidStack.EMPTY : stored.copyWithAmount(moved);
        }
    }
    private PortCapabilities() { }
}
