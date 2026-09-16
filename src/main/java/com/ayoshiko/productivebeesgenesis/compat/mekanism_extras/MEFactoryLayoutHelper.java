package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;

/** Mekanism Extras factory layout formulas isolated from the common layout helper. */
public final class MEFactoryLayoutHelper {

	private static final int TERTIARY_OUTPUT_Y = 97;
	private static final int SLOT_HEIGHT = 18;
	private static final int FLUID_TANK_HEIGHT = 30;

	private MEFactoryLayoutHelper() {
	}

	public static int getImageWidthAddition(ExtraFactoryTier tier) {
		int index = tier.ordinal();
		return (36 * (index + 2)) + (2 * index);
	}

	public static int getInventoryLabelX(ExtraFactoryTier tier) {
		int index = tier.ordinal();
		return (22 * (index + 2)) - (3 * index);
	}

	public static int getBaseX(ExtraFactoryTier tier) {
		return 27;
	}

	public static int getBaseXMult(ExtraFactoryTier tier) {
		return 19;
	}

	public static int getFluidTankX(ExtraFactoryTier tier) {
		return 7;
	}

	public static int getFluidTankY(ExtraFactoryTier tier) {
		return TERTIARY_OUTPUT_Y + SLOT_HEIGHT - FLUID_TANK_HEIGHT - 1;
	}
}
