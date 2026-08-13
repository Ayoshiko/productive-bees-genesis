package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ObjIntConsumer;

/**
	 * 工厂升级状态与运行时辅助类 — 封装升级重算、ticks计算、信息显示、
	 * 进程激活、输入槽拉取和服务器tick更新逻辑。
	 * <br/>
	 * 抽自 {@link AbstractMekCentrifugeFactory}，降低单文件行数（Task 11）。
	 * 所有方法为静态，通过传入工厂实例操作，避免引入额外状态。
	 *
	 * @author ayoshiko
	 * @since Task 11
	 */
public final class FactoryUpgradeStateHelper {

	private FactoryUpgradeStateHelper() {
	}

	// ===== 升级重算与查询 =====

	/**
	 * 重算升级效果 — 复刻MEKExtras，支持STACK升级并行和CREATIVE无限能量。
	 * <br/>
	 * 由 {@link AbstractMekCentrifugeFactory#recalculateUpgrades(Upgrade)} 在调用
	 * super 后委托执行。
	 *
	 * @param factory	工厂实例
	 * @param upgrade	触发的升级类型
	 */
	public static void recalculateUpgrades(@NotNull AbstractMekCentrifugeFactory factory, @NotNull Upgrade upgrade) {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MekCreativeEnergyHelper.recalculateCreativeEnergy(factory.energyContainer(), upgrade,
					MekUpgradeSupport.hasCreativeUpgrade(factory));
		}
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) factory;
		if (upgrade == Upgrade.SPEED) {
			accessor.productivebeesgenesis$setTicksRequired(MekanismUtils.getTicks(factory, factory.baseTicksRequired()));
		}
		int maxOps = getUpgradeMaxOperations(factory);
		int speedAdjustedOps = MekanismUtils.getOperationsPerTick(
				factory, factory.baseTicksRequired(), maxOps);
		accessor.productivebeesgenesis$setOperationsPerTick(
				MekExtrasUpgradeSemantics.operationsPerTick(
						MekUpgradeSupport.hasCreativeUpgrade(factory), maxOps, speedAdjustedOps));
	}

	/**
	 * 计算STACK升级提供的最大并行数（2^stackUpgrades，满级8=256倍）。
	 *
	 * @param factory	工厂实例
	 * @return 最大并行数
	 */
	public static int getUpgradeMaxOperations(@NotNull AbstractMekCentrifugeFactory factory) {
		int stackUpgrades = MekUpgradeSupport.getStackUpgrades(factory);
		// 位运算替代 Math.pow：stackUpgrades 最大 16，1 << 16 = 65536 不会溢出
		return stackUpgrades > 0 ? 1 << stackUpgrades : 1;
	}

	/**
	 * 获取处理所需 ticks。对齐 Mekanism Extras：CREATIVE 返回 0，实现最大处理速率。
	 *
	 * @param factory	工厂实例
	 * @return 所需ticks
	 */
	public static int getTicksRequired(@NotNull AbstractMekCentrifugeFactory factory) {
		return MekUpgradeSupport.hasCreativeUpgrade(factory) ? 0
				: ((TileEntityFactoryAccessor) factory).productivebeesgenesis$getTicksRequired();
	}

	/**
	 * 构建升级信息列表 — STACK显示并行倍数，CREATIVE显示∞效率和0能耗。
	 *
	 * @param factory	工厂实例
	 * @param upgrade	升级类型
	 * @return 信息组件列表
	 */
	@NotNull
	public static List<Component> getInfo(@NotNull AbstractMekCentrifugeFactory factory, @NotNull Upgrade upgrade) {
		List<Component> ret = new ArrayList<>(UpgradeUtils.getMultScaledInfo(factory, upgrade));
		if (MekUpgradeSupport.isStackUpgrade(upgrade)) {
			ret.clear();
			// 位运算替代 Math.pow（int → double 自动拓宽）
			double stack = 1 << MekUpgradeSupport.getStackUpgrades(factory);
			ret.add(Component.translatable("gui.productivebeesgenesis.upgrades.stack", stack));
		} else if (MekUpgradeSupport.isCreativeUpgrade(upgrade)) {
			ret.add(Component.translatable("gui.mekanism.upgrades.effect", "∞"));
			ret.add(Component.translatable("gui.productivebeesgenesis.energy_consumption", 0));
		}
		return ret;
	}

	// ===== 进程激活状态 =====

	/**
	 * 设置PB进程激活状态 — 触发激活/反激活回调后设置活跃状态。
	 *
	 * @param factory			工厂实例
	 * @param active			是否激活
	 * @param process			进程索引
	 * @param setActiveState	设置活跃状态的回调（工厂继承的受保护方法，由工厂以方法引用传入）
	 */
	public static void setPbActiveState(@NotNull AbstractMekCentrifugeFactory factory, boolean active, int process,
			@NotNull ObjIntConsumer<Boolean> setActiveState) {
		if (active) {
			factory.productivebeesgenesis$onProcessActivated(process);
		} else {
			factory.productivebeesgenesis$onProcessDeactivated(process);
		}
		setActiveState.accept(active, process);
	}

	// ===== 输入槽拉取 =====

	/**
	 * 获取用于拉取的输入槽列表 — 工厂版每进程1个输入槽，按顺序填充。
	 *
	 * @param factory	工厂实例
	 * @return 输入槽列表
	 */
	@NotNull
	public static List<IInventorySlot> getInputSlotsForPull(@NotNull AbstractMekCentrifugeFactory factory) {
		int processes = factory.processes();
		List<IInventorySlot> slots = new ArrayList<>(processes);
		for (int i = 0; i < processes; i++) {
			IInventorySlot slot = factory.inputSlot(i);
			if (slot != null) slots.add(slot);
		}
		return slots;
	}

	// ===== 服务器tick更新 =====

	/**
	 * 服务器端tick更新 — 先走SMELTING管线，再处理PB配方，末尾推送输出到AE2网络。
	 * <br/>
	 * 由 {@link AbstractMekCentrifugeFactory#onUpdateServer()} 委托执行，
	 * super调用通过回调传入，避免辅助类无法调用super的限制。
	 * <p>
	 * skipPb 批量收获机制（镜像 MekCentrifugeTickHandler / ApiaryTickHandler）：
	 * 在 256x JDTE 加速下，onUpdateServer 每游戏刻会被调用 256 次，若每次都完整执行
	 * PB 配方处理将导致服务器冻结。通过 {@link TickBatchSkipState#decideAction} 采用
	 * "虚拟 tick 银行 + 每 tick 预算"策略：本 gameTick 第一个入口执行 PB（从共享预算取批量倍率），
	 * 后续调用跳过 PB 处理，仅保留 super 与 AE2 推送；同 gameTick 已由 JDTE flush 完整处理时
	 * 完全跳过（含 super，避免双跑）。decideAction 内部已调用 tracker.onTick(level)，
	 * 故此处不再单独调用（避免重复计数）。
	 * <p>
	 * AE2 推送/拉取批处理（v2.0.0+）：{@link CentrifugeFactoryCommonLogic#pushAe2OutputsAndPullInputs}
	 * 已移入 if (!skipPb) 块内,与 PB 一致地批处理。256x 加速下仅第 1 次 tick 执行完整 AE2 操作,
	 * 第 2-256 次 tick 跳过 AE2 推送/拉取（但仍执行 tryConnectNode 和 injectAe2Energy 保证能量供应）。
	 * 进度累加和能量消耗由 superCall.getAsBoolean() 处理,不损失产出速率。
	 *
	 * @param factory		工厂实例
	 * @param inputSlots	输入槽列表（工厂继承的受保护字段，由工厂传入）
	 * @param superCall		super.onUpdateServer() 的回调
	 * @return 是否需要发送更新包
	 */
	public static boolean onUpdateServer(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull List<IInventorySlot> inputSlots, @NotNull BooleanSupplier superCall) {
		// 入口处通过 TickBatchSkipState 判断是否跳过 PB（内部已调用 tracker.onTick,避免重复计数）
		TickAccelTracker tracker = factory.productivebeesgenesis$getTickAccelTracker();
		Level level = factory.productivebeesgenesis$getAe2Level();
		TickBatchSkipState skipState = factory.productivebeesgenesis$getTickBatchSkipState();
		TickBatchSkipState.TickAction action = skipState.decideAction(tracker, level);
		if (action == TickBatchSkipState.TickAction.ALREADY_HANDLED) {
			// 同 gameTick 已由 JDTE flush 完整处理：完全跳过（含 super，避免双跑）
			return false;
		}
		boolean skipPb = action == TickBatchSkipState.TickAction.SKIP;

		// tryConnectNode 由 Task 13 volatile 门控优化,保留每 tick 调用
		factory.productivebeesgenesis$getAe2LifecycleHandler().tryConnectNode(factory);
		// injectAe2Energy 保留每 tick 调用,保证能量供应
		factory.productivebeesgenesis$injectAe2Energy();
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) factory;
		long energyBeforeSuper = factory.energyContainer().getEnergy();
		boolean sendUpdatePacket = superCall.getAsBoolean(); // super 始终调用

		boolean result;
		if (!skipPb) {
			// 输入槽状态变化频率低,每 gameTick 1 次足够,跳过 256x 下 255 次无意义操作
			factory.pbUpgradeDelegate.processPbUpgradeInput();
			factory.delegate.resetSortingMark();
			// 执行 PB：设置批量倍率（虚拟 tick 银行取款,本 gameTick 第一次调用）
			factory.pbProcessor.setTickMultiplier(skipState.getBatchMultiplier());
			result = MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
				sendUpdatePacket, energyBeforeSuper, factory.energyContainer(), factory.processes(),
				inputSlots, factory.pbProcessor, factory, factory.getActive(), factory::setActive,
				v -> accessor.productivebeesgenesis$setLastUsage(v));
			// AE2 推送/拉取与 PB 一致批处理：256x 加速下第 2-256 次 tick 跳过
			// tryConnectNode 和 injectAe2Energy 仍每 tick 调用,保证能量供应
			CentrifugeFactoryCommonLogic.pushAe2OutputsAndPullInputs(factory);
		} else {
			// 跳过 PB：本 gameTick 后续调用（256x 加速下第 2~256 次）,仅保留 super 返回值
			result = sendUpdatePacket;
		}

		return result;
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine.flushAcceleratedTicks} 工厂版入口。
	 * <br/>
	 * JDTE 批量 pass 结束时调用一次：从共享预算取批量倍率并执行一次<b>完整 tick</b>
	 * （tryConnectNode + 能量注入 + super + PB 配方处理 + AE2 推送/拉取），
	 * 与基础机/蜂箱的 flush 语义一致；同一 gameTick 的去重由调用方
	 * （{@link AbstractMekCentrifugeFactory#productivebeesgenesis$flushAcceleratedTicks}）的门控负责。
	 *
	 * @param factory    工厂方块实体
	 * @param inputSlots 工厂输入槽
	 * @param batchMultiplier 批量倍率（调用方已从共享预算取出）
	 * @param superCall  super.onUpdateServer() 的回调（SMELTING 管线 + ejector）
	 */
	public static void onCoalescedFlush(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull List<IInventorySlot> inputSlots, int batchMultiplier,
			@NotNull BooleanSupplier superCall) {
		TileEntityFactoryAccessor accessor = (TileEntityFactoryAccessor) factory;
		// 与 onUpdateServer 的 !skipPb 分支对齐：tryConnectNode 与能量注入在 super 前执行
		factory.productivebeesgenesis$getAe2LifecycleHandler().tryConnectNode(factory);
		factory.productivebeesgenesis$injectAe2Energy();
		long energyBeforeSuper = factory.energyContainer().getEnergy();
		boolean sendUpdatePacket = superCall.getAsBoolean();
		// 升级输入、排序重置、PB 配方处理（批量倍率由调用方传入）
		factory.pbUpgradeDelegate.processPbUpgradeInput();
		factory.delegate.resetSortingMark();
		factory.pbProcessor.setTickMultiplier(batchMultiplier);
		MekCentrifugeFactoryHelper.processPbRecipesAndUpdate(
				sendUpdatePacket, energyBeforeSuper, factory.energyContainer(), factory.processes(),
				inputSlots, factory.pbProcessor, factory, factory.getActive(), factory::setActive,
				v -> accessor.productivebeesgenesis$setLastUsage(v));
		// AE2 推送/拉取与 PB 同批处理（与 onUpdateServer 的 !skipPb 分支一致）
		CentrifugeFactoryCommonLogic.pushAe2OutputsAndPullInputs(factory);
	}
}
