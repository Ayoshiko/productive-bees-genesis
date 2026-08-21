package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import org.jetbrains.annotations.Nullable;

/**
	 * AE2 能量注入协调器
	 * <br/>
	 * 协调从 AE 网络提取能量并注入到离心机的 {@link MachineEnergyContainer}，
	 * 按配置优先级决定 AppliedFlux 与 AE2 原生能量的提取顺序。
	 * <p>
	 * <b>职责（DIP）</b>：依赖 {@link IAe2OutputHostBase} 抽象获取网格节点与能量容器，
	 * 不直接引用具体 TileEntity，保证任何实现 IAe2OutputHostBase 的类均可复用本注入器。
	 * <p>
	 * <b>职责（SRP）</b>：仅负责"协调提取与注入"流程，不负责：
	 * <ul>
	 *   <li>能量提取的底层实现（由 {@link Ae2EnergyBridge} 负责）</li>
	 *   <li>是否启用的配置守卫（由 {@link IAe2OutputHostBase#productivebeesgenesis$injectAe2Energy} 入口负责）</li>
	 *   <li>tick 时机控制（由调用方 tick 处理器负责）</li>
	 * </ul>
	 * <p>
	 * <b>提取流程（水位缓冲 + 真实需求差额提取）</b>：
	 * <ol>
	 *   <li>双重守卫：AE2 已安装 + grid 非 null</li>
	 *   <li>两批低水位、四批高水位，容量由统一策略负责并可修复旧存档异常容量</li>
	 *   <li>以四批高水位与当前缓存的差额作为本次提取目标，不设置固定 FE/t 截断</li>
	 *   <li>按优先级先 SIMULATE 模拟提取确定可获取量，再 MODULATE 实际提取</li>
	 *   <li>用 setEnergy 注入到容器（clamp 到 maxEnergy 防止溢出）</li>
	 * </ol>
	 * <p>
	 * 本地缓存使用两批低水位、四批高水位。既能覆盖高并行机器的下一批操作，
	 * 又不会为了填满历史异常容量而向 ME 网络发起无界请求。
	 * <p>
	 * <b>线程安全</b>：本类无状态，所有方法均为静态方法。AE2 网络操作在主线程进行，
	 * MachineEnergyContainer 内部使用原子类型保证线程安全。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
public final class Ae2EnergyInjector {

	private Ae2EnergyInjector() {}

	/**
	 * 从 AE 网络提取能量并注入到离心机的能量容器
	 * <br/>
	 * 按 {@link ModConfig#SERVER} 的优先级配置决定 AppliedFlux 与 AE2 原生能量的提取顺序。
	 * <p>
	 * <b>v2.0.0 变更</b>：移除 {@code maxAmount} 参数，注入量由容器剩余容量
	 * （{@code maxEnergy - currentEnergy}）决定。SIMULATE 模式确定 ME 网络实际可提取量，
	 * 最终注入量 = {@code min(容器剩余容量, ME 网络可提取量)}，与 Mek-Energistics 对齐。
	 * <p>
	 * <b>守卫</b>：
	 * <ul>
	 *   <li>AE2 未安装时返回 0（双重防御，调用方应已守卫）</li>
	 *   <li>host 为 null 时返回 0</li>
	 *   <li>grid 为 null 时返回 0</li>
	 *   <li>容器为 null 时返回 0</li>
	 *   <li>容器已满（remainingCapacity <= 0）时返回 0</li>
	 * </ul>
	 * <p>
	 * <b>提取策略</b>：
	 * <ul>
	 *   <li>优先源先 SIMULATE 模拟提取，获取实际可提取量（避免网络能量不足时 MODULATE 浪费）</li>
	 *   <li>再用 MODULATE 实际提取 SIMULATE 返回的量</li>
	 *   <li>剩余需求从次优先源 MODULATE 提取（不再 SIMULATE，因已通过容量计算保证容器可接收）</li>
	 * </ul>
	 * 注入到容器使用 setEnergy + clamp，避免依赖 canInsert 谓词。
	 *
	 * @param host AE2 输出宿主（离心机方块实体）
	 * @return 实际注入到容器的 FE 总量
	 *
	 * @since 2.0.0
	 */
	public static long injectEnergy(IAe2OutputHostBase host) {
		return injectEnergy(host, 1);
	}

	/**
	 * Refills the standard local buffer at the supplied accelerator batch size.
	 * Passing the batch multiplier from the tick handler avoids recomputing upgrade
	 * effects more than once per real tick.
	 */
	public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;
		return injectEnergy(host, host.productivebeesgenesis$getRequiredEnergyForBatch(batchMultiplier));
	}

	/**
	 * Refills the local buffer from the two-batch low-water mark to the four-batch
	 * high-water mark, clamped to Mekanism's standard upgrade-derived capacity.
	 */
	public static long injectEnergy(IAe2OutputHostBase host, long requiredThisTick) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;

		MachineEnergyContainer<?> container = host.productivebeesgenesis$getAe2EnergySource();
		if (container == null) return 0;

		requiredThisTick = Math.max(0L, requiredThisTick);

		long currentEnergy = container.getEnergy();
		long maxEnergy = container.getMaxEnergy();
		// 创意升级（无限容量）守卫：能耗恒为 0 无需外部供能；若能量未满，
		// remainingCapacity 为天文数字，缺失此守卫会一次性抽干 ME 网络存量
		// 填入无限容器（修改前因 target=2×需求=0 天然短路，此处显式防御）
		if (maxEnergy == Long.MAX_VALUE) return 0;
		// 每次注入前归一化容量，同时迁移旧存档残留的历史峰值缓存。
		MekCentrifugeEnergyScaling.normalizeCapacity(host);
		maxEnergy = container.getMaxEnergy();
		currentEnergy = container.getEnergy();
		long remainingCapacity = Ae2EnergyMath.remainingCapacity(currentEnergy, maxEnergy);
		if (remainingCapacity <= 0) return 0;

		// 两批低水位滞回：仍有完整批次储备时不重复探测 ME 网络。
		if (!Ae2EnergyMath.belowLowWater(currentEnergy, maxEnergy, requiredThisTick)) return 0;

		// 能量注入退避：ME 网络无能量期间跳过 SIMULATE 探测（每次均为完整网络遍历）。
		// 窗口 ≤ 4 tick 且容器缓冲 ≥ 2×单批需求，退避期内满速消耗无饥饿；
		// 任意部分成功立即重置（见方法尾部 recordSuccess）。
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Ae2PushBackoff energyBackoff = holder == null ? null : holder.getPushState().getEnergyBackoff();
		long nowNanos = System.nanoTime();
		if (energyBackoff != null && energyBackoff.shouldSkip(nowNanos)) return 0;
		// 只补到四批实际需求的高水位，避免历史异常容量触发巨额网络提取。
		long targetEnergy = Ae2EnergyMath.highWaterTarget(maxEnergy, requiredThisTick);
		long toExtract = targetEnergy > currentEnergy
		? targetEnergy - currentEnergy : 0L;
		if (toExtract <= 0L) return 0;
		IGrid grid = getConnectedGrid(host);
		if (grid == null) return 0;

		// Bug 7：读取优先级配置 — 由宿主提供，蜂箱与离心机相互独立
		boolean preferAppliedFlux = host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy();
		// 是否允许提取 AE2 原生能量 — 关闭后仅从 AppliedFlux 提取，
		// 避免网络 FE 不足时过量抽取 AE 原生能量导致 ME 网络断电（v1.0.2 新增配置）
		boolean nativeEnergyEnabled = host.productivebeesgenesis$isAeNativeEnergyInputEnabled();

		// 按优先级提取（原生能量禁用时退化为仅 AppliedFlux）
		// 按本次需求量执行，AE2 能量服务负责网络自身的能源安全
		long firstExtracted;
		long secondExtracted = 0;
		if (preferAppliedFlux || !nativeEnergyEnabled) {
			// 先 AppliedFlux，再 AE2 原生（原生禁用时跳过回退，仅 AppliedFlux）
			firstExtracted = Ae2EnergyMath.clampExtracted(extractFromAppliedFlux(grid, toExtract), toExtract);
			long remaining = toExtract - firstExtracted;
			if (remaining > 0 && nativeEnergyEnabled) {
				secondExtracted = Ae2EnergyMath.clampExtracted(
						extractFromAeNative(grid, remaining), remaining);
			}
		} else {
			// 先 AE2 原生，再 AppliedFlux
			firstExtracted = Ae2EnergyMath.clampExtracted(
					extractFromAeNative(grid, toExtract), toExtract);
			long remaining = toExtract - firstExtracted;
			if (remaining > 0) {
				secondExtracted = Ae2EnergyMath.clampExtracted(extractFromAppliedFlux(grid, remaining), remaining);
			}
		}

		// 注入到容器（clamp 防止溢出，虽然 remainingCapacity 已保证）
		Ae2EnergyMath.InjectionResult result = Ae2EnergyMath.apply(
				currentEnergy, maxEnergy, firstExtracted, secondExtracted);
		if (result.injected() > 0L) {
			container.setEnergy(result.energy());
		}
		// 退避记账：完全失败（网络无能量或 grid 断开）时进入短退避跳过后续探测；
		// 任意部分成功立即重置。holder 为 null 的边缘场景跳过记账（行为同旧版）。
		if (energyBackoff != null) {
			if (result.injected() > 0L) {
				energyBackoff.recordSuccess();
			} else {
				energyBackoff.recordFailure(System.nanoTime());
			}
		}
		return result.injected();
	}

	/**
	 * 从 AppliedFlux 提取 FE：按本次真实需求执行 bounded SIMULATE + MODULATE。
	 * <p>
	 * <b>守卫</b>：AppliedFlux 未安装时直接返回 0（上层自动回退 AE2 原生能量提取），
	 * 避免每次注入能量都执行两次守卫空调用（Issue #8 追加修复）。
	 *
	 * @param grid       AE2 网格
	 * @param toExtract  请求提取的 FE 数量
	 * @return 实际提取的 FE 数量
	 */
	private static long extractFromAppliedFlux(IGrid grid, long toExtract) {
		// AppliedFlux 未安装时短路：调用方回退 AE2 原生提取，行为与 FluxKey 守卫返回 0 等价
		if (!AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return 0;
		// 按本次真实需求做 bounded SIMULATE，避免极大探测请求触发额外网络扫描。
		long available = Ae2EnergyBridge.extractAppliedFluxFe(
				grid, toExtract, Actionable.SIMULATE, null);
		if (available <= 0) return 0;
		long amount = Math.min(toExtract, available);
		// MODULATE 实际提取（amount 不超过同一次 SIMULATE 的结果）
		return Ae2EnergyBridge.extractAppliedFluxFe(
				grid, amount, Actionable.MODULATE, null);
	}

	/**
	 * 从 AE2 原生能量提取并转换为 FE：按本次需求 bounded SIMULATE + MODULATE
	 * <br/>
	 * 与 {@link #extractFromAppliedFlux} 对称：探测网络可用 AE 原生能量，
	 * 按本次需求量执行，不再使用极大存量探测或固定 5% 截断。
	 * 网络自身的能源安全交由 AE2 能量服务处理，本模组不再截断实际机器需求。
	 *
	 * @param grid       AE2 网格
	 * @param toExtract  请求提取的 FE 数量
	 * @return 实际提取并转换为 FE 的数量
	 */
	private static long extractFromAeNative(IGrid grid, long toExtract) {
		// 按本次真实需求做 bounded SIMULATE，避免把极大请求传入 AE2 能量服务。
		long available = Ae2EnergyBridge.extractAeEnergyAsFe(
				grid, toExtract, Actionable.SIMULATE);
		if (available <= 0) return 0;
		long amount = Math.min(toExtract, available);
		return Ae2EnergyBridge.extractAeEnergyAsFe(grid, amount, Actionable.MODULATE);
	}

	/**
	 * 获取宿主已连接的 AE2 网格
	 * <br/>
	 * Task 12：通过 {@link Ae2GridNodeManager#getCachedGrid} 使用 holder 缓存，
	 * gridChanged 回调失效，避免高频注入能量时重复调用 managedNode.getGrid()。
	 *
	 * @param host 输出宿主
	 * @return 已连接的网格，未连接或节点不存在时返回 null
	 */
	@Nullable
	private static IGrid getConnectedGrid(IAe2OutputHostBase host) {
		return Ae2GridNodeManager.getCachedGrid(host);
	}
}
