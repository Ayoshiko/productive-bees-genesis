package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.lib.inventory.HandlerTransitRequest;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 弹出清单构建器 —— 替换 Mekanism {@code InventoryUtils.getEjectItemMap} 的 O(n²) 实现。
 * <p>
 * <b>原实现的问题</b>（{@code InventoryUtils.getEjectItemMap}）：
 * <pre>
 * List&lt;IInventorySlot&gt; shuffled = new ArrayList&lt;&gt;(slots);   // 每次调用分配
 * Collections.shuffle(shuffled);                             // 每次调用洗牌
 * for (IInventorySlot slot : shuffled) {
 *     ...
 *     request.addItem(simulatedExtraction, slots.indexOf(slot));  // O(n) 线性查找 → 整体 O(n²)
 * }
 * </pre>
 * 洗牌的目的是避免固定弹出顺序造成靠后槽位饿死。原版机器只有 1-2 个输出槽时开销可忽略，
 * 但本模组最高等级工厂有 18 个进程 × 3 个物品输出槽 = 54 个槽，且弹出延迟被优化到 1 tick：
 * 每刻要付一次 {@code ArrayList} 分配 + 洗牌 + 54×54 次 identity 比较。
 * <p>
 * <b>本实现</b>：用轮转起点替代洗牌（同样避免饿死，且相邻刻的顺序稳定，便于对端缓存），
 * 遍历时直接用下标调用 {@code addItem(stack, index)}，全程 O(n)、零分配。
 * <p>
 * 语义与原版逐字对齐：同样以 {@code extractItem(count, SIMULATE, EXTERNAL)} 作为可弹出量的
 * 判定（因此本模组「超堆叠输出槽整栈弹出」的能力保持生效），同样跳过模拟结果为空的槽位。
 *
 * @since 2.1.0
 */
public final class EjectItemMapBuilder {

	private EjectItemMapBuilder() {
	}

	/**
	 * 按轮转顺序把可弹出的槽位登记进弹出清单。
	 *
	 * @param request 目标清单（原地填充）
	 * @param slots   本侧面暴露的槽位列表（下标即对端 {@code IItemHandler} 的槽位索引）
	 * @param cursor  轮转游标（调用方持有，用返回值更新）
	 * @return 下一次调用应使用的轮转游标
	 */
	public static int build(HandlerTransitRequest request, List<IInventorySlot> slots, int cursor) {
		int size = slots.size();
		if (size == 0) return 0;
		int start = RoundRobinSlotTraversal.normalize(cursor, size);
		for (int offset = 0; offset < size; offset++) {
			int index = RoundRobinSlotTraversal.index(start, offset, size);
			IInventorySlot slot = slots.get(index);
			if (slot == null || slot.isEmpty()) continue;
			// 与 Mekanism 原实现一致：用 EXTERNAL 模拟抽取判定真实可弹出量
			ItemStack simulated = slot.extractItem(slot.getCount(), Action.SIMULATE, AutomationType.EXTERNAL);
			if (!simulated.isEmpty()) request.addItem(simulated, index);
		}
		return RoundRobinSlotTraversal.advance(start, size);
	}
}
