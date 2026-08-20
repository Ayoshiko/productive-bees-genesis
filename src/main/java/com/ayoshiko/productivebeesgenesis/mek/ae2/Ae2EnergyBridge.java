package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;
import org.jetbrains.annotations.Nullable;

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
	 * <b>类加载安全</b>：FluxKey/EnergyType 是 AppliedFlux 的 compileOnly 类，
 * 全部引用被隔离到内部类 {@link FluxKeys} 中，本类方法体不出现 FluxKey 类型字面引用。
 * 方法入口守卫仅对解释执行的懒解析有效 — 能量注入每 tick 高频调用，
 * 方法被 JIT 编译时会解析常量池全部类引用，AppliedFlux 未安装时直接
 * NoClassDefFoundError（Issue #8 追加报告的崩溃场景）。
 * Holder 内部类仅在守卫通过后的首次访问时初始化，根治该问题。
 * <p>
 * <b>线程安全</b>：本类无状态，所有方法均为静态方法。AE2 的 IGrid / MEStorage / IEnergyService
 * 内部自带线程安全保证（AE2 网络在主线程处理 tick）。
 *
 * @since 2.0.0
 * @author Ayoshiko
 */
public final class Ae2EnergyBridge {

	/** AE2 到 FE 的能量转换比例：1 AE = 2 FE（AE2 标准比例） */
	private static final double AE_TO_FE_RATIO = 2.0;

	/**
	 * FluxKey 引用隔离 Holder — AppliedFlux 未安装时主类加载/验证不触发 FluxKey 解析
	 * <br/>
	 * <b>隔离要求</b>：主类方法体不得出现任何 FluxKey 类型流 — 不仅 FluxKey.of() 调用，
	 * 还包括将 FluxKey 类型值传给 {@code extract(AEKey, ...)} 的子类型检查
	 * （HotSpot 验证器在 AEKey 已加载时会强制解析未加载的 FluxKey，Issue #8 追加场景）。
	 * 故 extract 调用本身也封装在本嵌套类中，主类仅以 appeng 类型参数与之交互。
	 * <p>
	 * JVM 保证本类仅在守卫通过后首次访问时初始化（线程安全），
	 * 同时将 {@code FluxKey.of(EnergyType.FE)} 缓存为静态常量消除重复调用开销。
	 */
	private static final class FluxKeys {
		/** FE 能量 key（AppliedFlux 在 ME 网络中存储 FE 的 AEKey） */
		static final FluxKey FE = FluxKey.of(EnergyType.FE);

		/** 在隔离边界内执行 ME 提取 — FluxKey&lt;:AEKey 子类型检查只发生在本类验证时（AppliedFlux 已装） */
		static long extract(MEStorage meStorage, long amount, Actionable mode, IActionSource source) {
			return meStorage.extract(FE, amount, mode, source);
		}
	}

	/**
	 * 懒加载 Holder — AE2 未安装时主类初始化不触发 {@link BaseActionSource} 类解析
	 * <br/>
	 * 与 Ae2OutputPusher/Ae2FluidPusher 的 ActionSourceHolder 模式一致（防御深度）。
	 */
	private static final class ActionSourceHolder {
		/** 全局共享的 AE2 操作源 — {@link BaseActionSource} 完全无状态，全局只需 1 个实例 */
		static final IActionSource INSTANCE = new BaseActionSource() {};
	}

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
		// 守卫：AppliedFlux 未安装时立即返回，不触发 FluxKeys Holder 类初始化
		if (!AppliedFluxIntegrationLoader.isAppliedFluxLoaded()) return 0;
		if (grid == null || amount <= 0) return 0;

		try {
			IStorageService storageService = grid.getService(IStorageService.class);
			MEStorage meStorage = storageService.getInventory();
			IActionSource actionSource = source != null ? source : ActionSourceHolder.INSTANCE;
			// 隔离边界内执行提取：FluxKey 类型流不出现在主类方法体（验证/JIT 均不解析 FluxKey）
			long extracted = FluxKeys.extract(meStorage, amount, mode, actionSource);
			return Ae2EnergyMath.clampExtracted(extracted, amount);
		} catch (LinkageError | RuntimeException e) {
			// LinkageError 覆盖 NoSuchMethodError/NoClassDefFoundError（AE2/AppliedFlux 版本不兼容）；
			// RuntimeException 覆盖 NPE/IllegalStateException 等运行时异常。
			// 不捕获 Throwable 以避免吞没 OutOfMemoryError/StackOverflowError 等严重错误。
			LogThrottle.warn("ae2_energy_extract_flux",
					"AE2 AppliedFlux 能量提取异常, 安全回退 0: {}", e.toString());
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
			return Ae2EnergyMath.aeToFe(extractedAe, amount, AE_TO_FE_RATIO);
		} catch (LinkageError | RuntimeException e) {
			// LinkageError 覆盖 AE2 版本不兼容场景；RuntimeException 覆盖运行时异常。
			// 不捕获 Throwable 以避免吞没 OutOfMemoryError/StackOverflowError 等严重错误。
			LogThrottle.warn("ae2_energy_extract_ae",
					"AE2 原生能量提取异常, 安全回退 0: {}", e.toString());
			return 0;
		}
	}
}
