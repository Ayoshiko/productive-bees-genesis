package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.FMLLoader;

/**
 * ProductiveBeesGenesis 主 Mixin 配置插件
 * <br/>
 * 原理：实现 IMixinConfigPlugin 接口，在 Mixin 加载阶段（早于 ModList 完整初始化）
 * 通过 FMLLoader.getLoadingModList() 检测可选依赖 mod 的加载状态，
 * 条件性地应用引用了 ME/EME 类的 Mixin，避免因依赖类缺失导致类加载失败或 Mixin 应用崩溃。
 * <br/>
 * 受控的 Mixin（@Mixin 目标或类体 import 引用了可选 mod 的类）：
 * <ul>
 *   <li>MixinExtraFactory / MixinFactoryForME / TileEntityExtraFactoryAccessor — 引用 ME(mekanism_extras)的类，仅当 ME 加载时应用</li>
 *   <li>MixinExtraFactoryForEME / MixinEMExtraFactory / TileEntityEMExtraFactoryAccessor — 引用 EME(emextras)的类，仅当 EME 加载时应用</li>
 * </ul>
 * 其他 Mixin（离心机/PB原版类等）不依赖可选 mod，始终应用。
 */
public class MixinConfigPlugin implements IMixinConfigPlugin {

    /** ME(MekanismExtras)的 modId */
    private static final String ME_MOD_ID = "mekanism_extras";
    /** EME(EvolvedMekanismExtras)的 modId */
    private static final String EME_MOD_ID = "emextras";

    /** 引用 ME 类的 Mixin 简单类名集合（@Mixin 目标或类体 import 了 ME 的类） */
    private static final Set<String> ME_MIXINS = Set.of(
            "MixinExtraFactory",
            "MixinFactoryForME",
            "TileEntityExtraFactoryAccessor"
    );

    /** 引用 EME 类的 Mixin 简单类名集合（@Mixin 目标或类体 import 了 EME 的类） */
    private static final Set<String> EME_MIXINS = Set.of(
            "MixinExtraFactoryForEME",
            "MixinEMExtraFactory",
            "TileEntityEMExtraFactoryAccessor"
    );

    /**
     * Holder 模式 — 线程安全的懒加载
     * <br/>
     * JVM 类初始化阶段保证原子性，检测结果在 Mixin 阶段计算一次后常驻，
     * 避免 shouldApplyMixin 每次调用都重复访问 FMLLoader。
     */
    private static final class Holder {
        /** ME 是否加载（Mixin 阶段检测，仅计算一次） */
        static final boolean ME_LOADED = isModLoaded(ME_MOD_ID);
        /** EME 是否加载（Mixin 阶段检测，仅计算一次） */
        static final boolean EME_LOADED = isModLoaded(EME_MOD_ID);
    }

    /**
     * 在 Mixin 加载阶段检测 mod 是否已加载
     * <br/>
     * 原理：FMLLoader.getLoadingModList() 返回当前正在加载的 mod 列表，
     * 此阶段早于 ModList.get() 可用时机。getModFileById 返回 null 表示该 mod 未加载。
     * 异常时安全降级为 false，避免 FMLLoader 状态异常导致 Mixin 阶段崩溃。
     */
    private static boolean isModLoaded(String modId) {
        try {
            return FMLLoader.getLoadingModList().getModFileById(modId) != null;
        } catch (Throwable t) {
            // 防御性：FMLLoader 状态异常时安全降级，不应用可选 Mixin
            return false;
        }
    }

    /** 从全限定类名提取简单类名（最后一段，不含包名） */
    private static String simpleClassName(String className) {
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    @Override
    public void onLoad(String mixinPackage) {
        // 无额外初始化逻辑
    }

    @Override
    public List<String> getMixins() {
        // 不动态追加 Mixin，返回 null 由 mixins.json 静态声明
        return null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 条件性应用 Mixin
     * <br/>
     * 原理：从 mixinClassName 提取简单类名，判断其是否属于 ME/EME 受控集合，
     * 仅当对应 mod 已加载时才返回 true；其他 Mixin 始终返回 true。
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = simpleClassName(mixinClassName);
        if (ME_MIXINS.contains(simpleName)) {
            return Holder.ME_LOADED;
        }
        if (EME_MIXINS.contains(simpleName)) {
            return Holder.EME_LOADED;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 无需处理目标类合并
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无前置处理
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无后置处理
    }
}
