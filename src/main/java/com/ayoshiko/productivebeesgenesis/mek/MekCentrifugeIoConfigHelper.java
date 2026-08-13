package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityEjectorAccessor;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import mekanism.api.IContentsListener;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.config.MekanismConfig;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.IProxiedSlotInfo;
import mekanism.common.tile.interfaces.ISideConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * MEK centrifuge IO/fluid configuration support (split from {@link MekCentrifugeFactoryHelper}, SRP).
	 * <br/>
	 * Responsibilities: fluid output holder creation, tertiary output slot and IO configuration,
	 * and fluid output ConfigInfo wiring. Stateless; entry points are forwarded by
	 * {@link MekCentrifugeFactoryHelper}.
	 */
final class MekCentrifugeIoConfigHelper {

	private MekCentrifugeIoConfigHelper() {
	}

	// ===== 流体输出槽创建 =====

	/**
	 * 创建流体输出槽持有者 — 根据 mekCentrifugeMultiFluidTank 和 isClient 选择模式。
	 * <p>
	 * <b>Task 2/4 根因修复：</b>客户端始终创建 MULTI holder（无视 ModConfig.SERVER），
	 * 避免客户端 SINGLE(1 tank) 与服务端 MULTI(N tanks) 的 DataSlot 数量差异导致 out of bounds。
	 * 通过同步值 {@code isMultiFluidModeSynced} 控制 Tab 是否显示（而非 holder 类型）。
	 * <p>
	 * <b>设计原则（Task 5 修正）：</b>MULTI 模式遵循"一个输入并行一个流体槽"，
	 * 即 maxTanks = {@code processes}。每子槽容量 = {@link Integer#MAX_VALUE}，
	 * 256× 加速下单进程每 tick 约 100 万 mB，单槽可容纳约 2140 tick 产出，避免流体推送瓶颈。
	 *
	 * @param factory 工厂实例
	 * @param listener 监听器
	 * @param processes 进程数(tier.processes)
	 * @param fluidTankMultiplier 容量倍率(MULTI 模式下忽略,每子槽固定 Integer.MAX_VALUE)
	 * @param isClient 是否为客户端 — true 时始终创建 MULTI holder(根因修复)
	 * @param tankSetter SINGLE 下赋值给 fluidOutputTank;MULTI 设置主槽引用
	 * @param tankCountSetter 接收初始槽位数(MULTI=maxTanks,SINGLE=1),构造时写入实例字段避免 Tab 窗口过窄
	 * @return IFluidTankHolder
	 */
	@NotNull
	public static IFluidTankHolder createFluidOutputHolder(
			@NotNull ISideConfiguration factory,
			@NotNull IContentsListener listener,
			int processes,
			@NotNull IntSupplier fluidTankMultiplier,
			boolean isClient,
			@NotNull Consumer<IExtendedFluidTank> tankSetter,
			@NotNull Consumer<Integer> tankCountSetter) {
		// Task 12: ModConfig.SERVER 未加载时(客户端构造期间)默认创建 MULTI,匹配服务端可能的 MULTI
		boolean configAvailable = false;
		boolean multiFluidEnabled = false;
		int maxTanksPerFluidConfig = 0; // v2.0.9: 默认自动计算
		try {
			multiFluidEnabled = ModConfig.SERVER.mekCentrifugeMultiFluidTank.get();
			maxTanksPerFluidConfig = ModConfig.SERVER.mekCentrifugeMaxTanksPerFluid.get();
			configAvailable = true;
		} catch (NullPointerException e) {
			DevLog.warn("fluid_tank", "createFluidOutputHolder 调用时 ModConfig.SERVER 未加载(潜在问题 9)");
		}
		// Task 2/4: 客户端始终创建 MULTI holder,服务端根据配置决定
		// !configAvailable(NPE)也创建 MULTI — 客户端构造期间 ModConfig.SERVER 未加载,需匹配服务端可能的 MULTI
		boolean createMulti = isClient || multiFluidEnabled || !configAvailable;
		if (createMulti) {
			// 设计：一个输入并行一个流体槽（maxTanks = processes）
			// 每子槽容量 = Integer.MAX_VALUE，256× 加速下单进程每 tick 约 100 万 mB，可容纳 2140 tick 产出
			int maxTanks = processes;
			int tankCapacity = Integer.MAX_VALUE;
			// v2.0.9: 传入 maxTanksPerFluidConfig（0=自动计算 maxTanks/2），由 Holder 构造时解析
			MultiFluidTankHolder multiHolder = new MultiFluidTankHolder(maxTanks, tankCapacity, listener,
				maxTanksPerFluidConfig);
			// Task 2: 调用 tankSetter 设置主槽引用,修复 fluidOutputTank 字段为 null 的核心 bug
			// Task 5: 构造时已预分配全部槽位,getTanks().get(0) 返回预分配的第 0 个槽
			tankSetter.accept(multiHolder.getTanks().get(0));
			// Task 1: 构造时即将 fluidOutputTankCount 设为 maxTanks,避免客户端 Tab 窗口基于默认值 1 计算过窄
			tankCountSetter.accept(maxTanks);
			return multiHolder;
		}
		// SINGLE 模式:保持原逻辑(单槽共享,容量随进程数和 tier 倍率缩放)
		FluidTankHelper helper = FluidTankHelper.forSideWithConfig(factory);
		long baseCapacity = readFluidTankCapacitySafely();
		long multiplier = fluidTankMultiplier.getAsInt();
		int capacity = (int) Math.min(baseCapacity * processes * multiplier, Integer.MAX_VALUE);
		IExtendedFluidTank tank = BasicFluidTank.output(capacity, listener);
		tankSetter.accept(tank);
		helper.addTank(tank);
		// Task 1: SINGLE 模式槽位数固定为 1,构造时即写入实例字段
		tankCountSetter.accept(1);
		return helper.build();
	}

	/**
	 * 安全读取流体槽容量配置 — ModConfig.SERVER 未加载时返回默认值 256000
	 * <br/>
	 * 客户端 Container 构造期间 ModConfig.SERVER 可能未加载,直接读取会抛 NPE。
	 * 使用默认值不影响功能:客户端容量仅用于显示,实际流体数据通过 NBT 同步恢复。
	 */
	private static long readFluidTankCapacitySafely() {
		try {
			return ModConfig.SERVER.mekCentrifugeFluidTankCapacity.get();
		} catch (NullPointerException e) {
			return 256000L;
		}
	}

	// ===== 构造函数公共逻辑 =====

	/**
	 * 注册副输出槽2并配置工厂 IO 与弹出器 — 抽取自三工厂构造函数。
	 * 任务:1. tertiaryOutputSlots 加入 outputSlots;2. setupItemIOConfig 注册 OUTPUT;
	 * 3. 配置流体输出侧面(右侧);4. 重写 ejectorComponent 添加 FLUID 弹出。
	 * Task 6 多槽动态弹出:MULTI_PER_FLUID 通过 IProxiedSlotInfo.FluidProxy 包装 MultiFluidTankHolder,
	 * 让 Ejector 通过 IFluidTankHolder.getTanks(side) 动态获取槽列表(包括后创建的槽),而非静态 List 副本。
	 * SINGLE 传 primaryFluidOutputTank,行为与原版一致。
	 * @param factory 工厂实例;@param configComponent 配置组件;@param inputSlots 输入槽;@param outputSlots 输出槽(会被追加)
	 * @param tertiaryOutputSlots 副输出槽2;@param processes 进程数;@param energySlot 能量槽;@param energyContainer 能量容器
	 * @param fluidOutputHolder 流体持有者;@param primaryFluidOutputTank 主流体槽(SINGLE 用);@param fluidEjectRate 弹出速率
	 * @return TileComponentEjector
	 */
	@NotNull
	public static TileComponentEjector setupTertiarySlotsAndIO(
			@NotNull TileEntityMekanism factory,
			@NotNull TileComponentConfig configComponent,
			@NotNull List<IInventorySlot> inputSlots,
			@NotNull List<IInventorySlot> outputSlots,
			@NotNull IInventorySlot[] tertiaryOutputSlots,
			int processes,
			@NotNull EnergyInventorySlot energySlot,
			@NotNull MachineEnergyContainer<?> energyContainer,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@Nullable IExtendedFluidTank primaryFluidOutputTank,
			@NotNull IntSupplier fluidEjectRate) {
		// 将副输出槽2加入outputSlots列表，使其参与侧面配置和弹出器
		for (int i = 0; i < processes; i++) {
			outputSlots.add(tertiaryOutputSlots[i]);
		}
		// 重新调用setupItemIOConfig，将tertiaryOutputSlots注册到OUTPUT DataType
		configComponent.setupItemIOConfig(inputSlots, outputSlots, energySlot, false);
		configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);
		// Task 6: 配置流体输出侧面（右侧）— 区分 MULTI_PER_FLUID 和 SINGLE 模式
		// MULTI_PER_FLUID 模式：使用 IProxiedSlotInfo.FluidProxy 包装 MultiFluidTankHolder,
		//   Ejector 通过 IProxiedSlotInfo.FluidProxy.getTanks() -> multiHolder.getTanks() 动态获取槽列表
		//   语义等价于 "Ejector 通过 IFluidTankHolder.getTanks(side) 动态获取槽列表"
		//   直接调用 config.addSlotInfo 绕过 createInfo 的 List 强转(因为 MultiFluidTankHolder 不是 List)
		// SINGLE 模式：传入单个槽,通过 setupOutputConfig 走原版路径
		setupFluidOutputConfig(configComponent, fluidOutputHolder, primaryFluidOutputTank);
		// 重写ejectorComponent添加FLUID弹出（父类TileEntityFactory只配置了ITEM）
		// 使用自定义流体弹出速率，并把物品弹出 tickDelay 设为 1 tick
		// 注：chemicalAutoEjectRate 在此作为物品弹出速率参数，与 Mekanism 原版 TileEntityFactory 一致
		TileComponentEjector ejector = new TileComponentEjector(factory, MekanismConfig.general.chemicalAutoEjectRate,
			fluidEjectRate);
		((TileEntityEjectorAccessor) ejector).productivebeesgenesis$setTickDelay(1);
		ejector.setOutputData(configComponent, TransmissionType.ITEM, TransmissionType.FLUID);
		return ejector;
	}

	/**
	 * Task 6: 配置流体输出侧面 — MULTI_PER_FLUID 用 IProxiedSlotInfo.FluidProxy,SINGLE 用 setupOutputConfig
	 * <br/>
	 * <b>Ejector 弹出原理：</b>Ejector 通过 {@code FluidSlotInfo.getTanks()} 获取槽列表遍历弹出。
	 * <ul>
	 *   <li>MULTI_PER_FLUID 模式：使用 {@link IProxiedSlotInfo.FluidProxy} 包装 multiHolder::getTanks,
	 *       Ejector 每次弹出调用 FluidProxy.getTanks() 动态获取当前所有槽(包括后创建的槽),
	 *       而非静态 List 副本(原方案问题:后创建的槽不会出现在 Ejector 弹出列表中)。</li>
	 *   <li>SINGLE 模式：通过 setupOutputConfig 走原版路径,行为不变。</li>
	 * </ul>
	 * <p>
	 * <b>绕过 createInfo 的原因：</b>TileComponentConfig.createInfo 对 FLUID 类型强转 List&lt;IExtendedFluidTank&gt;,
	 * 直接传入 MultiFluidTankHolder 实例会 ClassCastException。FluidProxy 继承 FluidSlotInfo,
	 * 可直接通过 config.addSlotInfo 注册,无需 createInfo 中介。
	 * SRP 抽出避免 setupTertiarySlotsAndIO 过长。
	 */
	private static void setupFluidOutputConfig(
			@NotNull TileComponentConfig configComponent,
			@Nullable IFluidTankHolder fluidOutputHolder,
			@Nullable IExtendedFluidTank primaryFluidOutputTank) {
		if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			// MULTI_PER_FLUID 模式:用 IProxiedSlotInfo.FluidProxy 包装,动态获取槽列表
			// canInput=false, canOutput=true(输出槽语义)
			// supplier 调用 multiHolder.getTanks() 返回当前所有槽的副本(防御性)
			IProxiedSlotInfo fluidProxy = new IProxiedSlotInfo.FluidProxy(false, true, multiHolder::getTanks);
			ConfigInfo fluidConfig = configComponent.getConfig(TransmissionType.FLUID);
			if (fluidConfig != null) {
				fluidConfig.addSlotInfo(DataType.OUTPUT, fluidProxy);
			}
			return;
		}
		// SINGLE 模式:走原版 setupOutputConfig 路径(单个槽包装成 List)
		// primaryFluidOutputTank 可能为 null(构造初期 fallback),由 setupOutputConfig 内部处理
		configComponent.setupOutputConfig(TransmissionType.FLUID, primaryFluidOutputTank, RelativeSide.RIGHT);
	}
}
