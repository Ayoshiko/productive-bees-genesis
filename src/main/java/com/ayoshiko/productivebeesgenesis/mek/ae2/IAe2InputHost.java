package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import mekanism.api.inventory.IInventorySlot;

import java.util.List;

/**
	 * AE2 输入拉取宿主接口 — 离心机从 AE2 网络拉取蜜脾到输入槽的契约
	 * <br/>
	 * 继承 {@link IAe2OutputHostBase} 复用网格节点、状态持有者、能量源等基础方法，
	 * 不继承 {@link IAe2OutputHost}（避免强引用 AE2 的 IInWorldGridNodeHost）。
	 * <p>
	 * <b>类加载安全</b>：本接口无任何 import appeng 语句，TileEntity 实现本接口后
	 * 即使 AE2 未安装也能正常加载。AE2 网格访问通过 Object 类型 + instanceof 检查完成。
	 * <p>
	 * <b>对称设计</b>：与 IAe2OutputHost 平级，由同一 TileEntity 同时实现。
	 * 推送用 poweredInsert，拉取用 poweredExtraction；推送遍历输出槽，拉取遍历 MEStorage。
	 * <p>
	 * 所有方法使用 productivebeesgenesis$ 前缀避免 Mixin 冲突。
	 *
	 * @since 2.0.0
	 */
public interface IAe2InputHost extends IAe2OutputHostBase {

	/**
	 * 输入拉取是否启用 — 全局配置 AND per-tile 开关
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.isInputPullEnabled()，
	 * 由 holder 内部判断 cachedInputPullEnabled (全局) && aeItemInputEnabled (per-tile)。
	 * 配置缓存通过 refreshConfigCache 每 100 tick 刷新一次。
	 *
	 * @return true 表示拉取功能已启用
	 */
	default boolean productivebeesgenesis$isInputPullEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isInputPullEnabled();
	}

	/**
	 * per-tile 拉取开关 getter
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.isAeItemInputEnabled()。
	 */
	default boolean productivebeesgenesis$isAeItemInputEnabled() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null && holder.isAeItemInputEnabled();
	}

	/**
	 * per-tile 拉取开关 setter
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.setAeItemInputEnabled(boolean)。
	 */
	default void productivebeesgenesis$setAeItemInputEnabled(boolean enabled) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.setAeItemInputEnabled(enabled);
	}

	/**
	 * per-tile NBT 忽略开关 getter
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.isAeInputNbtIgnore()。
	 * holder 为 null 时回退 true（与 nbtIgnore 字段默认值一致）。
	 */
	default boolean productivebeesgenesis$isAeInputNbtIgnore() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder == null || holder.isAeInputNbtIgnore();
	}

	/**
	 * per-tile NBT 忽略开关 setter
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.setAeInputNbtIgnore(boolean)。
	 */
	default void productivebeesgenesis$setAeInputNbtIgnore(boolean ignore) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.setAeInputNbtIgnore(ignore);
	}

	/**
	 * 切换 per-tile 拉取开关（取反当前状态）
	 * <br/>
	 * 实现类需在切换后调用 markForSave() 持久化状态。
	 */
	void productivebeesgenesis$toggleAeItemInput();

	/**
	 * 切换 per-tile NBT 忽略开关（取反当前状态）
	 * <br/>
	 * 实现类需在切换后调用 markForSave() 持久化状态。
	 */
	void productivebeesgenesis$toggleAeInputNbtIgnore();

	/**
	 * 获取上次拉取的游戏刻
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.getLastPullTick()。
	 * holder 为 null 时返回 Long.MIN_VALUE（表示从未拉取）。
	 */
	default long productivebeesgenesis$getLastPullTick() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder == null ? Long.MIN_VALUE : holder.getLastPullTick();
	}

	/**
	 * 更新上次拉取的游戏刻
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.updateLastPullTick(long)。
	 */
	default void productivebeesgenesis$updateLastPullTick(long tick) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.updateLastPullTick(tick);
	}

	/**
	 * Task 12：递增内部调用计数器并返回新值
	 * <br/>
	 * 替代 getGameTime 作为节流依据，兼容 JDTE 加速。
	 */
	default long productivebeesgenesis$incrementPullCallCounter() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null ? holder.incrementPullCallCounter() : 0L;
	}

	/** Task 12：获取上次实际拉取时的计数器值 */
	default long productivebeesgenesis$getLastPullCounter() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null ? holder.getLastPullCounter() : Long.MIN_VALUE / 2;
	}

	/**
	 * Task 12：更新上次实际拉取时的计数器值
	 *
	 * @param counter 拉取时的计数器值
	 */
	default void productivebeesgenesis$updateLastPullCounter(long counter) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.updateLastPullCounter(counter);
	}

	/**
	 * 获取用于拉取的输入槽列表
	 * <br/>
	 * 基础离心机返回单元素列表（1个输入槽），
	 * 工厂版返回多元素列表（每进程1个输入槽）。
	 * 拉取器按顺序填充，槽满则跳到下一个。
	 *
	 * @return 输入槽列表（非空，按填充优先级排序）
	 */
	List<IInventorySlot> productivebeesgenesis$getInputSlotsForPull();

	/**
	 * 获取输入过滤器
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.getOrCreateInputFilter()，
	 * 返回 per-tile 持有的过滤器实例（懒初始化）。
	 *
	 * @return 过滤器实例，holder 为 null 时返回 null
	 */
	default Ae2InputFilter productivebeesgenesis$getAeInputFilter() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null ? holder.getOrCreateInputFilter() : null;
	}

	/**
	 * 获取并递增类型轮转索引（用于 N > processCount 时的类型轮转）
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder。
	 * 每次调用返回当前索引并将索引递增 processCount（mod total）。
	 *
	 * @param processCount 进程数（每次轮转处理的类型数）
	 * @param total 可用类型总数
	 * @return 当前轮转起始索引（范围 [0, total)）
	 */
	default int productivebeesgenesis$getAndIncrementTypeRotation(int processCount, int total) {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return 0;
		return holder.getAndIncrementTypeRotation(processCount, total);
	}

	/**
	 * 获取加速倍率检测器
	 * <br/>
	 * 默认实现委托给 Ae2OutputStateHolder.getTickAccelTracker()。
	 * 调用方需在 onUpdateServer 入口或 pullInputs 入口处调用 tracker.onTick(level)，
	 * 然后在节流逻辑中读取 tracker.getMultiplier()。
	 * <p>
	 * 用于自动检测 JDT、加速火把、IF:Souls、JDTE、EAEP 等加速模组，
	 * 跟踪同一 level.getGameTime() 内被调用的次数作为加速倍率 M。
	 *
	 * @return TickAccelTracker 实例，holder 为 null 时返回 null
	 */
	default TickAccelTracker productivebeesgenesis$getTickAccelTracker() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null ? holder.getTickAccelTracker() : null;
	}

	/**
	 * 获取输入回送退避状态（Task 10）
	 * <br/>
	 * 默认实现委托给 Ae2PushStateHolder.getReturnBackoff()。
	 * 用于 Ae2InputPuller 回送失败后的指数退避，减少"拉取-失败-回送"循环频率。
	 *
	 * @return 回送退避状态，holder 为 null 时返回 null
	 */
	default Ae2PushBackoff productivebeesgenesis$getReturnBackoff() {
		Ae2OutputStateHolder holder = productivebeesgenesis$getAe2StateHolder();
		return holder != null ? holder.getPushState().getReturnBackoff() : null;
	}
}
