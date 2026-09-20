package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeProductionSampling;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/** PB 模板与纯数量内核之间的适配；过滤发生在采样前，模板只读。 */
public final class PbRecipeOutputSampler {
	/**
	 * 只写本次私有结果，不执行外部 IO。baseAmount 尚未乘 multiplier；物理边界饱和投影，
	 * 网络边界用 ProductAmount 精确相乘。回调失败时调用方须丢弃整个未提交结果。
	 */
	public interface QuantityOutput {
		void item(ItemStack template, long baseAmount, int multiplier);
		void fluid(FluidStack template, long baseAmount, int multiplier);
	}

	public static void sampleAmounts(QuantityOutput output, Map<ItemStack, ChancedOutput> items,
			@Nullable FluidStack fluid, int rolls, int productivityModifier, float stabilityBonus,
			boolean discardWax, RandomGenerator random) {
		if (rolls <= 0) return;
		int modifier = Math.max(1, productivityModifier);
		for (var entry : items.entrySet()) {
			if (discardWax && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			var value = entry.getValue();
			long amount = CentrifugeProductionSampling.sampleOutput(random, rolls,
					value.min(), value.max(), value.chance(), stabilityBonus);
			if (amount > 0) output.item(entry.getKey(), amount, modifier);
		}
		if (fluid != null && !fluid.isEmpty()
				&& !(discardWax && UselessByproductUpgradeHelper.isHoney(fluid))) {
			output.fluid(fluid, (long) fluid.getAmount() * rolls, modifier);
		}
	}

	private PbRecipeOutputSampler() { }
}
