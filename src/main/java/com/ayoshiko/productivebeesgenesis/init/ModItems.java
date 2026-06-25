package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.item.ItemInfinitySwordReplica;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

import cy.jdkdigital.productivebees.init.ModDataComponents;

import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.api.security.SecurityMode;
import mekanism.common.attachments.component.AttachedEjector;
import mekanism.common.attachments.component.AttachedSideConfig;
import mekanism.common.attachments.component.AttachedSideConfig.LightConfigInfo;
import mekanism.common.attachments.component.UpgradeAware;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.block.attribute.Attributes.AttributeRedstone;
import mekanism.common.block.attribute.Attributes.AttributeSecurity;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.tier.FactoryTier;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品注册类
 * <br/>
 * 注册5个MEK离心机BlockItem，添加MekanismDataComponents实现数据持久化。
 * DataComponents包括：EJECTOR（弹出器）、SIDE_CONFIG（侧面配置）、
 * SECURITY（安全模式）、REDSTONE_CONTROL（红石控制）、UPGRADES（升级）。
 * <p>
 * EM扩展：当EvolvedMekanism加载时，通过registerEMFactoryItems()动态注册5个EM等级
 * 的BlockItem，存入EM_FACTORY_ITEMS Map。注册名与对应方块一致，确保
 * MekCentrifugeBlockType.wrapAsBlockRegistryObject()中的Item DeferredHolder能正确解析。
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ProductiveBeesGenesis.MOD_ID);

    /** MEK离心机默认侧面配置：物品标准机器、流体右侧输出并自动弹出、能量仅输入 */
    public static final AttachedSideConfig MEK_CENTRIFUGE_SIDE_CONFIG = Util.make(() -> {
        Map<TransmissionType, LightConfigInfo> configInfo = new EnumMap<>(TransmissionType.class);
        configInfo.put(TransmissionType.ITEM, LightConfigInfo.MACHINE);
        configInfo.put(TransmissionType.FLUID, LightConfigInfo.RIGHT_OUTPUT);
        configInfo.put(TransmissionType.ENERGY, LightConfigInfo.INPUT_ONLY);
        return new AttachedSideConfig(configInfo);
    });

    /** 基础MEK离心机BlockItem */
    public static final DeferredItem<ItemBlockMekCentrifuge> MEK_CENTRIFUGE =
            ITEMS.register("mek_centrifuge", () -> new ItemBlockMekCentrifuge(ModBlocks.MEK_CENTRIFUGE.get(), machineItemProperties(ModBlocks.MEK_CENTRIFUGE.get())));

    /** 基础工厂BlockItem */
    public static final DeferredItem<ItemBlockMekCentrifuge> BASIC_MEK_CENTRIFUGE_FACTORY =
            ITEMS.register("basic_mek_centrifuge_factory", () -> new ItemBlockMekCentrifuge(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get(), machineItemProperties(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get())));

    /** 高级工厂BlockItem */
    public static final DeferredItem<ItemBlockMekCentrifuge> ADVANCED_MEK_CENTRIFUGE_FACTORY =
            ITEMS.register("advanced_mek_centrifuge_factory", () -> new ItemBlockMekCentrifuge(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY.get(), machineItemProperties(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY.get())));

    /** 精英工厂BlockItem */
    public static final DeferredItem<ItemBlockMekCentrifuge> ELITE_MEK_CENTRIFUGE_FACTORY =
            ITEMS.register("elite_mek_centrifuge_factory", () -> new ItemBlockMekCentrifuge(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY.get(), machineItemProperties(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY.get())));

    /** 终极工厂BlockItem */
    public static final DeferredItem<ItemBlockMekCentrifuge> ULTIMATE_MEK_CENTRIFUGE_FACTORY =
            ITEMS.register("ultimate_mek_centrifuge_factory", () -> new ItemBlockMekCentrifuge(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get(), machineItemProperties(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get())));

    /** 无尽·创世蜜脾 — 自定义蜜脾物品，预置 bee_type 数据组件 */
    public static final DeferredItem<Item> INFINITY_CREATION_COMB =
            ITEMS.register("infinitycreation_comb", () -> new Item(new Item.Properties()
                    .component(ModDataComponents.BEE_TYPE.get(),
                            ResourceLocation.fromNamespaceAndPath("productivebees", "infinitycreation"))));

    /** 无尽·创世蜜脾块 BlockItem */
    public static final DeferredItem<BlockItem> INFINITY_CREATION_COMB_BLOCK_ITEM =
            ITEMS.register("infinitycreation_comb_block", () -> new BlockItem(ModBlocks.INFINITY_CREATION_COMB_BLOCK.get(), new Item.Properties()));

    /** 寰宇支配之剑（验证）— 1:1 复刻原版剑渲染流程，用于隔离验证 cosmic 渲染管线 */
    public static final DeferredItem<Item> INFINITY_SWORD_REPLICA =
            ITEMS.register("infinity_sword_replica", () -> new ItemInfinitySwordReplica(new Item.Properties()));

    /**
     * EM工厂BlockItem映射 — 由registerEMFactoryItems()在EM加载时填充
     * <br/>
     * Key=FactoryTier（EM运行时扩展的枚举值），Value=对应的DeferredItem。
     * 使用ConcurrentHashMap保证线程安全。
     */
    public static final Map<FactoryTier, DeferredItem<ItemBlockMekCentrifuge>> EM_FACTORY_ITEMS = new ConcurrentHashMap<>();

    /**
     * ME工厂BlockItem映射 — 由registerMEFactoryItems()在ME加载时填充
     * <br/>
     * Key=ExtraFactoryTier（ME独立枚举，编译时可用），Value=对应的DeferredItem。
     * 使用ConcurrentHashMap保证线程安全。
     */
    public static final Map<ExtraFactoryTier, DeferredItem<ItemBlockMekCentrifuge>> ME_FACTORY_ITEMS = new ConcurrentHashMap<>();

    /**
     * EME工厂BlockItem映射 — 由registerEMEFactoryItems()在EME加载时填充
     * <br/>
     * Key=EMExtraFactoryTier（EME独立枚举，编译时可用），Value=对应的DeferredItem。
     * 使用ConcurrentHashMap保证线程安全。
     */
    public static final Map<EMExtraFactoryTier, DeferredItem<ItemBlockMekCentrifuge>> EME_FACTORY_ITEMS = new ConcurrentHashMap<>();

    private ModItems() {}

    /**
     * 注册EM等级的工厂BlockItem
     * <br/>
     * 当EvolvedMekanism加载时，遍历EM_FACTORIES中的方块，为每个方块注册同名的BlockItem。
     * 注册名与方块一致（如overclocked_mek_centrifuge_factory），确保MekCentrifugeBlockType
     * 中wrapAsBlockRegistryObject()创建的Item DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在ModBlocks.registerEMFactories()之后、ITEMS.register(eventBus)之前调用。
     */
    public static void registerEMFactoryItems() {
        if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
            return;
        }
        for (Map.Entry<FactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>>> entry : ModBlocks.EM_FACTORIES.entrySet()) {
            FactoryTier tier = entry.getKey();
            DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> deferredBlock = entry.getValue();
            String registryName = tier.getBaseTier().getLowerName() + "_mek_centrifuge_factory";
            // 注册BlockItem，使用与原版相同的machineItemProperties添加Mekanism DataComponents
            DeferredItem<ItemBlockMekCentrifuge> deferredItem = ITEMS.register(registryName,
                    () -> new ItemBlockMekCentrifuge(deferredBlock.get(), machineItemProperties(deferredBlock.get())));
            EM_FACTORY_ITEMS.put(tier, deferredItem);
        }
    }

    /**
     * 注册ME等级的工厂BlockItem
     * <br/>
     * 当MekanismExtras加载时，遍历ME_FACTORIES中的方块，为每个方块注册同名的BlockItem。
     * 注册名与方块一致（如absolute_extra_mek_centrifuge_factory），确保MekCentrifugeBlockType
     * 中wrapAsBlockRegistryObject()创建的Item DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在ModBlocks.registerMEFactories()之后、ITEMS.register(eventBus)之前调用。
     */
    public static void registerMEFactoryItems() {
        if (!MekCompatHooks.isMekanismExtrasLoaded()) {
            return;
        }
        for (Map.Entry<ExtraFactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>>> entry : ModBlocks.ME_FACTORIES.entrySet()) {
            ExtraFactoryTier tier = entry.getKey();
            DeferredBlock<MekCentrifugeBlock<TileEntityExtraMekCentrifugeFactory, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>>> deferredBlock = entry.getValue();
            String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory";
            DeferredItem<ItemBlockMekCentrifuge> deferredItem = ITEMS.register(registryName,
                    () -> new ItemBlockMekCentrifuge(deferredBlock.get(), machineItemProperties(deferredBlock.get())));
            ME_FACTORY_ITEMS.put(tier, deferredItem);
        }
    }

    /**
     * 注册EME等级的工厂BlockItem
     * <br/>
     * 当EvolvedMekanismExtras加载时，遍历EME_FACTORIES中的方块，为每个方块注册同名的BlockItem。
     * 注册名与方块一致（如absolute_overclocked_emextra_mek_centrifuge_factory），确保MekCentrifugeBlockType
     * 中wrapAsBlockRegistryObject()创建的Item DeferredHolder能正确解析。
     * <p>
     * 调用时机：必须在ModBlocks.registerEMEFactories()之后、ITEMS.register(eventBus)之前调用。
     */
    public static void registerEMEFactoryItems() {
        if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
            return;
        }
        for (Map.Entry<EMExtraFactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>>> entry : ModBlocks.EME_FACTORIES.entrySet()) {
            EMExtraFactoryTier tier = entry.getKey();
            DeferredBlock<MekCentrifugeBlock<TileEntityEMExtraMekCentrifugeFactory, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>>> deferredBlock = entry.getValue();
            String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory";
            DeferredItem<ItemBlockMekCentrifuge> deferredItem = ITEMS.register(registryName,
                    () -> new ItemBlockMekCentrifuge(deferredBlock.get(), machineItemProperties(deferredBlock.get())));
            EME_FACTORY_ITEMS.put(tier, deferredItem);
        }
    }

    /**
     * 创建机器BlockItem属性，添加MekanismDataComponents
     * <br/>
     * 参考Mek-Energistics的MeBlockDeferredRegister.machineProperties()和blockItemProperties()。
     * EJECTOR和SIDE_CONFIG在machineProperties中添加，SECURITY/REDSTONE/UPGRADES在blockItemProperties中添加。
     */
    private static Item.Properties machineItemProperties(net.minecraft.world.level.block.Block block) {
        Item.Properties props = new Item.Properties();
        // 机器属性：弹出器 + 侧面配置（使用离心机专用配置，包含流体右侧输出）
        props.component(MekanismDataComponents.EJECTOR, AttachedEjector.DEFAULT);
        props.component(MekanismDataComponents.SIDE_CONFIG, MEK_CENTRIFUGE_SIDE_CONFIG);
        // 条件属性：安全模式 + 红石控制 + 升级
        if (Attribute.has(block, AttributeSecurity.class)) {
            props.component(MekanismDataComponents.SECURITY, SecurityMode.PUBLIC);
        }
        if (Attribute.has(block, AttributeRedstone.class)) {
            props.component(MekanismDataComponents.REDSTONE_CONTROL, RedstoneControl.DISABLED);
        }
        if (Attribute.has(block, AttributeUpgradeSupport.class)) {
            props.component(MekanismDataComponents.UPGRADES, UpgradeAware.EMPTY);
        }
        return props;
    }
}
