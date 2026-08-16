package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

/**
	 * PB配方处理上下文接口
	 * <br/>
	 * 定义 Factory TileEntity 需要向 {@link PbRecipeProcessor} 提供的依赖。
	 * 三个 Factory TileEntity 实现此接口，将 PB 配方处理委托给 PbRecipeProcessor，
	 * 消除约400行重复代码。
	 * <p>
	 * 遵循依赖倒置原则：PbRecipeProcessor 依赖此抽象而非具体 TileEntity，
	 * 降低耦合度，便于后续扩展新的工厂类型。
	 * <p>
	 * <b>诊断优先(Task 3):</b>default 方法添加 DEV 日志,确认 SINGLE 模式下的流体槽查找行为。
	 * MULTI 模式下工厂类重写这些方法,日志在工厂类中添加。
	 */
public interface PbRecipeContext {

	/** Marks context-owned transient processing state for persistence. */
	default void productivebeesgenesis$markForSave() {
		if (this instanceof BlockEntity blockEntity) {
			blockEntity.setChanged();
		}
	}

	/** 获取世界实例（用于配方查找和随机数） */
	Level level();

	/**
	 * 获取能量容器（用于能量检查和消耗）
	 * <br/>
	 * 返回 MachineEnergyContainer 而非 IEnergyContainer，
	 * 因为 PB 处理需要调用 getEnergyPerTick() 获取每 tick 能量消耗。
	 */
	MachineEnergyContainer<?> energyContainer();

	/**
	 * 是否安装了 MEKExtras 创造升级
	 * <br/>
	 * Task 1.3 修复：CREATIVE 升级提供零能量消耗。PB 配方处理路径
	 * （{@link PbRecipeProcessor}）不依赖 MEKExtras Mixin 自动清零
	 * {@code getEnergyPerTick}（Mixin 加载时序可能失效），
	 * 改为手动检查作为兜底，与蜂箱的 {@code ApiaryUpgradeHandler.getEnergyMultiplier} 一致。
	 * <p>
	 * 通过 {@link MekUpgradeSupport#hasCreativeUpgrade} 门面间接访问，
	 * MEKExtras 未加载时安全返回 false。
	 *
	 * @return true 如果安装了 CREATIVE 升级
	 */
	boolean hasCreativeUpgrade();

	/** 获取指定进程的输入槽 */
	IInventorySlot inputSlot(int process);

	/** 获取指定进程的主输出槽 */
	IInventorySlot primaryOutputSlot(int process);

	/** 获取指定进程的副输出槽1（可能为null） */
	IInventorySlot secondaryOutputSlot(int process);

	/** 获取指定进程的副输出槽2 */
	IInventorySlot tertiaryOutputSlot(int process);

	/** 获取共享流体输出槽 */
	IExtendedFluidTank fluidOutputTank();

	// ===== Task 9: 多槽流体输出路由（MULTI_PER_FLUID 模式） =====

	/**
	 * 返回适合插入指定流体的输出槽
	 * <br/>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式（默认）：使用此默认实现，等价于 {@link #fluidOutputTank()}，
	 *       行为与修改前完全一致</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法，委托给
	 *       {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder#getTankForInsert}
	 *       实现按流体类型路由</li>
	 * <li>基础机器（{@link TileEntityMekCentrifuge}）/
	 * 蜂箱（{@link com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbRecipeContextAdapter}）：
	 *       单槽场景，使用默认实现</li>
	 * </ul>
	 *
	 * @param stack 待插入流体（仅取类型信息，不修改）
	 * @return 目标槽；默认实现始终返回主槽
	 */
	default IExtendedFluidTank fluidOutputTankForInsert(FluidStack stack) {
		IExtendedFluidTank result = fluidOutputTank();
		// 诊断优先(Task 3):SINGLE 模式查找插入槽日志(MULTI 模式由工厂类重写记录)
		if (result == null) {
			DevLog.warn("fluid_tank", "找不到合适槽位(SINGLE) incoming={}", stack.getFluid());
		}
		return result;
	}

	/**
	 * 返回流体输出槽总数
	 * <br/>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式：使用此默认实现，返回 1</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法，返回
	 *       {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder#getTankCount}</li>
	 * </ul>
	 *
	 * @return 流体输出槽总数（默认 1）
	 */
	default int fluidOutputTankCount() {
		return 1;
	}

	/**
	 * 按索引返回流体输出槽
	 * <br/>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式：使用此默认实现，忽略 index 返回主槽，
	 *       行为与 {@link #fluidOutputTank()} 一致</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法，返回
	 *       {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder#getTanks()}
	 *       中指定索引的槽位（供 Ejector / AE2 推送遍历）</li>
	 * </ul>
	 *
	 * @param index 槽位索引（0-based）
	 * @return 指定索引的槽位；默认实现忽略 index 返回主槽
	 */
	default IExtendedFluidTank fluidOutputTank(int index) {
		return fluidOutputTank();
	}

	/**
	 * 检查指定流体是否与当前流体输出槽类型不匹配（无法插入）
	 * <br/>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式：默认实现返回 false（不检查），由工厂类重写为
	 *       "主槽非空且类型不匹配 → true"，避免无限重试浪费 TPS</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法，委托给
	 *       {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder#isTypeMismatch}
	 *       判断"无匹配槽且已达槽位上限 → true"</li>
	 * </ul>
	 * <p>
	 * 供 {@link PbRecipeOutputChecker#isOutputBlocked} 判断是否因类型不匹配
	 * 触发暂停（Task 12 方案 F）。
	 *
	 * @param stack 待检查流体（仅取类型信息，不修改）
	 * @return true 若类型不匹配且无法插入；默认 false
	 */
	default boolean isFluidTankTypeMismatch(FluidStack stack) {
		return false;
	}

	/**
	 * 检查所有已分配的流体输出槽是否都已满载
	 * <br/>
	 * <b>Task 4 修复背景：</b>原 {@code PbRecipeProcessor.cachedFluidTankFull} 仅检查主槽,
	 * 在 MULTI_PER_FLUID 模式下其他槽可能仍有空间但被错误判定为满,导致机器暂停。
	 * <p>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式（默认）：使用此默认实现,等价于检查主槽
	 *       {@code fluidOutputTank().getFluidAmount() >= fluidOutputTank().getCapacity()},
	 *       行为与修改前完全一致</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法,遍历所有已分配槽检查满载状态</li>
	 * </ul>
	 * <p>
	 * <b>暂停语义：</b>仅"所有槽都满载"为 true,仍需配合
	 * {@link #canAllocateNewFluidTank()} 判断是否还能分配新槽接收新流体类型。
	 *
	 * @return true 如果所有已分配的流体输出槽都满载;默认实现仅检查主槽
	 */
	default boolean areAllFluidTanksFull() {
		IExtendedFluidTank tank = fluidOutputTank();
		boolean result = tank != null && tank.getFluidAmount() >= tank.getCapacity();
		// 诊断优先(Task 3):SINGLE 模式所有槽满检查(MULTI 模式由工厂类重写记录)
		return result;
	}

	/**
	 * 检查是否还能分配新槽位接收新流体类型
	 * <br/>
	 * <b>Task 4 修复背景：</b>MULTI_PER_FLUID 模式下即使所有已分配槽都满载,
	 * 只要还能分配新槽（未达 maxTanks 上限）,机器也不应暂停 — 新流体类型可写入新槽。
	 * <p>
	 * <b>向后兼容设计：</b>
	 * <ul>
	 *   <li>SINGLE 模式（默认）：返回 false,无扩展能力,主槽满即暂停</li>
	 *   <li>MULTI_PER_FLUID 模式：工厂类重写此方法,返回
	 *       {@code holder.getTankCount() < holder.getMaxTanks()}</li>
	 * </ul>
	 * <p>
	 * <b>暂停语义：</b>"所有槽满载 且 无法分配新槽" 才触发暂停,
	 * 即 {@code areAllFluidTanksFull() && !canAllocateNewFluidTank()}。
	 *
	 * @return true 如果可以分配新槽;SINGLE 模式默认返回 false
	 */
	default boolean canAllocateNewFluidTank() {
		boolean result = false;
		// 诊断优先(Task 3):SINGLE 模式始终返回 false(MULTI 模式由工厂类重写记录)
		return result;
	}

	/** 获取进程总数 */
	int processes();

	/** 获取基础处理时间（BASE_TICKS_REQUIRED） */
	int baseTicksRequired();

	/** 工厂是否能运行（红石控制等） */
	boolean canFunction();

	/**
	 * 设置指定进程的PB激活状态
	 * <br/>
	 * 注意：不直接使用 setActiveState 名称，因为父类的 setActiveState 是 protected，
	 * 接口方法必须是 public，会导致访问权限冲突。
	 * 实现方在此方法内部调用 setActiveState 即可。
	 */
	void setPbActiveState(boolean active, int process);

	/** 获取生产力倍率（影响输出数量和输入消耗） */
	int productivityModifier();

	/**
	 * 获取 PB 原版产量升级带来的并行数。
	 * <br/>
	 * 原版离心机按升级等级使用 4/8/16/32 并行；本实现不设置额外并行上限，
	 * 与 PB 原版 CentrifugeBlockEntity 的 getProductivityModifier 语义一致。
	 * 蜂箱上下文不使用此接口。
	 */
	default int productivityParallelModifier() {
		return 1;
	}

	/** 为当前活跃配方预留一个对应流体槽；单槽机器无需处理。 */
	default void reserveFluidOutputType(FluidStack stack) {
		// no-op
	}

	/**
	 * 获取稳定性概率加成 — 提升非保底产物的产出概率
	 * <br/>
	 * 仅离心机生效（蜂箱不支持 STABILITY 升级）。加成公式：{@code (已装数+1) × 0.15}，
	 * 截断到 1.0。默认 0.0 表示未安装 stability 升级，不影响概率判定。
	 * <p>
	 * <b>性能</b>：由 {@link PbRecipeCompleter} 在每次 accumulate 入口调用一次（循环外），
	 * 不在概率判定循环内重复查询。
	 *
	 * @return 稳定性概率加成 [0.0, 1.0]，默认 0.0
	 */
	default float stabilityBonus() {
		return 0.0f;
	}

	/** Whether this machine currently discards honey and pollen-puff byproducts. */
	default boolean suppressesUselessByproducts() {
		return this instanceof IPbUpgradeProvider provider
				&& provider.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;
	}

	/** 新产物是否优先直接写入 AE；默认关闭，保持原本地输出槽行为。 */
	default boolean productivebeesgenesis$isDirectAeOutputEnabled() {
		return false;
	}

	/**
	 * 尝试将一项新生成的物品直接写入 AE。
	 *
	 * @return AE 实际接收数量；默认 0 表示全部回退本地输出槽
	 */
	default int productivebeesgenesis$pushGeneratedItemToAe(ItemStack stack) {
		return 0;
	}

	/**
	 * 尝试将新生成的流体直接写入 AE。
	 *
	 * @return AE 实际接收量；默认 0 表示全部回退本地流体槽
	 */
	default long productivebeesgenesis$pushGeneratedFluidToAe(FluidStack stack, long amount) {
		return 0L;
	}

	/** Simulates direct AE fluid acceptance without mutating the network. */
	default long productivebeesgenesis$simulateGeneratedFluidToAe(FluidStack stack, long amount) {
		return 0L;
	}

	/**
	 * Called after local fluid reaches the high-water mark for the current output batch.
	 * Implementations with an enabled AE2 fluid output may drain before the current
	 * high-parallel batch attempts its next local commit. The default keeps non-AE hosts inert.
	 */
	default void productivebeesgenesis$onLocalFluidOutputCommitted() {
	}

	/**
	 * 获取每tick操作次数（受速度升级影响）
	 * <br/>
	 * 对应 MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1)，
	 * MU扩展下可大于1，未加载MU时返回1。
	 */
	int operationsPerTick();

	/**
	 * 根据基础时间计算受速度升级影响的实际处理时间
	 * <br/>
	 * 对应 MekanismUtils.getTicks(this, baseTime)。
	 *
	 * @param baseTime 基础处理时间
	 * @return 受升级影响的实际处理时间
	 */
	int getTicksForBase(int baseTime);

	/**
	 * 检查输入物品是否有SMELTING配方（用于PB处理前的优先级判断）
	 * <br/>
	 * 对应 getRecipeType().getInputCache().containsInput(level, input)。
	 *
	 * @param input 输入物品
	 * @return true 如果存在SMELTING配方
	 */
	boolean containsSmeltingInput(ItemStack input);

	// ===== Task 5: 输出槽状态标志位 =====

	/** 输出槽是否有物品（供 EjectorMixin 读取，判断是否需要弹出） */
	boolean productivebeesgenesis$hasOutputItems();

	/** 输出槽是否已满（供 PbRecipeOutputChecker.isOutputBlocked 读取，存在任意进程满时为true） */
	boolean productivebeesgenesis$outputSlotsFull();

	/**
	 * 输出槽是否已满（按进程）
	 * <br/>
	 * 供 {@link PbRecipeProcessor} 判断指定进程的输出槽是否已满，避免全局标志导致
	 * 单个进程输出槽满时所有进程暂停。
	 *
	 * @param process 进程索引
	 * @return true 如果该进程的所有物品输出槽均无剩余空间
	 */
	default boolean productivebeesgenesis$outputSlotsFull(int process) {
		return productivebeesgenesis$outputSlotsFull();
	}

	/** 重新计算输出槽状态标志（在输出槽 IContentsListener 中调用） */
	void productivebeesgenesis$updateOutputSlotFlags();

	/**
	 * 开始批量输出插入
	 * <br/>
	 * 在 {@link PbRecipeProcessor} 完成一次 PB 配方输出前调用，
	 * 期间输出槽 listener 只标记 dirty，避免每次 insertItem 都全量扫描。
	 */
	void productivebeesgenesis$beginOutputBatch();

	/**
	 * 结束批量输出插入
	 *
	 * @param process 发生变化的进程索引
	 */
	void productivebeesgenesis$endOutputBatch(int process);

	/**
	 * 仅重算单个槽位的状态缓存（Task 7 增量更新）
	 * <br/>
	 * 由 {@link PbRecipeCompleter#planAndExecute} 在每次 setStack/growStack 后调用，
	 * 标记该槽位为已知状态，{@code endOutputBatch} 时复用缓存避免重复扫描。
	 * <p>
	 * 默认 no-op：基础机器（{@link TileEntityMekCentrifuge}）和蜂箱适配器
	 * （{@link ApiaryPbRecipeContextAdapter}）不使用此优化，endBatch 时走全量扫描路径。
	 * 工厂类通过 {@link IFactoryPbDelegateAccess} 委托到 {@link FactoryPbContextDelegate}。
	 *
	 * @param process  进程索引
	 * @param slotIdx  槽位索引（0=主输出，1=副输出1，2=副输出2）
	 * @param slot     输出槽
	 */
	default void productivebeesgenesis$updateSlotOnly(int process, int slotIdx, IInventorySlot slot) {
		// no-op：由工厂委托实现优化
	}

	// ===== Task 11: 激活状态计数器 =====

	/**
	 * 进程激活时调用（递增计数器）
	 * <br/>
	 * 使用 CAS 防止重复递增，内部维护 boolean[] 跟踪每进程状态。
	 */
	void productivebeesgenesis$onProcessActivated(int process);

	/**
	 * 进程失活时调用（递减计数器）
	 * <br/>
	 * 使用 CAS 防止重复递减，内部维护 boolean[] 跟踪每进程状态。
	 */
	void productivebeesgenesis$onProcessDeactivated(int process);

	/** 是否有任意PB进程激活（O(1) 计数器读取，替代 O(processes) 遍历） */
	boolean productivebeesgenesis$hasActiveProcess();
}
