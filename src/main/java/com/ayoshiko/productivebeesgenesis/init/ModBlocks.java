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
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块注册类
 * <br/>
 * 注册5个MEK离心机方块（1基础+4工厂），使用MekCentrifugeBlock泛型方块。
 * BlockType通过MekCentrifugeBlockType定义，包含Mekanism的Attribute系统。
 * <p>
 * EM扩展：当EvolvedMekanism加载时，通过registerEMFactories()动态注册5个EM等级
 * （OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）的工厂方块，存入EM_FACTORIES Map。
 * EM等级在编译时不存在（通过Mixin运行时扩展枚举），必须通过MekCompatHooks反射获取。
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(ProductiveBeesGenesis.MOD_ID);

    /** 基础MEK离心机 */
    public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifuge, BlockTypeTile<TileEntityMekCentrifuge>>> MEK_CENTRIFUGE =
            BLOCKS.register("mek_centrifuge", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.MEK_CENTRIFUGE));

    /** 基础工厂 */
    public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> BASIC_MEK_CENTRIFUGE_FACTORY =
            BLOCKS.register("basic_mek_centrifuge_factory", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.BASIC_MEK_CENTRIFUGE_FACTORY));

    /** 高级工厂 */
    public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ADVANCED_MEK_CENTRIFUGE_FACTORY =
            BLOCKS.register("advanced_mek_centrifuge_factory", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ADVANCED_MEK_CENTRIFUGE_FACTORY));

    /** 精英工厂 */
    public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ELITE_MEK_CENTRIFUGE_FACTORY =
            BLOCKS.register("elite_mek_centrifuge_factory", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ELITE_MEK_CENTRIFUGE_FACTORY));

    /** 终极工厂 */
    public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ULTIMATE_MEK_CENTRIFUGE_FACTORY =
            BLOCKS.register("ultimate_mek_centrifuge_factory", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ULTIMATE_MEK_CENTRIFUGE_FACTORY));

    /** 无尽·创世蜜脾块 — 自定义蜜脾方块，属性参考PB蜜脾块 */
    public static final DeferredBlock<Block> INFINITY_CREATION_COMB_BLOCK =
            BLOCKS.register("infinitycreation_comb_block", () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .sound(SoundType.WOOD)
                    .strength(0.3F)
                    .requiresCorrectToolForDrops()));

    /**
     * EM工厂方块映射 — 由registerEMFactories()在EM加载时填充
     * <br/>
     * Key=FactoryTier（EM运行时扩展的枚举值），Value=对应的DeferredBlock。
     * 使用具体泛型类型（与原版4等级一致），确保ModBlockEntities的类型推断正确。
     * 使用ConcurrentHashMap保证线程安全（填充与查询可能并发）。
     */
    public static final Map<FactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>>> EM_FACTORIES = new ConcurrentHashMap<>();

    /**
     * ME工厂方块映射 — 由registerMEFactories()在ME加载时填充
     * <br/>
     * Key=ExtraFactoryTier（ME独立枚举，编译时可用），Value=对应的DeferredBlock。
     * ME工厂使用ExtraFactoryMachine BlockType和TileEntityExtraMekCentrifugeFactory。
     * 使用ConcurrentHashMap保证线程安全。
     */
    public static final Map<ExtraFactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>>> ME_FACTORIES = new ConcurrentHashMap<>();

    /**
     * EME工厂方块映射 — 由registerEMEFactories()在EME加载时填充
     * <br/>
     * Key=EMExtraFactoryTier（EME独立枚举，编译时可用），Value=对应的DeferredBlock。
     * EME工厂使用EMExtraFactoryMachine BlockType和TileEntityEMExtraMekCentrifugeFactory。
     * 使用ConcurrentHashMap保证线程安全。
     */
    public static final Map<EMExtraFactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>>> EME_FACTORIES = new ConcurrentHashMap<>();

    private ModBlocks() {}

    /**
     * 注册EM等级的工厂方块
     * <br/>
     * 当EvolvedMekanism加载时，遍历5个EM FactoryTier，为每个tier注册一个MekCentrifugeBlock。
     * 注册名格式：{tier.getBaseTier().getLowerName()}_mek_centrifuge_factory
     * （如overclocked_mek_centrifuge_factory），与MekCentrifugeBlockType.getEMFactoryBlock()
     * 中的命名约定保持一致，确保AttributeUpgradeable的DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在BLOCKS.register(eventBus)之前调用，因为DeferredRegister需要所有方块
     * 定义在register之前完成。
     */
    public static void registerEMFactories() {
        if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
            return;
        }
        for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
            String registryName = tier.getBaseTier().getLowerName() + "_mek_centrifuge_factory";
            Machine.FactoryMachine<TileEntityMekCentrifugeFactory> blockType = MekCentrifugeBlockType.getEMFactoryType(tier);
            if (blockType == null) {
                // BlockType未初始化（initEMTiers未调用），跳过并记录警告
                ProductiveBeesGenesis.LOGGER.warn("EM工厂BlockType未初始化，跳过方块注册: {}", tier.name());
                continue;
            }
            // 注册EM工厂方块，使用与原版相同的MekCentrifugeBlock模式
            DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> deferredBlock =
                    BLOCKS.register(registryName, () -> new MekCentrifugeBlock<>(blockType));
            EM_FACTORIES.put(tier, deferredBlock);
        }
    }

    /**
     * 获取EM等级工厂方块
     *
     * @param tier EM工厂等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
     * @return 对应的DeferredBlock，不存在时返回null
     */
    public static DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> getEMFactoryBlock(FactoryTier tier) {
        return EM_FACTORIES.get(tier);
    }

    /**
     * 注册ME等级的工厂方块
     * <br/>
     * 当MekanismExtras加载时，遍历4个ExtraFactoryTier（ABSOLUTE/SUPREME/COSMIC/INFINITE），
     * 为每个tier注册一个MekCentrifugeBlock。
     * 注册名格式：{tier.getAdvanceTier().getLowerName()}_extra_mek_centrifuge_factory
     * （如absolute_extra_mek_centrifuge_factory），与MekCentrifugeBlockType.getMEFactoryBlock()
     * 中的命名约定保持一致，确保ExtraAttributeUpgradeable的DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在initMETiers()之后、BLOCKS.register(eventBus)之前调用。
     */
    public static void registerMEFactories() {
        if (!MekCompatHooks.isMekanismExtrasLoaded()) {
            return;
        }
        for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
            String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory";
            ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> blockType = MekCentrifugeBlockType.getMEFactoryType(tier);
            if (blockType == null) {
                ProductiveBeesGenesis.LOGGER.warn("ME工厂BlockType未初始化，跳过方块注册: {}", tier.name());
                continue;
            }
            DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>> deferredBlock =
                    BLOCKS.register(registryName, () -> new MekCentrifugeBlock<>(blockType));
            ME_FACTORIES.put(tier, deferredBlock);
        }
    }

    /**
     * 获取ME等级工厂方块
     *
     * @param tier ME工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
     * @return 对应的DeferredBlock，不存在时返回null
     */
    public static DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>> getMEFactoryBlock(ExtraFactoryTier tier) {
        return ME_FACTORIES.get(tier);
    }

    /**
     * 注册EME等级的工厂方块
     * <br/>
     * 当EvolvedMekanismExtras加载时，遍历4个EMExtraFactoryTier（ABSOLUTE_OVERCLOCKED/
     * SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL），为每个tier注册一个MekCentrifugeBlock。
     * 注册名格式：{tier.getEMExtraTier().getLowerName()}_emextra_mek_centrifuge_factory
     * （如absolute_overclocked_emextra_mek_centrifuge_factory），与MekCentrifugeBlockType.getEMEFactoryBlock()
     * 中的命名约定保持一致，确保EMExtraAttributeUpgradeable的DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在initEMETiers()之后、BLOCKS.register(eventBus)之前调用。
     */
    public static void registerEMEFactories() {
        if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
            return;
        }
        for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
            String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory";
            EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory> blockType = MekCentrifugeBlockType.getEMEFactoryType(tier);
            if (blockType == null) {
                ProductiveBeesGenesis.LOGGER.warn("EME工厂BlockType未初始化，跳过方块注册: {}", tier.name());
                continue;
            }
            DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>> deferredBlock =
                    BLOCKS.register(registryName, () -> new MekCentrifugeBlock<>(blockType));
            EME_FACTORIES.put(tier, deferredBlock);
        }
    }

    /**
     * 获取EME等级工厂方块
     *
     * @param tier EME工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
     * @return 对应的DeferredBlock，不存在时返回null
     */
    public static DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>> getEMEFactoryBlock(EMExtraFactoryTier tier) {
        return EME_FACTORIES.get(tier);
    }
}
