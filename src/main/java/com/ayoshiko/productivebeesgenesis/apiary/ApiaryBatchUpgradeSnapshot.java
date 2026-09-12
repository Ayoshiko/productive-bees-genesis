package com.ayoshiko.productivebeesgenesis.apiary;

/**
 * 一轮批量产出（flush）内的机器级状态快照。
 * <p>
 * <b>为什么需要</b>：{@code flushPendingProductions} 按蜜蜂类型分组后，对<b>每一个组</b>
 * 调用一次 {@link BeeProduceProcessor#processBatchProduce}。而下列取值全部是
 * <b>机器级</b>状态 —— 与蜂种分组无关，一轮 flush 内恒定：
 * <ul>
 *   <li>{@link ApiaryUpgradeCache} 的 getter：每个 getter 内部都会执行一次
 *       {@code internalCounter.incrementAndGet()}（AtomicLong 锁定自增），
 *       作为 100 次调用刷新守卫。逐组调用意味着 N 个蜂种就多付 N 次锁定操作。</li>
 *   <li>{@code getPbUpgradeInstalledCount}：PB 升级数量查询，同样与蜂种无关。</li>
 *   <li>per-tile 输出路由开关：直连 AE / 离心机优先 / 直接弹出。</li>
 * </ul>
 * <p>
 * <b>用户场景</b>：{@code 蜂箱每个蜜蜂格子都放不同种类的蜜蜂} 时，N 个槽位就是 N 个分组，
 * 逐组重复查询等于把固定开销乘以 N —— 混养相比纯养多付的正是这一部分。
 * 本快照把「每组一次」压成「每轮一次」。
 * <p>
 * <b>为什么一轮内可以缓存</b>：flush 在服务端 tick 线程内同步执行，中途不会有玩家操作
 * 改变升级组件或 per-tile 开关；升级变更走 {@code invalidateUpgradeCache()}，
 * 下一轮 flush 重建快照即可看到新值。因此不与现有失效机制冲突。
 * <p>
 * <b>关于刷新节奏</b>：{@code ApiaryUpgradeCache} 的设计意图是「外部每 tick 调用一次
 * {@code tickRefresh()}」驱动刷新；逐组 getter 调用属于计划外的额外计数，只会让刷新
 * 比设计值更频繁。收敛为每轮一次后刷新节奏回到设计值，且升级真实变更时由
 * {@code invalidate()} 立即失效，不依赖计数。
 *
 * @param geneSamplerCount            基因采样器已安装数量
 * @param productivityMultiplier      生产力倍率
 * @param discardUselessByproducts    是否安装了无用副产物升级
 * @param hasCombBlockUpgrade         是否安装了蜜脾块（BLOCK/Ω）升级
 * @param hasEssenceConversionUpgrade 是否安装了精华转化升级
 * @param directAeOutputEnabled       per-tile 直连 AE 输出开关
 * @param directContainerOutputEnabled per-tile 产物直通相邻容器开关
 * @param centrifugeDirectTransferEnabled 离心机优先 + 直接弹出同时开启（产物直连离心机）
 */
record ApiaryBatchUpgradeSnapshot(
		int geneSamplerCount,
		float productivityMultiplier,
		boolean discardUselessByproducts,
		boolean hasCombBlockUpgrade,
		boolean hasEssenceConversionUpgrade,
		boolean directAeOutputEnabled,
		boolean directContainerOutputEnabled,
		boolean centrifugeDirectTransferEnabled) {

	/**
	 * 采集当前机器级状态。
	 *
	 * @param apiary         蜂箱方块实体
	 * @param upgradeHandler 升级处理器（所有倍率查询经其缓存）
	 * @return 本轮 flush 共享的只读快照
	 */
	static ApiaryBatchUpgradeSnapshot capture(TileEntityMekApiary apiary, ApiaryUpgradeHandler upgradeHandler) {
		return new ApiaryBatchUpgradeSnapshot(
				upgradeHandler.getGeneSamplerCount(),
				upgradeHandler.getProductivityMultiplier(),
				apiary.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0,
				upgradeHandler.hasCombBlockUpgrade(),
				apiary.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION) > 0,
				apiary.isDirectAeOutputEnabled(),
				apiary.isDirectContainerOutputEnabled(),
				apiary.isCentrifugePriorityEnabled() && apiary.isDirectEjectEnabled());
	}
}
