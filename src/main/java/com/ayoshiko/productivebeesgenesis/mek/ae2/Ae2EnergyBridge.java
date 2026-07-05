package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;

import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;

/**
 * AE 网络能量提取桥
 * <br/>
 * 封装从 AE2 网络提取能量的两类来源，对外提供统一的 FE 单位接口。
 * <p>
 * <b>两个能量来源</b>：
 * <ul>
 *   <li>{@link #extractAppliedFluxFe} — 从 AppliedFlux 在 ME 网络中存储的 FE 提取，
 *       通过 {@link FluxKey} 索引 MEStorage</li>
 *   <li>{@link #extractAeEnergyAsFe} — 从 AE2 原生网络能量提取 AE 并转换为 FE</li>
 * </ul>
 * <p>
 * <b>职责（SRP）</b>：仅负责"提取能量"这一原子操作，不参与优先级决策、容量计算、
 * 注入容器等上层逻辑。优先级策略由 {@link Ae2EnergyInjector} 根据配置项决定调用顺序。
 * <p>
 * <b>能量转换比例</b>：AE2 标准 1 AE = 2 FE，{@link #extractAeEnergyAsFe} 内部
 * 将请求的 FE 量除以 2 转换为 AE 量，提取后再乘以 2 转回 FE 返回。
 * <p>
 * <b>类加载安全</b>：本类直接 import {@link FluxKey} 和 {@link EnergyType}
 * （编译时为 compileOnly 依赖）。运行时当 AppliedFlux 未安装时，
 * {@link #extractAppliedFluxFe} 在方法入口通过 {@link AppliedFluxIntegrationLoader#isAppliedFluxLoaded()}
 * 守卫立即返回 0，不会执行到引用 FluxKey 的字节码，故 JVM 不会触发 FluxKey 类的加载
 * （方法入口的 boolean 检查不依赖未加载的类）。
 * <p>
 * <b>线程安全</b>：本类无状态，所有方法均为静态方法。AE2 的 IGrid / MEStorage / IEnergyService
 * 内部自带线程安全保证（AE2 网络在主线程处理 tick）。
 *
 * @since 1.8.0
 * @author Ayoshiko
 */
public final class Ae2EnergyBridge {

	/** AE2 到 FE 的能量转换比例：1 AE = 2 FE（AE2 标准比例） */
	private static final double AE_TO_FE_RATIO = 2.0;

	/** 全局共享的 AE2 操作源 — BaseActionSource 完全无状态，全局只需 1 个实例 */
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2EnergyBridge() {}

	/**
	 * 从 AppliedFlux 在 ME 网络中存储的 FE 提取
	 * <br/>
	 * 通过 {@link FluxKey#of(EnergyType)} 索引 {@link MEStorage}，
	 * 调用 {@link MEStorage#extract} 提取指定数量的 FE。
	 * <p>
	 * <b>守卫</b>：AppliedFlux 未安装时立即返回 0，避免触发 {@link FluxKey} 类加载。
	 * grid 为 null 时返回 0。
	 * <p>
	 * <b>异常处理</b>：捕获所有异常（包括 NoSuchMethodError 等版本不兼容错误），
	 * 返回 0 并记录日志，避免单个集成异常导致离心机 tick 崩溃。
	 *
	 * @param grid    AE2 网格，null 时返回 0
	 * @param amount  请求提取的 FE 数量（必须 >= 0）
	 * @param mode    提取模式：{@link Actionable#SIMULATE} 模拟，{@link Actionable#MODULATE} 实际执行
	 * @param source  AE2 操作源，null 时使用全局默认 BaseActionSource
	 * @return 实际提取的 FE 数量，失败时返回 0
	 */
	public static long extractAppliedFluxFe(@Nullable IGrid grid, long amount, Actionable mode,
											@Nullable IActionSource source) {
		// 守卫：AppliedFlux 未安装时立即返回，不触发 FluxKey 类加载
		if (!AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return 0;
		if (grid == null || amount <= 0) return 0;

		try {
			IStorageService storageService = grid.getService(IStorageService.class);
			MEStorage meStorage = storageService.getInventory();
			IActionSource actionSource = source != null ? source : ACTION_SOURCE;
			// FluxKey.of(EnergyType.FE) 返回表示 FE 能量的 AEKey
			// 此行仅在 AppliedFlux 已安装时执行（守卫已保证），不会触发类加载失败
			FluxKey feKey = FluxKey.of(EnergyType.FE);
			return meStorage.extract(feKey, amount, mode, actionSource);
		} catch (Throwable t) {
			// 版本不兼容或运行时异常时安全回退，避免离心机 tick 崩溃
			return 0;
		}
	}

	/**
	 * 从 AE2 原生网络能量提取并转换为 FE
	 * <br/>
	 * 调用 {@link IEnergyService#extractAEPower(double, Actionable, PowerMultiplier)}
	 * 从 AE2 网络的能源服务提取 AE 能量，再按 {@link #AE_TO_FE_RATIO} 转换为 FE 返回。
	 * <p>
	 * <b>能量转换</b>：请求 {@code amount} FE 等价于 {@code amount / 2.0} AE，
	 * 提取的 AE 量再乘以 2 转换为 FE 返回。例如请求 1000 FE → 提取 500 AE → 返回 1000 FE。
	 * <p>
	 * <b>守卫</b>：AE2 未安装时返回 0（调用方应已通过 {@link Ae2IntegrationLoader#isAe2Loaded()}
	 * 守卫，此处二次防御）。grid 为 null 时返回 0。
	 *
	 * @param grid    AE2 网格，null 时返回 0
	 * @param amount  请求提取的 FE 数量（必须 >= 0）
	 * @param mode    提取模式
	 * @return 实际提取并转换后的 FE 数量，失败时返回 0
	 */
	public static long extractAeEnergyAsFe(@Nullable IGrid grid, long amount, Actionable mode) {
		// AE2 未安装时安全返回（二次防御，调用方应已守卫）
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		if (grid == null || amount <= 0) return 0;

		try {
			IEnergyService energyService = grid.getEnergyService();
			// FE → AE 转换：amount FE = amount / 2.0 AE
			double aeAmount = amount / AE_TO_FE_RATIO;
			double extractedAe = energyService.extractAEPower(aeAmount, mode, PowerMultiplier.ONE);
			// AE → FE 转换：extractedAe AE = extractedAe * 2.0 FE
			return (long) (extractedAe * AE_TO_FE_RATIO);
		} catch (Throwable t) {
			// 异常时安全回退，避免离心机 tick 崩溃
			return 0;
		}
	}
}
