package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.stacks.AEKey;
import mekanism.api.chemical.ChemicalStack;
import net.neoforged.fml.ModList;

/** 化学品身份来自 Applied Mekanistics；没有安装时不解析其类型。 */
final class AeMeChemical {
	static boolean isChemical(AEKey key) { return ModList.get().isLoaded("appmek") && Loaded.isChemical(key); }
	static AEKey key(ChemicalStack stack) { return ModList.get().isLoaded("appmek") ? Loaded.key(stack) : null; }
	static ChemicalStack stack(AEKey key) { return isChemical(key) ? Loaded.stack(key) : ChemicalStack.EMPTY; }
	private static final class Loaded {
		static boolean isChemical(AEKey key) { return key instanceof me.ramidzkh.mekae2.ae2.MekanismKey; }
		static AEKey key(ChemicalStack stack) { return me.ramidzkh.mekae2.ae2.MekanismKey.of(stack.copyWithAmount(1)); }
		static ChemicalStack stack(AEKey key) { return ((me.ramidzkh.mekae2.ae2.MekanismKey) key).withAmount(1); }
	}
	private AeMeChemical() { }
}
