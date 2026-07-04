package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import mekanism.api.IContentsListener;
import mekanism.common.recipe.lookup.monitor.FactoryRecipeCacheLookupMonitor;

/**
 * 工厂离心机 PB 上下文委托 — 组合封装三个工厂的公共状态和方法
 * <br/>
 * Task 10 重构：三个工厂类（{@link TileEntityMekCentrifugeFactory}、
 * {@link TileEntityExtraMekCentrifugeFactory}、{@link TileEntityEMExtraMekCentrifugeFactory}）
 * 继承不同的 Mekanism 父类，无法通过继承抽取公共逻辑。本类采用组合模式封装：
 * <ul>
 *   <li>Task 5 输出槽状态标志位（{@link OutputSlotFlagManager}）</li>
 *   <li>Task 11 激活状态计数器（{@link AtomicInteger} + boolean[]）</li>
 *   <li>Task 16 输出槽内容版本号</li>
 *   <li>Task 7 sortInventory 去抖标志</li>
 *   <li>输出槽 listener 创建（统一递增版本号 + 去抖触发排序/unpause）</li>
 * </ul>
 * 消除三个工厂约 300 行重复代码。工厂类通过委托调用本类方法，
 * 自身只保留槽位布局、配方查找、构造函数等工厂特有逻辑。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只负责工厂公共状态管理，不涉及槽位布局、配方查找</li>
 *   <li>依赖倒置：通过回调接口（{@link UnpauseCallback}）访问工厂的 lookupMonitor，不依赖具体类型</li>
 *   <li>开闭原则：新增工厂类型时只需创建本类实例并设置回调，不修改本类</li>
 * </ul>
 * <p>
 * 线程安全：方块实体在服务端单线程执行，字段无需同步锁；
 * volatile 用于保证可见性（outputContentsVersion 可能被 Ejector Mixin 读取）。
 */
public class FactoryPbContextDelegate {

	/** 输出槽标志位管理器 — 维护 hasOutputItems/outputSlotsFull/outputItemCount */
	private final OutputSlotFlagManager outputSlotFlagManager;

	/** 激活进程计数器 — O(1) 判断整体激活状态，替代 O(processes) 遍历 */
	private final AtomicInteger activeProcessCount = new AtomicInteger(0);

	/** 每进程 PB 激活状态跟踪（CAS 状态守卫防重复计数；0=false/1=true） */
	private final java.util.concurrent.atomic.AtomicIntegerArray pbActiveStates;

	/** 输出槽内容版本号（输出槽内容变更时递增，供 Ejector Mixin 判断是否跳过 outputItems） */
	private volatile long outputContentsVersion = 0L;

	/** sortInventory 去抖标志（同 tick 内只标记一次 sortingNeeded，避免 AE2 高频拉取触发全量排序） */
	private volatile boolean sortingMarkedThisTick = false;

	/** addSlots 中传入的排序监听器，输出槽变更/批量结束时需要触发 */
	@Nullable
	private IContentsListener updateSortingListener;

	/** recipe cache lookup monitor 的 unpause 回调（工厂通过 lambda 提供边界检查） */
	@Nullable
	private UnpauseCallback unpauseCallback;

	/**
	 * recipe cache unpause 回调接口
	 * <br/>
	 * 三个工厂的 recipeCacheLookupMonitors 数组类型相同（FactoryRecipeCacheLookupMonitor<?>[]），
	 * 但获取方式不同（原版直接访问，ME/EME 需要 unchecked cast）。通过回调接口统一访问，
	 * 避免本类依赖具体工厂类型。
	 */
	@FunctionalInterface
	public interface UnpauseCallback {
		void unpause(int process);
	}

	/**
	 * 完整构造函数 — 在 addSlots() 中调用（此时 tier.processes 和 this 都可用）
	 */
	public FactoryPbContextDelegate(PbRecipeContext context) {
		this.outputSlotFlagManager = new OutputSlotFlagManager(context);
		this.pbActiveStates = new java.util.concurrent.atomic.AtomicIntegerArray(context.processes());
	}

	/**
	 * 工厂方法 — 一步完成委托创建、排序监听器设置和 unpause 回关注册
	 * <br/>
	 * 抽取三个工厂 addSlots 中重复的初始化代码：
	 * <ol>
	 *   <li>构造委托实例</li>
	 *   <li>设置排序监听器（输出槽变更时去抖触发排序）</li>
	 *   <li>设置 unpause 回调（输出槽变更时解除对应进程的配方缓存暂停）</li>
	 * </ol>
	 *
	 * @param context                 PB上下文（工厂实现）
	 * @param updateSortingListener   排序监听器（addSlots 中传入）
	 * @param recipeCacheLookupMonitors 配方缓存查找监视器数组（工厂父类字段，用于 unpause 调用）
	 * @return 配置好的委托实例
	 */
	public static FactoryPbContextDelegate create(
			PbRecipeContext context,
			@Nullable IContentsListener updateSortingListener,
			FactoryRecipeCacheLookupMonitor<?>[] recipeCacheLookupMonitors) {
		FactoryPbContextDelegate delegate = new FactoryPbContextDelegate(context);
		delegate.setUpdateSortingListener(updateSortingListener);
		delegate.setUnpauseCallback(process -> {
			if (process >= 0 && process < recipeCacheLookupMonitors.length) {
				recipeCacheLookupMonitors[process].unpause();
			}
		});
		return delegate;
	}

	/**
	 * 设置排序监听器（addSlots 中传入的 updateSortingListener）
	 * <br/>
	 * 输出槽变更或批量结束时触发，标记 sortingNeeded。
	 */
	public void setUpdateSortingListener(@Nullable IContentsListener listener) {
		this.updateSortingListener = listener;
	}

	/**
	 * 设置 unpause 回调（工厂通过 lambda 提供 recipeCacheLookupMonitors[process].unpause()）
	 * <br/>
	 * 回调内部应做边界检查（process >= 0 && process < recipeCacheLookupMonitors.length）。
	 */
	public void setUnpauseCallback(@Nullable UnpauseCallback callback) {
		this.unpauseCallback = callback;
	}

	/**
	 * 创建输出槽内容变更监听器
	 * <br/>
	 * 在 addSlots 中为每个进程的输出槽创建 listener。封装公共逻辑：
	 * <ol>
	 *   <li>通知 {@link OutputSlotFlagManager} 槽位变更（批量模式下只标记 dirty）</li>
	 *   <li>递增 {@link #outputContentsVersion}（通知 Ejector Mixin 需要重新尝试输出）</li>
	 *   <li>去抖触发排序（同 tick 内只触发一次，避免 AE2 高频拉取触发全量排序）</li>
	 *   <li>独立触发 unpause（每进程独立，不被 sorting 去抖抑制）</li>
	 * </ol>
	 *
	 * @param process 进程索引（用于 unpause 对应进程的 lookupMonitor）
	 * @return 输出槽 IContentsListener
	 */
	public IContentsListener createOutputSlotListener(int process) {
		return () -> {
			outputSlotFlagManager.onSlotChanged();
			notifyOutputChanged(process);
		};
	}

	/**
	 * 通知输出槽内容变更（递增版本号 + 去抖触发排序 + 独立触发 unpause）
	 * <br/>
	 * sorting 去抖：同 tick 内只标记一次 sortingNeeded，避免 AE2 高频拉取触发全量排序。
	 * unpause 独立于 sorting 去抖：每进程独立触发，确保多进程同 tick 输出时都能恢复配方查找。
	 * 用于 listener 和 {@link #endOutputBatch} 的公共逻辑。
	 */
	private void notifyOutputChanged(int process) {
		outputContentsVersion++;
		if (!sortingMarkedThisTick) {
			sortingMarkedThisTick = true;
			if (updateSortingListener != null) {
				updateSortingListener.onContentsChanged();
			}
		}
		// unpause 独立于 sorting 去抖，每进程独立触发
		if (unpauseCallback != null) {
			unpauseCallback.unpause(process);
		}
	}

	// ===== Task 5: 输出槽状态标志位方法 =====

	/** 是否有任意输出槽含物品（供 EjectorMixin 读取） */
	public boolean hasOutputItems() {
		return outputSlotFlagManager.hasOutputItems();
	}

	/** 是否有任意进程的所有物品输出槽已满（供 EjectorMixin 自适应保护使用） */
	public boolean outputSlotsFull() {
		return outputSlotFlagManager.outputSlotsFull();
	}

	/** 指定进程的所有物品输出槽是否已满（供 PbRecipeProcessor 按进程判断） */
	public boolean outputSlotsFull(int process) {
		return outputSlotFlagManager.outputSlotsFull(process);
	}

	/** 所有输出槽的物品总数（O(1) 读取，供 Ejector Mixin 替代 countOutputItems 遍历） */
	public long outputItemCount() {
		return outputSlotFlagManager.outputItemCount();
	}

	/** 遍历所有进程的输出槽重新计算标志位（由输出槽 IContentsListener 触发，外部插入/初始化用） */
	public void updateOutputSlotFlags() {
		outputSlotFlagManager.updateAll();
	}

	/** 开始批量输出插入；嵌套调用安全 */
	public void beginOutputBatch() {
		outputSlotFlagManager.beginBatch();
	}

	/**
	 * 结束批量输出插入
	 * <br/>
	 * 批量结束时统一更新标志位、递增版本号、去抖触发排序 + 独立触发 unpause。
	 *
	 * @param process 发生变化的进程索引
	 */
	public void endOutputBatch(int process) {
		if (outputSlotFlagManager.endBatch(process)) {
			notifyOutputChanged(process);
		}
	}

	/** 输出槽内容版本号（供 Ejector Mixin 判断是否跳过 outputItems） */
	public long outputContentsVersion() {
		return outputContentsVersion;
	}

	// ===== Task 11: 激活状态计数器方法 =====

	/**
	 * 进程激活时调用（递增计数器）
	 * <br/>
	 * 使用状态守卫防止重复递增：仅状态 false→true 时递增计数器。
	 */
	public void onProcessActivated(int process) {
		MekCentrifugeFactoryHelper.onProcessActivated(process, pbActiveStates, activeProcessCount);
	}

	/**
	 * 进程失活时调用（递减计数器）
	 * <br/>
	 * 使用状态守卫防止重复递减：仅状态 true→false 时递减计数器。
	 */
	public void onProcessDeactivated(int process) {
		MekCentrifugeFactoryHelper.onProcessDeactivated(process, pbActiveStates, activeProcessCount);
	}

	/** 是否有任意 PB 进程激活（O(1) 计数器读取，替代 O(processes) 遍历） */
	public boolean hasActiveProcess() {
		return activeProcessCount.get() > 0;
	}

	// ===== Task 7: sortInventory 去抖 =====

	/**
	 * 重置去抖标志（每 tick 开始时调用）
	 * <br/>
	 * 允许本 tick 重新标记 sortingNeeded。
	 */
	public void resetSortingMark() {
		sortingMarkedThisTick = false;
	}
}