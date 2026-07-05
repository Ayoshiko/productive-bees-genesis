package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * AE2 能量注入协调器
 * <br/>
 * 协调从 AE 网络提取能量并注入到离心机的 {@link MachineEnergyContainer}，
 * 按配置优先级决定 AppliedFlux 与 AE2 原生能量的提取顺序。
 * <p>
 * <b>职责（DIP）</b>：依赖 {@link IAe2OutputHost} 抽象获取网格节点与能量容器，
 * 不直接引用具体 TileEntity，保证任何实现 IAe2OutputHost 的类均可复用本注入器。
 * <p>
 * <b>职责（SRP）</b>：仅负责"协调提取与注入"流程，不负责：
 * <ul>
 *   <li>能量提取的底层实现（由 {@link Ae2EnergyBridge} 负责）</li>
 *   <li>是否启用的配置守卫（由 {@link IAe2OutputHost#productivebeesgenesis$injectAe2Energy} 入口负责）</li>
 *   <li>tick 时机控制（由调用方 tick 处理器负责）</li>
 * </ul>
 * <p>
 * <b>提取流程</b>：
 * <ol>
 *   <li>双重守卫：AE2 已安装 + grid 非 null</li>
 *   <li>获取容器并计算剩余容量（maxEnergy - currentEnergy）</li>
 *   <li>实际注入量 = min(maxAmount, remainingCapacity)</li>
 *   <li>按优先级先 SIMULATE 模拟提取确定可获取量，再 MODULATE 实际提取</li>
 *   <li>用 setEnergy 注入到容器（clamp 到 maxEnergy 防止溢出）</li>
 * </ol>
 * <p>
 * <b>线程安全</b>：本类无状态，所有方法均为静态方法。AE2 网络操作在主线程进行，
 * MachineEnergyContainer 内部使用原子类型保证线程安全。
 *
 * @since 1.8.0
 * @author Ayoshiko
 */
public final class Ae2EnergyInjector {

	private Ae2EnergyInjector() {}

	/**
	 * 从 AE 网络提取能量并注入到离心机的能量容器
	 * <br/>
	 * 按 {@link ModConfig#SERVER} 的优先级配置决定 AppliedFlux 与 AE2 原生能量的提取顺序。
	 * <p>
	 * <b>守卫</b>：
	 * <ul>
	 *   <li>AE2 未安装时返回 0（双重防御，调用方应已守卫）</li>
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
	 * @param host      AE2 输出宿主（离心机方块实体）
	 * @param maxAmount 单 tick 最大注入量（FE），<= 0 时返回 0
	 * @return 实际注入到容器的 FE 总量
	 */
	public static long injectEnergy(IAe2OutputHost host, long maxAmount) {
		// 守卫1：AE2 未安装（双重防御，调用方应已守卫）
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null || maxAmount <= 0) return 0;

		// 获取已连接的网格
		IGrid grid = getConnectedGrid(host);
		if (grid == null) return 0;

		// 获取能量容器
		MachineEnergyContainer<?> container = host.productivebeesgenesis$getAe2EnergySource();
		if (container == null) return 0;

		// 计算剩余容量
		long currentEnergy = container.getEnergy();
		long maxEnergy = container.getMaxEnergy();
		long remainingCapacity = maxEnergy - currentEnergy;
		if (remainingCapacity <= 0) return 0;

		// 实际注入量 = min(请求量, 剩余容量)
		long toExtract = Math.min(maxAmount, remainingCapacity);

		// 读取优先级配置
		boolean preferAppliedFlux = readPreferAppliedFlux();

		// 按优先级提取
		long firstExtracted;
		long secondExtracted = 0;
		if (preferAppliedFlux) {
			// 先 AppliedFlux，再 AE2 原生
			firstExtracted = extractFromAppliedFlux(grid, toExtract);
			long remaining = toExtract - firstExtracted;
			if (remaining > 0) {
				secondExtracted = Ae2EnergyBridge.extractAeEnergyAsFe(grid, remaining, Actionable.MODULATE);
			}
		} else {
			// 先 AE2 原生，再 AppliedFlux
			firstExtracted = Ae2EnergyBridge.extractAeEnergyAsFe(grid, toExtract, Actionable.MODULATE);
			long remaining = toExtract - firstExtracted;
			if (remaining > 0) {
				secondExtracted = extractFromAppliedFlux(grid, remaining);
			}
		}

		// 注入到容器（clamp 防止溢出，虽然 remainingCapacity 已保证）
		long totalInjected = firstExtracted + secondExtracted;
		if (totalInjected > 0) {
			long newEnergy = Math.min(currentEnergy + totalInjected, maxEnergy);
			container.setEnergy(newEnergy);
		}
		return totalInjected;
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
	 * 通过 {@link IAe2OutputHost#productivebeesgenesis$getAe2GridNode()} 获取节点对象，
	 * 转换为 {@link IManagedGridNode} 后调用 {@code getGrid()} 取已连接网格。
	 *
	 * @param host 输出宿主
	 * @return 已连接的网格，未连接或节点不存在时返回 null
	 */
	@Nullable
	private static IGrid getConnectedGrid(IAe2OutputHost host) {
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return null;
		return managedNode.getGrid();
	}

	/**
	 * 读取优先级配置
	 * <br/>
	 * 配置未加载或读取异常时默认为 true（优先 AppliedFlux），因为 AppliedFlux 是
	 * FE 原生存储，提取效率更高（无需 AE↔FE 转换）。
	 *
	 * @return true 优先从 AppliedFlux 提取，false 优先从 AE2 原生能量提取
	 */
	private static boolean readPreferAppliedFlux() {
		if (ModConfig.SERVER == null) return true;
		try {
			return ModConfig.SERVER.mekCentrifugePreferAppliedFluxOverAeEnergy.get();
		} catch (Throwable t) {
			// 配置项异常时安全回退为默认值
			return true;
		}
	}
}
