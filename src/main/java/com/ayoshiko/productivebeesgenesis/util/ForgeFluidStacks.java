package com.ayoshiko.productivebeesgenesis.util;

import net.minecraftforge.fluids.FluidStack;

/** Forge 1.20.1 equivalents of newer FluidStack convenience methods. */
public final class ForgeFluidStacks {
    public static boolean matches(net.minecraftforge.fluids.FluidStack first,net.minecraftforge.fluids.FluidStack second){return first.isFluidStackIdentical(second);}
    private ForgeFluidStacks() {}
    public static FluidStack copyWithAmount(FluidStack stack, int amount) {
        if (stack.isEmpty() || amount <= 0) return FluidStack.EMPTY;
        FluidStack copy = stack.copy();
        copy.setAmount(amount);
        return copy;
    }
    public static boolean isSameFluid(FluidStack left, FluidStack right) {
        return left == right || left != null && right != null && left.isFluidEqual(right);
    }
}
