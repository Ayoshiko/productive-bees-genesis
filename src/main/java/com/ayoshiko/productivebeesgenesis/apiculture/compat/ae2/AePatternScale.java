package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternBatchEditor;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status;

/** 批次冻结倍率与方向；每叠仍独立检查数量、编码及同键合计。 */
public record AePatternScale(long factor, boolean divide) implements MePatternBatchEditor {
	public static Prepared capture(long factor, boolean divide) {
		return factor < 1 ? new Prepared(Status.INVALID, null) : new Prepared(Status.OK, new AePatternScale(factor, divide));
	}
	@Override public boolean supports(ItemStack pattern) { return AEItems.PROCESSING_PATTERN.is(pattern); }
	@Override public MePatternPlan prepare(ItemStack pattern) {
		var plan = AePatternEditor.prepare(pattern, factor, divide);
		return plan.status() == Status.OK && ItemStack.matches(pattern, plan.result()) ? MePatternPlan.failed(Status.PATTERN_NO_CHANGE) : plan;
	}
	@Override public boolean current(ItemStack sample) { return factor > 0; }
	@Override public String title() { return (divide ? "÷ " : "× ") + factor; }
}
