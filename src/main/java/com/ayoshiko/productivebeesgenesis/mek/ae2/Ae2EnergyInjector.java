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
	 * <b>提取流程（v1.0.2 填满容器 + 差额提取）</b>：
	 * <ol>
	 *   <li>双重守卫：AE2 已安装 + grid 非 null</li>
	 *   <li>容量下限保证（2×单tick需求储备），仅扩容不缩容</li>
	 *   <li>以容器剩余容量（maxEnergy - currentEnergy）作为本次提取目标，差多少补多少</li>
	 *   <li>按优先级先 SIMULATE 模拟提取确定可获取量，再 MODULATE 实际提取</li>
	 *   <li>用 setEnergy 注入到容器（clamp 到 maxEnergy 防止溢出）</li>
	 * </ol>
	 * <p>
	 * <b>v1.0.2 设计意图</b>：提取目标为<b>填满容器</b>，GUI 能量条与实际缓存一致——
	 * 供电充足时机器内部缓存满。此前版本（v2.0.0 系列）按 2×需求低水位注入，
	 * 与 GUI 显示分母（能量升级扩容后的 maxEnergy）脱节，导致"显示空/不填满"。
	 * 最终注入量由 {@code min(容器剩余容量, ME 网络可提取量)} 决定，稳态下
	 * 每 tick 仅补回本 tick 消耗的差额，网络操作次数不变。
	 * <p>
	 * <b>线程安全</b>：本类无状态，所有方法均为静态方法。AE2 网络操作在主线程进行，
	 * MachineEnergyContainer 内部使用原子类型保证线程安全。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
public final class Ae2EnergyInjector {

	/**
	 * 网络存量探测请求量（SIMULATE）：取远超任何实际存量的值，
	 * 使 SIMULATE 返回值等于网络真实可用量。取 Long.MAX_VALUE / 4
	 * 避免 AE2/AppliedFlux 内部 FE↔AE 换算（×2）时溢出。
	 */
	private static final long NETWORK_PROBE_AMOUNT = Long.MAX_VALUE / 4;

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
	 * Refills the container to full capacity at the supplied accelerator batch size.
	 * Passing the batch multiplier from the tick handler avoids recomputing upgrade
	 * effects more than once per real tick.
	 */
	public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;
		return injectEnergy(host, host.productivebeesgenesis$getRequiredEnergyForBatch(batchMultiplier));
	}

	/**
	 * Refills the container up to its full capacity, keeping a two-batch reserve as the
	 * minimum capacity floor. Tick handlers use this overload so upgrade, process and
	 * accelerator multipliers are evaluated only once per batch.
	 * <p>
	 * <b>注入目标语义（v1.0.2）</b>：提取目标从容量的低水位标记（2×需求）改为
	 * <b>填满容器</b>。原低水位实现与 GUI 显示分母（{@code maxEnergy}）脱节：能量升级
	 * 使 Mekanism 容量扩为 {@code 基础 × 2^n}，而注入水位仅 {@code 2×每tick需求}，
	 * 供电充足时 GUI 能量条仍显示接近 0%（用户反馈的"显示空/不填满"）。
	 * <p>
	 * 填满语义下的稳态成本不变：容器满时 {@code remainingCapacity <= 0} 直接短路
	 * （零网络操作）；每 tick 仅补回本 tick 消耗的差额，SIMULATE+MODULATE 次数与
	 * 原实现一致。首次填充为一次性大额提取，受 ME 网络实际存量限制（SIMULATE
	 * 返回可提取量），与外部 FE 电缆首次充能机器的行为一致。
	 * <p>
	 * 容量下限（2×需求储备）保留：防止恰好填满→耗尽循环导致同步能量条抖动，
	 * 并保证加速批量扣除有足够缓冲。
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

		// 容量下限：2×需求储备（防抖动 + 批量扣除缓冲），仅扩容不缩容
		long minCapacity = MekCentrifugeEnergyScaling.bufferedCapacityForDemand(requiredThisTick);
		if (minCapacity > 0 && maxEnergy < minCapacity) {
			container.setMaxEnergy(minCapacity);
			maxEnergy = container.getMaxEnergy();
		}
		long remainingCapacity = Ae2EnergyMath.remainingCapacity(currentEnergy, maxEnergy);
		if (remainingCapacity <= 0) return 0;

		// 能量注入退避：ME 网络无能量期间跳过 SIMULATE 探测（每次均为完整网络遍历）。
		// 窗口 ≤ 4 tick 且容器缓冲 ≥ 2×单批需求，退避期内满速消耗无饥饿；
		// 任意部分成功立即重置（见方法尾部 recordSuccess）。
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Ae2PushBackoff energyBackoff = holder == null ? null : holder.getPushState().getEnergyBackoff();
		long nowNanos = System.nanoTime();
		if (energyBackoff != null && energyBackoff.shouldSkip(nowNanos)) return 0;

		// 注入目标 = 填满容器：供电充足时内部缓存满，GUI 能量条按 maxEnergy 满显示
		long toExtract = remainingCapacity;

		IGrid grid = getConnectedGrid(host);
		if (grid == null) return 0;

		// Bug 7：读取优先级配置 — 由宿主提供，蜂箱与离心机相互独立
		boolean preferAppliedFlux = host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy();
		// 是否允许提取 AE2 原生能量 — 关闭后仅从 AppliedFlux 提取，
		// 避免网络 FE 不足时过量抽取 AE 原生能量导致 ME 网络断电（v1.0.2 新增配置）
		boolean nativeEnergyEnabled = host.productivebeesgenesis$isAeNativeEnergyInputEnabled();

		// 按优先级提取（原生能量禁用时退化为仅 AppliedFlux）
		// v1.0.2：两条路径均按 networkExtractCap 保留 5% 网络存量，防止大额请求抽干共享网络
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
	 * 从 AppliedFlux 提取 FE：先 SIMULATE 探测网络真实存量，按保留比例封顶后 MODULATE 实际提取
	 * <br/>
	 * v1.0.2：探测请求改用大额 PROBE（SIMULATE 返回 min(请求, 存量)，即网络真实可用量），
	 * 提取量经 {@link Ae2EnergyMath#networkExtractCap} 封顶（保留 5% 存量），
	 * 防止大额首次填充请求瞬间抽干共享 FE 存储。供电充足时提取量远小于存量，
	 * 封顶不生效，行为与原"SIMULATE 需求量 + MODULATE"实现一致。
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
		// SIMULATE 大额探测：返回值 = min(PROBE, 网络存量) = 网络真实可用量
		long available = Ae2EnergyBridge.extractAppliedFluxFe(
				grid, NETWORK_PROBE_AMOUNT, Actionable.SIMULATE, null);
		if (available <= 0) return 0;
		long amount = Math.min(toExtract, Ae2EnergyMath.networkExtractCap(available));
		if (amount <= 0) return 0;
		// MODULATE 实际提取（amount ≤ available，不会超抽）
		return Ae2EnergyBridge.extractAppliedFluxFe(
				grid, amount, Actionable.MODULATE, null);
	}

	/**
	 * 从 AE2 原生能量提取并转换为 FE：SIMULATE 探测 + 保留比例封顶 + MODULATE（v1.0.2）
	 * <br/>
	 * 与 {@link #extractFromAppliedFlux} 对称：探测网络可用 AE 原生能量，
	 * 经 {@link Ae2EnergyMath#networkExtractCap} 封顶后实际提取。
	 * 保留 5% 存量优先保障 ME 网络自身运行（AE 原生能量过低会导致网络掉电）。
	 *
	 * @param grid       AE2 网格
	 * @param toExtract  请求提取的 FE 数量
	 * @return 实际提取并转换为 FE 的数量
	 */
	private static long extractFromAeNative(IGrid grid, long toExtract) {
		// SIMULATE 大额探测网络可用量（extractAeEnergyAsFe 内部完成 AE↔FE 换算与 clamp）
		long available = Ae2EnergyBridge.extractAeEnergyAsFe(
				grid, NETWORK_PROBE_AMOUNT, Actionable.SIMULATE);
		if (available <= 0) return 0;
		long amount = Math.min(toExtract, Ae2EnergyMath.networkExtractCap(available));
		if (amount <= 0) return 0;
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
