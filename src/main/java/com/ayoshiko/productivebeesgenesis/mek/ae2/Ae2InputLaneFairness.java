package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * 多类型并发拉取时的「车道 + 配额」公平分配公式（无状态纯函数，可直接单测）。
 * <p>
 * 背景：排序器 {@code Ae2InputPuller.PULL_ENTRY_ORDER} 末位按
 * {@link Ae2InputFairnessScheduler} 的窗口服务量升序，本意是让少被服务的类型先拉。
 * 但执行阶段原先没有任何上限，导致排序第一名：
 * <ul>
 *   <li>拿走整个 {@code normalQuota}（后续类型 entryQuota 恒为 0）；</li>
 *   <li>把全部输入槽占满（高堆叠槽上限千万级，永不腾空），
 *       其余类型的 {@code getSlotRemainingCapacity} 恒为 0。</li>
 * </ul>
 * 结果是过滤名单内其余物品被饿死 —— 顺序再公平也拿不到槽。本类提供两个上限
 * 把「排序公平」落实为「实际公平」：单类型场景（typeCount &lt;= 1）返回不设限的值，
 * 保持原有行为与吞吐完全不变。
 */
final class Ae2InputLaneFairness {

	/** 不设限哨兵：单类型场景返回，调用方按「无上限」处理。 */
	static final int UNLIMITED_LANES = Integer.MAX_VALUE;

	private Ae2InputLaneFairness() {
	}

	/**
	 * 单个类型本轮最多可新占用的空槽数（已被同类型占用的槽不计入，可继续续满）。
	 * <br/>
	 * 向上取整保证 slotCount &lt; typeCount 时每个类型仍至少能拿 1 条车道，
	 * 否则少数类型会永远拿不到槽。
	 *
	 * @param slotCount 输入槽总数
	 * @param typeCount 本轮参与竞争的类型数
	 */
	static int emptyLaneBudget(int slotCount, int typeCount) {
		if (typeCount <= 1) return UNLIMITED_LANES;
		if (slotCount <= 0) return 0;
		return Math.max(1, (slotCount + typeCount - 1) / typeCount);
	}

	/**
	 * 单个类型本轮的速率配额份额（向上取整，保证小配额下每类型至少 1 个）。
	 * <br/>
	 * 只用于受速率限制的普通条目；无限条目按设计忽略速率预算，不走本方法。
	 *
	 * @param normalQuota 本轮全类型共享的速率配额
	 * @param typeCount   本轮参与竞争的类型数
	 */
	static long typeQuotaShare(long normalQuota, int typeCount) {
		if (typeCount <= 1) return normalQuota;
		if (normalQuota <= 0L) return 0L;
		long types = typeCount;
		return Math.max(1L, (normalQuota + types - 1L) / types);
	}

	/**
	 * 判断当前容量规划轮是否应执行。
	 * <p>
	 * 第 0 轮始终执行：单类型场景只有这一轮。第 1 轮仅在公平上限确实截断过
	 * 候选时补齐，避免再次遍历全部「类型 x 槽位」。
	 */
	static boolean shouldRunPass(int pass, boolean fairPassTruncated) {
		return pass == 0 || fairPassTruncated;
	}
}
