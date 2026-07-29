package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

/**
 * Mekanism Extras (ME) 兼容注册统一入口
 * <br/>
 * 供主注册类（{@code ModBlocks}/{@code ModItems}/{@code ModBlockEntities}）调用，
 * 集中触发 ME 相关的方块/物品/方块实体注册。
 * <p>
 * 本类本身不直接 import ME 的类，只调用同包下的隔离注册类。
 * 仅在 MekanismExtras 加载时由主注册类通过运行时守卫调用，
 * 避免 ME 未加载时触发 ME 类的类加载。
 * <p>
 * 分步方法（{@link #registerFactories()} 等）保留与原主注册类相同的调用粒度，
 * 确保主类调用顺序（方块 → 方块实体 → 物品）不被破坏；
 * {@link #registerAll()} 提供统一入口，供需要一次性注册所有内容的场景使用。
 */
public final class MECompatLoader {

	private MECompatLoader() {}

	/**
	 * 注册 ME 等级的离心机工厂方块
	 * <br/>
	 * 委托至 {@link MEBlockRegistration#registerFactories()}，结果填充到 {@code ModBlocks.ME_FACTORIES}。
	 */
	public static void registerFactories() {
		MEBlockRegistration.registerFactories();
	}

	/**
	 * 注册 ME 等级的蜂箱工厂方块
	 * <br/>
	 * 委托至 {@link MEBlockRegistration#registerApiaryFactories()}，结果填充到 {@code ModBlocks.ME_APIARY_FACTORIES}。
	 */
	public static void registerApiaryFactories() {
		MEBlockRegistration.registerApiaryFactories();
	}

	/**
	 * 注册 ME 等级的离心机工厂 BlockItem
	 * <br/>
	 * 委托至 {@link MEItemRegistration#registerFactoryItems()}，结果填充到 {@code ModItems.ME_FACTORY_ITEMS}。
	 */
	public static void registerFactoryItems() {
		MEItemRegistration.registerFactoryItems();
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 委托至 {@link MEItemRegistration#registerApiaryFactoryItems()}，结果填充到 {@code ModItems.ME_APIARY_FACTORY_ITEMS}。
	 */
	public static void registerApiaryFactoryItems() {
		MEItemRegistration.registerApiaryFactoryItems();
	}

	/**
	 * 注册 ME 等级的离心机工厂 BlockEntityType
	 * <br/>
	 * 委托至 {@link MEBlockEntityRegistration#registerFactoryTiles()}，结果填充到 {@code MekCentrifugeMEBlockType.ME_FACTORY_TILES}。
	 */
	public static void registerFactoryTiles() {
		MEBlockEntityRegistration.registerFactoryTiles();
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 委托至 {@link MEBlockEntityRegistration#registerApiaryFactoryTiles()}，结果填充到 {@code MekApiaryMEBlockType.ME_APIARY_FACTORY_TILES}。
	 */
	public static void registerApiaryFactoryTiles() {
		MEBlockEntityRegistration.registerApiaryFactoryTiles();
	}

	/**
	 * 注册 ME 等级的离心机工厂 MenuType
	 * <br/>
	 * 委托至 {@link MEMenuTypeRegistration#registerFactoryMenuType()}，结果存入 {@link MEMenuTypeRegistration#ME_CENTRIFUGE_FACTORY}。
	 * 必须在 {@code ModMenuTypes.register(eventBus)} 之前调用。
	 * ME 未加载时安全跳过，避免触发 {@link MEMenuTypeRegistration} 类加载导致 NoClassDefFoundError。
	 */
	public static void registerCentrifugeMenuType() {
		if (com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks.isMekanismExtrasLoaded()) {
			MEMenuTypeRegistration.registerFactoryMenuType();
		}
	}

	/**
	 * 注册全部 ME 相关内容（方块 + 物品 + 方块实体）
	 * <br/>
	 * 调用顺序：方块 → 物品 → 方块实体（物品依赖方块的 DeferredBlock，方块实体依赖方块的 DeferredBlock）。
	 * 调用时机：必须在 BLOCKS/ITEMS/BLOCK_ENTITIES 的 register(eventBus) 之前调用。
	 * <p>
	 * 注意：主注册类不应直接调用本方法，而应通过分步方法保持原有调用顺序，
	 * 避免 DeferredRegister 重复注册同名条目导致 IllegalStateException。
	 */
	public static void registerAll() {
		registerFactories();
		registerApiaryFactories();
		registerFactoryItems();
		registerApiaryFactoryItems();
		registerFactoryTiles();
		registerApiaryFactoryTiles();
	}
}
