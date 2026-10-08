package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AEProcessingPattern;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 仅改写原生处理样板的数量；保留稀疏槽位、完整键及其它物品组件。 */
public final class AePatternEditor {
	public static MePatternPlan prepare(ItemStack original, long factor, boolean divide) {
		if (!AEItems.PROCESSING_PATTERN.is(original)) return MePatternPlan.failed(Status.PATTERN_UNSUPPORTED);
		if (factor < 1 || original.getCount() < 1 || original.getCount() > 64 || original.getCount() > original.getMaxStackSize()) return MePatternPlan.failed(Status.INVALID);
		var encoded = original.get(AEComponents.ENCODED_PROCESSING_PATTERN);
		if (encoded == null || encoded.sparseInputs().isEmpty()
				|| encoded.sparseInputs().size() > AEProcessingPattern.MAX_INPUT_SLOTS || encoded.sparseOutputs().isEmpty()
				|| encoded.sparseOutputs().size() > AEProcessingPattern.MAX_OUTPUT_SLOTS || encoded.sparseOutputs().getFirst() == null || encoded.containsMissingContent())
			return MePatternPlan.failed(Status.PATTERN_INVALID);
		try {
			if (!valid(encoded.sparseInputs()) || !valid(encoded.sparseOutputs())) return MePatternPlan.failed(Status.PATTERN_INVALID);
		} catch (ArithmeticException invalid) { return MePatternPlan.failed(Status.PATTERN_INVALID); }
		if (divide && (!divisible(encoded.sparseInputs(), factor) || !divisible(encoded.sparseOutputs(), factor)))
			return MePatternPlan.failed(Status.PATTERN_NOT_DIVISIBLE);
		try {
			var inputs = scale(encoded.sparseInputs(), factor, divide); var outputs = scale(encoded.sparseOutputs(), factor, divide);
			// AE2 合并同键时使用 long 求和；逐格未溢出仍不足以证明样板可执行。
			if (!valid(inputs) || !valid(outputs)) return MePatternPlan.failed(Status.PATTERN_INVALID);
			var result = original.copy(); AEProcessingPattern.encode(result, inputs, outputs);
			new AEProcessingPattern(AEItemKey.of(result));
			var rows = new ArrayList<Row>();
			append(rows, encoded.sparseInputs(), inputs, Kind.PATTERN_INPUT);
			append(rows, encoded.sparseOutputs(), outputs, Kind.PATTERN_OUTPUT);
			return new MePatternPlan(Status.OK, result, rows);
		} catch (ArithmeticException overflow) { return MePatternPlan.failed(Status.PATTERN_OVERFLOW); }
	}
	private static boolean valid(List<GenericStack> stacks) {
		var totals = new HashMap<AEKey, Long>();
		for (var stack : stacks) if (stack != null) {
			if (stack.amount() <= 0 || AEItems.MISSING_CONTENT.is(stack.what())) return false;
			totals.merge(stack.what(), stack.amount(), Math::addExact);
		}
		return !totals.isEmpty();
	}
	private static boolean divisible(List<GenericStack> stacks, long factor) {
		for (var stack : stacks) if (stack != null && stack.amount() % factor != 0) return false;
		return true;
	}
	private static List<GenericStack> scale(List<GenericStack> stacks, long factor, boolean divide) {
		var result = new ArrayList<GenericStack>(stacks.size());
		for (var stack : stacks) result.add(stack == null ? null : new GenericStack(stack.what(), divide ? stack.amount() / factor : Math.multiplyExact(stack.amount(), factor)));
		return result;
	}
	private static void append(List<Row> rows, List<GenericStack> before, List<GenericStack> after, Kind kind) {
		for (int i = 0; i < before.size(); i++) {
			var stack = before.get(i); if (stack == null) continue;
			var key = stack.what(); var icon = key instanceof AEItemKey item ? item.toStack(1) : key instanceof AEFluidKey fluid ? new ItemStack(fluid.getFluid().getBucket()) : ItemStack.EMPTY;
			String unit = key instanceof AEFluidKey || AeMeChemical.isChemical(key) ? " mB" : AeMeEnergy.isFe(key) ? " FE" : "";
			String label = (i + 1) + " · " + key.getDisplayName().getString() + " · " + key.getId() + unit;
			if (label.length() > 128) label = label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
			rows.add(new Row(kind, icon, label, stack.amount(), after.get(i).amount(), false));
		}
	}
	private AePatternEditor() { }
}
