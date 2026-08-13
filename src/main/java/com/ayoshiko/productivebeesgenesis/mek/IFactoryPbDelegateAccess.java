package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;

/**
	 * 工厂PB上下文委托访问接口 — 消除三个工厂的重复委托方法
	 * <br/>
	 * 三个工厂（{@link TileEntityMekCentrifugeFactory}、
	 * {@link TileEntityExtraMekCentrifugeFactory}、
	 * {@link TileEntityEMExtraMekCentrifugeFactory}）各自有约50行
	 * 纯委托代码（{@code productivebeesgenesis$xxx} 方法全部转发给 {@link FactoryPbContextDelegate}）。
	 * 本接口为这些方法提供默认实现，工厂只需实现 {@link #productivebeesgenesis$getDelegate()} 返回委托实例。
	 * <p>
	 * 继承 {@link IAe2OutputHostBase}（间接继承 {@link PbRecipeContext}）和 {@link IMekCentrifugeTile}
	 * 保持接口契约不变。Task 3 后工厂类实现 {@link IAe2OutputHostBase} 而非 {@code IAe2OutputHost}，
	 * AE2 线缆连接契约由 Task 4 的 Mixin 动态添加。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：只封装委托转发逻辑，不涉及槽位布局或配方处理</li>
	 *   <li>开闭原则：新增工厂类型只需实现本接口 + 提供 delegate getter</li>
	 * </ul>
	 */
public interface IFactoryPbDelegateAccess extends IAe2OutputHostBase, IMekCentrifugeTile {

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

	@Override
	default void productivebeesgenesis$updateSlotOnly(int process, int slotIdx,
		mekanism.api.inventory.IInventorySlot slot) {
		productivebeesgenesis$getDelegate().updateSlotOnly(process, slotIdx, slot);
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

	// ===== 直连快速通道：输入槽访问 =====

	/** 输入槽数量 = processes()（每进程1个输入槽） */
	@Override
	default int productivebeesgenesis$getInputSlotCount() {
		return processes();
	}

	/**
	 * 获取指定索引的输入槽 — 委托给 PbRecipeContext.inputSlot(process)
	 * <br/>
	 * 工厂离心机每进程1个输入槽，索引与进程索引一一对应。
	 */
	@Override
	default mekanism.api.inventory.IInventorySlot productivebeesgenesis$getInputSlot(int index) {
		if (index < 0 || index >= processes()) return null;
		return inputSlot(index);
	}

	/**
	 * 获取工厂的 PB 配方处理器 — 供 isValidInput 默认实现检查 PB CentrifugeRecipe
	 * <br/>
	 * 工厂类实现此方法返回自身的 {@code pbProcessor} 字段。
	 * 注意:子类若重新声明 pbProcessor 字段(字段隐藏),必须 override 此方法返回自身实例,
	 * 否则会返回父类的 pbProcessor(可能为 null 或不同实例)。
	 *
	 * @return PB 配方处理器实例
	 */
	PbRecipeProcessor productivebeesgenesis$getPbProcessor();

	/**
	 * 检查物品是否为有效的离心配方输入
	 * <br/>
	 * 同时检查 SMELTING 配方(containsSmeltingInput)和 PB CentrifugeRecipe(pbProcessor.findPbRecipe),
	 * 与基础离心机 TileEntityMekCentrifuge.containsRecipe 语义一致。
	 * 修复:原实现仅调用 containsSmeltingInput,导致所有工厂离心机无法通过直连快速通道接收 PB 蜜脾。
	 * NPE 防御:getPbProcessor() 理论不应返回 null(工厂构造时即初始化),
	 * 但作为接口默认方法,需对未知实现保持防御性,避免接口契约缺陷导致 NPE 阻断整个 isValidInput 判定。
	 */
	@Override
	default boolean productivebeesgenesis$isValidInput(net.minecraft.world.item.ItemStack stack) {
		// Bug 2 修复：PB 蜜脾/蜜脾块强制走 PB 配方路径，避免 SMELTING（c:honeycombs 标签）误匹配
		// 与基础离心机 TileEntityMekCentrifuge.containsRecipe 语义一致
		if (productivebeesgenesis$isPbCombInput(stack)) {
			PbRecipeProcessor processor = productivebeesgenesis$getPbProcessor();
			return processor != null && processor.findPbRecipe(stack) != null;
		}
		// 非 PB 蜜脾：同时检查 SMELTING 配方和 PB CentrifugeRecipe
		if (containsSmeltingInput(stack)) return true;
		// NPE 防御:接口默认方法无法保证所有实现类都正确初始化 pbProcessor
		PbRecipeProcessor processor = productivebeesgenesis$getPbProcessor();
		return processor != null && processor.findPbRecipe(stack) != null;
	}
}
