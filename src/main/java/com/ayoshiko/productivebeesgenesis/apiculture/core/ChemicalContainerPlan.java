package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.world.item.ItemStack;

/** 单件副本上的化学品交接；多罐总量用精确整数检查，不能溢出后伪装守恒。 */
final class ChemicalContainerPlan {
	private static final int MAX_TANKS = 32;
	record Change(ItemStack container, ChemicalStack chemical) {
		Change { container = container.copy(); chemical = chemical.copy(); }
		@Override public ItemStack container() { return container.copy(); }
		@Override public ChemicalStack chemical() { return chemical.copy(); }
	}
	static ChemicalStack content(ItemStack original) {
		if (original.isEmpty()) return ChemicalStack.EMPTY;
		var copy = original.copyWithCount(1); var beforeItem = copy.copy(); var before = snapshot(copy);
		var handler = copy.getCapability(Capabilities.CHEMICAL.item());
		if (handler == null) return ChemicalStack.EMPTY;
		var content = handler.extractChemical(Long.MAX_VALUE, Action.SIMULATE).copy();
		if (!ItemStack.matches(copy, beforeItem) || amount(before, content).compareTo(BigInteger.valueOf(content.getAmount())) < 0)
			throw new IllegalStateException("Invalid chemical content simulation");
		conserved(before, snapshot(copy), content, 0);
		return content;
	}
	static Change prepare(ItemStack original, ChemicalStack requested, boolean fill) {
		if (original.isEmpty() || requested.isEmpty()) return null;
		var copy = original.copyWithCount(1); var beforeItem = copy.copy(); var before = snapshot(copy);
		var handler = copy.getCapability(Capabilities.CHEMICAL.item());
		if (handler == null) return null;
		long possible = fill ? requested.getAmount() - checked(handler.insertChemical(requested.copy(), Action.SIMULATE), requested)
				: checked(handler.extractChemical(requested.copy(), Action.SIMULATE), requested);
		if (!ItemStack.matches(copy, beforeItem)) throw new IllegalStateException("Chemical simulation changed the item");
		conserved(before, snapshot(copy), requested, 0);
		if (possible == 0) return null;
		var offered = requested.copyWithAmount(possible);
		long actual = fill ? possible - checked(handler.insertChemical(offered.copy(), Action.EXECUTE), offered)
				: checked(handler.extractChemical(offered.copy(), Action.EXECUTE), offered);
		var result = copy.copy();
		if (result.getCount() != 1 || actual > 0 && ItemStack.matches(result, beforeItem)) throw new IllegalStateException("Chemical result did not change a single item");
		conserved(before, snapshot(result), requested, fill ? actual : -actual);
		if (!ItemStack.matches(result, copy)) throw new IllegalStateException("Chemical inspection changed the result");
		return actual == 0 ? null : new Change(result, requested.copyWithAmount(actual));
	}
	private static List<ChemicalStack> snapshot(ItemStack item) {
		var handler = item.getCapability(Capabilities.CHEMICAL.item()); var result = new ArrayList<ChemicalStack>();
		if (handler == null) return result;
		int tanks = handler.getChemicalTanks();
		if (tanks < 0 || tanks > MAX_TANKS) throw new IllegalArgumentException("Chemical container inspection budget exceeded");
		for (int i = 0; i < tanks; i++) {
			var chemical = handler.getChemicalInTank(i);
			if (handler.getChemicalTankCapacity(i) < chemical.getAmount()) throw new IllegalStateException("Invalid chemical tank contents");
			if (!chemical.isEmpty()) result.add(chemical.copy());
		}
		return result;
	}
	private static long checked(ChemicalStack result, ChemicalStack requested) {
		if (!result.isEmpty() && !same(result, requested) || result.getAmount() < 0 || result.getAmount() > requested.getAmount())
			throw new IllegalStateException("Chemical container returned an invalid amount or type");
		return result.getAmount();
	}
	private static void conserved(List<ChemicalStack> before, List<ChemicalStack> after, ChemicalStack key, long delta) {
		if (!amount(after, key).subtract(amount(before, key)).equals(BigInteger.valueOf(delta))) throw new IllegalStateException("Chemical delta differs from return value");
		for (var other : before) if (!same(other, key) && !amount(after, other).equals(amount(before, other))) throw new IllegalStateException("Container changed another chemical");
		for (var other : after) if (!same(other, key) && !amount(after, other).equals(amount(before, other))) throw new IllegalStateException("Container created another chemical");
	}
	private static BigInteger amount(List<ChemicalStack> contents, ChemicalStack key) {
		var amount = BigInteger.ZERO;
		for (var chemical : contents) if (same(chemical, key)) amount = amount.add(BigInteger.valueOf(chemical.getAmount()));
		return amount;
	}
	static boolean same(ChemicalStack first, ChemicalStack second) { return ChemicalStack.isSameChemical(first, second); }
	private ChemicalContainerPlan() { }
}
