package com.ayoshiko.productivebeesgenesis.capability;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 限流 IItemHandler 包装器（Task 13）
 * <br/>
 * 包装一个内部 IItemHandler，对外部通过 Capability 拉取物品的行为进行每 tick 总量限制。
 * <p>
 * 背景：AE2 ME 接口高频拉取离心机输出槽时，会触发 IContentsListener → setSortingNeeded(true)
 * → 全量排序扫描，导致主线程卡顿。此包装器在 extractItem 路径上限制单 tick 内可拉取的物品总数。
 * <p>
 * 线程模型与并发安全：
 * <ul>
 *   <li>extractItem 可被 AE2 异步线程并发调用，resetTick 由服务端主线程每 tick 调用一次</li>
 *   <li>{@code extractedThisTick} 使用 AtomicInteger 维护本 tick 已提取计数</li>
 *   <li>{@code lastResetTick} 使用 AtomicLong，配合 CAS 保证重置仅发生一次</li>
 *   <li>extractItem 采用 "CAS 占用配额 + 差额回退" 模式，原子地完成"读-判定-累加"，
 *       杜绝多线程穿插导致的超额提取</li>
 *   <li>回退配额使用 safeRelease，防止跨 tick 重置使计数器变为负值</li>
 * </ul>
 * <p>
 * 使用方式：由 BlockEntity 在 tick 时调用 {@link #resetTick(long)} 更新当前游戏刻。
 * 默认 limit=0 表示无限制（兼容现有行为，不影响正常游戏）。
 */
public class RateLimitedItemHandler implements IItemHandler {

	/** 被包装的原始 handler */
	private final IItemHandler inner;

	/** 限流值供给方（0=无限制），动态读取配置以支持运行时修改 */
	private final IntSupplier limitSupplier;

	/** 本 tick 已提取的物品总数（原子操作） */
	private final AtomicInteger extractedThisTick = new AtomicInteger(0);

	/** 上次重置计数器时的游戏刻（AtomicLong 配合 CAS 保证重置原子性） */
	private final AtomicLong lastResetTick = new AtomicLong(-1L);

	public RateLimitedItemHandler(@NotNull IItemHandler inner, @NotNull IntSupplier limitSupplier) {
		this.inner = inner;
		this.limitSupplier = limitSupplier;
	}

	/**
	 * 供 BlockEntity 在每 tick 调用，更新当前游戏刻。
	 * <br/>
	 * 当 tick 变更时重置计数器。使用 CAS 保证"判定 tick 变更 + 推进 lastResetTick"原子完成，
	 * 避免重复调用场景下多个线程同时通过判定并各自 set(0)，丢失其他线程刚累加的计数。
	 *
	 * @param currentTick 当前游戏刻（level.getGameTime()）
	 */
	public void resetTick(long currentTick) {
		long last = lastResetTick.get();
		// 仅当 tick 真正变更，且 CAS 成功推进 lastResetTick 时才重置计数器
		// CAS 成功的唯一线程独占执行 set(0)，其他并发调用者 CAS 失败后直接放弃
		if (currentTick != last && lastResetTick.compareAndSet(last, currentTick)) {
			extractedThisTick.set(0);
		}
	}

	@Override
	public int getSlots() {
		return inner.getSlots();
	}

	@NotNull
	@Override
	public ItemStack getStackInSlot(int slot) {
		return inner.getStackInSlot(slot);
	}

	/**
	 * 插入直接委托给内部 handler — 限流只针对外部拉取（extract），不影响内部插入
	 */
	@NotNull
	@Override
	public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		return inner.insertItem(slot, stack, simulate);
	}

	/**
	 * 限流核心：限制单 tick 内可提取的物品总数（线程安全，可被 AE2 异步线程并发调用）。
	 * <br/>
	 * 采用 <b>CAS 占用配额 + 差额回退</b> 模式，将"读计数-判定配额-累加预占"合并为一个原子操作：
	 * <ol>
	 *   <li>读取 limit（0=无限制），limit<=0 时直接委托</li>
	 *   <li>CAS 循环：读 prev → 若 prev>=limit 则配额用尽返回 EMPTY →
	 *       计算 attempt=min(请求量, limit-prev) → CAS(prev, prev+attempt) 成功则预占完成，失败则重试</li>
	 *   <li>调用 inner.extractItem(slot, attempt, simulate) 实际提取</li>
	 *   <li>非 simulate：若实际提取数 &lt; 预占数，safeRelease 回退差额，避免浪费配额</li>
	 *   <li>simulate：不真正消耗配额，safeRelease 回退全部预占</li>
	 * </ol>
	 * <p>
	 * CAS 原理：{@code compareAndSet(prev, prev+attempt)} 仅当计数器当前值仍为 prev 时才更新为
	 * prev+attempt；若期间被其他线程抢先更新，CAS 返回 false，循环重读后重试。
	 * 这保证任意时刻所有线程预占的配额总和不会超过 limit，从根本上消除原 check-then-act 竞态。
	 */
	@NotNull
	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		int limit = limitSupplier.getAsInt();
		// amount<=0：遵循 IItemHandler 契约直接委托；limit<=0 表示无限制，直接委托
		if (amount <= 0 || limit <= 0) {
			return inner.extractItem(slot, amount, simulate);
		}

		// 单次预占上限不超过 limit，避免一次性预占整 tick 配额造成浪费
		int need = Math.min(amount, limit);

		// CAS 占用配额：原子地完成"读-判定-累加"，杜绝多线程穿插超额
		int prev, attempt;
		do {
			prev = extractedThisTick.get();
			if (prev >= limit) {
				// 本 tick 配额已用尽，阻断拉取
				return ItemStack.EMPTY;
			}
			// 本次可预占的配额 = min(请求量, 剩余配额)
			attempt = Math.min(need, limit - prev);
			if (attempt <= 0) {
				return ItemStack.EMPTY;
			}
		} while (!extractedThisTick.compareAndSet(prev, prev + attempt));
		// 至此已原子地预占 attempt 个配额（extractedThisTick 已 +attempt）

		ItemStack extracted = inner.extractItem(slot, attempt, simulate);
		if (!simulate) {
			int actual = extracted.getCount();
			// 实际提取少于预占（如槽位物品不足）时回退差额，避免浪费配额
			if (actual < attempt) {
				safeRelease(attempt - actual);
			}
		} else {
			// simulate 模式不真正消耗配额，回退全部预占
			safeRelease(attempt);
		}
		return extracted;
	}

	@Override
	public int getSlotLimit(int slot) {
		return inner.getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return inner.isItemValid(slot, stack);
	}

	/**
	 * 安全回退配额：原子扣减且不会使计数器变为负值。
	 * <br/>
	 * 防御跨 tick 重置竞态：若 resetTick 在"预占"与"回退"之间执行 set(0)，
	 * 直接 addAndGet(-delta) 会使计数器变负，导致新 tick 首次提取可超 limit。
	 * 此处用 CAS 循环确保回退后计数器 &gt;= 0（保守策略：宁可少扣不可负值）。
	 *
	 * @param delta 需回退的配额数（&gt;0）
	 */
	private void safeRelease(int delta) {
		if (delta <= 0) {
			return;
		}
		int prev;
		do {
			prev = extractedThisTick.get();
			if (prev <= 0) {
				// 计数器已被重置或已不足，不再扣减
				return;
			}
		} while (!extractedThisTick.compareAndSet(prev, Math.max(0, prev - delta)));
	}

	/** 测试/调试用：获取本 tick 已提取数量 */
	int getExtractedThisTick() {
		return extractedThisTick.get();
	}
}
