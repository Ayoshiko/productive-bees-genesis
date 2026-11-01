package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceCatalog;
import com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.ChancedOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只协调本地容量与事务；物品、采样、升级继续复用普通机械蜂箱的实现。 */
final class ApiaryMixedProduction {
	private final TileEntityMekApiary tile;
	private final BeeSlot slot;
	private final ResourceLocation type;
	private final boolean nativeProduction;
	private final boolean routed;
	private final ApiaryBatchUpgradeSnapshot upgrades;
	private final Map<ItemStack, ChancedOutput> outputs = new LinkedHashMap<>();
	private final List<ItemStack> maximum = new ArrayList<>();
	private final FluidStack fluid;
	private final int gene;
	private final EssenceConversionUpgradeHelper.BatchPlan conversion;
	private final boolean discard, blocks;
	private final GeneSampler.Snapshot sampling;
	private final List<ItemStack> alternatives;
	private long limit;

	ApiaryMixedProduction(TileEntityMekApiary tile, ServerLevel level, ResourceLocation type,
			PbResourceCatalog.Spec spec, ApiaryUpgradeHandler upgrades, BeeSlot slot) {
		this.tile = tile;
		this.slot = slot;
		this.type = type;
		this.nativeProduction = spec.mixed() || spec.dynamic();
		this.upgrades = ApiaryBatchUpgradeSnapshot.capture(tile, upgrades);
		routed = spec.dynamic() || this.upgrades.directAeOutputEnabled()
				|| this.upgrades.directContainerOutputEnabled() || this.upgrades.centrifugeDirectTransferEnabled();
		this.gene = slot.getProductivityLevel();
		conversion = tile.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION) > 0
				? EssenceConversionUpgradeHelper.plan(level) : null;
		discard = tile.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;
		blocks = upgrades.hasCombBlockUpgrade();
		sampling = GeneSampler.snapshot(slot.getBeeData(), type, upgrades.getGeneSamplerCount(),
				tile.getPbUpgradeInstalledCount(PbUpgradeType.GENE_TYPE_ONLY) > 0,
				tile.getPbUpgradeInstalledCount(PbUpgradeType.GENE_FULL_PURITY) > 0);
		alternatives = sampling.possible();
		long rolls = (long) Math.ceil(upgrades.getProductivityMultiplier());
		if (rolls < 1 || rolls > 1_000_000) throw new IllegalArgumentException("Invalid mixed upgrade rounds");
		limit = Integer.MAX_VALUE / rolls;
		for (var entry : spec.items()) {
			if (entry.chance() == 0 || entry.max() == 0) continue;
			var template = entry.template();
			int adjusted = BeeProductivityGene.adjustStackCount(entry.max(), gene);
			if (adjusted == Integer.MAX_VALUE) throw new ArithmeticException("Mixed item count overflow");
			long perCycle = Math.multiplyExact(rolls, adjusted);
			if (perCycle > Integer.MAX_VALUE) throw new ArithmeticException("Mixed item cycle overflow");
			maximum.add(template.copyWithCount((int) perCycle));
			limit = Math.min(limit, Integer.MAX_VALUE / perCycle);
			outputs.put(template, new ChancedOutput(entry.min(),entry.max(),entry.chance()));
		}
		// 没有普通物品路线的纯资源蜂不凭采样器获得默认蜂蜜。
		fluid = !nativeProduction || discard ? FluidStack.EMPTY : BeeFluidOutputResolver.resolveFluidOutput(type, level).copy();
		if (!fluid.isEmpty()) limit = Math.min(limit, Integer.MAX_VALUE / fluid.getAmount());
		if (spec.dynamic()) {
			// 原动态生成器以 int 周期及生产力轮数工作；保留其上限并按实际基因缩小可提交批次。
			long geneMultiplier = Math.max(1, BeeProductivityGene.adjustStackCount(1, gene));
			limit = Math.min(limit, Integer.MAX_VALUE / Math.multiplyExact(rolls, geneMultiplier));
		}
	}

	long maximumCycles(long requested) {
		return maximumCycles(requested, null);
	}

	long maximumCycles(long requested, ApiaryConversionProcessor.ConversionPlan flower) {
		long high = Math.min(Math.max(0, requested), limit);
		if (routed) return high;
		if (high == 0 || fits(high, flower)) return high;
		long low = 0;
		while (low < high) {
			long middle = (low + high + 1) >>> 1;
			if (fits(middle, flower)) low = middle;
			else high = middle - 1;
		}
		return low;
	}

	private boolean fits(long requested, ApiaryConversionProcessor.ConversionPlan flower) {
		long cycles = Math.max(0, requested - (flower != null && flower.occupiesCycle() ? 1 : 0));
		var items = new ArrayList<ItemStack>(maximum.size());
		for (var stack : maximum) items.add(stack.copyWithCount(Math.toIntExact(Math.multiplyExact(stack.getCount(), cycles))));
		// 自定义数据包也可能压缩基因。若转换改变候选，回退到完整转换上界，不能假定基因永远不转换。
		var possible = alternatives.stream().map(stack -> stack.copyWithCount(Math.toIntExact(cycles))).toList();
		if (conversion != null && !sameItems(possible, conversion.maximum(possible))) {
			items.addAll(possible);
			return prepare(withFlower(upgrade(items, true), flower), cycles) != null;
		}
		var base = prepare(withFlower(upgrade(items, true), flower), cycles);
		return base != null && base.fitsAlternatives(alternatives, cycles);
	}

	ApiaryRoutedOutput sample(long cycles, long rolls, long seed) {
		return sample(cycles, rolls, seed, List.of());
	}

	ApiaryRoutedOutput sample(long cycles, long rolls, long seed, List<ItemStack> extra) {
		var generated = tile.getProduceProcessor().prepareResourceBatch(slot, Math.toIntExact(cycles), type,
				outputs, upgrades, rolls, conversion, sampling, seed, nativeProduction);
		var items = new ArrayList<>(generated.items());
		items.addAll(extra);
		return new ApiaryRoutedOutput(tile, new BeeProduceProcessor.Generated(items, generated.fluid(), generated.fluidAmount()), upgrades);
	}

	private List<ItemStack> upgrade(List<ItemStack> items, boolean maximum) {
		return ApiaryOutputUpgrades.apply(items, discard, blocks, conversion, maximum);
	}

	private static List<ItemStack> withFlower(List<ItemStack> items, ApiaryConversionProcessor.ConversionPlan flower) {
		if (flower == null) return items;
		var result = new ArrayList<>(items);
		result.addAll(flower.maximumOutput());
		return result;
	}

	private static boolean sameItems(List<ItemStack> first, List<ItemStack> second) {
		if (first.size() != second.size()) return false;
		for (var stack : first) if (second.stream().noneMatch(other -> ItemStack.matches(stack, other))) return false;
		return true;
	}

	private ApiaryLocalOutputPlan prepare(List<ItemStack> items, long cycles) {
		var batchFluid = fluid.isEmpty() ? FluidStack.EMPTY
				: com.ayoshiko.productivebeesgenesis.util.ForgeFluidStacks.copyWithAmount(fluid, Math.toIntExact(Math.multiplyExact(fluid.getAmount(), cycles)));
		return ApiaryLocalOutputPlan.prepare(tile.getOutputSlots(), tile.getFluidTank(), items, batchFluid);
	}
}
