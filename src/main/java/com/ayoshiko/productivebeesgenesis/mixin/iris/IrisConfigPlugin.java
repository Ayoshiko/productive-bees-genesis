package com.ayoshiko.productivebeesgenesis.mixin.iris;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;

/**
 * Iris 光影兼容 Mixin 配置插件
 * <br/>
 * 原理：实现 IMixinConfigPlugin 接口，在 shouldApplyMixin 中检查
 * LoadingModList 是否包含 "iris" 模组。仅当 Iris 已安装时才应用
 * 本配置中的所有 Mixin，避免在无 Iris 环境下因缺少依赖类而崩溃。
 * <p>
 * 性能优化：使用 volatile 字段缓存 iris 加载状态，避免每次 shouldApplyMixin
 * 调用都遍历 LoadingModList。首次调用时计算，后续直接返回缓存值。
 */
public class IrisConfigPlugin implements IMixinConfigPlugin {

	/** Iris 模组 ID */
	private static final String IRIS_MOD_ID = "iris";

	/** 配置插件专属日志器，避免早期加载阶段依赖主模组类 */
	private static final Logger LOGGER = LogManager.getLogger("ProductiveBeesGenesis");

	/**
	 * Iris 加载状态缓存
	 * <br/>
	 * 使用 volatile 保证可见性：在 Mixin 应用阶段（早期加载线程）写入后，
	 * 后续 shouldApplyMixin 调用线程能立即读到最新值。
	 * <p>
	 * 使用 Boolean 包装类型的 Holder 模式：
	 * <ul>
	 *   <li>null：尚未计算</li>
	 *   <li>Boolean.TRUE/FALSE：已计算的结果</li>
	 * </ul>
	 * 同步块保证首次计算只执行一次（双重检查锁定）。
	 */
	private static volatile Boolean irisLoadedCache = null;

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
	 * 原理：使用 anyMatch 流式判断 LoadingModList 中是否存在 modId 为 "iris" 的条目，
	 * 结果缓存到 {@link #irisLoadedCache}，避免每次调用都遍历模组列表。
	 */
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		Boolean cached = irisLoadedCache;
		if (cached != null) {
			return cached;
		}
		synchronized (IrisConfigPlugin.class) {
			cached = irisLoadedCache;
			if (cached == null) {
				try {
					cached = LoadingModList.get().getMods().stream()
							.anyMatch(modInfo -> IRIS_MOD_ID.equals(modInfo.getModId()));
				} catch (Exception e) {
					// 加载早期阶段 LoadingModList 可能尚未初始化，默认不应用 Iris Mixin
					LOGGER.warn("无法读取 LoadingModList，跳过 Iris Mixin 应用", e);
					cached = Boolean.FALSE;
				}
				irisLoadedCache = cached;
			}
		}
		return cached;
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
