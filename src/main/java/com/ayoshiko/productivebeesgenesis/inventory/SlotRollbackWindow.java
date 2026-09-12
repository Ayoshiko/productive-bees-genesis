package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import net.minecraft.world.item.ItemStack;

/**
 * 槽位「外部退回窗口」
 * <br/>
 * 记录本槽最近一次被外部自动化取走的物品与数量，只允许在窗口期内、按不超过该数量的
 * 同种物品退回。把「外部能否插入」的放宽严格限制在
 * 「物流模组取走后插入失败、再把剩余原样退回」这一种语义上。
 * <p>
 * <b>为什么必须同时卡物品、数量和时间</b>：只比物品类型时，任何后续新推送都会命中，
 * 输出槽等于变成半开放的输入口 —— 外部把待加工物品塞进输出槽后机器不会加工它，
 * AE2 合成 CPU 已记账却永远等不到产物，整个合成任务卡在最后一段。
 * <p>
 * <b>过期即作废</b>：窗口过期时一并释放样本引用，不让一次久远的提取长期挂账。
 * <p>
 * 线程安全：只在服务端主线程的槽位插入/提取路径上访问，无需同步。
 */
public final class SlotRollbackWindow {

	/** 退回窗口长度（刻）：1 秒。物流模组「取出 → 插入失败 → 退回」在同一逻辑流程内完成。 */
	public static final long WINDOW_TICKS = 20L;

	/** 最近一次被取走的物品样本（count=1，仅用于类型与组件比对）。 */
	private ItemStack sample = ItemStack.EMPTY;

	/** 尚可退回的数量。 */
	private int credit;

	/** 记录时的服务端刻。 */
	private long recordedTick = ServerTickClock.UNSET;

	/**
	 * 是否存在待核销的退回凭据。
	 * <p>
	 * 供插入热路径做前置短路：只读普通字段，避免在没有凭据时也去读
	 * {@link ServerTickClock#now()} 的 volatile 值。时间加速（如 JDTE 手杖 256×）
	 * 会成倍放大机器自身的槽位操作，这个短路让「从未发生外部提取」的槽位近乎零成本。
	 */
	public boolean isArmed() {
		return credit > 0;
	}

	/**
	 * 记录一次成功的外部提取，并重置本窗口。
	 * <p>
	 * <b>时间加速语义</b>：本窗口以<b>真实服务端刻</b>计时，与方块实体的虚拟刻无关。
	 * 256× 加速下同一真实刻内可能发生多次外部提取，此时后一次覆盖前一次 —— 这是正确的：
	 * 「取出 → 插入失败 → 退回」是物流模组同一次搬运流程内的配对动作，覆盖不会切断配对，
	 * 反而避免凭据被反复复用而累积出超额退回额度。
	 *
	 * @param extracted 实际被取走的物品栈；空栈直接忽略
	 * @param tick      当前服务端刻
	 */
	public void record(ItemStack extracted, long tick) {
		if (extracted.isEmpty()) return;
		this.sample = extracted.copyWithCount(1);
		this.credit = extracted.getCount();
		this.recordedTick = tick;
	}

	/**
	 * 查询针对给定物品当前可用的退回额度。
	 * <p>
	 * 窗口过期、时间回拨或物品不符时返回 0；其中过期会顺手作废额度，
	 * 使这笔提取不可能再被任何后续插入蹭用。
	 *
	 * @param stack 尝试退回的物品栈
	 * @param now   当前服务端刻
	 * @return 可用额度，范围 &gt;= 0
	 */
	public int creditFor(ItemStack stack, long now) {
		int remaining = credit;
		if (remaining <= 0) return 0;
		if (stack.isEmpty() || sample.isEmpty()) return 0;
		if (!isWithinWindow(recordedTick, now)) {
			clear();
			return 0;
		}
		// 只有与本槽刚被取走的物品完全同类型才认；数量由 credit 另行约束
		if (!ItemStack.isSameItemSameComponents(sample, stack)) return 0;
		return remaining;
	}

	/**
	 * 判断一笔提取凭据是否仍在有效窗口内。
	 * <p>
	 * 拆成纯函数便于单测覆盖四类边界：任一端未初始化、时间回拨、恰好到期与已过期。
	 * 「未初始化」判为无效而非视为永久有效，是为了让服务器刚启动或跨存档残留的读数
	 * 不可能放行任何退回。
	 * <p>
	 * 入参是<b>真实服务端刻</b>：时间加速（虚拟刻）只是把同一真实刻内的槽位操作次数放大，
	 * 不会缩短窗口，因此 256× 加速下的「取出 → 退回」配对仍稳稳落在窗口内。
	 *
	 * @param recordedTick 记录凭据时的服务端刻
	 * @param now          当前服务端刻
	 * @return 在窗口内返回 true
	 */
	static boolean isWithinWindow(long recordedTick, long now) {
		if (recordedTick == ServerTickClock.UNSET || now == ServerTickClock.UNSET) return false;
		if (now < recordedTick) return false;
		return now - recordedTick <= WINDOW_TICKS;
	}

	/**
	 * 扣减已退回的数量；额度用尽时同时释放样本引用。
	 *
	 * @param amount 实际退回量，非正数忽略
	 */
	public void consume(int amount) {
		if (amount <= 0) return;
		credit = Math.max(0, credit - amount);
		if (credit == 0) {
			sample = ItemStack.EMPTY;
		}
	}

	/** 清空窗口状态。 */
	public void clear() {
		sample = ItemStack.EMPTY;
		credit = 0;
		recordedTick = ServerTickClock.UNSET;
	}
}
