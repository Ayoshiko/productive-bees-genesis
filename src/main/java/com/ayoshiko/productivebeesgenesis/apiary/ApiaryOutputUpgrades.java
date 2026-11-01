package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 普通生产与扩展资源共用的物品后处理；输入列表由本次调用独占。 */
final class ApiaryOutputUpgrades {
	private static final CombBlockConverter COMBS = new CombBlockConverter();
	private ApiaryOutputUpgrades() {}

	static List<ItemStack> apply(List<ItemStack> items, boolean discard, boolean blocks,
			EssenceConversionUpgradeHelper.BatchPlan conversion, boolean maximum) {
		if (discard) items.removeIf(UselessByproductUpgradeHelper::isPollenPuff);
		if (blocks) items = COMBS.convertCombsToBlocksInPlace(items);
		return conversion == null ? items : maximum ? conversion.maximum(items) : conversion.convert(items);
	}
}
