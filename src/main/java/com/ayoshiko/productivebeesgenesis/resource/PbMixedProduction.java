package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.apiary.BeeProductivityGene;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.util.BeeHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** PB 静态配方快照；基因按每轮原始栈换算，物品和资源共享已抽出的升级轮数。 */
public final class PbMixedProduction {
	private final List<PbResourceCatalog.ItemOutput> outputs;
	private final int gene;
	private final long maximumRolls;

	public PbMixedProduction(List<PbResourceCatalog.ItemOutput> outputs, double modifier, int gene,
			boolean blocks, boolean discard) {
		this.gene = gene;
		if (!Double.isFinite(modifier) || modifier < 1 || modifier > 1_000_000 || gene < 0 || gene > 3) {
			throw new IllegalArgumentException("Invalid PB mixed multiplier");
		}
		maximumRolls = (long) Math.ceil(modifier);
		var accepted = new ArrayList<PbResourceCatalog.ItemOutput>();
		for (var output : outputs) {
			var template = output.template();
			if (output.chance() <= 0 || output.max() == 0
					|| (discard && UselessByproductUpgradeHelper.isPollenPuff(template))) continue;
			if (blocks && template.getItem() instanceof HoneycombItem) {
				template = BeeHelper.getCombBlockFromHoneyComb(template);
				if (template.isEmpty()) throw new IllegalArgumentException("Missing PB comb block mapping");
			}
			accepted.add(new PbResourceCatalog.ItemOutput(template, output.min(), output.max(), output.chance()));
		}
		this.outputs = List.copyOf(accepted);
	}

	public List<ItemStack> maximum() {
		var result = new ArrayList<ItemStack>();
		for (var output : outputs) {
			int count = Math.toIntExact(Math.multiplyExact(maximumRolls, adjusted(output.max())));
			result.add(output.template().copyWithCount(count));
		}
		return result;
	}

	public List<ItemStack> sample(long rolls, long seed) {
		if (rolls < 0 || rolls > maximumRolls) throw new IllegalArgumentException("Invalid PB mixed rounds");
		var random = RandomSource.create(seed);
		var result = new ArrayList<ItemStack>();
		for (var output : outputs) {
			long amount = 0;
			for (long roll = 0; roll < rolls; roll++) {
				if (random.nextFloat() > output.chance()) continue;
				long range = (long) output.max() - output.min() + 1;
				int raw = range == 1 ? output.min()
						: Math.toIntExact(output.min() + random.nextInt(Math.toIntExact(range)));
				amount = Math.addExact(amount, adjusted(raw));
			}
			if (amount > 0) result.add(output.template().copyWithCount(Math.toIntExact(amount)));
		}
		return result;
	}

	private int adjusted(int raw) {
		int adjusted = BeeProductivityGene.adjustStackCount(raw, gene);
		if (adjusted == Integer.MAX_VALUE) throw new ArithmeticException("PB mixed item count overflow");
		return adjusted;
	}
}
