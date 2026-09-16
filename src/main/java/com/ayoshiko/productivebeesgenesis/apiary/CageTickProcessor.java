package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

/**
 * 蜂笼输入 tick 处理器
 * <br/>
 * 从 {@link ApiaryTickHandler} 拆分，负责在每服务端 tick 驱动蜂笼/刷怪蛋与蜜蜂槽之间的转移：
 * <ul>
 *   <li>装入蜜蜂：cageInSlot 含蜜蜂的蜂笼或资源蜂刷怪蛋 → 蜜蜂转移到空 BeeSlot</li>
 *   <li>取出蜜蜂：cageInSlot 空蜂笼 + 存在非空 BeeSlot → 蜜蜂转移到蜂笼 → 含蜜蜂的蜂笼输出到 cageOutSlot</li>
 *   <li>自动喂食：cageInSlot 内为带基因小食时，先由 {@link GeneTreatAutoFeeder} 挑蜜蜂喂食</li>
 * </ul>
 * <p>
 * 本处理器仅负责 tick 驱动时机（在蜜蜂生产逻辑之前执行），实际的蜂笼转移细节由
 * {@link ApiarySlotManager#processCageInput} 委托至 {@link ApiaryCageHandler} 完成，
 * 保持单一的蜂笼操作实现入口。
 * <p>
 * 线程安全：与 {@link ApiarySlotManager} 相同，服务端单线程执行。
 *
 * @since 1.0.0
 */
class CageTickProcessor {

	/** 槽位管理器引用 — 委托蜂笼输入处理 */
	private final ApiarySlotManager slotManager;

	/** 基因小食自动喂食器 — 处理小食优先于蜂笼操作 */
	private final GeneTreatAutoFeeder autoFeeder;

	/**
	 * 构造蜂笼输入 tick 处理器
	 *
	 * @param slotManager 槽位管理器
	 * @param autoFeeder  基因小食自动喂食器（由 {@link ApiaryTickHandler} 注入）
	 */
	CageTickProcessor(ApiarySlotManager slotManager, GeneTreatAutoFeeder autoFeeder) {
		this.slotManager = slotManager;
		this.autoFeeder = autoFeeder;
	}

	/**
	 * 每 tick 驱动蜂笼输入处理 — 在蜜蜂生产逻辑之前执行
	 * <br/>
	 * 先尝试自动喂食小食（若输入槽内是带基因小食），再处理蜂笼输入，
	 * 确保喂食优先于蜂笼装入/取出，且小食不会被误当蜂笼处理。
	 * 之后由 {@link BeeSlotTickProcessor} 推进生产逻辑，使新装入的蜜蜂能在同一 tick 开始工作。
	 */
	void tick() {
		// 自动喂食：cageInSlot 内若为带基因小食，按属性缺口挑蜜蜂喂食（消耗 1 个）
		// 异常已由 GeneTreatAutoFeeder 内部节流捕获，这里只兜底 Error 级别的类加载问题，
		// 避免可选依赖缺失时中断蜂笼处理。
		if (autoFeeder != null) {
			try {
				autoFeeder.tryAutoFeed();
			} catch (LinkageError e) {
				LogThrottle.warn("apiary_auto_feed_linkage",
						"基因小食自动喂食因类加载失败而跳过：{}", e.toString());
			}
		}
		// 处理蜂笼输入 — 蜜蜂从蜂笼转移到蜂槽（在生产逻辑前执行）
		slotManager.processCageInput();
	}
}
