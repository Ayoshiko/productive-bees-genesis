package com.ayoshiko.productivebeesgenesis.mixin;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
	 * ProductiveBeesGenesis 主 Mixin 配置插件
	 * <br/>
	 * 原理：实现 IMixinConfigPlugin 接口，在 Mixin 加载阶段（早于 ModList 完整初始化）
	 * 通过 FMLLoader.getLoadingModList() 检测可选依赖 mod 的加载状态，
	 * 条件性地应用引用了 ME/EME/AE2 类的 Mixin，避免因依赖类缺失导致类加载失败或 Mixin 应用崩溃。
	 * <br/>
	 * 受控的 Mixin（@Mixin 目标或类体 import 引用了可选 mod 的类）：
	 * <ul>
	 *   <li>ExtraFactoryMixin / FactoryForMEMixin / TileEntityExtraFactoryAccessor — 引用
	 * ME(mekanism_extras)的类，仅当 ME 加载时应用</li>
	 *   <li>ExtraFactoryForEMEMixin / EMExtraFactoryMixin / TileEntityEMExtraFactoryAccessor — 引用
	 * EME(emextras)的类，仅当 EME 加载时应用</li>
	 *   <li>Ae2ApiaryMixin / Ae2CentrifugeMixin / Ae2CentrifugeFactoryMixin — 引用 AE2 的 IAe2OutputHost 接口，仅当 AE2 加载时应用</li>
	 *   <li>Ae2ExtraCentrifugeFactoryMixin / Ae2ExtraCentrifugeFactoryInputMixin — 引用 AE2 接口且目标类继承 ME
	 * 基类，仅当 AE2+ME 加载时应用</li>
	 *   <li>Ae2EMExtraCentrifugeFactoryMixin / Ae2EMExtraCentrifugeFactoryInputMixin — 引用 AE2 接口且目标类继承 EME
	 * 基类，仅当 AE2+EME 加载时应用</li>
	 *   <li>JdteApiaryCoalescedMixin / JdteCentrifugeCoalescedMixin / JdteCentrifugeFactoryCoalescedMixin —
	 *       引用 JDTE 的 {@code CoalescedAcceleratedMachine} 接口（类声明 implements），仅当 jdte 加载时应用。
	 *       目标类均为本模组自有方块实体；接口由应用类加载器（NeoForge mod classloader）加载，
	 *       MixinClassLoader 委托父加载器解析，与目标类看到的是同一 Class 实例，无类加载约束冲突。</li>
	 * </ul>
	 * 其他 Mixin（离心机/PB原版类等）不依赖可选 mod，始终应用。
	 * <br/>
	 * <b>注意</b>：不要在 {@link #onLoad(String)} 中通过 {@code Class.forName} 反射加载 Mekanism 类，
	 * 因为 Mixin 加载阶段是 JVM 类加载的敏感窗口期，强制加载 Mekanism 类会触发其父类/接口链的早期链接，
	 * 可能导致其他模组（如 Re:Avaritia 的 IItemStackExtensionMixin）的 Mixin 目标被提前加载，
	 * 报错 {@code target was loaded too early}。@Accessor 字段校验若需要，应在 ASM 字节码层面或
	 * mod 完成加载后的事件中执行，不在 Mixin 加载阶段触发任何类加载。
	 */
public class MixinConfigPlugin implements IMixinConfigPlugin {

	/** ME(MekanismExtras)的 modId */
	private static final String ME_MOD_ID = "mekanism_extras";
	/** EME(EvolvedMekanismExtras)的 modId */
	private static final String EME_MOD_ID = "emextras";
	/** AE2(AppliedEnergistics2)的 modId */
	private static final String AE2_MOD_ID = "ae2";
	/** Mek Energistics 的 modId */
	private static final String MEKENERGISTICS_MOD_ID = "mekenergistics";
	/** Building Gadgets 2 的 modId */
	private static final String BUILDING_GADGETS_MOD_ID = "buildinggadgets2";

	/** Just Dire Things Extras 的 modId（CoalescedAcceleratedMachine 合并接口注入） */
	private static final String JDTE_MOD_ID = "jdte";

	/**
	 * 引用 ME 类的 Mixin 简单类名集合（@Mixin 目标或类体 import 了 ME 的类）
	 * <br/>
	 * ExtraUpgradeStackMixin 引用 {@link com.jerry.mekextras.api.ExtraUpgrade}（ME API 类），
	 * 仅在 ME 加载时应用。其 @Mixin 目标是 Mekanism 的 TileComponentUpgrade（始终可加载），
	 * 但类体中的 ExtraUpgrade.STACK 比较要求 ME 已加载，否则 NoClassDefFoundError。
	 * <p>
	 * GuiUpgradeWindowMixin 同样引用 ExtraUpgrade.STACK，拦截 GuiUpgradeWindow.renderForeground
	 * 的 getMax() 调用，修复离心机工厂 STACK 升级 Tab 显示 16/8 应为 16/16 的 bug。
	 * <p>
	 * ExtraFactoryInputInventorySlotMixin 引用 ExtraFactoryInputInventorySlot（ME 类），
	 * 拦截 ME 工厂输入槽的 getLimit 覆盖，用我们的配置倍率替代 ME 的 8&lt;&lt;tier.ordinal()。
	 * <p>
	 * ExtraFactoryOutputInventorySlotMixin 引用 ExtraFactoryOutputInventorySlot（ME 类），
	 * 拦截 ME 工厂输出槽的 getLimit 覆盖，用我们的输出槽配置倍率替代 ME 的 8&lt;&lt;tier.ordinal()。
	 */
	private static final Set<String> ME_MIXINS = Set.of(
			"ExtraFactoryMixin",
			"FactoryForMEMixin",
			"TileEntityExtraFactoryAccessor",
			"ExtraUpgradeStackMixin",
			"GuiUpgradeWindowMixin",
			"ExtraFactoryInputInventorySlotMixin",
			"ExtraFactoryOutputInventorySlotMixin"
	);

	/** 仅依赖 JDTE 的合并加速 Mixin。 */
	private static final Set<String> JDTE_MIXINS = Set.of(
		"JdteApiaryCoalescedMixin",
		"JdteCentrifugeCoalescedMixin",
		"JdteCentrifugeFactoryCoalescedMixin"
	);

	/** 目标继承 Mekanism Extras 基类，必须同时加载 JDTE 与 ME。 */
	private static final Set<String> JDTE_ME_MIXINS = Set.of(
		"JdteExtraCentrifugeFactoryCoalescedMixin"
	);

	/** 目标继承 Evolved Mekanism Extras 基类，必须同时加载 JDTE 与 EME。 */
	private static final Set<String> JDTE_EME_MIXINS = Set.of(
		"JdteEMExtraCentrifugeFactoryCoalescedMixin"
	);

	/** 引用 EME 类的 Mixin 简单类名集合（@Mixin 目标或类体 import 了 EME 的类）
	 * <br/>
	 * EMExtraFactoryInputInventorySlotMixin 引用 EMExtraFactoryInputInventorySlot（EME 类），
	 * 拦截 EME 工厂输入槽的 getLimit 覆盖，用我们的配置倍率替代 EME 的 8/16/32/64。
	 * <br/>
	 * EMExtraFactoryOutputInventorySlotMixin 引用 EMExtraFactoryOutputInventorySlot（EME 类），
	 * 拦截 EME 工厂输出槽的 getLimit 覆盖，用我们的输出槽配置倍率替代 EME 的 8/16/32/64。 */
	private static final Set<String> EME_MIXINS = Set.of(
			"EMExtraFactoryMixin",
			"TileEntityEMExtraFactoryAccessor",
			"EMExtraFactoryInputInventorySlotMixin",
			"EMExtraFactoryOutputInventorySlotMixin"
	);

	/** 同时引用 Mekanism Extras 与 Evolved Mekanism Extras 的 Mixin。 */
	private static final Set<String> ME_EME_MIXINS = Set.of(
			"ExtraFactoryForEMEMixin"
	);

	/** AE2 接口注入 Mixin（目标类始终可加载，仅要求 AE2 已安装） */
	private static final Set<String> AE2_MIXINS = Set.of(
			"Ae2ApiaryMixin",
			"Ae2CentrifugeMixin",
			"Ae2CentrifugeFactoryMixin"
	);

	/** AE2 接口注入 Mixin（目标类继承 ME 基类，要求 AE2+ME 同时安装） */
	private static final Set<String> AE2_ME_MIXINS = Set.of(
			"Ae2ExtraCentrifugeFactoryMixin",
			"Ae2ExtraCentrifugeFactoryInputMixin"
	);

	/** AE2 接口注入 Mixin（目标类继承 EME 基类，要求 AE2+EME 同时安装） */
	private static final Set<String> AE2_EME_MIXINS = Set.of(
			"Ae2EMExtraCentrifugeFactoryMixin",
			"Ae2EMExtraCentrifugeFactoryInputMixin"
	);

	/** 引用 mekenergistics 类的 Mixin 简单类名集合（目标类仅当该 mod 加载时存在） */
	private static final Set<String> MEKENERGISTICS_MIXINS = Set.of(
			"MekEnergisticsInstallerGuardMixin",
			"MekEnergisticsTargetResolverGuardMixin"
	);

	/** 引用 buildinggadgets2 类的 Mixin 简单类名集合（目标类仅当该 mod 加载时存在） */
	private static final Set<String> BUILDING_GADGETS_MIXINS = Set.of(
			"RenderBlockBeLoadFixMixin"
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
		/** AE2 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean AE2_LOADED = isModLoaded(AE2_MOD_ID);
		/** Mek Energistics 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean MEKENERGISTICS_LOADED = isModLoaded(MEKENERGISTICS_MOD_ID);
		/** Building Gadgets 2 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean BUILDING_GADGETS_LOADED = isModLoaded(BUILDING_GADGETS_MOD_ID);

		/** JDTE 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean JDTE_LOADED = isModLoaded(JDTE_MOD_ID);
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
		} catch (LinkageError | RuntimeException t) {
			// LinkageError 覆盖 FMLLoader 版本不兼容；RuntimeException 覆盖状态异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			// 防御性：FMLLoader 状态异常时安全降级，不应用可选 Mixin
			// Mixin 早期阶段日志系统可能未就绪，使用 System.err 输出（单次检测不会刷屏）
			System.err.println("[ProductiveBeesGenesis] Mixin 阶段检测 mod [" + modId
					+ "] 失败, 视为未加载: " + t);
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
		// v12 Task 17 曾在此处调用 validateAccessors() 通过 Class.forName 预校验 @Accessor 字段，
		// 但该方式会强制加载 Mekanism 类，触发其父类/接口链的早期链接，导致其他模组（如 Re:Avaritia）
		// 的 Mixin 目标 IItemStackExtension 被提前加载，报错 "target was loaded too early"。
		// 已回退：@Accessor 字段校验若需要，应在 mod 完成加载后的事件中或 ASM 字节码层面执行，
		// 不在 Mixin 加载阶段触发任何类加载。
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
	 * 原理：从 mixinClassName 提取简单类名，判断其是否属于 ME/EME/AE2 受控集合，
	 * 仅当对应 mod 已加载时才返回 true；其他 Mixin 始终返回 true。
	 * <br/>
	 * AE2 Mixin 分三类：
	 * <ul>
	 *   <li>AE2_MIXINS — 仅要求 AE2 加载（目标类始终可加载）</li>
	 *   <li>AE2_ME_MIXINS — 要求 AE2+ME 同时加载（目标类继承 ME 基类）</li>
	 *   <li>AE2_EME_MIXINS — 要求 AE2+EME 同时加载（目标类继承 EME 基类）</li>
	 * </ul>
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
		if (ME_EME_MIXINS.contains(simpleName)) {
			return Holder.ME_LOADED && Holder.EME_LOADED;
		}
		// AE2 接口注入 Mixin — 仅在 AE2 已安装时应用
		if (AE2_MIXINS.contains(simpleName)) {
			return Holder.AE2_LOADED;
		}
		// AE2 + ME Mixin — 目标类继承 ME 基类，需两者同时加载
		if (AE2_ME_MIXINS.contains(simpleName)) {
			return Holder.AE2_LOADED && Holder.ME_LOADED;
		}
		// AE2 + EME Mixin — 目标类继承 EME 基类，需两者同时加载
		if (AE2_EME_MIXINS.contains(simpleName)) {
			return Holder.AE2_LOADED && Holder.EME_LOADED;
		}
		if (MEKENERGISTICS_MIXINS.contains(simpleName)) {
			return Holder.MEKENERGISTICS_LOADED;
		}
		if (BUILDING_GADGETS_MIXINS.contains(simpleName)) {
			return Holder.BUILDING_GADGETS_LOADED;
		}
		if (JDTE_MIXINS.contains(simpleName)) {
			return Holder.JDTE_LOADED;
		}
		if (JDTE_ME_MIXINS.contains(simpleName)) {
			return Holder.JDTE_LOADED && Holder.ME_LOADED;
		}
		if (JDTE_EME_MIXINS.contains(simpleName)) {
			return Holder.JDTE_LOADED && Holder.EME_LOADED;
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
