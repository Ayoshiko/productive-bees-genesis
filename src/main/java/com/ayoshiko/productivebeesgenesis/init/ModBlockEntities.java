package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlockType;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryBlockType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.registration.impl.TileEntityTypeDeferredRegister;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.base.TileEntityMekanism;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
	 * 方块实体注册类
	 * <br/>
	 * 使用Mekanism的TileEntityTypeDeferredRegister注册BlockEntityType，
	 * 自动配置server/client ticker和Mekanism标准Capability。
	 * 每个工厂等级拥有独立的BlockEntityType，避免方块类型不匹配崩溃。
	 * <p>
	 * EM扩展：当EvolvedMekanism加载时，通过registerEMFactoryTiles()为5个EM等级
	 * 动态注册BlockEntityType，填充MekCentrifugeBlockType.ModBlockEntitiesHolder.EM_FACTORY_TILES Map。
	 * 该Map被MekCentrifugeBlockType.getFactoryTileEntityType()的懒加载Supplier使用。
	 * <p>
	 * EME扩展：当EvolvedMekanismExtras加载时，委托 {@link EMECompatLoader} 完成 EME 工厂 BlockEntityType 注册，
	 * 填充 MekCentrifugeEMEBlockType.EME_FACTORY_TILES 和 MekApiaryEMEBlockType.EME_APIARY_FACTORY_TILES。
	 */
public final class ModBlockEntities {

	public static final TileEntityTypeDeferredRegister BLOCK_ENTITIES =
			new TileEntityTypeDeferredRegister(ProductiveBeesGenesis.MOD_ID);

	/** 基础离心机BlockEntityType */
	public static final TileEntityTypeRegistryObject<TileEntityMekCentrifuge> MEK_CENTRIFUGE =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.MEK_CENTRIFUGE,
					(pos, state) -> new TileEntityMekCentrifuge(ModBlocks.MEK_CENTRIFUGE, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** MEK通用机械蜂箱BlockEntityType — 生产周期1200 ticks */
	public static final TileEntityTypeRegistryObject<TileEntityMekApiary> MEK_APIARY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.MEK_APIARY,
					(pos, state) -> new TileEntityMekApiary(ModBlocks.MEK_APIARY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 通用机械蜂箱 — 基础工厂BlockEntityType */
	public static final TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> BASIC_MEK_APIARY_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.BASIC_MEK_APIARY_FACTORY,
					(pos, state) -> new TileEntityMekApiaryFactory(ModBlocks.BASIC_MEK_APIARY_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 通用机械蜂箱 — 高级工厂BlockEntityType */
	public static final TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ADVANCED_MEK_APIARY_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ADVANCED_MEK_APIARY_FACTORY,
					(pos, state) -> new TileEntityMekApiaryFactory(ModBlocks.ADVANCED_MEK_APIARY_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 通用机械蜂箱 — 精英工厂BlockEntityType */
	public static final TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ELITE_MEK_APIARY_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ELITE_MEK_APIARY_FACTORY,
					(pos, state) -> new TileEntityMekApiaryFactory(ModBlocks.ELITE_MEK_APIARY_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 通用机械蜂箱 — 终极工厂BlockEntityType */
	public static final TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ULTIMATE_MEK_APIARY_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ULTIMATE_MEK_APIARY_FACTORY,
					(pos, state) -> new TileEntityMekApiaryFactory(ModBlocks.ULTIMATE_MEK_APIARY_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 基础工厂离心机BlockEntityType（3并行） */
	public static final TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> BASIC_MEK_CENTRIFUGE_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY,
					(pos, state) -> new TileEntityMekCentrifugeFactory(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 高级工厂离心机BlockEntityType（5并行） */
	public static final TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ADVANCED_MEK_CENTRIFUGE_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY,
					(pos, state) -> new TileEntityMekCentrifugeFactory(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 精英工厂离心机BlockEntityType（7并行） */
	public static final TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ELITE_MEK_CENTRIFUGE_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY,
					(pos, state) -> new TileEntityMekCentrifugeFactory(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	/** 终极工厂离心机BlockEntityType（9并行） */
	public static final TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ULTIMATE_MEK_CENTRIFUGE_FACTORY =
			BLOCK_ENTITIES.mekBuilder(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
					(pos, state) -> new TileEntityMekCentrifugeFactory(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY, pos, state))
					.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
					.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
					.withSimple(Capabilities.CONFIG_CARD)
					.build();

	static {
		// 设置MekCentrifugeBlockType中的延迟引用
		MekCentrifugeBlockType.ModBlockEntitiesHolder.MEK_CENTRIFUGE = MEK_CENTRIFUGE;
		MekCentrifugeBlockType.ModBlockEntitiesHolder.BASIC_MEK_CENTRIFUGE_FACTORY = BASIC_MEK_CENTRIFUGE_FACTORY;
		MekCentrifugeBlockType.ModBlockEntitiesHolder.ADVANCED_MEK_CENTRIFUGE_FACTORY = ADVANCED_MEK_CENTRIFUGE_FACTORY;
		MekCentrifugeBlockType.ModBlockEntitiesHolder.ELITE_MEK_CENTRIFUGE_FACTORY = ELITE_MEK_CENTRIFUGE_FACTORY;
		MekCentrifugeBlockType.ModBlockEntitiesHolder.ULTIMATE_MEK_CENTRIFUGE_FACTORY = ULTIMATE_MEK_CENTRIFUGE_FACTORY;
		// 设置MekApiaryBlockType中的延迟引用
		MekApiaryBlockType.ModBlockEntitiesHolder.MEK_APIARY = MEK_APIARY;
		// 设置MekApiaryFactoryBlockType中的延迟引用（工厂版4等级）
		MekApiaryFactoryBlockType.ModBlockEntitiesHolder.BASIC_MEK_APIARY_FACTORY = BASIC_MEK_APIARY_FACTORY;
		MekApiaryFactoryBlockType.ModBlockEntitiesHolder.ADVANCED_MEK_APIARY_FACTORY = ADVANCED_MEK_APIARY_FACTORY;
		MekApiaryFactoryBlockType.ModBlockEntitiesHolder.ELITE_MEK_APIARY_FACTORY = ELITE_MEK_APIARY_FACTORY;
		MekApiaryFactoryBlockType.ModBlockEntitiesHolder.ULTIMATE_MEK_APIARY_FACTORY = ULTIMATE_MEK_APIARY_FACTORY;
	}

	private ModBlockEntities() {}

	/**
	 * 注册EM等级的工厂BlockEntityType
	 * <br/>
	 * 当EvolvedMekanism加载时，遍历EM FactoryTier，为每个tier注册独立的BlockEntityType。
	 * 使用TileEntityMekCentrifugeFactory作为TileEntity类，配置server/client ticker和CONFIG_CARD。
	 * 注册结果填充到MekCentrifugeBlockType.ModBlockEntitiesHolder.EM_FACTORY_TILES Map，
	 * 供MekCentrifugeBlockType.getFactoryTileEntityType()的懒加载Supplier使用。
	 * <p>
	 * 调用时机：必须在ModBlocks.registerEMFactories()之后（需要DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus)之前调用。
	 */
	public static void registerEMFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
			return;
		}
		for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
			DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
				Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> deferredBlock = ModBlocks.getEMFactoryBlock(tier);
			if (deferredBlock == null) {
				// 方块未注册（registerEMFactories未调用或失败），跳过并记录警告
				ProductiveBeesGenesis.LOGGER.warn("EM工厂方块未注册，跳过TileEntity注册: {}", tier.name());
				continue;
			}
			// 注册EM工厂BlockEntityType，使用与原版相同的配置模式
			TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> tileType =
					BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityMekCentrifugeFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			// 填充EM_FACTORY_TILES Map，供MekCentrifugeBlockType的懒加载Supplier使用
			MekCentrifugeBlockType.ModBlockEntitiesHolder.EM_FACTORY_TILES.put(tier, tileType);
		}
	}

	/**
	 * 注册ME等级的工厂BlockEntityType
	 * <br/>
	 * 当MekanismExtras加载时，委托给 {@link MECompatLoader#registerFactoryTiles()} 执行实际注册。
	 * 注册结果填充到MekCentrifugeMEBlockType.ME_FACTORY_TILES Map，
	 * 供MekCentrifugeMEBlockType.createMEFactoryBlockType()的懒加载Supplier使用。
	 * <p>
	 * 调用时机：必须在ModBlocks.registerMEFactories()之后（需要DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus)之前调用。
	 * <p>
	 * 本方法仅保留运行时守卫与委托调用，避免主注册类编译期依赖 ME 的类。
	 */
	public static void registerMEFactoryTiles() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerFactoryTiles();
		}
	}

	/**
	 * 注册EME等级的工厂BlockEntityType
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerCentrifugeBlockEntities()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 MekCentrifugeEMEBlockType.EME_FACTORY_TILES Map，
	 * 供 MekCentrifugeEMEBlockType.createEMEFactoryBlockType() 的懒加载 Supplier 使用。
	 * <p>
	 * 调用时机：必须在 {@link ModBlocks#registerEMEFactories()} 之后（需要DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus)之前调用。
	 */
	public static void registerEMEFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerCentrifugeBlockEntities();
	}

	/**
	 * 注册 EM 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 当 EvolvedMekanism 加载时，遍历 5 个 EM FactoryTier（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE），
	 * 为每个 tier 注册独立的 BlockEntityType。
	 * 使用 TileEntityMekApiaryFactory 作为 TileEntity 类（EM 蜂箱工厂复用原版 TileEntity），
	 * 配置 server/client ticker 和 CONFIG_CARD。
	 * 注册结果填充到 MekApiaryFactoryBlockType.ModBlockEntitiesHolder.EM_APIARY_FACTORY_TILES Map，
	 * 供 MekApiaryFactoryBlockType.getFactoryTileEntityType() 的懒加载 Supplier 使用。
	 * <p>
	 * 调用时机：必须在 ModBlocks.registerEMApiaryFactories() 之后（需要 DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus) 之前调用。
	 */
	public static void registerEMApiaryFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
			return;
		}
		for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
			DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
				BlockTypeTile<TileEntityMekApiaryFactory>>> deferredBlock = ModBlocks.getEMApiaryFactoryBlock(tier);
			if (deferredBlock == null) {
				ProductiveBeesGenesis.LOGGER.warn("EM 蜂箱工厂方块未注册，跳过 TileEntity 注册: {}", tier.name());
				continue;
			}
			TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> tileType =
					BLOCK_ENTITIES.mekBuilder(deferredBlock,
							(pos, state) -> new TileEntityMekApiaryFactory(deferredBlock, pos, state))
							.serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
							.clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
							.withSimple(Capabilities.CONFIG_CARD)
							.build();
			MekApiaryFactoryBlockType.ModBlockEntitiesHolder.EM_APIARY_FACTORY_TILES.put(tier, tileType);
		}
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 当 MekanismExtras 加载时，委托给 {@link MECompatLoader#registerApiaryFactoryTiles()} 执行实际注册。
	 * 注册结果填充到 MekApiaryMEBlockType.ME_APIARY_FACTORY_TILES Map，
	 * 供 MekApiaryMEBlockType.createMEApiaryFactoryBlockType() 的懒加载 Supplier 使用。
	 * <p>
	 * 调用时机：必须在 ModBlocks.registerMEApiaryFactories() 之后（需要 DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus) 之前调用。
	 * <p>
	 * 本方法仅保留运行时守卫与委托调用，避免主注册类编译期依赖 ME 的类。
	 */
	public static void registerMEApiaryFactoryTiles() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerApiaryFactoryTiles();
		}
	}

	/**
	 * 注册 EME 等级的蜂箱工厂 BlockEntityType
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerApiaryBlockEntities()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 MekApiaryEMEBlockType.EME_APIARY_FACTORY_TILES Map，
	 * 供 MekApiaryEMEBlockType.createEMEApiaryFactoryBlockType() 的懒加载 Supplier 使用。
	 * <p>
	 * 调用时机：必须在 {@link ModBlocks#registerEMEApiaryFactories()} 之后（需要 DeferredBlock）、
	 * BLOCK_ENTITIES.register(eventBus) 之前调用。
	 */
	public static void registerEMEApiaryFactoryTiles() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerApiaryBlockEntities();
	}

	/** 注册到事件总线 */
	public static void register(IEventBus eventBus) {
		BLOCK_ENTITIES.register(eventBus);
	}
}
