package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * 蜂笼输入 tick 处理器
 * <br/>
 * 从 {@link ApiaryTickHandler} 拆分，负责在每服务端 tick 驱动蜂笼与蜜蜂槽之间的双向转移：
 * <ul>
 *   <li>装入蜜蜂：cageInSlot 含蜜蜂的蜂笼 → 蜜蜂转移到空 BeeSlot → 空蜂笼输出到 cageOutSlot</li>
 *   <li>取出蜜蜂：cageInSlot 空蜂笼 + 存在非空 BeeSlot → 蜜蜂转移到蜂笼 → 含蜜蜂的蜂笼输出到 cageOutSlot</li>
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

	/**
	 * 构造蜂笼输入 tick 处理器
	 *
	 * @param slotManager 槽位管理器
	 */
	CageTickProcessor(ApiarySlotManager slotManager) {
		this.slotManager = slotManager;
	}

	/**
	 * 每 tick 驱动蜂笼输入处理 — 在蜜蜂生产逻辑之前执行
	 * <br/>
	 * 确保蜜蜂从蜂笼转移到蜂槽后再由 {@link BeeSlotTickProcessor} 推进生产逻辑，
	 * 使新装入的蜜蜂能在同一 tick 开始工作。
	 */
	void tick() {
		// 处理蜂笼输入 — 蜜蜂从蜂笼转移到蜂槽（在生产逻辑前执行）
		slotManager.processCageInput();
	}
}
