package com.ayoshiko.productivebeesgenesis.mek;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.world.item.ItemStack;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.inventory.CentrifugeInputStackMultipliers;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

/**
 * 基础MEK离心机输出槽与流体槽管理器
 * <br/>
 * Task 11 重构：从 {@link TileEntityMekCentrifuge} 抽取输出槽/流体槽相关状态与逻辑，
 * 包括槽位初始化、输出槽标志位维护（hasOutputItems/outputSlotsFull/outputItemCount）、
 * 批量插入管理（beginOutputBatch/endOutputBatch）、槽位上限缓存等。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：只管理输出槽和流体槽的状态，不涉及配方处理或tick逻辑</li>
 *   <li>依赖倒置：持有 {@link TileEntityMekCentrifuge} 引用访问父类字段和回调</li>
 * </ul>
 * <p>
 * 线程安全：方块实体在服务端单线程执行，volatile 字段保证可见性（标志位可能被 Ejector Mixin 读取）。
 */
class MekCentrifugeSlotManager {

	// ===== 槽位引用 =====
	/** 副输出槽1 — PB配方第2个物品输出（分等级堆叠倍率） */
	private TieredOutputInventorySlot secondaryOutputSlot;

	/** 副输出槽2 — PB配方第3个物品输出（分等级堆叠倍率） */
	private TieredOutputInventorySlot tertiaryOutputSlot;

	/** 流体输出槽 — 接收PB配方的流体输出 */
	private IExtendedFluidTank fluidOutputTank;

	// ===== 输出槽状态标志位（由 IContentsListener 维护，供 EjectorMixin 和 PbRecipeProcessor 读取） =====
	/** 输出槽是否有物品（供 EjectorMixin 读取，避免每次弹出遍历槽位） */
	private volatile boolean hasOutputItems = false;

	/** 输出槽是否已满（供 areOutputSlotsFull 读取，避免每次完成配方遍历3个槽） */
	private volatile boolean outputSlotsFull = false;

	/** Task 16: 输出槽内容版本号（输出槽内容变更时递增，供 Ejector Mixin 判断是否需要跳过 outputItems） */
	private final AtomicLong outputContentsVersion = new AtomicLong(0L);

	/**
	 * Step 5: 输出槽物品总数（主+副1+副2）
	 * <br/>
	 * 由 {@link #updateOutputSlotFlags} 维护，供 Ejector Mixin O(1) 读取，
	 * 替代 O(processes×3) 遍历的 countOutputItems。volatile 保证可见性。
	 */
	private volatile long outputItemCount = 0L;

	// ===== 批量插入管理 =====
	/** 输出槽批量更新深度（completePbRecipe/completeMyriadCreations 期间 >0） */
	private int outputBatchDepth = 0;

	/** 批量期间输出槽是否发生变化 */
	private boolean outputBatchDirty = false;

	// ===== 槽位上限缓存 =====
	/**
	 * 输出槽上限 identity 缓存
	 * <br/>
	 * {@code slot.getLimit(stack)} 在 owo 派生组件下会触发昂贵的 DataComponentMap 查询，
	 * 而输出槽中的栈引用在多数插入操作中保持不变（仅 count 变化）。
	 * 索引 0=主输出，1=副输出1，2=副输出2。
	 */
	private final ItemStack[] cachedLimitStacks = new ItemStack[3];
	private final int[] cachedLimits = new int[3];

	// ===== 监听器引用 =====
	/** getInitialInventory 中传入的 recipe cache unpause 监听器 */
	private IContentsListener recipeCacheUnpauseListener;

	/** 所属方块实体引用 */
	private final TileEntityMekCentrifuge tile;

	MekCentrifugeSlotManager(TileEntityMekCentrifuge tile) {
		this.tile = tile;
	}

	// ===== 槽位初始化 =====

	/**
	 * 构建物品槽位持有者 — 添加2个副输出槽
	 * <br/>
	 * 父类只有1个输出槽，PB离心配方最多3个物品输出。
	 * 布局：3个输出槽竖排于x=134，y分别为17/35/53，间隔18。
	 * <p>
	 * 性能优化：3个输出槽使用组合 listener，内容变更时同时触发
	 * recipeCacheUnpauseListener 和 {@link #updateOutputSlotFlags}，
	 * 维护 hasOutputItems/outputSlotsFull 标志位。
	 */
	IInventorySlotHolder buildInventory(IContentsListener listener,
										IContentsListener recipeCacheListener,
										IContentsListener recipeCacheUnpauseListener) {
		InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(tile);
		this.recipeCacheUnpauseListener = recipeCacheUnpauseListener;

		// 输入槽 — 与父类相同位置
		InputInventorySlot inputSlot = InputInventorySlot.at(tile::containsRecipe, recipeCacheListener, 64, 17);
		// Task 7: 注入输入槽分等级堆叠倍率（基础离心机使用 basic 配置）
		((TieredInputSlot) inputSlot).productivebeesgenesis$setInputStackMultiplier(CentrifugeInputStackMultipliers.forBasic());
		builder.addSlot(inputSlot)
				.tracksWarnings(slot -> slot.warning(WarningType.NO_MATCHING_RECIPE,
						tile.getWarningCheck(RecipeError.NOT_ENOUGH_INPUT)));

		// 输出槽组合 listener：原 recipeCacheUnpauseListener + 标志位更新 + 版本号递增
		// 批量模式下只标记 dirty，避免 completePbRecipe/completeMyriadCreations 中每次 insertItem 都遍历槽位
		IContentsListener outputListener = createOutputListener();

		// 主输出槽 — 竖排第1个（x=134, y=17）
		OutputInventorySlot outputSlot = OutputInventorySlot.at(outputListener, 134, 17);
		builder.addSlot(outputSlot)
				.tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT,
						tile.getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE)));

		// 副输出槽1/2 — 使用 TieredOutputInventorySlot 支持分等级堆叠倍率
		// 倍率由 getStackMultiplierForTier 动态提供，基础版使用 basic 倍率
		IntSupplier stackMultiplier = getStackMultiplierForTier();
		// 副输出槽1 — 竖排第2个（x=134, y=35）
		secondaryOutputSlot = TieredOutputInventorySlot.at(stackMultiplier, outputListener, 134, 35);
		builder.addSlot(secondaryOutputSlot);

		// 副输出槽2 — 竖排第3个（x=134, y=53）
		tertiaryOutputSlot = TieredOutputInventorySlot.at(stackMultiplier, outputListener, 134, 53);
		builder.addSlot(tertiaryOutputSlot);

		// 能量槽 — 与父类相同位置
		EnergyInventorySlot energySlot = EnergyInventorySlot.fillOrConvert(
				tile.accessor().productivebeesgenesis$getEnergyContainer(), tile::getLevel, listener, 64, 53);
		builder.addSlot(energySlot);

		// 通过Accessor设置父类的包私有字段
		tile.accessor().productivebeesgenesis$setInputSlot(inputSlot);
		tile.accessor().productivebeesgenesis$setOutputSlot(outputSlot);
		tile.accessor().productivebeesgenesis$setEnergySlot(energySlot);

		// 初始化输出槽标志位（基于初始空槽状态）
		updateOutputSlotFlags();

		return builder.build();
	}

	/**
	 * 构建流体槽位持有者 — 添加PB流体输出槽
	 * <br/>
	 * TileEntityRecipeMachine的1参数getInitialFluidTanks是final的，
	 * 需要重写3参数版本。TileEntityElectricMachine默认没有FluidTank，重写此方法添加。
	 */
	IFluidTankHolder buildFluidTanks(IContentsListener listener,
			IContentsListener recipeCacheListener,
			IContentsListener recipeCacheUnpauseListener) {
		FluidTankHelper helper = FluidTankHelper.forSideWithConfig(tile);
		fluidOutputTank = BasicFluidTank.output(ModConfig.SERVER.mekCentrifugeFluidTankCapacity.get(), listener);
		helper.addTank(fluidOutputTank);
		return helper.build();
	}

	// ===== 等级堆叠倍率 =====

	/**
	 * 获取基础版离心机的堆叠倍率供应商
	 * <br/>
	 * 基础版离心机使用 {@code mekCentrifugeStackBasic} 配置值(通过子段访问)。
	 * 工厂版离心机（TileEntityMekCentrifugeFactory 等）有自己的 addSlots() 实现，
	 * 不使用本管理器，其堆叠倍率需在各工厂子类中独立实现。
	 *
	 * @return 堆叠倍率供应商，传入 TieredOutputInventorySlot
	 */
	private IntSupplier getStackMultiplierForTier() {
		// v1.13.0 子段抽取后,通过 centrifuge().stackMultiplier 访问
		return () -> ModConfig.SERVER.centrifuge().stackMultiplier.mekCentrifugeStackBasic.get();
	}

	/**
	 * 创建输出槽内容变更监听器
	 * <br/>
	 * 批量模式下只标记 dirty，避免 completePbRecipe/completeMyriadCreations 中每次 insertItem 都遍历槽位。
	 * 非批量模式下触发 recipeCacheUnpauseListener、更新标志位、递增版本号。
	 */
	private IContentsListener createOutputListener() {
		return () -> {
			if (outputBatchDepth > 0) {
				outputBatchDirty = true;
				return;
			}
			if (recipeCacheUnpauseListener != null) {
				recipeCacheUnpauseListener.onContentsChanged();
			}
			updateOutputSlotFlags();
			// Task 16: 输出槽内容变化时递增版本号，通知 Ejector Mixin 需要重新尝试输出
			outputContentsVersion.incrementAndGet();
		};
	}

	// ===== 输出槽标志位维护 =====

	/**
	 * 重新计算并更新输出槽状态标志（由输出槽 IContentsListener 调用）
	 * <br/>
	 * 一次遍历同时更新 hasOutputItems、outputSlotsFull 和 outputItemCount，
	 * 替代原 areOutputSlotsFull 的3槽遍历和 EjectorMixin 的全槽遍历。
	 * volatile 保证可见性：服务端tick线程写入，EjectorMixin 同线程读取。
	 */
	void updateOutputSlotFlags() {
		// 主输出槽（OutputInventorySlot）和副输出槽（TieredOutputInventorySlot）的公共父类为 BasicInventorySlot
		BasicInventorySlot[] slots = {
				tile.accessor().productivebeesgenesis$getOutputSlot(),
				secondaryOutputSlot,
				tertiaryOutputSlot
		};
		boolean hasItems = false;
		boolean full = true;
		long itemCount = 0;
		for (int i = 0; i < slots.length; i++) {
			BasicInventorySlot slot = slots[i];
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
	private int getCachedSlotLimit(int index, BasicInventorySlot slot, ItemStack stack) {
		if (stack == cachedLimitStacks[index]) {
			return cachedLimits[index];
		}
		int limit = slot.getLimit(stack);
		cachedLimitStacks[index] = stack;
		cachedLimits[index] = limit;
		return limit;
	}

	// ===== 批量插入管理 =====

	/** 开始批量输出插入；嵌套调用安全 */
	void beginOutputBatch() {
		outputBatchDepth++;
	}

	/**
	 * 结束批量输出插入，统一触发一次标志位更新和 recipe cache unpause
	 * <br/>
	 * 包含下溢保护：若 endOutputBatch 调用次数多于 beginOutputBatch（配对错误），
	 * batchDepth 会变为负值，导致后续 begin/end 永久失配（depth 始终 < 0，== 0 永不成立）。
	 * 检测到下溢时重置为 0 并记录 warn 日志，使批量逻辑可自恢复。
	 */
	void endOutputBatch() {
		if (--outputBatchDepth < 0) {
			outputBatchDepth = 0;
			DevLog.warn("centrifuge_batch", "endOutputBatch 调用次数多于 beginOutputBatch，batchDepth 已重置为 0（配对错误）");
			return;
		}
		if (outputBatchDepth == 0 && outputBatchDirty) {
			outputBatchDirty = false;
			if (recipeCacheUnpauseListener != null) {
				recipeCacheUnpauseListener.onContentsChanged();
			}
			updateOutputSlotFlags();
			outputContentsVersion.incrementAndGet();
		}
	}

	// ===== 状态查询方法 =====

	boolean hasOutputItems() {
		return hasOutputItems;
	}

	boolean outputSlotsFull() {
		return outputSlotsFull;
	}

	long outputContentsVersion() {
		return outputContentsVersion.get();
	}

	long outputItemCount() {
		return outputItemCount;
	}

	TieredOutputInventorySlot getSecondaryOutputSlot() {
		return secondaryOutputSlot;
	}

	TieredOutputInventorySlot getTertiaryOutputSlot() {
		return tertiaryOutputSlot;
	}

	IExtendedFluidTank getFluidOutputTank() {
		return fluidOutputTank;
	}
}
