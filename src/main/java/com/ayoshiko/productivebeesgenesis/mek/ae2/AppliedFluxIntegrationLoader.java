package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.neoforged.fml.ModList;

/**
 * AppliedFlux 集成加载器
 * <br/>
 * 使用 Holder 模式实现线程安全的懒加载单例，检测 AppliedFlux 模组是否安装。
 * <p>
 * <b>检测策略</b>：通过 {@link ModList#get()} 的 {@code isLoaded("appflux")} 判断。
 * 不能使用 {@code FMLLoader.getLoadingModList()}，因为 AppliedFlux 不是 FML 早期
 * 可见的模组（其 mod 文件在 FML 列表构造阶段尚未注册），在 FML 早期阶段调用会
 * 返回 false 的负面结果，故必须使用运行时可用的 {@link ModList}。
 * <p>
 * <b>职责（SRP）</b>：仅负责检测 AppliedFlux 是否加载，不承担集成开关判断。
 * 应用层是否启用能量输入由 {@code ModConfig.SERVER.mekCentrifugeAeEnergyInputEnabled}
 * 控制，与本类解耦，便于后续任务接入配置界面时不受检测逻辑影响。
 * <p>
 * <b>线程安全</b>：Holder 模式由 JVM 类加载机制保证线程安全的延迟初始化，
 * {@link ModList} 内部使用并发集合保证线程安全读取。
 *
 * @since 1.8.0
 * @author Ayoshiko
 */
public final class AppliedFluxIntegrationLoader {

	/** Holder 模式：JVM 类加载时保证线程安全的延迟初始化 */
	private static final class Holder {
		static final AppliedFluxIntegrationLoader INSTANCE = new AppliedFluxIntegrationLoader();
	}

	/** AppliedFlux 是否已安装（运行时检测，仅检测一次） */
	private final boolean appliedFluxLoaded;

	private AppliedFluxIntegrationLoader() {
		this.appliedFluxLoaded = detectAppliedFlux();
	}

	/** 获取单例实例 */
	public static AppliedFluxIntegrationLoader getInstance() {
		return Holder.INSTANCE;
	}

	/** AppliedFlux 是否已安装 */
	public static boolean isAppliedFluxLoaded() {
		return getInstance().appliedFluxLoaded;
	}

	/**
	 * 检测 AppliedFlux 是否安装
	 * <br/>
	 * 使用 {@link ModList#get()} 运行时检测，避免 FML 早期阶段不可见的问题。
	 * ModList 在模组加载完成后才填充，本类的 Holder 在首次访问时（必然晚于模组加载）
	 * 才触发检测，保证 ModList 已就绪。
	 */
	private static boolean detectAppliedFlux() {
		try {
			return ModList.get().isLoaded("appflux");
		} catch (Throwable t) {
			// ModList 不可用（如测试环境）时安全回退为未安装
			return false;
		}
	}
}
