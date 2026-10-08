package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.behaviors.ContainerItemStrategies;
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

/** 原生处理样板倍率与同类型资源替换；保留稀疏槽位、精确数量及其它组件。 */
public final class AePatternEditor {
	public static MePatternPlan prepare(ItemStack original, long factor, boolean divide) {
		var status = validate(original); if (status != Status.OK) return MePatternPlan.failed(status);
		if (factor < 1) return MePatternPlan.failed(Status.INVALID);
		var encoded = original.get(AEComponents.ENCODED_PROCESSING_PATTERN);
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
	public static MePatternPlan replace(ItemStack original, int row, ItemStack sample) {
		var status = validate(original); if (status != Status.OK) return MePatternPlan.failed(status);
		var source = sourceKey(original, row);
		if (source == null) return MePatternPlan.failed(Status.STALE);
		var target = sampleKey(source, sample);
		if (target == null) return MePatternPlan.failed(Status.PATTERN_SAMPLE_INVALID);
		if (source.equals(target)) return MePatternPlan.failed(Status.PATTERN_NO_CHANGE);
		return replace(original, source, target);
	}
	static AEKey sourceKey(ItemStack original, int row) {
		var encoded = original.get(AEComponents.ENCODED_PROCESSING_PATTERN); int index = 0;
		for (var stack : encoded.sparseInputs()) if (stack != null && index++ == row) return stack.what();
		for (var stack : encoded.sparseOutputs()) if (stack != null && index++ == row) return stack.what();
		return null;
	}
	static MePatternPlan replace(ItemStack original, AEKey source, AEKey target) {
		var status = validate(original); if (status != Status.OK) return MePatternPlan.failed(status);
		var encoded = original.get(AEComponents.ENCODED_PROCESSING_PATTERN);
		boolean found = encoded.sparseInputs().stream().anyMatch(stack -> stack != null && source.equals(stack.what()))
				|| encoded.sparseOutputs().stream().anyMatch(stack -> stack != null && source.equals(stack.what()));
		if (!found) return MePatternPlan.failed(Status.PATTERN_NO_MATCH);
		try {
			var inputs = replace(encoded.sparseInputs(), source, target); var outputs = replace(encoded.sparseOutputs(), source, target);
			if (!valid(inputs) || !valid(outputs)) return MePatternPlan.failed(Status.PATTERN_INVALID);
			var result = original.copy(); AEProcessingPattern.encode(result, inputs, outputs); new AEProcessingPattern(AEItemKey.of(result));
			var rows = new ArrayList<Row>(); append(rows, encoded.sparseInputs(), inputs, Kind.PATTERN_INPUT); append(rows, encoded.sparseOutputs(), outputs, Kind.PATTERN_OUTPUT);
			return new MePatternPlan(Status.OK, result, rows);
		} catch (ArithmeticException overflow) { return MePatternPlan.failed(Status.PATTERN_OVERFLOW); }
	}
	static AEKey sampleKey(AEKey source, ItemStack sample) {
		if (sample.isEmpty()) return null;
		var probe = sample.copyWithCount(1); var before = probe.copy();
		AEKey key;
		if (source instanceof AEItemKey) key = AEItemKey.of(probe);
		else {
			// 按原类型读取已注册策略，避免多能力容器被其它类型抢先匹配。
			var content = ContainerItemStrategies.getContainedStack(probe, source.getType());
			key = content == null || content.amount() <= 0 ? null : content.what();
		}
		return key != null && key.getType() == source.getType() && !AEItems.MISSING_CONTENT.is(key) && ItemStack.matches(before, probe) ? key : null;
	}
	private static List<GenericStack> replace(List<GenericStack> stacks, AEKey source, AEKey target) {
		var result = new ArrayList<GenericStack>(stacks.size());
		for (var stack : stacks) result.add(stack != null && source.equals(stack.what()) ? new GenericStack(target, stack.amount()) : stack);
		return result;
	}
	static Status validate(ItemStack original) {
		if (!AEItems.PROCESSING_PATTERN.is(original)) return Status.PATTERN_UNSUPPORTED;
		if (original.getCount() < 1 || original.getCount() > Math.min(64, original.getMaxStackSize())) return Status.INVALID;
		var encoded = original.get(AEComponents.ENCODED_PROCESSING_PATTERN);
		if (encoded == null || encoded.sparseInputs().isEmpty() || encoded.sparseInputs().size() > AEProcessingPattern.MAX_INPUT_SLOTS
				|| encoded.sparseOutputs().isEmpty() || encoded.sparseOutputs().size() > AEProcessingPattern.MAX_OUTPUT_SLOTS
				|| encoded.sparseOutputs().getFirst() == null || encoded.containsMissingContent()) return Status.PATTERN_INVALID;
		try { return valid(encoded.sparseInputs()) && valid(encoded.sparseOutputs()) ? Status.OK : Status.PATTERN_INVALID; }
		catch (ArithmeticException invalid) { return Status.PATTERN_INVALID; }
	}
	static boolean valid(List<GenericStack> stacks) {
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
	static void append(List<Row> rows, List<GenericStack> before, List<GenericStack> after, Kind kind) {
		for (int i = 0; i < before.size(); i++) {
			var stack = before.get(i); if (stack == null) continue;
			var key = after.get(i).what(); var icon = key instanceof AEItemKey item ? item.toStack(1) : key instanceof AEFluidKey fluid ? new ItemStack(fluid.getFluid().getBucket()) : ItemStack.EMPTY;
			String unit = key instanceof AEFluidKey || AeMeChemical.isChemical(key) ? " mB" : AeMeEnergy.isFe(key) ? " FE" : "";
			String label = (i + 1) + " · " + key.getDisplayName().getString() + " · " + key.getId() + unit;
			if (label.length() > 128) label = label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
			rows.add(new Row(kind, icon, label, stack.amount(), after.get(i).amount(), !key.equals(stack.what())));
		}
	}
	static Status blankStatus(ItemStack original) {
		if (!AEItems.BLANK_PATTERN.is(original)) return Status.PATTERN_NEEDS_BLANK;
		if (original.getCount() < 1 || original.getCount() > Math.min(64, original.getMaxStackSize())
				|| original.has(AEComponents.ENCODED_CRAFTING_PATTERN) || original.has(AEComponents.ENCODED_PROCESSING_PATTERN)
				|| original.has(AEComponents.ENCODED_SMITHING_TABLE_PATTERN) || original.has(AEComponents.ENCODED_STONECUTTING_PATTERN)) return Status.PATTERN_INVALID;
		return Status.OK;
	}
	private AePatternEditor() { }
}
