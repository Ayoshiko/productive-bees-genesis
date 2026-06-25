package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.registration.impl.TileEntityTypeDeferredRegister;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tier.FactoryTier;
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
 */
public final class ModBlockEntities {

    static final TileEntityTypeDeferredRegister BLOCK_ENTITIES =
            new TileEntityTypeDeferredRegister(ProductiveBeesGenesis.MOD_ID);

    /** 基础离心机BlockEntityType */
    public static final TileEntityTypeRegistryObject<TileEntityMekCentrifuge> MEK_CENTRIFUGE =
            BLOCK_ENTITIES.mekBuilder(ModBlocks.MEK_CENTRIFUGE,
                    (pos, state) -> new TileEntityMekCentrifuge(ModBlocks.MEK_CENTRIFUGE, pos, state))
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
            DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> deferredBlock = ModBlocks.getEMFactoryBlock(tier);
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
     * 当MekanismExtras加载时，遍历4个ExtraFactoryTier（ABSOLUTE/SUPREME/COSMIC/INFINITE），
     * 为每个tier注册独立的BlockEntityType。
     * 使用TileEntityExtraMekCentrifugeFactory作为TileEntity类，配置server/client ticker和CONFIG_CARD。
     * 注册结果填充到MekCentrifugeBlockType.ModBlockEntitiesHolder.ME_FACTORY_TILES Map，
     * 供MekCentrifugeBlockType.createMEFactoryBlockType()的懒加载Supplier使用。
     * <p>
     * 调用时机：必须在ModBlocks.registerMEFactories()之后（需要DeferredBlock）、
     * BLOCK_ENTITIES.register(eventBus)之前调用。
     */
    public static void registerMEFactoryTiles() {
        if (!MekCompatHooks.isMekanismExtrasLoaded()) {
            return;
        }
        for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
            DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>> deferredBlock = ModBlocks.getMEFactoryBlock(tier);
            if (deferredBlock == null) {
                ProductiveBeesGenesis.LOGGER.warn("ME工厂方块未注册，跳过TileEntity注册: {}", tier.name());
                continue;
            }
            TileEntityTypeRegistryObject<TileEntityExtraMekCentrifugeFactory> tileType =
                    BLOCK_ENTITIES.mekBuilder(deferredBlock,
                            (pos, state) -> new TileEntityExtraMekCentrifugeFactory(deferredBlock, pos, state))
                            .serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
                            .clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
                            .withSimple(Capabilities.CONFIG_CARD)
                            .build();
            MekCentrifugeBlockType.ModBlockEntitiesHolder.ME_FACTORY_TILES.put(tier, tileType);
        }
    }

    /**
     * 注册EME等级的工厂BlockEntityType
     * <br/>
     * 当EvolvedMekanismExtras加载时，遍历4个EMExtraFactoryTier（ABSOLUTE_OVERCLOCKED/
     * SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），为每个tier注册独立的BlockEntityType。
     * 使用TileEntityEMExtraMekCentrifugeFactory作为TileEntity类，配置server/client ticker和CONFIG_CARD。
     * 注册结果填充到MekCentrifugeBlockType.ModBlockEntitiesHolder.EME_FACTORY_TILES Map，
     * 供MekCentrifugeBlockType.createEMEFactoryBlockType()的懒加载Supplier使用。
     * <p>
     * 调用时机：必须在ModBlocks.registerEMEFactories()之后（需要DeferredBlock）、
     * BLOCK_ENTITIES.register(eventBus)之前调用。
     */
    public static void registerEMEFactoryTiles() {
        if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
            return;
        }
        for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
            DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>> deferredBlock = ModBlocks.getEMEFactoryBlock(tier);
            if (deferredBlock == null) {
                ProductiveBeesGenesis.LOGGER.warn("EME工厂方块未注册，跳过TileEntity注册: {}", tier.name());
                continue;
            }
            TileEntityTypeRegistryObject<TileEntityEMExtraMekCentrifugeFactory> tileType =
                    BLOCK_ENTITIES.mekBuilder(deferredBlock,
                            (pos, state) -> new TileEntityEMExtraMekCentrifugeFactory(deferredBlock, pos, state))
                            .serverTicker((level, pos, state, tile) -> TileEntityMekanism.tickServer(level, pos, state, tile))
                            .clientTicker((level, pos, state, tile) -> TileEntityMekanism.tickClient(level, pos, state, tile))
                            .withSimple(Capabilities.CONFIG_CARD)
                            .build();
            MekCentrifugeBlockType.ModBlockEntitiesHolder.EME_FACTORY_TILES.put(tier, tileType);
        }
    }

    /** 注册到事件总线 */
    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
