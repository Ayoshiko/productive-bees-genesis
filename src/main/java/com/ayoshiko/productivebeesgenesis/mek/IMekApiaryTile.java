package com.ayoshiko.productivebeesgenesis.mek;

/**
	 * MEK 通用机械蜂箱统一标记接口。
	 * <br/>
	 * 参照 {@link IMekCentrifugeTile} 的设计模式，用于 TileComponentEjectorMixin 和
	 * TileComponentEjectorCooldownMixin 通过 instanceof 统一识别所有蜂箱类型，
	 * 避免硬依赖工厂版子类引发 ClassNotFoundException。
	 * <p>
	 * 覆盖范围（通过基类 {@code TileEntityMekApiary} 实现此接口自动覆盖所有子类）：
	 * <ul>
	 *   <li>TileEntityMekApiary（基础蜂箱）</li>
	 *   <li>TileEntityMekApiaryFactory（原版工厂蜂箱）</li>
	 *   <li>TileEntityExtraMekApiaryFactory（ME扩展工厂蜂箱）</li>
	 *   <li>TileEntityEMExtraMekApiaryFactory（EME扩展工厂蜂箱）</li>
	 * </ul>
	 * <p>
	 * 与离心机的差异：蜂箱配置项不含 skipUnchanged/skipTicks/minInterval/busyThreshold/busyCooldown，
	 * 这些节流特性在蜂箱上自动关闭（cachedSkipUnchanged=false 等），仅保留阻塞冷却与单 tick 上限。
	 * <p>
	 * 设计原则（OCP）：通过新增标记接口扩展蜂箱弹出优化，不修改离心机现有行为。
	 * 蜂箱的 ejection 配置与离心机独立，互不共享配置值。
	 *
	 * @since 2.0.0
	 */
public interface IMekApiaryTile {

	/**
	 * 输出槽内容版本号。
	 * <br/>
	 * 蜂箱不使用 skipUnchanged 节流特性，此方法返回固定值 0 即可。
	 * 保留方法以满足 Mixin 辅助方法的统一调用契约。
	 */
	long productivebeesgenesis$outputContentsVersion();

	/**
	 * 返回输出槽是否已满，供 Ejector Mixin 在输出槽满时取消跳过。
	 * <br/>
	 * 当所有物品输出槽均无剩余空间时返回 true；此时若继续跳过 outputItems，可能导致产物积压、机器停机，
	 * 因此 Mixin 会立即重置跳过计数器并尝试输出。
	 */
	boolean productivebeesgenesis$outputSlotsFull();

	/**
	 * 返回所有输出槽的物品总数，供 Ejector Mixin 替代 O(n) 遍历计数。
	 * <br/>
	 * 蜂箱输出槽数量少（9-18），直接遍历计数足够高效。
	 * 用于 Mixin 比较调用 outputItems 前后的物品总量，判断是否成功弹出。
	 */
	default long productivebeesgenesis$outputItemCount() {
		return 0L;
	}
}
