package com.ayoshiko.productivebeesgenesis.mek;

/**
 * 工厂PB上下文委托访问接口 — 消除三个工厂的重复委托方法
 * <br/>
 * 三个工厂（{@link TileEntityMekCentrifugeFactory}、
 * {@link TileEntityExtraMekCentrifugeFactory}、
 * {@link TileEntityEMExtraMekCentrifugeFactory}）各自有约50行
 * 纯委托代码（{@code productivebeesgenesis$xxx} 方法全部转发给 {@link FactoryPbContextDelegate}）。
 * 本接口为这些方法提供默认实现，工厂只需实现 {@link #productivebeesgenesis$getDelegate()} 返回委托实例。
 * <p>
 * 继承 {@link PbRecipeContext} 和 {@link IMekCentrifugeTile} 保持接口契约不变。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只封装委托转发逻辑，不涉及槽位布局或配方处理</li>
 *   <li>开闭原则：新增工厂类型只需实现本接口 + 提供 delegate getter</li>
 * </ul>
 */
public interface IFactoryPbDelegateAccess extends PbRecipeContext, IMekCentrifugeTile {

	/** 获取工厂的PB上下文委托实例 — 供默认方法转发使用 */
	FactoryPbContextDelegate productivebeesgenesis$getDelegate();

	// ===== Task 5: 输出槽状态标志位 =====

	@Override
	default boolean productivebeesgenesis$hasOutputItems() {
		return productivebeesgenesis$getDelegate().hasOutputItems();
	}

	@Override
	default boolean productivebeesgenesis$outputSlotsFull() {
		return productivebeesgenesis$getDelegate().outputSlotsFull();
	}

	@Override
	default void productivebeesgenesis$updateOutputSlotFlags() {
		productivebeesgenesis$getDelegate().updateOutputSlotFlags();
	}

	@Override
	default boolean productivebeesgenesis$outputSlotsFull(int process) {
		return productivebeesgenesis$getDelegate().outputSlotsFull(process);
	}

	@Override
	default void productivebeesgenesis$beginOutputBatch() {
		productivebeesgenesis$getDelegate().beginOutputBatch();
	}

	@Override
	default void productivebeesgenesis$endOutputBatch(int process) {
		productivebeesgenesis$getDelegate().endOutputBatch(process);
	}

	// ===== Task 16: 输出槽内容版本号/物品总数 =====

	@Override
	default long productivebeesgenesis$outputContentsVersion() {
		return productivebeesgenesis$getDelegate().outputContentsVersion();
	}

	@Override
	default long productivebeesgenesis$outputItemCount() {
		return productivebeesgenesis$getDelegate().outputItemCount();
	}

	// ===== Task 11: 激活状态计数器 =====

	@Override
	default void productivebeesgenesis$onProcessActivated(int process) {
		productivebeesgenesis$getDelegate().onProcessActivated(process);
	}

	@Override
	default void productivebeesgenesis$onProcessDeactivated(int process) {
		productivebeesgenesis$getDelegate().onProcessDeactivated(process);
	}

	@Override
	default boolean productivebeesgenesis$hasActiveProcess() {
		return productivebeesgenesis$getDelegate().hasActiveProcess();
	}
}
