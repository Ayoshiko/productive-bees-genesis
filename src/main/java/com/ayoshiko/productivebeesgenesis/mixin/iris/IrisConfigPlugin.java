package com.ayoshiko.productivebeesgenesis.mixin.iris;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
	 * Iris 光影兼容 Mixin 配置插件
	 * <br/>
	 * 原理：实现 IMixinConfigPlugin 接口，在 shouldApplyMixin 中检查
	 * LoadingModList 是否包含 "iris" 模组。仅当 Iris 已安装时才应用
	 * 本配置中的所有 Mixin，避免在无 Iris 环境下因缺少依赖类而崩溃。
	 * <p>
	 * 与主 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 风格一致：
	 * 使用 Holder 模式（JVM 类初始化保证线程安全）+ FMLLoader.getLoadingModList() 检测。
	 */
public class IrisConfigPlugin implements IMixinConfigPlugin {

	/** Iris 模组 ID */
	private static final String IRIS_MOD_ID = "iris";

	/**
	 * Holder 模式 — 线程安全的懒加载
	 * <br/>
	 * JVM 类初始化阶段保证原子性，检测结果在 Mixin 阶段计算一次后常驻，
	 * 避免 shouldApplyMixin 每次调用都重复访问 FMLLoader。
	 */
	private static final class Holder {
		/** Iris 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean IRIS_LOADED = isIrisLoaded();
	}

	/**
	 * 在 Mixin 加载阶段检测 Iris 是否已加载
	 * <br/>
	 * 原理：FMLLoader.getLoadingModList() 返回当前正在加载的 mod 列表，
	 * 此阶段早于 ModList.get() 可用时机。getModFileById 返回 null 表示该 mod 未加载。
	 * 异常时安全降级为 false，避免 FMLLoader 状态异常导致 Mixin 阶段崩溃。
	 */
	private static boolean isIrisLoaded() {
		try {
			return FMLLoader.getLoadingModList().getModFileById(IRIS_MOD_ID) != null;
		} catch (LinkageError | RuntimeException t) {
			// LinkageError 覆盖 FMLLoader 版本不兼容；RuntimeException 覆盖状态异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			// 防御性：FMLLoader 状态异常时安全降级，不应用 Iris Mixin
			// Mixin 早期阶段日志系统可能未就绪，使用 System.err 输出（单次检测不会刷屏）
			System.err.println("[ProductiveBeesGenesis] Mixin 阶段检测 Iris 失败, 视为未加载: " + t);
			return false;
		}
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	/**
	 * 仅当已加载 Iris 模组时才应用 Mixin
	 * <br/>
	 * 原理：通过 Holder 模式缓存 Iris 加载状态，避免每次调用都访问 FMLLoader。
	 */
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return Holder.IRIS_LOADED;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
