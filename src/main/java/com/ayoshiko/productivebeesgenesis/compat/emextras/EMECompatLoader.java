package com.ayoshiko.productivebeesgenesis.compat.emextras;

/**
	 * EvolvedMekanismExtras (EME) 兼容注册统一入口
	 * <br/>
	 * 供主注册类（{@link com.ayoshiko.productivebeesgenesis.init.ModBlocks}、
	 * {@link com.ayoshiko.productivebeesgenesis.init.ModItems}、
	 * {@link com.ayoshiko.productivebeesgenesis.init.ModBlockEntities}）调用，
	 * 集中触发 EME 相关的方块/物品/方块实体注册。
	 * 仅在 EvolvedMekanismExtras 加载时被调用，避免 EME 未加载时触发 EME 类的类加载。
	 * <p>
	 * 本包下的类可直接 import EME 的类（EMExtraFactoryTier、EMExtraMachine 等），
	 * 因为这些类仅在 EME 已加载时才会被加载和执行。
	 * <p>
	 * 设计说明：提供细粒度方法（{@link #registerCentrifugeBlocks()}、{@link #registerApiaryBlocks()} 等）
	 * 而非仅一个 {@link #registerAll()}，是为了保持主类中离心机和蜂箱的初始化顺序依赖
	 * （initEMETiers 必须在对应 register 方法之前调用）。
	 */
public final class EMECompatLoader {

	private EMECompatLoader() {}

	/**
	 * 注册全部 EME 相关内容
	 * <br/>
	 * 调用顺序：方块 → 物品 → 方块实体（物品依赖方块的 DeferredBlock，方块实体依赖方块的 DeferredBlock）。
	 * 调用时机：必须在 BLOCKS/ITEMS/BLOCK_ENTITIES 的 register(eventBus) 之前调用，
	 * 且对应的 initEMETiers() 必须已执行。
	 * <p>
	 * 注意：主注册类按字段分组调用细粒度方法（如 {@link #registerCentrifugeBlocks()}），
	 * 而非调用此方法，以保持主类的初始化顺序（离心机 initEMETiers 在蜂箱 initEMETiers 之前）。
	 */
	public static void registerAll() {
		registerCentrifugeBlocks();
		registerApiaryBlocks();
		registerCentrifugeItems();
		registerApiaryItems();
		registerCentrifugeBlockEntities();
		registerApiaryBlockEntities();
	}

	/**
	 * 注册 EME 离心机工厂方块
	 * <br/>
	 * 委托 {@link EMEBlockRegistration#registerFactories()}。
	 * 调用时机：必须在 {@code MekCentrifugeBlockType.initEMETiers()} 之后、
	 * BLOCKS.register(eventBus) 之前调用。
	 */
	public static void registerCentrifugeBlocks() {
		EMEBlockRegistration.registerFactories();
	}

	/**
	 * 注册 EME 蜂箱工厂方块
	 * <br/>
	 * 委托 {@link EMEBlockRegistration#registerApiaryFactories()}。
	 * 调用时机：必须在 {@code MekApiaryEMEBlockType.initEMETiers(...)} 之后、
	 * BLOCKS.register(eventBus) 之前调用。
	 */
	public static void registerApiaryBlocks() {
		EMEBlockRegistration.registerApiaryFactories();
	}

	/**
	 * 注册 EME 离心机工厂 BlockItem
	 * <br/>
	 * 委托 {@link EMEItemRegistration#registerFactoryItems()}。
	 * 调用时机：必须在 {@link #registerCentrifugeBlocks()} 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerCentrifugeItems() {
		EMEItemRegistration.registerFactoryItems();
	}

	/**
	 * 注册 EME 蜂箱工厂 BlockItem
	 * <br/>
	 * 委托 {@link EMEItemRegistration#registerApiaryFactoryItems()}。
	 * 调用时机：必须在 {@link #registerApiaryBlocks()} 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerApiaryItems() {
		EMEItemRegistration.registerApiaryFactoryItems();
	}

	/**
	 * 注册 EME 离心机工厂 BlockEntityType
	 * <br/>
	 * 委托 {@link EMEBlockEntityRegistration#registerFactoryTiles()}。
	 * 调用时机：必须在 {@link #registerCentrifugeBlocks()} 之后、BLOCK_ENTITIES.register(eventBus) 之前调用。
	 */
	public static void registerCentrifugeBlockEntities() {
		EMEBlockEntityRegistration.registerFactoryTiles();
	}

	/**
	 * 注册 EME 蜂箱工厂 BlockEntityType
	 * <br/>
	 * 委托 {@link EMEBlockEntityRegistration#registerApiaryFactoryTiles()}。
	 * 调用时机：必须在 {@link #registerApiaryBlocks()} 之后、BLOCK_ENTITIES.register(eventBus) 之前调用。
	 */
	public static void registerApiaryBlockEntities() {
		EMEBlockEntityRegistration.registerApiaryFactoryTiles();
	}

	/**
	 * 注册 EME 等级的离心机工厂 MenuType
	 * <br/>
	 * 委托 {@link EMEMenuTypeRegistration#registerFactoryMenuType()}，结果存入 {@link
	 * EMEMenuTypeRegistration#EME_CENTRIFUGE_FACTORY}。
	 * 必须在 {@code ModMenuTypes.register(eventBus)} 之前调用。
	 * EME 未加载时安全跳过，避免触发 {@link EMEMenuTypeRegistration} 类加载导致 NoClassDefFoundError。
	 */
	public static void registerCentrifugeMenuType() {
		if (com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			EMEMenuTypeRegistration.registerFactoryMenuType();
		}
	}
}
