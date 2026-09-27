package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 物品推送助手，提供模拟容量与执行插入两个入口。
 * <p>
 * 输出槽先模拟再抽取；缓冲产物直出可直接插入拷贝，按实际剩余量记账。
 * <ul>
 *   <li>从输出槽弹出时：先模拟得到 {@code accepted}，再从槽里精确取出这么多，
 *       避免「先取出、目标却塞不下」而必须回填的往返（回填会额外触发监听器与同步）。</li>
 *   <li>目标拒收时可记住相同组件与数量的请求，避免本刻反复遍历。</li>
 * </ul>
 * <p>
 * 使用 {@link ItemHandlerHelper#insertItemStacked} 而不是逐槽 {@code insertItem}：
 * 它先填同类型已有堆叠、再用空槽，与玩家手动 Shift 放入的行为一致，
 * 并且天然支持数量超过 {@code maxStackSize} 的超堆叠（我们的输出槽会给出这种堆叠）。
 *
 * @since 2.1.0
 */
public final class ItemPushHelper {

	private ItemPushHelper() {
	}

	/**
	 * 模拟插入，返回目标能接收的数量。
	 *
	 * @param target 目标物品处理器
	 * @param stack  待插入物品（不会被修改）
	 * @return 可接收数量（0 表示塞不下）
	 */
	public static int simulateInsert(IItemHandler target, ItemStack stack) {
		if (target == null || stack.isEmpty()) return 0;
		ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, stack, true);
		int remaining = remainder.isEmpty() ? 0 : remainder.getCount();
		return Math.max(0, stack.getCount() - remaining);
	}

	/**
	 * 执行插入，返回未被接收的剩余部分。
	 *
	 * @param target 目标物品处理器
	 * @param stack  待插入物品（不会被修改）
	 * @return 剩余物品（空表示全部插入）
	 */
	public static ItemStack insert(IItemHandler target, ItemStack stack) {
		if (target == null || stack.isEmpty()) return stack;
		return ItemHandlerHelper.insertItemStacked(target, stack, false);
	}
}
