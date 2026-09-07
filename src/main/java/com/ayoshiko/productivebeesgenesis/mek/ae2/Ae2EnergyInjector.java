package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
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
	 * <b>提取流程（批次预算差额提取）</b>：
	 * <ol>
	 *   <li>双重守卫：AE2 已安装 + grid 非 null</li>
	 *   <li>调用入口直接设置当前负载的稳定目标容量，相同负载下不执行缩容/扩容往返</li>
	 *   <li>活动批次只提取预算缺口；批次结束后补满保留的当前容量，不设置固定 FE/t 截断</li>
	 *   <li>按优先级直接 MODULATE 提取，返回多少就注入多少，避免重复网络遍历</li>
	 *   <li>用 setEnergy 注入到容器（clamp 到 maxEnergy 防止溢出）</li>
	 * </ol>
	 * <p>
	 * 批次容量跨 tick 保留，使普通 FE 电缆有机会在两次机器 tick 之间完成充能；
	 * 负载、倍率或升级变化时，入口直接调整到新目标，不会先裁能再扩回相同容量。
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
	 * 正批次倍率按熔炼整批预算缺口注能；非正倍率补满当前保留的批次容量。
	 * 最终注入量始终受容器剩余容量和 ME 网络实际可提取量共同限制。
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
	 *   <li>优先源按容器差额执行一次有界 MODULATE，返回实际提取量</li>
	 *   <li>未满足的剩余差额再从次优先源提取</li>
	 *   <li>两条路径的总注入量最终再次按容器剩余空间夹紧</li>
	 * </ul>
	 * 注入到容器使用 setEnergy + clamp，避免依赖 canInsert 谓词。
	 *
	 * @param host AE2 输出宿主（离心机方块实体）
	 * @param batchMultiplier 正数表示当前批次虚拟 tick 数；非正数表示批次后按当前容量补电
	 * @return 实际注入到容器的 FE 总量
	 *
	 * @since 2.0.0
	 */
	public static long injectEnergy(IAe2OutputHostBase host) {
		return injectEnergy(host, 1);
	}

	/**
	 * 按当前熔炼批次预算缺口或已准备容量差额，从 AE2 网络补充本地能量。
	 *
	 * @param host AE2 输出宿主
	 * @param batchMultiplier 正数表示活动批次倍率；非正数表示补满当前容量
	 * @return 实际注入的 FE
	 */
	public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier) {
		long requiredEnergy = batchMultiplier > 0 && host != null
				&& host.productivebeesgenesis$usesSmeltingEnergyBudget()
				? host.productivebeesgenesis$getRequiredEnergyForBatch(batchMultiplier) : 0L;
		return injectEnergy(host, batchMultiplier, requiredEnergy);
	}

	/**
	 * 使用调用入口已计算的批次需求补充能量，避免再次扫描全部输入通道。
	 *
	 * @param host AE2 输出宿主
	 * @param batchMultiplier 正数表示活动批次；非正数表示补满当前已准备容量
	 * @param requiredEnergy 当前熔炼批次的 FE 需求
	 * @return 实际注入的 FE
	 */
	public static long injectEnergy(IAe2OutputHostBase host, int batchMultiplier, long requiredEnergy) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (host == null) return 0;

		MachineEnergyContainer<?> container = host.productivebeesgenesis$getAe2EnergySource();
		if (container == null) return 0;

		long currentEnergy = container.getEnergy();
		long maxEnergy = container.getMaxEnergy();
		// 创意升级（无限容量）无需外部供能。有限升级在极高倍率下也可能被
		// Mekanism 夹到 Long.MAX_VALUE，不能仅凭容量数值把这两种情况混为一谈。
		if (maxEnergy == Long.MAX_VALUE && host.hasCreativeUpgrade()) return 0;
		// 调用入口已先归一化容量；这里只使用确定容量计算严格有界的差额。
		long remainingCapacity = Ae2EnergyMath.remainingCapacity(currentEnergy, maxEnergy);
		if (remainingCapacity <= 0) return 0;

		// 能量注入退避：ME 网络无能量期间跳过后续提取，任意部分成功立即重置。
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Ae2PushBackoff energyBackoff = holder == null ? null : holder.getPushState().getEnergyBackoff();
		long nowNanos = System.nanoTime();
		if (energyBackoff != null && energyBackoff.shouldSkip(nowNanos)) return 0;
		// 有活动批次时只请求本批次预算，避免把整个超大容量作为一次 AE2 请求。
		// 空闲机器仍按剩余容量补满，保持原有自动充能语义。
		long normalizedRequired = Math.max(0L, requiredEnergy);
		long toExtract;
		if (batchMultiplier > 0 && host.productivebeesgenesis$usesSmeltingEnergyBudget()
				&& normalizedRequired > 0L) {
			toExtract = Ae2EnergyMath.requiredShortfall(
					currentEnergy, normalizedRequired, remainingCapacity);
		} else {
			toExtract = remainingCapacity;
		}
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
	 * 从 AppliedFlux 提取 FE：按容器差额直接执行一次有界 MODULATE。
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
		return Ae2EnergyBridge.extractAppliedFluxFe(
				grid, toExtract, Actionable.MODULATE, null);
	}

	/**
	 * 从 AE2 原生能量提取并转换为 FE：按容器差额直接执行一次有界 MODULATE
	 * <br/>
	 * 与 {@link #extractFromAppliedFlux} 对称：不再先模拟后重复遍历供能节点，
	 * 也不使用极大存量探测或固定 5% 截断。
	 * 网络自身的能源安全交由 AE2 能量服务处理，本模组不再截断实际机器需求。
	 *
	 * @param grid       AE2 网格
	 * @param toExtract  请求提取的 FE 数量
	 * @return 实际提取并转换为 FE 的数量
	 */
	private static long extractFromAeNative(IGrid grid, long toExtract) {
		return Ae2EnergyBridge.extractAeEnergyAsFe(grid, toExtract, Actionable.MODULATE);
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
