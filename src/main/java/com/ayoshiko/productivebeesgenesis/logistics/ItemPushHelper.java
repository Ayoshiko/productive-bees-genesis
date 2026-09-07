package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 物品推送助手 —— 统一「先模拟、再放入」的两段式插入。
 * <p>
 * <b>为什么必须先模拟：</b>我们要在<em>动手之前</em>知道目标到底能吃下多少。
 * <ul>
 *   <li>从输出槽弹出时：先模拟得到 {@code accepted}，再从槽里精确取出这么多，
 *       避免「先取出、目标却塞不下」而必须回填的往返（回填会额外触发监听器与同步）。</li>
 *   <li>产物直通时：产物还在待提交缓冲里，先模拟能确认目标可接收，再执行插入并按
 *       <b>实际</b>插入量记账；目标违反 simulate/execute 一致性时也不会多扣产物。</li>
 *   <li>目标一点都吃不下时（0 接受）可以立刻短路，把该物品类型记入本刻拒收备忘，
 *       后续同类型槽位直接跳过，省掉整轮遍历。</li>
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
