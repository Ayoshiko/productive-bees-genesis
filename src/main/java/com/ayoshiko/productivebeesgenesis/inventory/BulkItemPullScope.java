package com.ayoshiko.productivebeesgenesis.inventory;

import java.util.function.LongSupplier;
import mekanism.common.inventory.slot.BinInventorySlot;
import net.minecraft.world.item.ItemStack;

/**
 * 本模组同步 AE2 拉取期间，允许已核对的储物实现按请求数量返回物品，避免逐组复制。
 * 不引用 AE2 类型；只保留当前线程的目标模板，退出或异常时立即恢复外层作用域。
 */
public final class BulkItemPullScope {

	private static final ThreadLocal<ItemStack> TARGET = new ThreadLocal<>();

	private BulkItemPullScope() {
	}

	public static long extract(ItemStack template, LongSupplier operation) {
		ItemStack previous = TARGET.get();
		TARGET.set(template);
		try {
			return operation.getAsLong();
		} finally {
			if (previous == null) TARGET.remove();
			else TARGET.set(previous);
		}
	}

	public static boolean allowsBin(Object slot, ItemStack stack) {
		// 子类可能有额外提取规则，只接管已核对源码的原版 Bin。
		if (slot.getClass() != BinInventorySlot.class) return false;
		return matchesTarget(stack);
	}

	public static boolean matchesTarget(ItemStack stack) {
		ItemStack target = TARGET.get();
		return target != null && !target.isEmpty()
				&& ItemStack.isSameItemSameComponents(target, stack);
	}
}
