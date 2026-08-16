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
	 * <b>提取流程（v2.0.0 按需差额提取，与 Mek-Energistics 对齐）</b>：
	 * <ol>
	 *   <li>双重守卫：AE2 已安装 + grid 非 null</li>
	 *   <li>获取容器并计算剩余容量（maxEnergy - currentEnergy）作为本次提取上限</li>
	 *   <li>按优先级先 SIMULATE 模拟提取确定可获取量，再 MODULATE 实际提取</li>
	 *   <li>用 setEnergy 注入到容器（clamp 到 maxEnergy 防止溢出）</li>
	 * </ol>
	 * <p>
	 * <b>v2.0.0 设计意图</b>：移除 perTick 注入上限配置（{@code aeEnergyInjectionPerTick}），
	 * 改为按容器剩余容量差额提取——容器差多少就补多少，最终注入量由
	 * {@code min(容器剩余容量, ME 网络可提取量)} 决定。避免人为 perTick 上限导致
	 * 高耗能场景下能量补充速度跟不上消耗，与 Mek-Energistics 的能量注入策略对齐。
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
	 * Refills to the supplied batch demand plus one reserve batch. Passing the batch multiplier
	 * from the tick handler avoids recomputing upgrade effects more than once per real tick.
	 */
	public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;
		return injectEnergy(host, host.productivebeesgenesis$getRequiredEnergyForBatch(batchMultiplier));
	}

	/**
	 * Refills toward a two-batch high-water mark derived from the precomputed real-tick demand.
	 * Tick handlers use this overload so upgrade, process and accelerator multipliers are evaluated
	 * only once per batch.
	 */
	public static long injectEnergy(IAe2OutputHostBase host, long requiredThisTick) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;

		MachineEnergyContainer<?> container = host.productivebeesgenesis$getAe2EnergySource();
		if (container == null) return 0;

		requiredThisTick = Math.max(0L, requiredThisTick);

		long targetEnergy = MekCentrifugeEnergyScaling.bufferedCapacityForDemand(requiredThisTick);
		long currentEnergy = container.getEnergy();
		long maxEnergy = container.getMaxEnergy();
		if (targetEnergy > maxEnergy) {
			container.setMaxEnergy(targetEnergy);
			maxEnergy = container.getMaxEnergy();
		}
		long remainingCapacity = Ae2EnergyMath.remainingCapacity(currentEnergy, maxEnergy);
		if (remainingCapacity <= 0) return 0;

		long toExtract = Math.min(remainingCapacity,
				targetEnergy > currentEnergy ? targetEnergy - currentEnergy : 0L);
		if (toExtract <= 0) return 0;

		IGrid grid = getConnectedGrid(host);
		if (grid == null) return 0;

		// Bug 7：读取优先级配置 — 由宿主提供，蜂箱与离心机相互独立
		boolean preferAppliedFlux = host.productivebeesgenesis$getPreferAppliedFluxOverAeEnergy();

		// 按优先级提取
		long firstExtracted;
		long secondExtracted = 0;
		if (preferAppliedFlux) {
			// 先 AppliedFlux，再 AE2 原生
			firstExtracted = Ae2EnergyMath.clampExtracted(extractFromAppliedFlux(grid, toExtract), toExtract);
			long remaining = toExtract - firstExtracted;
			if (remaining > 0) {
				secondExtracted = Ae2EnergyMath.clampExtracted(
						Ae2EnergyBridge.extractAeEnergyAsFe(grid, remaining, Actionable.MODULATE), remaining);
			}
		} else {
			// 先 AE2 原生，再 AppliedFlux
			firstExtracted = Ae2EnergyMath.clampExtracted(
					Ae2EnergyBridge.extractAeEnergyAsFe(grid, toExtract, Actionable.MODULATE), toExtract);
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
		return result.injected();
	}

	/**
	 * 从 AppliedFlux 提取 FE：先 SIMULATE 确定可提取量，再 MODULATE 实际提取
	 * <br/>
	 * SIMULATE 步骤用于应对 ME 网络中 AppliedFlux FE 存量不足的场景，
	 * 避免直接 MODULATE 时网络返回 0 但仍产生调用开销。
	 *
	 * @param grid       AE2 网格
	 * @param toExtract  请求提取的 FE 数量
	 * @return 实际提取的 FE 数量
	 */
	private static long extractFromAppliedFlux(IGrid grid, long toExtract) {
		// SIMULATE 模拟提取，确定实际可获取量
		long simulated = Ae2EnergyBridge.extractAppliedFluxFe(
				grid, toExtract, Actionable.SIMULATE, null);
		if (simulated <= 0) return 0;
		// MODULATE 实际提取（使用 SIMULATE 返回的量，避免超量）
		return Ae2EnergyBridge.extractAppliedFluxFe(
				grid, simulated, Actionable.MODULATE, null);
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
