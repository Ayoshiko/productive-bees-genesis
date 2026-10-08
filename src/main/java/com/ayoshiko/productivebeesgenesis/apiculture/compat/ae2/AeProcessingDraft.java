package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AEProcessingPattern;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeProcessingDraft;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 当前菜单的自由处理定义；失败不发布部分编辑，不持有材料或外部库存。 */
public final class AeProcessingDraft implements MeProcessingDraft {
	private List<GenericStack> inputs = List.of(), outputs = List.of();
	@Override public Status edit(MeTerminalRequest.Action action, int index, long amount, ItemStack sample, boolean contents, BooleanSupplier current) {
		var nextInputs = new ArrayList<>(inputs); var nextOutputs = new ArrayList<>(outputs);
		if (action == PATTERN_ADD_INPUT || action == PATTERN_ADD_OUTPUT) {
			if (amount < 1) return Status.INVALID;
			boolean output = action == PATTERN_ADD_OUTPUT; var target = output ? nextOutputs : nextInputs;
			if (target.size() >= (output ? AEProcessingPattern.MAX_OUTPUT_SLOTS : AEProcessingPattern.MAX_INPUT_SLOTS)) return Status.PATTERN_DRAFT_FULL;
			var key = key(sample, contents); if (key == null) return Status.PATTERN_SAMPLE_INVALID;
			target.add(new GenericStack(key, amount));
		} else if (action == PATTERN_CLEAR) {
			nextInputs.clear(); nextOutputs.clear();
		} else if (action == PATTERN_SET_AMOUNT || action == PATTERN_REMOVE) {
			if (index < 0 || index >= inputs.size() + outputs.size()) return Status.STALE;
			var target = index < inputs.size() ? nextInputs : nextOutputs; int slot = index < inputs.size() ? index : index - inputs.size();
			if (action == PATTERN_REMOVE) target.remove(slot);
			else { if (amount < 1) return Status.INVALID; target.set(slot, new GenericStack(target.get(slot).what(), amount)); }
		} else return Status.INVALID;
		try {
			if (!nextInputs.isEmpty() && !AePatternEditor.valid(nextInputs) || !nextOutputs.isEmpty() && !AePatternEditor.valid(nextOutputs)) return Status.PATTERN_INVALID;
		} catch (ArithmeticException overflow) { return Status.PATTERN_OVERFLOW; }
		// 样本策略可能进入外部代码；返回后复核账户、菜单和鼠标，再发布定义。
		if (!current.getAsBoolean()) return Status.STALE;
		inputs = List.copyOf(nextInputs); outputs = List.copyOf(nextOutputs);
		return Status.OK;
	}
	private static AEKey key(ItemStack sample, boolean contents) {
		if (sample.isEmpty()) return null;
		var probe = sample.copyWithCount(1); var before = probe.copy(); AEKey key;
		if (contents) {
			var stack = ContainerItemStrategies.getContainedStack(probe);
			key = stack == null || stack.amount() <= 0 ? null : stack.what();
		} else key = AEItemKey.of(probe);
		return key != null && !AEItems.MISSING_CONTENT.is(key) && ItemStack.matches(before, probe) ? key : null;
	}
	@Override public MePatternPlan preview(ItemStack blank) {
		var rows = new ArrayList<Row>(); AePatternEditor.append(rows, inputs, inputs, Kind.PATTERN_INPUT); AePatternEditor.append(rows, outputs, outputs, Kind.PATTERN_OUTPUT);
		var status = AePatternEditor.blankStatus(blank);
		if (status != Status.OK) return new MePatternPlan(status, ItemStack.EMPTY, rows);
		if (inputs.isEmpty() || outputs.isEmpty()) return new MePatternPlan(Status.PATTERN_INCOMPLETE, ItemStack.EMPTY, rows);
		var encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
		var result = blank.transmuteCopy(encoded.getItem(), blank.getCount());
		result.set(AEComponents.ENCODED_PROCESSING_PATTERN, Objects.requireNonNull(encoded.get(AEComponents.ENCODED_PROCESSING_PATTERN)));
		new AEProcessingPattern(AEItemKey.of(result));
		return result.getCount() > result.getMaxStackSize() ? new MePatternPlan(Status.PATTERN_INVALID, ItemStack.EMPTY, rows) : new MePatternPlan(Status.OK, result, rows);
	}
}
