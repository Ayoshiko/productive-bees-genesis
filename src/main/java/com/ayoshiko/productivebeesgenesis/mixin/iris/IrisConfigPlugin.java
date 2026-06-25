package com.ayoshiko.productivebeesgenesis.mixin.iris;

import java.util.List;
import java.util.Set;

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
 */
public class IrisConfigPlugin implements IMixinConfigPlugin {

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
	 * 原理：遍历 LoadingModList 中的所有模组，筛选 modId 为 "iris" 的条目，
	 * 若列表非空则返回 true，表示应用当前 Mixin。
	 */
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !LoadingModList.get().getMods().stream().filter(modInfo -> modInfo.getModId().equals("iris")).toList().isEmpty();
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
