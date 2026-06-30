package com.ayoshiko.productivebeesgenesis.mek;

import java.util.Collections;
import java.util.List;

import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.client.recipe_viewer.type.RecipeViewerRecipeType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.config.MekanismConfig;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityElectricMachineAccessor;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;

/**
 * 基础MEK离心机方块实体
 * <br/>
 * 继承Mekanism的TileEntityElectricMachine，复用完整的能量/侧面配置/升级/GUI体系。
 * 扩展PB离心配方查找：先查PB CentrifugeRecipe，找到则用PB逻辑处理（概率多物品输出+流体），
 * 未找到则回退到Mekanism的SMELTING配方。
 * <p>
 * 额外添加2个副输出槽+FluidTank，支持PB配方的多物品和流体输出。
 * 通过Accessor Mixin访问父类的包私有字段（inputSlot/outputSlot/energySlot/energyContainer）。
 * <p>
 * Task 9 重构：PB配方处理逻辑委托给 {@link PbRecipeProcessor}，通过实现 {@link PbRecipeContext}
 * 提供依赖，消除与工厂版重复的约600行PB配方处理代码。
 * 基础机器与工厂版的差异：
 * <ul>
 *   <li>单进程（processes()=1），PbRecipeProcessor 内部用长度1的数组管理</li>
 *   <li>active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理，setPbActiveState 为 no-op</li>
 *   <li>SMELTING 检查在 tryProcessPbRecipe 中完成（工厂版在 MekCentrifugeFactoryHelper 中完成）</li>
 * </ul>
 */
public class TileEntityMekCentrifuge extends TileEntityElectricMachine
		implements PbRecipeContext, IMekCentrifugeTile {

	/** 副输出槽1 — PB配方第2个物品输出 */
	private OutputInventorySlot secondaryOutputSlot;

	/** 副输出槽2 — PB配方第3个物品输出 */
	private OutputInventorySlot tertiaryOutputSlot;

	/** 流体输出槽 — 接收PB配方的流体输出 */
	private IExtendedFluidTank fluidOutputTank;

	/**
	 * PB配方处理器 — 封装所有PB离心配方处理逻辑
	 * <br/>
	 * Task 9：从原 TileEntityMekCentrifuge 抽取的 PB 配方处理逻辑（findPbRecipe、completePbRecipe、
	 * completeMyriadCreations 等），与三个工厂类共用同一实现，消除约600行重复代码。
	 */
	private final PbRecipeProcessor pbProcessor;

	/** 上一tick是否在处理PB配方 — 用于检测PB停止时恢复SMELTING激活状态 */
	private boolean pbWasProcessing;

	// ===== 输出槽状态标志位（由 IContentsListener 维护，供 EjectorMixin 和 PbRecipeProcessor 读取） =====
	/** 输出槽是否有物品（供 EjectorMixin 读取，避免每次弹出遍历槽位） */
	private volatile boolean hasOutputItems = false;

	/** 输出槽是否已满（供 areOutputSlotsFull 读取，避免每次完成配方遍历3个槽） */
	private volatile boolean outputSlotsFull = false;

	/** Task 16: 输出槽内容版本号（输出槽内容变更时递增，供 Ejector Mixin 判断是否需要跳过 outputItems） */
	private volatile long outputContentsVersion = 0L;

	/**
	 * Step 5: 输出槽物品总数（主+副1+副2）
	 * <br/>
	 * 由 {@link #updateOutputSlotFlags} 维护，供 Ejector Mixin O(1) 读取，
	 * 替代 O(processes×3) 遍历的 countOutputItems。volatile 保证可见性。
	 */
	private volatile long outputItemCount = 0L;

	/** 输出槽批量更新深度（completePbRecipe/completeMyriadCreations 期间 >0） */
	private int outputBatchDepth = 0;

	/**
	 * 输出槽上限 identity 缓存
	 * <br/>
	 * {@code slot.getLimit(stack)} 在 owo 派生组件下会触发昂贵的 DataComponentMap 查询，
	 * 而输出槽中的栈引用在多数插入操作中保持不变（仅 count 变化）。
	 * 索引 0=主输出，1=副输出1，2=副输出2。
	 */
	private final ItemStack[] cachedLimitStacks = new ItemStack[3];
	private final int[] cachedLimits = new int[3];

	/** 批量期间输出槽是否发生变化 */
	private boolean outputBatchDirty = false;

	/** getInitialInventory 中传入的 recipe cache unpause 监听器 */
	private IContentsListener recipeCacheUnpauseListener;

	public TileEntityMekCentrifuge(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
		super(blockProvider, pos, state, BASE_TICKS_REQUIRED);
		// Task 9：初始化PB配方处理器（基础机器单进程，logPrefix 区分日志来源）
		pbProcessor = new PbRecipeProcessor(this, "MEK离心机");
		// 重写侧面配置：3个输出槽（参考PrecisionSawmill）
		// 注：secondaryOutputSlot/tertiaryOutputSlot 在 super() 期间通过 getInitialInventory()
		// （TileEntityMekanism 构造函数虚方法调用）已赋值，此处 List.of 不会 NPE。
		// 切勿将 setupItemIOConfig 移到 getInitialInventory 之前或重构 super() 调用顺序，
		// 否则 List.of 的 null 检查会立即抛出 NPE。
		configComponent.setupItemIOConfig(
				Collections.singletonList(accessor().productivebeesgenesis$getInputSlot()),
				List.of(accessor().productivebeesgenesis$getOutputSlot(), secondaryOutputSlot, tertiaryOutputSlot),
				accessor().productivebeesgenesis$getEnergySlot(), false);
		configComponent.setupInputConfig(TransmissionType.ENERGY, accessor().productivebeesgenesis$getEnergyContainer());
		// 流体槽作为输出配置（右侧），参考TileEntityNutritionalLiquifier
		configComponent.setupOutputConfig(TransmissionType.FLUID, fluidOutputTank, RelativeSide.RIGHT);

		// 使用自定义流体弹出速率覆盖 Mekanism 默认值，同时把物品弹出 tickDelay 设为 1 tick
		// （TileComponentEjectorMixin 会在此基础上根据输出槽状态做动态调整）
		ejectorComponent = new TileComponentEjector(this, MekanismConfig.general.chemicalAutoEjectRate,
				() -> ModConfig.SERVER.mekCentrifugeFluidEjectRate.get());
		((TileEntityEjectorAccessor) ejectorComponent).productivebeesgenesis$setTickDelay(1);
		// 同时弹出物品和流体
		ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
	}

	@NotNull
	@Override
	public IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekanismRecipeType.SMELTING;
	}

	/**
	 * JEI配方查看器跳转支持
	 * <br/>
	 * 返回SMELTING类型，使JEI中点击配方时能正确跳转到熔炼配方类别。
	 * 参考Mekanism原版TileEntityEnergizedSmelter的实现。
	 */
	@NotNull
	@Override
	public IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return RecipeViewerRecipeType.SMELTING;
	}

	/**
	 * 重写containsRecipe — 同时查找Mekanism SMELTING和PB CentrifugeRecipe
	 * <br/>
	 * 输入槽通过this::containsRecipe判断物品是否有效。
	 * 默认实现只查SMELTING配方缓存，PB蜜脾不在其中会被拒绝。
	 * 重写后同时检查PB配方，使PB蜜脾能放入输入槽。
	 * <p>
	 * Task 9：PB配方查找委托给 {@link PbRecipeProcessor#findPbRecipe}。
	 */
	@Override
	public boolean containsRecipe(@NotNull ItemStack input) {
		if (super.containsRecipe(input)) return true;
		return pbProcessor.findPbRecipe(input) != null;
	}

	/**
	 * 重写getInitialInventory — 添加2个副输出槽
	 * <br/>
	 * 父类只有1个输出槽，PB离心配方最多3个物品输出。
	 * 重写后添加secondaryOutputSlot和tertiaryOutputSlot。
	 * 由于父类字段是包私有的，通过Accessor Mixin设置。
	 * <p>
	 * 布局：3个输出槽竖排于x=134，y分别为17/35/53，间隔18。
	 * <p>
	 * 性能优化：3个输出槽使用组合 listener，内容变更时同时触发
	 * recipeCacheUnpauseListener 和 {@link #updateOutputSlotFlags}，
	 * 维护 hasOutputItems/outputSlotsFull 标志位，避免 areOutputSlotsFull
	 * 和 EjectorMixin 每次都遍历槽位。
	 */
	@NotNull
	@Override
	protected IInventorySlotHolder getInitialInventory(@NotNull IContentsListener listener,
													   @NotNull IContentsListener recipeCacheListener,
													   @NotNull IContentsListener recipeCacheUnpauseListener) {
		InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this);
		this.recipeCacheUnpauseListener = recipeCacheUnpauseListener;

		// 输入槽 — 与父类相同位置
		InputInventorySlot inputSlot = InputInventorySlot.at(this::containsRecipe, recipeCacheListener, 64, 17);
		builder.addSlot(inputSlot)
				.tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE, getWarningCheck(RecipeError.NOT_ENOUGH_INPUT)));

		// 输出槽组合 listener：原 recipeCacheUnpauseListener + 标志位更新 + 版本号递增
		// 批量模式下只标记 dirty，避免 completePbRecipe/completeMyriadCreations 中每次 insertItem 都遍历槽位
		IContentsListener outputListener = () -> {
			if (outputBatchDepth > 0) {
				outputBatchDirty = true;
				return;
			}
			recipeCacheUnpauseListener.onContentsChanged();
			updateOutputSlotFlags();
			// Task 16: 输出槽内容变化时递增版本号，通知 Ejector Mixin 需要重新尝试输出
			outputContentsVersion++;
		};

		// 主输出槽 — 竖排第1个（x=134, y=17）
		OutputInventorySlot outputSlot = OutputInventorySlot.at(outputListener, 134, 17);
		builder.addSlot(outputSlot)
				.tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT, getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE)));

		// 副输出槽1 — 竖排第2个（x=134, y=35）
		secondaryOutputSlot = OutputInventorySlot.at(outputListener, 134, 35);
		builder.addSlot(secondaryOutputSlot);

		// 副输出槽2 — 竖排第3个（x=134, y=53）
		tertiaryOutputSlot = OutputInventorySlot.at(outputListener, 134, 53);
		builder.addSlot(tertiaryOutputSlot);

		// 能量槽 — 与父类相同位置
		EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(
				accessor().productivebeesgenesis$getEnergyContainer(), this::getLevel, listener, 64, 53);
		builder.addSlot(energySlot);

		// 通过Accessor设置父类的包私有字段
		accessor().productivebeesgenesis$setInputSlot(inputSlot);
		accessor().productivebeesgenesis$setOutputSlot(outputSlot);
		accessor().productivebeesgenesis$setEnergySlot(energySlot);

		// 初始化输出槽标志位（基于初始空槽状态）
		updateOutputSlotFlags();

		return builder.build();
	}

	/**
	 * 初始化FluidTank — 添加PB流体输出槽
	 * <br/>
	 * TileEntityRecipeMachine的1参数getInitialFluidTanks是final的，
	 * 需要重写3参数版本。TileEntityElectricMachine默认没有FluidTank，重写此方法添加。
	 */
	@NotNull
	@Override
	protected IFluidTankHolder getInitialFluidTanks(@NotNull IContentsListener listener,
													 @NotNull IContentsListener recipeCacheListener,
													 @NotNull IContentsListener recipeCacheUnpauseListener) {
		FluidTankHelper helper = FluidTankHelper.forSideWithConfig(this);
		fluidOutputTank = BasicFluidTank.output(ModConfig.SERVER.mekCentrifugeFluidTankCapacity.get(), listener);
		helper.addTank(fluidOutputTank);
		return helper.build();
	}

	/** 获取Accessor — 用于访问父类包私有字段 */
	private TileEntityElectricMachineAccessor accessor() {
		return (TileEntityElectricMachineAccessor) this;
	}

	/** 获取流体输出槽 — GUI显示用 */
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() {
		return fluidOutputTank;
	}

	/** 获取副输出槽1 — GUI显示用 */
	@NotNull
	public OutputInventorySlot getSecondaryOutputSlot() {
		return secondaryOutputSlot;
	}

	/** 获取副输出槽2 — GUI显示用 */
	@NotNull
	public OutputInventorySlot getTertiaryOutputSlot() {
		return tertiaryOutputSlot;
	}

	/**
	 * 重写getScaledProgress — PB配方处理时使用pbProcessor的进度
	 * <br/>
	 * 父类的getScaledProgress()使用operatingTicks/ticksRequired。
	 * PB配方处理时operatingTicks不被更新（PB用自己的pbOperatingTicks），
	 * 所以需要重写此方法，在PB处理时返回pbOperatingTicks/processingTime。
	 * <p>
	 * Task 9：进度读取委托给 {@link PbRecipeProcessor#isPbProcessing} 和
	 * {@link PbRecipeProcessor#getPbScaledProgress}（process=0，缩放因子=1）。
	 */
	@Override
	public double getScaledProgress() {
		if (pbProcessor.isPbProcessing(0)) {
			return pbProcessor.getPbScaledProgress(1, 0);
		}
		return super.getScaledProgress();
	}

	/**
	 * 服务端tick — 总是调用super以确保ejector被tick
	 * <br/>
	 * 参考Mekanism原版TileEntityNutritionalLiquifier的做法：总是调用super.onUpdateServer()，
	 * 确保TileEntityConfigurableMachine中的ejectorComponent.tickServer()被执行（否则输出无法自动弹出）。
	 * super会处理SMELTING配方（通过recipeCacheLookupMonitor.updateAndProcess()），
	 * PB配方在tryProcessPbRecipe中独立处理（内部会跳过有SMELTING配方的输入，避免双重处理）。
	 * <p>
	 * 声音控制：基础机器只有1个输入槽，不可能同时处理SMELTING和PB配方。
	 * PB停止时直接setActive(false)，如果SMELTING有配方在处理，下一tick的super会重新setActive(true)。
	 * <p>
	 * 能量追踪：super前保存能量，PB处理后计算总消耗（SMELTING + PB），
	 * 与工厂版 {@link MekCentrifugeFactoryHelper#processPbRecipesAndUpdate} 逻辑对齐。
	 * <p>
	 * 注意：父类 TileEntityElectricMachine.onUpdateServer() 已经调用 energySlot.fillContainerOrConvert()，
	 * 子类不应重复调用，否则每tick会执行两次能量容器填充（造成无意义的性能开销）。
	 */
	@Override
	protected boolean onUpdateServer() {
		// 性能监控：默认关闭，isEnabled()为false时不产生System.nanoTime开销
		boolean monitor = PerformanceMonitor.isEnabled();
		long tickStartNanos = monitor ? System.nanoTime() : 0L;

		// super前保存能量，用于计算总消耗（SMELTING + PB），与工厂版逻辑保持一致
		var energyContainer = accessor().productivebeesgenesis$getEnergyContainer();
		long energyBefore = energyContainer.getEnergy();

		boolean sendUpdatePacket = super.onUpdateServer();

		// PB配方独立处理（不走Mekanism管线）
		boolean pbResult = tryProcessPbRecipe();
		if (pbResult) {
			setActive(true);
			pbWasProcessing = true;
		} else if (pbWasProcessing) {
			// PB刚停止 — 直接设为false
			// 基础机器只有1个输入槽，PB停止时不可能有SMELTING在处理
			// 如果SMELTING有配方，下一tick的super.onUpdateServer()会重新setActive(true)
			setActive(false);
			pbWasProcessing = false;
		}

		// 计算总能量消耗（SMELTING + PB），基于实际能量差
		long totalUsage = energyBefore - energyContainer.getEnergy();

		// 性能监控：记录tick耗时和能量消耗（仅启用时）
		if (monitor) {
			PerformanceMonitor monitorInst = PerformanceMonitor.getInstance();
			monitorInst.recordTickTime(System.nanoTime() - tickStartNanos);
			if (totalUsage > 0) {
				monitorInst.recordEnergyConsumed(totalUsage);
			}
		}

		// 不再重复调用 energySlot.fillContainerOrConvert() — 父类 super.onUpdateServer() 已处理
		return sendUpdatePacket;
	}

	/**
	 * 尝试PB离心配方处理
	 * <br/>
	 * SMELTING配方优先于PB配方：如果输入物品存在SMELTING配方，则跳过PB处理，
	 * 交由super.onUpdateServer()的Mekanism管线处理，避免同一输入被双重处理。
	 * <p>
	 * Task 9 重构：PB配方处理逻辑委托给 {@link PbRecipeProcessor#tryProcessPbRecipe}，
	 * 此处只保留SMELTING前置检查（工厂版在 MekCentrifugeFactoryHelper 中完成）。
	 * <p>
	 * 与工厂版的差异：
	 * <ul>
	 *   <li>基础机器的 active 由 onUpdateServer 中的 pbWasProcessing 逻辑管理，
	 *       setPbActiveState 为 no-op，因此 SMELTING 命中时用 resetPbState（不触发 setPbActiveState）</li>
	 *   <li>SMELTING 检查结果缓存由 PbRecipeProcessor.hasSmeltingRecipe 管理（与工厂版一致）</li>
	 * </ul>
	 *
	 * @return true 如果正在处理PB配方
	 */
	private boolean tryProcessPbRecipe() {
		try {
			if (level == null || level.isClientSide) return false;
			if (!canFunction()) return false;

			ItemStack input = accessor().productivebeesgenesis$getInputSlot().getStack();
			if (input.isEmpty()) {
				// 空输入：重置 PB 状态和 SMELTING 缓存（与原版 clearPbState + lastCheckedInput=EMPTY 一致）
				pbProcessor.resetPbState(0);
				pbProcessor.resetSmeltingCache(0);
				return false;
			}

			// SMELTING 配方检查（带缓存，输入变更时才重新查询）
			// SMELTING 优先于 PB，有 SMELTING 配方时跳过 PB 处理，交由 super 的 Mekanism 管线
			if (pbProcessor.hasSmeltingRecipe(0, input)) {
				// 有 SMELTING 配方：重置 PB 状态（不调用 setPbActiveState，避免与 SMELTING 的 setActive 冲突）
				pbProcessor.resetPbState(0);
				return false;
			}

			// 委托给 PbRecipeProcessor 处理 PB 配方（含万象创世路径、输出聚合、Task 8 输出槽满前置检查）
			return pbProcessor.tryProcessPbRecipe(0);
		} catch (Exception e) {
			// 捕获异常防止tick崩溃，记录错误日志并重置PB状态
			ProductiveBeesGenesis.LOGGER.error("tryProcessPbRecipe 异常，重置PB状态", e);
			pbProcessor.resetPbState(0);
			return false;
		}
	}

	/**
	 * 重新计算并更新输出槽状态标志（由输出槽 IContentsListener 调用）
	 * <br/>
	 * 一次遍历同时更新 {@link #hasOutputItems}（供 EjectorMixin 读取）、
	 * {@link #outputSlotsFull}（供 {@link PbRecipeProcessor} 读取）和
	 * {@link #outputItemCount}（Step 5: 供 Ejector Mixin O(1) 读取替代 countOutputItems 遍历），
	 * 替代原 areOutputSlotsFull 的3槽遍历和 EjectorMixin 的全槽遍历。
	 * volatile 保证可见性：服务端tick线程写入，EjectorMixin 同线程读取。
	 */
	private void updateOutputSlotFlags() {
		OutputInventorySlot[] slots = {
				accessor().productivebeesgenesis$getOutputSlot(),
				secondaryOutputSlot,
				tertiaryOutputSlot
		};
		boolean hasItems = false;
		boolean full = true;
		long itemCount = 0;
		for (int i = 0; i < slots.length; i++) {
			OutputInventorySlot slot = slots[i];
			if (slot == null) continue;
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty()) {
				hasItems = true;
				itemCount += stack.getCount();
				if (stack.getCount() < getCachedSlotLimit(i, slot, stack)) {
					full = false;
				}
			} else {
				full = false;
			}
		}
		this.hasOutputItems = hasItems;
		this.outputSlotsFull = full;
		this.outputItemCount = itemCount;
	}

	/**
	 * 获取输出槽上限（带 identity 缓存）
	 * <br/>
	 * 避免每次 {@link #updateOutputSlotFlags} 都调用 {@code slot.getLimit(stack)}，
	 * 从而跳过 owo 派生组件的昂贵 DataComponentMap 查询。
	 *
	 * @param index 槽位缓存索引（0=主输出，1=副输出1，2=副输出2）
	 * @param slot  输出槽
	 * @param stack 当前栈（非空）
	 * @return 槽位上限
	 */
	private int getCachedSlotLimit(int index, OutputInventorySlot slot, ItemStack stack) {
		if (stack == cachedLimitStacks[index]) {
			return cachedLimits[index];
		}
		int limit = slot.getLimit(stack);
		cachedLimitStacks[index] = stack;
		cachedLimits[index] = limit;
		return limit;
	}

	/** 开始批量输出插入；嵌套调用安全 */
	private void beginOutputBatch() {
		outputBatchDepth++;
	}

	/** 结束批量输出插入，统一触发一次标志位更新和 recipe cache unpause */
	private void endOutputBatch() {
		if (--outputBatchDepth == 0 && outputBatchDirty) {
			outputBatchDirty = false;
			if (recipeCacheUnpauseListener != null) {
				recipeCacheUnpauseListener.onContentsChanged();
			}
			updateOutputSlotFlags();
			outputContentsVersion++;
		}
	}

	/**
	 * 输出槽是否有物品（供 TileComponentEjectorMixin 读取，避免每次弹出遍历所有槽位）
	 * <br/>
	 * Task 9：基础机器也实现 PbRecipeContext，Mixin 可统一走 instanceof PbRecipeContext 路径。
	 */
	@Override
	public boolean productivebeesgenesis$hasOutputItems() {
		return hasOutputItems;
	}

	/**
	 * 输出槽内容版本号（供 TileComponentEjectorCooldownMixin 读取）
	 * <br/>
	 * 每次输出槽内容变化时递增，使 Ejector Mixin 能在内容未变化时跳过 outputItems 调用。
	 */
	@Override
	public long productivebeesgenesis$outputContentsVersion() {
		return outputContentsVersion;
	}

	/**
	 * Step 5: 返回所有输出槽物品总数（O(1)，供 Ejector Mixin 替代 countOutputItems 遍历）
	 * <br/>
	 * 由 {@link #updateOutputSlotFlags} 维护，volatile 保证可见性。
	 */
	@Override
	public long productivebeesgenesis$outputItemCount() {
		return outputItemCount;
	}

	/**
	 * 输出槽是否已满（供 TileComponentEjectorCooldownMixin 和 PbRecipeProcessor 读取）
	 * <br/>
	 * 返回由 IContentsListener 维护的 {@link #outputSlotsFull} 标志位，所有物品输出槽无剩余空间时为 true。
	 * Mixin 在输出槽满时强制重置跳过计数器，避免产物因跳过 outputItems 而积压停机。
	 */
	@Override
	public boolean productivebeesgenesis$outputSlotsFull() {
		return outputSlotsFull;
	}

	// ===== PbRecipeContext 接口实现 =====
	// Task 9：基础机器实现 PbRecipeContext，将 PB 配方处理委托给 PbRecipeProcessor。
	// 与工厂版的差异：setPbActiveState / onProcessActivated / onProcessDeactivated 为 no-op，
	// 因为基础机器的 active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理（不用计数器）。

	@Override
	public Level level() {
		return level;
	}

	@Override
	public MachineEnergyContainer<?> energyContainer() {
		return accessor().productivebeesgenesis$getEnergyContainer();
	}

	@Override
	public IInventorySlot inputSlot(int process) {
		return accessor().productivebeesgenesis$getInputSlot();
	}

	@Override
	public IInventorySlot primaryOutputSlot(int process) {
		return accessor().productivebeesgenesis$getOutputSlot();
	}

	@Override
	public IInventorySlot secondaryOutputSlot(int process) {
		return secondaryOutputSlot;
	}

	@Override
	public IInventorySlot tertiaryOutputSlot(int process) {
		return tertiaryOutputSlot;
	}

	@Override
	public IExtendedFluidTank fluidOutputTank() {
		return fluidOutputTank;
	}

	@Override
	public int processes() {
		return 1;
	}

	@Override
	public int baseTicksRequired() {
		return BASE_TICKS_REQUIRED;
	}

	@Override
	public boolean canFunction() {
		return super.canFunction();
	}

	/**
	 * 设置 PB 进程激活状态 — 基础机器为 no-op
	 * <br/>
	 * 基础机器的 active 状态由 onUpdateServer 中的 pbWasProcessing 逻辑管理（setActive），
	 * 不通过 setPbActiveState 设置。PbRecipeProcessor 内部调用此方法时为 no-op，
	 * 避免与 SMELTING 的 setActive 冲突（SMELTING 处理时 super 已 setActive(true)）。
	 */
	@Override
	public void setPbActiveState(boolean active, int process) {
		// no-op：active 由 onUpdateServer 管理
	}

	@Override
	public int productivityModifier() {
		return 1;
	}

	@Override
	public int operationsPerTick() {
		return MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1);
	}

	@Override
	public int getTicksForBase(int baseTime) {
		return MekanismUtils.getTicks(this, baseTime);
	}

	@Override
	public boolean containsSmeltingInput(ItemStack input) {
		if (level == null) return false;
		return getRecipeType().getInputCache().containsInput(level, input);
	}

	@Override
	public void productivebeesgenesis$updateOutputSlotFlags() {
		updateOutputSlotFlags();
	}

	@Override
	public boolean productivebeesgenesis$outputSlotsFull(int process) {
		return outputSlotsFull;
	}

	@Override
	public void productivebeesgenesis$beginOutputBatch() {
		beginOutputBatch();
	}

	@Override
	public void productivebeesgenesis$endOutputBatch(int process) {
		endOutputBatch();
	}

	/** Task 11: 基础机器不用计数器，hasActiveProcess 直接读 pbProcessor 状态 */
	@Override
	public boolean productivebeesgenesis$hasActiveProcess() {
		return pbProcessor.isPbProcessing(0);
	}

	/** Task 11: 基础机器不用计数器，激活/失活为 no-op */
	@Override
	public void productivebeesgenesis$onProcessActivated(int process) {
		// no-op：基础机器用 pbWasProcessing 管理激活状态
	}

	@Override
	public void productivebeesgenesis$onProcessDeactivated(int process) {
		// no-op：基础机器用 pbWasProcessing 管理激活状态
	}

	// ===== 客户端同步和持久化 =====

	/**
	 * 同步PB进度到客户端
	 * <br/>
	 * Task 9：委托给 {@link PbRecipeProcessor#addContainerTrackers}，同步 pbOperatingTicks、
	 * pbProcessing、pbProcessingTime 数组（基础机器数组长度为1）。
	 */
	@Override
	public void addContainerTrackers(mekanism.common.inventory.container.MekanismContainer container) {
		super.addContainerTrackers(container);
		pbProcessor.addContainerTrackers(container);
	}

	/**
	 * 持久化PB配方处理进度
	 * <br/>
	 * Task 9：委托给 {@link PbRecipeProcessor#saveAdditional}。
	 * 注意：NBT 格式从 putInt 改为 putIntArray（数组长度1），旧存档（putInt）无法加载，
	 * 但模组暂未发布，无需兼容旧存档。
	 */
	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		pbProcessor.saveAdditional(nbt);
	}

	/** 加载PB配方处理进度 — 委托给 PbRecipeProcessor */
	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		pbProcessor.loadAdditional(nbt);
	}
}
