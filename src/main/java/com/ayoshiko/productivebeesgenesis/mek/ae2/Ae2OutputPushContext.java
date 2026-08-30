package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;

/**
 * 一轮输出推送的上下文快照（不可变） — 供逐槽/合并两条提交路径共用
 * <p>
 * 拆分 {@code Ae2OutputPusher} 时引入：两条路径各需 8 个相同的入参，
 * 用记录聚合可避免长参数列表，也让「一轮推送内这些值不再变化」这一约束显式化。
 *
 * @param host          输出宿主（离心机/蜂箱方块实体）
 * @param holder        AE2 状态持有者（账本、退避、缓存的归属）
 * @param buffers       跨 tick 复用缓冲区（含 per-tile 成本记账器）
 * @param meStorage     已解析的 ME 存储目标
 * @param keyBackoff    per-key 退避注册表
 * @param itemBackoff   宿主级物品推送退避
 * @param gameTick      当前游戏刻（预算归属）
 * @param nowNanos      本轮起始单调时钟（退避判定基准，统一取值避免同轮内漂移）
 * @param scanStart     本轮扁平槽位扫描起点
 * @param flatSlotCount 扁平槽位总数（processes × SLOTS_PER_PROCESS）
 */
record Ae2OutputPushContext(
		IAe2OutputHostBase host,
		Ae2OutputStateHolder holder,
		Ae2PushBuffers buffers,
		MEStorage meStorage,
		Ae2KeyBackoffRegistry<AEItemKey> keyBackoff,
		Ae2PushBackoff itemBackoff,
		long gameTick,
		long nowNanos,
		int scanStart,
		int flatSlotCount) {
}
