package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryBlockType;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader;
import com.ayoshiko.productivebeesgenesis.compat.emextras.MekApiaryEMEBlockType;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MekApiaryMEBlockType;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.content.blocktype.BlockTypeTile;

/**
 * Mek 系兼容扩展初始化器
 * <br/>
 * 从 {@link ProductiveBeesGenesis} 拆分而来，职责（SRP）：在 DeferredRegister
 * 注册前完成 EM/ME/EME 三层离心机工厂与两层蜂箱工厂的等级、方块、方块实体、
 * 物品与菜单类型注册，并处理 ME 可选依赖的反射降级。
 */
final class MekCompatInitializer {

	private MekCompatInitializer() {
	}

	/**
	 * 初始化 Mek 离心机扩展（EM/ME/EME 三层工厂）— 必须在 registerDeferredRegisters 之前完成。
	 * 顺序：initXxxTiers → registerXxxFactories → registerXxxFactoryTiles → registerXxxFactoryItems → registerXxxMenuType
	 */
	static void initMekCentrifugeExtensions() {
		// EM 扩展
		MekCentrifugeBlockType.initEMTiers();
		ModBlocks.registerEMFactories();
		ModBlockEntities.registerEMFactoryTiles();
		ModItems.registerEMFactoryItems();

		// ME 扩展（initMETiers 为 ULTIMATE 添加 ExtraAttributeUpgradeable）
		MekCentrifugeBlockType.initMETiers();
		ModBlocks.registerMEFactories();
		ModBlockEntities.registerMEFactoryTiles();
		ModItems.registerMEFactoryItems();
		// ME MenuType 注册（内部守卫，ME 未加载时安全跳过）
		MECompatLoader.registerCentrifugeMenuType();

		// EME 扩展（initEMETiers 为 ULTIMATE/ME ABSOLUTE 添加 EMExtraAttributeUpgradeable）
		MekCentrifugeBlockType.initEMETiers();
		ModBlocks.registerEMEFactories();
		ModBlockEntities.registerEMEFactoryTiles();
		ModItems.registerEMEFactoryItems();
		// EME MenuType 注册（内部守卫，EME 未加载时安全跳过）
		EMECompatLoader.registerCentrifugeMenuType();
	}

	/**
	 * 初始化 Mek 蜂箱扩展（ME/EME 两层工厂蜂箱）— 必须在 registerDeferredRegisters 之前完成。
	 * 顺序：initXxxTiers → registerXxxFactories → registerXxxFactoryTiles → registerXxxFactoryItems
	 * EME 依赖 ME：initEMETiers 需要 ME ABSOLUTE BlockType；ME 未加载时传 null，EME 仍可独立注册。
	 */
	static void initMekApiaryExtensions() {
		// EM 扩展（EvolvedMekanism）— EM 优先于 ME，ULTIMATE 升级到 EM OVERCLOCKED 蜂箱工厂
		// EM 通过 Mixin 扩展 FactoryTier 枚举，复用 TileEntityMekApiaryFactory，不需要独立 BlockType 类
		if (MekCompatHooks.isEvolvedMekanismLoaded()) {
			MekApiaryFactoryBlockType.initEMTiers();
			ModBlocks.registerEMApiaryFactories();
			ModBlockEntities.registerEMApiaryFactoryTiles();
			ModItems.registerEMApiaryFactoryItems();
		}

		// ME 扩展 — 守卫避免 MekApiaryMEBlockType 类加载触发 NoClassDefFoundError
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MekApiaryMEBlockType.initMETiers(MekApiaryFactoryBlockType.ULTIMATE_MEK_APIARY_FACTORY);
			ModBlocks.registerMEApiaryFactories();
			ModBlockEntities.registerMEApiaryFactoryTiles();
			ModItems.registerMEApiaryFactoryItems();
		}

		// EME 扩展 — 守卫避免 MekApiaryEMEBlockType 类加载触发 NoClassDefFoundError
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			// 获取 ME ABSOLUTE 蜂箱工厂 BlockType（ME 未加载时为 null，EME 仍可独立注册）
			// 通过反射获取 ExtraFactoryTier.ABSOLUTE，避免主类编译期硬依赖 ME 模组
			BlockTypeTile<?> meAbsoluteApiaryFactory = resolveMEAbsoluteTierAndApiaryFactory();
			// 传入 ULTIMATE 蜂箱工厂，为其添加 EME 升级链（指向 EME ABSOLUTE_OVERCLOCKED 蜂箱）
			MekApiaryEMEBlockType.initEMETiers(
					MekApiaryFactoryBlockType.ULTIMATE_MEK_APIARY_FACTORY,
					meAbsoluteApiaryFactory);
			ModBlocks.registerEMEApiaryFactories();
			ModBlockEntities.registerEMEApiaryFactoryTiles();
			ModItems.registerEMEApiaryFactoryItems();
		}
	}

	/**
	 * 反射获取 ME 的 ABSOLUTE 蜂箱工厂 BlockType
	 * <br/>
	 * 主类不能直接引用 {@code ExtraFactoryTier}（ME 可选依赖），否则 ME 未加载时
	 * 类加载验证会触发 NoClassDefFoundError。此方法通过反射读取
	 * {@code ExtraFactoryTier.ABSOLUTE} 静态字段，再调用
	 * {@link MekApiaryMEBlockType#getMEApiaryFactoryType(Object)}（参数为 Object）。
	 * ME 未加载或反射失败时返回 null，EME 蜂箱升级链将不包含 ME ABSOLUTE 跨系统升级（安全降级）。
	 *
	 * @return ME ABSOLUTE 蜂箱工厂 BlockType，ME 未加载或反射失败时返回 null
	 */
	@org.jetbrains.annotations.Nullable
	private static BlockTypeTile<?> resolveMEAbsoluteTierAndApiaryFactory() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return null;
		}
		try {
			Class<?> tierClass = Class.forName("com.jerry.mekextras.common.tier.ExtraFactoryTier");
			Object meAbsoluteTier = tierClass.getField("ABSOLUTE").get(null);
			if (meAbsoluteTier == null) {
				return null;
			}
			return MekApiaryMEBlockType.getMEApiaryFactoryType(meAbsoluteTier);
		} catch (ClassNotFoundException e) {
			// ME 已加载但类路径异常（理论上不应发生），安全降级
			ProductiveBeesGenesis.LOGGER.warn("反射加载 ExtraFactoryTier 失败，EME 蜂箱升级链不包含 ME ABSOLUTE", e);
			return null;
		} catch (NoSuchFieldException | IllegalAccessException e) {
			// ABSOLUTE 字段被移除/重命名/访问受限，安全降级
			ProductiveBeesGenesis.LOGGER.warn("反射读取 ExtraFactoryTier.ABSOLUTE 失败，EME 蜂箱升级链不包含 ME ABSOLUTE", e);
			return null;
		} catch (LinkageError e) {
			// ME 模组类初始化失败（ExceptionInInitializerError 等），安全降级
			ProductiveBeesGenesis.LOGGER.warn("ExtraFactoryTier 类初始化失败，EME 蜂箱升级链不包含 ME ABSOLUTE", e);
			return null;
		}
	}
}
