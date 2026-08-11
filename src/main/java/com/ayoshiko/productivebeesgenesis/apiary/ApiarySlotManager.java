package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import cy.jdkdigital.productivebees.init.ModFluids;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

/**
	 * 通用机械蜂箱槽位管理器
	 * <br/>
	 * 管理 BeeSlot 数组、蜂笼 I/O 槽、输出槽、能量槽、流体罐，
	 * 通过组合委托 NBT 序列化至 {@link ApiarySlotSerializer}、蜂笼操作至 {@link ApiaryCageHandler}。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅管理槽位数据结构与初始化，不涉及序列化或蜂笼转移细节</li>
	 *   <li>依赖倒置：持有 {@link TileEntityMekApiary} 引用访问父类字段和回调</li>
	 * </ul>
	 * <p>
	 * 线程安全：方块实体在服务端单线程执行，BeeSlot 内部使用 synchronized 保证
	 * 客户端同步线程与服务端 tick 线程并发读写安全。
	 * <p>
	 * 阶段五改造：蜜蜂槽/输出槽/流体罐容量改为构造参数传入，支持工厂版动态数量，
	 * 同时保留静态常量作为初始版默认值（向后兼容）。
	 */
public class ApiarySlotManager {

	// ===== 初始版默认常量（向后兼容） =====
	/** 初始版蜜蜂居住槽位数 */
	static final int DEFAULT_BEE_SLOT_COUNT = 3;

	/** 初始版蜜蜂列数（1 行 3 列） */
	static final int DEFAULT_BEE_COLS = 3;

	/** 初始版蜜蜂行数 */
	static final int DEFAULT_BEE_ROWS = 1;

	/** 初始版输出槽位数（3×3 矩形） */
	static final int DEFAULT_OUTPUT_SLOT_COUNT = 9;

	/** 初始版输出列数 */
	static final int DEFAULT_OUTPUT_COLS = 3;

	/** 初始版输出行数（3×3 矩形） */
	static final int DEFAULT_OUTPUT_ROWS = 3;

	/** 初始版流体罐容量（256000mB） */
	static final int DEFAULT_FLUID_TANK_CAPACITY = 256_000;

	/** 槽位步进（槽宽18+间距2=20） */
	private static final int SLOT_PITCH = ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP;

	// ===== 实例配置字段（工厂版可定制） =====
	/** 蜜蜂居住槽位数 */
	private final int beeSlotCount;

	/** 蜜蜂列数（固定 3 或 5） */
	private final int beeCols;

	/** 蜜蜂行数 */
	private final int beeRows;

	/** 输出槽位数 */
	private final int outputSlotCount;

	/** 输出列数 */
	private final int outputCols;

	/** 输出行数 */
	private final int outputRows;

	/** 流体罐容量（mB） */
	private final int fluidTankCapacity;

	// ===== 槽位引用 =====
	/** 蜜蜂居住槽数组（非 IInventorySlot，自定义数据结构） */
	private final BeeSlot[] beeSlots;

	/** 输出槽列表（按行列排布，使用 TieredOutputInventorySlot 支持分等级堆叠倍率） */
	private final List<BasicInventorySlot> outputSlots;
	/** 输出槽只读视图缓存 — buildInventory 完成后构建，避免每次 getOutputSlots() 创建新包装对象（GC 压力） */
	private List<BasicInventorySlot> cachedUnmodifiableOutputSlots;

	/** 槽位上限 identity 缓存（委托至 {@link SlotLimitCache}，含版本号失效机制） */
	private final SlotLimitCache slotLimitCache;

	/** 蜂笼输入槽（玩家放入空蜂笼） — 通过 Mixin Accessor 设置 obeyStackLimit=false，使蜂笼固定堆叠 64 */
	private InputInventorySlot cageInSlot;

	/** 蜂笼输出槽（装入蜜蜂的蜂笼自动输出至此） — 通过 Mixin Accessor 设置 obeyStackLimit=false，使蜂笼固定堆叠 64 */
	private OutputInventorySlot cageOutSlot;

	/** 能量槽 */
	private EnergyInventorySlot energySlot;

	/** 流体蜂蜜罐 */
	private IExtendedFluidTank fluidTank;

	/** 所属方块实体引用 */
	private final TileEntityMekApiary tile;

	// ===== 组合委托处理器 =====
	/** 蜂笼操作处理器（装入/取出/桶式操作） */
	private final ApiaryCageHandler cageHandler;

	/** 蜜蜂槽序列化器（NBT 持久化 + 网络同步） */
	private final ApiarySlotSerializer serializer;

	/**
	 * 默认构造（初始版参数：3蜜蜂/9输出/256000mB）
	 * <br/>
	 * 向后兼容：保留与原版相同的参数。
	 */
	public ApiarySlotManager(TileEntityMekApiary tile) {
		this(tile, DEFAULT_BEE_SLOT_COUNT, DEFAULT_BEE_COLS, DEFAULT_BEE_ROWS,
				DEFAULT_OUTPUT_SLOT_COUNT, DEFAULT_OUTPUT_COLS, DEFAULT_OUTPUT_ROWS,
				DEFAULT_FLUID_TANK_CAPACITY);
	}

	/**
	 * 工厂版构造（动态参数）
	 *
	 * @param tile               所属方块实体
	 * @param beeSlotCount       蜜蜂槽位数量
	 * @param beeCols            蜜蜂列数（工厂版固定5列）
	 * @param beeRows            蜜蜂行数（工厂版按等级 1/2/3/4 行）
	 * @param outputSlotCount    输出槽位数量
	 * @param outputCols         输出列数
	 * @param outputRows         输出行数
	 * @param fluidTankCapacity  流体罐容量（mB）
	 */
	public ApiarySlotManager(TileEntityMekApiary tile,
			int beeSlotCount, int beeCols, int beeRows,
			int outputSlotCount, int outputCols, int outputRows,
			int fluidTankCapacity) {
		this.tile = tile;
		this.beeSlotCount = beeSlotCount;
		this.beeCols = beeCols;
		this.beeRows = beeRows;
		this.outputSlotCount = outputSlotCount;
		this.outputCols = outputCols;
		this.outputRows = outputRows;
		this.fluidTankCapacity = fluidTankCapacity;
		this.beeSlots = new BeeSlot[beeSlotCount];
		for (int i = 0; i < beeSlotCount; i++) {
			beeSlots[i] = new BeeSlot();
		}
		this.outputSlots = new ArrayList<>(outputSlotCount);
		this.slotLimitCache = new SlotLimitCache(outputSlotCount);
		// 初始化组合委托处理器（持有 this 引用，操作时回调访问槽位数据）
		this.cageHandler = new ApiaryCageHandler(this);
		this.serializer = new ApiarySlotSerializer(this);
	}

	// ===== 槽位初始化 =====

	/**
	 * 构建物品槽位持有者
	 * <br/>
	 * 布局：蜜蜂槽（BeeSlot 数组）、输出槽矩阵、蜂笼输入/输出、能量槽。
	 * 槽位坐标由 ApiaryGuiLayoutHelper 计算（区分初始版/工厂版布局）。
	 * 通过 accessor 设置父类的 inputSlot/outputSlot/energySlot 字段。
	 */
	IInventorySlotHolder buildInventory(IContentsListener listener,
										IContentsListener recipeCacheListener,
										IContentsListener recipeCacheUnpauseListener) {
		InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(tile);

		// 通过 LayoutHelper 计算槽位坐标（支持初始版和工厂版）
		int imageWidth = ApiaryGuiLayoutHelper.getImageWidth(beeCols, outputCols);
		int cageInX = ApiaryGuiLayoutHelper.getCageInX(imageWidth, beeCols);
		int cageOutX = ApiaryGuiLayoutHelper.getCageOutX(imageWidth, beeCols);
		int cageY = ApiaryGuiLayoutHelper.getCageY(beeRows);
		int beeX = ApiaryGuiLayoutHelper.getBeeX(imageWidth, beeCols);
		int beeW = ApiaryGuiLayoutHelper.getBeeW(beeCols);
		int beeBottom = ApiaryGuiLayoutHelper.getBeeBottom(beeRows);
		int outputW = ApiaryGuiLayoutHelper.getOutputW(outputCols);
		int outputX = ApiaryGuiLayoutHelper.getOutputX(beeX, beeW, outputW);
		int outputY = ApiaryGuiLayoutHelper.getOutputY(beeBottom, beeRows);

		// 蜂笼输入槽 — 同时作为父类的 inputSlot，接受 PB 普通蜂笼和坚固蜂笼
		// 通过 BasicInventorySlotAccessor 设置 obeyStackLimit=false，使 getLimit() 返回 limit
		// （默认 Item.ABSOLUTE_MAX_STACK_SIZE=64），与蜂笼物品自身 maxStackSize 解耦，
		// 坚固蜂笼（默认 stacksTo(16)）也能堆叠 64
		cageInSlot = InputInventorySlot.at(
				stack -> stack.is(ModItems.BEE_CAGE.get()) || stack.is(ModItems.STURDY_BEE_CAGE.get()),
				recipeCacheListener, cageInX, cageY);
		((BasicInventorySlotAccessor) cageInSlot).productivebeesgenesis$setObeyStackLimit(false);
		builder.addSlot(cageInSlot);

		// 蜂笼输出槽 — 空蜂笼输出至此（取出蜜蜂后）
		// 同样设置 obeyStackLimit=false，与输入槽堆叠上限一致（64）
		cageOutSlot = OutputInventorySlot.at(listener, cageOutX, cageY);
		((BasicInventorySlotAccessor) cageOutSlot).productivebeesgenesis$setObeyStackLimit(false);
		builder.addSlot(cageOutSlot);

		// 输出槽 — 按 outputRows × outputCols 矩形排布
		// 使用 TieredOutputInventorySlot 支持分等级堆叠倍率，倍率由 ApiaryTierMultiplierResolver 动态提供
		// Bug 10: 绑定 NO_SPACE_IN_OUTPUT 警告，输出槽满时在警告Tab显示
		IntSupplier stackMultiplier = ApiaryTierMultiplierResolver.getStackMultiplierForTier(tile);
		for (int row = 0; row < outputRows; row++) {
			for (int col = 0; col < outputCols; col++) {
				TieredOutputInventorySlot outputSlot = TieredOutputInventorySlot.at(
						stackMultiplier, listener,
						outputX + col * SLOT_PITCH, outputY + row * SLOT_PITCH);
				outputSlots.add(outputSlot);
				builder.addSlot(outputSlot)
						.tracksWarnings(slot -> slot.warning(WarningType.NO_SPACE_IN_OUTPUT,
								tile.getWarningCheck(RecipeError.NOT_ENOUGH_OUTPUT_SPACE)));
			}
		}

		// 能量槽 — 通过 accessor 获取父类的 energyContainer
		energySlot = EnergyInventorySlot.fillOrConvert(
				tile.accessor().productivebeesgenesis$getEnergyContainer(),
				tile::getLevel, listener, ApiaryGuiLayoutHelper.ENERGY_X, ApiaryGuiLayoutHelper.ENERGY_Y);
		builder.addSlot(energySlot);

		// 通过 accessor 设置父类的包私有字段
		// inputSlot = cageInSlot, outputSlot = cageOutSlot（产物输出槽为 TieredOutputInventorySlot
		// 非 OutputInventorySlot 子类，父类 outputSlot 字段用 cageOutSlot 占位即可，
		// 蜂箱不走父类配方管线，outputSlot 仅用于父类能量填充等辅助逻辑）
		tile.accessor().productivebeesgenesis$setInputSlot(cageInSlot);
		tile.accessor().productivebeesgenesis$setOutputSlot(cageOutSlot);
		tile.accessor().productivebeesgenesis$setEnergySlot(energySlot);

		// 构建只读视图缓存，避免每次 getOutputSlots() 创建新包装对象
		this.cachedUnmodifiableOutputSlots = Collections.unmodifiableList(outputSlots);

		return builder.build();
	}

	/**
	 * 构建流体槽位持有者 — 添加蜂蜜流体罐
	 * <br/>
	 * 容量由构造参数决定。流体验证器仅接受 PB 蜂蜜流体，防止管道注入污染。
	 */
	IFluidTankHolder buildFluidTanks(IContentsListener listener,
			IContentsListener recipeCacheListener,
			IContentsListener recipeCacheUnpauseListener) {
		FluidTankHelper helper = FluidTankHelper.forSideWithConfig(tile);
		// 验证器：仅接受 PB 蜂蜜流体，拒绝其他流体自动化注入
		fluidTank = BasicFluidTank.create(fluidTankCapacity,
				stack -> stack.getFluid() == ModFluids.HONEY.get(),
				listener);
		helper.addTank(fluidTank);
		return helper.build();
	}

	// ===== NBT 序列化与网络同步（委托至序列化器） =====

	/** 保存蜜蜂槽数组到 NBT — 委托至 {@link ApiarySlotSerializer} */
	void saveBeeSlots(CompoundTag nbt) {
		serializer.saveBeeSlots(nbt);
	}

	/** 从 NBT 加载蜜蜂槽数组 — 委托至 {@link ApiarySlotSerializer} */
	void loadBeeSlots(CompoundTag nbt) {
		serializer.loadBeeSlots(nbt);
	}

	/** 添加容器追踪器 — 委托至 {@link ApiarySlotSerializer} */
	void addContainerTrackers(MekanismContainer container) {
		serializer.addContainerTrackers(container);
	}

	// ===== 输出空间检查 =====

	/**
	 * 失效槽位上限缓存 — 委托至 {@link SlotLimitCache}
	 * <br/>
	 * 由主类在 {@code ModConfigEvent.Reloading} 事件中调用，确保配置 reload 后
	 * 依赖 stackMultiplier 的 limit 缓存立即失效。保留为静态方法以维持对外 API 不变
	 * （{@link TileEntityMekApiary} 通过 {@code ApiarySlotManager.invalidateCache()} 调用）。
	 */
	static void invalidateCache() {
		SlotLimitCache.invalidateCache();
	}

	/**
	 * 检查所有输出槽是否已满
	 * <br/>
	 * 使用 slot.getLimit(existing) 动态查询上限（支持分等级堆叠倍率），
	 * 通过 identity 缓存避免高频 getLimit 调用。
	 */
	boolean isOutputFull() {
		for (int i = 0; i < outputSlots.size(); i++) {
			BasicInventorySlot slot = outputSlots.get(i);
			ItemStack existing = slot.getStack();
			if (existing.isEmpty()) return false;
			if (existing.getCount() < slotLimitCache.getCachedSlotLimit(i, slot, existing)) return false;
		}
		return true;
	}

	// ===== AE2 集成支持 =====

	/**
	 * 检查输出槽是否有任意物品 — 供 AE2 推送器空输出短路
	 * <br/>
	 * 蜂箱输出槽数量少（9-18），直接遍历足够高效，无需维护标志位。
	 *
	 * @return true 如果任意输出槽有物品
	 */
	boolean hasOutputItems() {
		for (int i = 0; i < outputSlots.size(); i++) {
			if (!outputSlots.get(i).isEmpty()) return true;
		}
		return false;
	}

	/**
	 * 统计所有输出槽的物品总数 — 供 Ejector Mixin 比较弹出前后物品量
	 * <br/>
	 * 用于 TileComponentEjectorCooldownMixin 判断是否成功弹出。
	 *
	 * @return 所有输出槽物品总数
	 */
	long outputItemCount() {
		long count = 0;
		for (int i = 0; i < outputSlots.size(); i++) {
			ItemStack stack = outputSlots.get(i).getStack();
			if (!stack.isEmpty()) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * 刷新输出槽状态标志 — 蜂箱为 no-op
	 * <br/>
	 * 蜂箱直接遍历检查输出槽，无需标志位。仅为满足 PbRecipeContext 接口契约。
	 */
	void updateOutputSlotFlags() {
		// no-op：蜂箱不使用标志位优化
	}

	// ===== Getters =====

	/** 获取蜜蜂槽数组（只读，修改通过 BeeSlot 内部方法） */
	BeeSlot[] getBeeSlots() {
		return beeSlots;
	}

	/** 获取世界实例（供产出处理器查询配方） */
	@org.jetbrains.annotations.Nullable
	net.minecraft.world.level.Level getLevel() {
		return tile.getLevel();
	}

	/** 获取指定索引的蜜蜂槽 */
	BeeSlot getBeeSlot(int index) {
		if (index < 0 || index >= beeSlotCount) {
			throw new IndexOutOfBoundsException("Bee slot index: " + index + ", max: " + beeSlotCount);
		}
		return beeSlots[index];
	}

	/** 获取蜜蜂槽位数量 */
	int getBeeSlotCount() {
		return beeSlotCount;
	}

	/** 获取蜜蜂列数 — GUI布局用 */
	int getBeeCols() { return beeCols; }

	/** 获取蜜蜂行数 — GUI布局用 */
	int getBeeRows() { return beeRows; }

	/** 获取输出槽列表（只读视图，防止外部修改内部结构；TieredOutputInventorySlot extends BasicInventorySlot） */
	List<BasicInventorySlot> getOutputSlots() {
		return cachedUnmodifiableOutputSlots;
	}

	/** 获取输出列数 — GUI布局用 */
	int getOutputCols() { return outputCols; }

	/** 获取输出行数 — GUI布局用 */
	int getOutputRows() { return outputRows; }

	/** 获取蜂笼输入槽 */
	BasicInventorySlot getCageInSlot() {
		return cageInSlot;
	}

	/** 获取蜂笼输出槽 */
	BasicInventorySlot getCageOutSlot() {
		return cageOutSlot;
	}

	/** 获取能量槽 */
	EnergyInventorySlot getEnergySlot() {
		return energySlot;
	}

	/** 获取流体蜂蜜罐 */
	IExtendedFluidTank getFluidTank() {
		return fluidTank;
	}

	/** 获取流体罐容量 — 工厂版按等级递增 */
	int getFluidTankCapacity() {
		return fluidTankCapacity;
	}

	/** 获取所属方块实体 — 供拆分出的处理器访问回调 */
	TileEntityMekApiary getTile() {
		return tile;
	}

	// ===== 蜂笼操作（委托至蜂笼处理器） =====

	/** 处理蜂笼输入 — 委托至 {@link ApiaryCageHandler} */
	void processCageInput() {
		cageHandler.processCageInput();
	}

	/** 桶式取出蜜蜂 — 委托至 {@link ApiaryCageHandler}，返回装好的蜂笼（失败返回 EMPTY） */
	ItemStack tryCageBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		return cageHandler.tryCageBeeAtSlot(slotIndex, cursorCage);
	}

	/** 确认蜜蜂取出成功 — 委托至 {@link ApiaryCageHandler}，清空 BeeSlot 并标记保存 */
	void confirmCageExtraction(int slotIndex) {
		cageHandler.confirmCageExtraction(slotIndex);
	}

	/** 桶式放入蜜蜂 — 委托至 {@link ApiaryCageHandler} */
	boolean tryReleaseBeeAtSlot(int slotIndex, ItemStack cursorCage) {
		return cageHandler.tryReleaseBeeAtSlot(slotIndex, cursorCage);
	}
}
