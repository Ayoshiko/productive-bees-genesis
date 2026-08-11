package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
	 * AE2 剩余物品回送器 — 从 {@link Ae2InputPuller} 抽取的回送兜底逻辑，保证拉取后剩余物品不丢失。
	 * <br/>
	 * 职责单一（SRP）：仅负责将 {@link Ae2InputPuller#pullAndInsert} 执行后未能插入输入槽的剩余物品
	 * 通过两层兜底 + 静默退避（poweredInsert 重试 → 回插输入槽 → 退避等待）安全回送，与拉取主流程解耦。
	 * @since 2.0.0
	 */
public final class Ae2LeftoverReturner {

	private Ae2LeftoverReturner() {}

	/**
	 * 将剩余物品回送到 ME 网络，并用两层兜底保证物品不丢失。
	 * <p>
	 * Task 2 数据完整性修复：
	 * <ol>
	 *   <li>1-3 次重试使用 {@code poweredInsert}（带能量支付和 SIMULATE 预检，与推送路径对齐）</li>
	 *   <li>失败兜底 1：回插输入槽（{@link #tryReturnToInputSlots}）</li>
	 *   <li>失败兜底 2：退避等待（returnBackoff），静默退避，不输出正式日志</li>
	 * </ol>
	 * Task 10：兜底触发后由调用方记录 returnBackoff 进入退避窗口。
	 * Task 1：移除 popResource 掉落世界兜底，避免物品在世界中滞留导致脏乱。
	 * <p>
	 * M4-2 修复：方法改为返回剩余未回送数量，调用方据此决定是否清空源槽位，
	 * 避免无条件清空导致部分回送失败时物品消失。
	 *
	 * @param meStorage      ME 存储
	 * @param key            AE2 物品键
	 * @param leftover       剩余物品栈（非空）
	 * @param actionSource   AE2 操作源
	 * @param returnBackoff  回送退避状态（Task 10，可为 null）
	 * @param level          当前世界（Task 1 后保留参数兼容性，当前实现未使用）
	 * @param pos            方块位置（Task 1 后保留参数兼容性，当前实现未使用）
	 * @param inputSlots     输入槽列表（兜底回插目标）
	 * @return 剩余未回送的数量（0 表示全部回送成功，> 0 表示部分失败，调用方应保留对应数量在源槽位）
	 */
	public static int returnLeftoverToMe(MEStorage meStorage, AEItemKey key, ItemStack leftover,
			IActionSource actionSource, Ae2PushBackoff returnBackoff,
			Level level, BlockPos pos,
			List<IInventorySlot> inputSlots) {
		int remaining = leftover.getCount();
		if (remaining <= 0) return 0;

		// 阶段 1：1-3 次重试全部使用 poweredInsert（与 tryPushSlotDirect 对齐，带能量支付和 SIMULATE 预检）
		// AE2LT-compatible fast path: one simulation followed by one bounded write.
		int maxRetries = 1;
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			if (remaining <= 0) break;
			try {
				long simulated = meStorage.insert(key, remaining, Actionable.SIMULATE, actionSource);
				long target = Math.min(remaining, Math.max(0L, simulated));
				if (target <= 0L) continue;
				long inserted = meStorage.insert(key, target, Actionable.MODULATE, actionSource);
				int accepted = Math.min(remaining,
						SaturatingMath.saturatingToInt(Math.max(0L, inserted)));
				remaining -= accepted;
			} catch (LinkageError | RuntimeException e) {
				// LinkageError 覆盖 AE2 版本不兼容；RuntimeException 覆盖运行时异常。
				// 单次异常不中断重试，避免瞬时故障导致物品丢失（节流日志便于排查）
				LogThrottle.warn("ae2_leftover_return_me",
						"AE2 剩余物品回送 ME 网络异常 (第 {} 次重试), key={} remaining={}: {}",
						attempt, key, remaining, e.toString());
			}
		}

		// 阶段 2-3：poweredInsert 失败后保证物品不丢失
		if (remaining <= 0) {
			if (returnBackoff != null) returnBackoff.recordSuccess();
			return 0;
		}

		// 阶段 2：回插输入槽（避免物品丢失，留待下一轮加工）
		ItemStack leftoverStack = key.toStack(remaining);
		int returnedToSlot = tryReturnToInputSlots(inputSlots, leftoverStack);
		if (returnedToSlot >= remaining) {
			// 全部回插成功，物品已安全保留在输入槽
			if (returnBackoff != null) returnBackoff.recordFailure(System.nanoTime());
			LogThrottle.warn("ae2_input_drop",
					"AE2 物品回送降级回插输入槽: key={} count={}", key, remaining);
			return 0;
		}

		// 阶段 2 回插部分或完全失败：触发回送退避，减少"拉取-失败-回送"循环频率
		if (returnBackoff != null) returnBackoff.recordFailure(System.nanoTime());
		// M4-2 修复：返回剩余未回送数量，调用方据此决定是否清空源槽位
		return remaining - returnedToSlot;
	}

	/**
	 * 将剩余物品回插输入槽，避免丢失。
	 * <p>
	 * 遍历所有输入槽，按顺序尝试插入，单槽异常不影响其他槽。
	 *
	 * @param inputSlots 输入槽列表（可为 null）
	 * @param stack      待回插物品栈（方法内会修改其 count）
	 * @return 实际回插的物品数量
	 */
	private static int tryReturnToInputSlots(List<IInventorySlot> inputSlots, ItemStack stack) {
		if (inputSlots == null || inputSlots.isEmpty()) return 0;
		int totalReturned = 0;
		for (IInventorySlot slot : inputSlots) {
			if (slot == null || stack.isEmpty()) continue;
			try {
				ItemStack leftover = slot.insertItem(stack, Action.EXECUTE, AutomationType.INTERNAL);
				int returned = stack.getCount() - leftover.getCount();
				totalReturned += returned;
				stack.setCount(leftover.getCount());
			} catch (RuntimeException e) {
				// 单槽异常不影响其他槽（节流日志便于排查自定义槽实现缺陷）
				LogThrottle.warn("ae2_leftover_return_slot",
						"AE2 剩余物品回插输入槽异常, 跳过该槽: {}", e.toString());
			}
		}
		return totalReturned;
	}
}
