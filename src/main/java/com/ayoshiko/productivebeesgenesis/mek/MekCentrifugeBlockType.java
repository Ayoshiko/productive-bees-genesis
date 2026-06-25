package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeUpgradeable;
import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeUpgradeable;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraFactoryType;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.block.attribute.AttributeSideConfig;
import mekanism.common.block.attribute.AttributeTier;
import mekanism.common.block.attribute.AttributeUpgradeable;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;

/**
 * MEK离心机BlockType定义
 * <br/>
 * 使用静态初始化，所有BlockType在类加载时创建。
 * TileEntityType引用通过lazy supplier延迟解析，避免循环类加载依赖。
 * <p>
 * 关键设计：工厂版使用without(AttributeUpgradeable.class)移除FactoryMachine构造器
 * 添加的原版AttributeUpgradeable（指向Mekanism原版电力熔炼炉），然后添加自定义的
 * AttributeUpgradeable（非匿名子类，确保getClass()返回AttributeUpgradeable.class），
 * 使ItemTierInstaller能通过Attribute.get(block, AttributeUpgradeable.class)找到正确的升级属性。
 * <p>
 * 升级链优先级：EM优先于ME。AttributeUpgradeable供EM/Mekanism installer使用，
 * 必须指向EM链（OVERCLOCKED）；ME链通过initMETiers()添加的ExtraAttributeUpgradeable实现
 * （ME installer使用ExtraAttributeUpgradeable）。两者共存，互不干扰。
 * <p>
 * EM扩展：当EvolvedMekanism加载时，通过initEMTiers()为5个EM等级（OVERCLOCKED/QUANTUM/
 * DENSE/MULTIVERSAL/CREATIVE）动态创建BlockType，存入EM_FACTORY_TYPES。
 * EM等级的FactoryTier在编译时不存在（EM通过Mixin在运行时扩展枚举），必须反射获取。
 * <p>
 * ME扩展：当MekanismExtras加载时，通过initMETiers()为4个ME等级（ABSOLUTE/SUPREME/
 * COSMIC/INFINITE）动态创建BlockType，存入ME_FACTORY_TYPES。
 * ME等级使用ExtraFactoryMachine基类+ExtraAttributeTier/ExtraAttributeUpgradeable属性，
 * 能量配置遵循ME的ExtraFactory.setMachineData模式（storage=max(origStorage,usage)*processes）。
 * 使用without(ExtraAttributeUpgradeable.class)移除ME Mixin注入的错误升级目标，
 * 然后添加自己的ExtraAttributeUpgradeable指向下一级离心机工厂。
 */
public final class MekCentrifugeBlockType {

    /** 基础MEK离心机BlockType — 不设置AttributeTier，使Basic Tier Installer能正确升级（fromTier=null匹配） */
    public static final BlockTypeTile<TileEntityMekCentrifuge> MEK_CENTRIFUGE = Machine.MachineBuilder
            .createMachine(() -> ModBlockEntitiesHolder.MEK_CENTRIFUGE, lang("mek_centrifuge"))
            .withEnergyConfig(() -> 50L, () -> 20_000L)
            .withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
            .with(Attributes.SECURITY)
            .withGui(() -> ModMenuTypes.MEK_CENTRIFUGE)
            .withSound(MekanismSounds.ENERGIZED_SMELTER)
            .with(new AttributeUpgradeable(wrapAsBlockRegistryObject(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY)))
            .build();

    /** 基础工厂BlockType（3并行） */
    public static final Machine.FactoryMachine<TileEntityMekCentrifugeFactory> BASIC_MEK_CENTRIFUGE_FACTORY =
            createFactoryBlockType(FactoryTier.BASIC, descriptionLang("basic_mek_centrifuge_factory"));

    /** 高级工厂BlockType（5并行） */
    public static final Machine.FactoryMachine<TileEntityMekCentrifugeFactory> ADVANCED_MEK_CENTRIFUGE_FACTORY =
            createFactoryBlockType(FactoryTier.ADVANCED, descriptionLang("advanced_mek_centrifuge_factory"));

    /** 精英工厂BlockType（7并行） */
    public static final Machine.FactoryMachine<TileEntityMekCentrifugeFactory> ELITE_MEK_CENTRIFUGE_FACTORY =
            createFactoryBlockType(FactoryTier.ELITE, descriptionLang("elite_mek_centrifuge_factory"));

    /** 终极工厂BlockType（9并行） */
    public static final Machine.FactoryMachine<TileEntityMekCentrifugeFactory> ULTIMATE_MEK_CENTRIFUGE_FACTORY =
            createFactoryBlockType(FactoryTier.ULTIMATE, descriptionLang("ultimate_mek_centrifuge_factory"));

    /**
     * EM工厂BlockType映射 — 由initEMTiers()在EM加载时填充
     * <br/>
     * Key=FactoryTier（EM运行时扩展的枚举值），Value=对应的FactoryMachine BlockType。
     * 使用ConcurrentHashMap保证线程安全（initEMTiers可能与BlockType查询并发）。
     */
    private static final Map<FactoryTier, Machine.FactoryMachine<TileEntityMekCentrifugeFactory>> EM_FACTORY_TYPES =
            new ConcurrentHashMap<>();

    /**
     * ME工厂BlockType映射 — 由initMETiers()在ME加载时填充
     * <br/>
     * Key=ExtraFactoryTier（ME的独立枚举），Value=对应的ExtraFactoryMachine BlockType。
     * 使用ConcurrentHashMap保证线程安全。
     */
    private static final Map<ExtraFactoryTier, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>> ME_FACTORY_TYPES =
            new ConcurrentHashMap<>();

    /**
     * EME工厂BlockType映射 — 由initEMETiers()在EME加载时填充
     * <br/>
     * Key=EMExtraFactoryTier（EME的独立枚举），Value=对应的EMExtraFactoryMachine BlockType。
     * 使用ConcurrentHashMap保证线程安全。
     */
    private static final Map<EMExtraFactoryTier, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>> EME_FACTORY_TYPES =
            new ConcurrentHashMap<>();

    private MekCentrifugeBlockType() {}

    /**
     * 创建工厂BlockType — 替换原版AttributeUpgradeable
     * <br/>
     * FactoryMachine构造器添加的AttributeUpgradeable指向Mekanism原版电力熔炼炉，
     * 需要替换为指向我们的离心机工厂。
     * 关键：使用without+with配合，且with传入非匿名AttributeUpgradeable实例
     * （确保attr.getClass()返回AttributeUpgradeable.class，使ItemTierInstaller能找到）。
     */
    @SuppressWarnings("unchecked")
    private static Machine.FactoryMachine<TileEntityMekCentrifugeFactory> createFactoryBlockType(
            FactoryTier tier, mekanism.api.text.ILangEntry description) {
        var builder = Machine.MachineBuilder
                .createFactoryMachine(() -> getFactoryTileEntityType(tier), description, FactoryType.SMELTING)
                // Energy: 与Mekanism原版工厂一致，usage=50L/tick，storage=20000L（不随等级变化）
                // 原版Mekanism工厂所有等级（BASIC/ADVANCED/ELITE/ULTIMATE）使用相同的20000L存储
                // EM等级也遵循此规则（EM的FactoryMixin只调整energySlot位置，不修改容量）
                // ME/EME等级才乘以processes（遵循ME的ExtraFactory.setMachineData模式）
                .withEnergyConfig(() -> 50L, () -> 20_000L)
                .withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
                .with(Attributes.SECURITY)
                .withGui(() -> ModMenuTypes.MEK_CENTRIFUGE_FACTORY)
                .withSound(MekanismSounds.ENERGIZED_SMELTER)
                .with(new AttributeTier<>(tier));

        // 移除FactoryMachine构造器添加的原版AttributeUpgradeable
        builder.without(AttributeUpgradeable.class);
        // 添加自定义AttributeUpgradeable，指向下一等级的离心机工厂
        // 使用非匿名实例确保getClass()=AttributeUpgradeable.class
        builder.with(new AttributeUpgradeable(wrapAsBlockRegistryObject(getNextTierBlock(tier))));

        return builder.build();
    }

    /**
     * 获取下一等级工厂的DeferredBlock
     * <br/>
     * 原版4等级走固定映射；EM加载时ULTIMATE指向OVERCLOCKED（EM优先），
     * 仅ME加载时ULTIMATE指向ABSOLUTE；EM等级走getEMNextTierBlock。
     * 必须有default分支：EM通过Mixin在运行时扩展FactoryTier枚举。
     */
    private static DeferredHolder<Block, ?> getNextTierBlock(FactoryTier currentTier) {
        return switch (currentTier) {
            case BASIC -> ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY;
            case ADVANCED -> ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY;
            case ELITE -> ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY;
            // EM优先于ME：AttributeUpgradeable供EM/Mekanism installer使用，必须指向EM链的OVERCLOCKED；
            // ME链通过initMETiers()添加的ExtraAttributeUpgradeable实现（ME installer使用ExtraAttributeUpgradeable）
            case ULTIMATE -> MekCompatHooks.isEvolvedMekanismLoaded()
                    ? getEMFactoryBlock("overclocked")
                    : MekCompatHooks.isMekanismExtrasLoaded()
                    ? getMEFactoryBlock("absolute")
                    : ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY;
            // EM运行时扩展的等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
            default -> getEMNextTierBlock(currentTier);
        };
    }

    /**
     * 获取EM等级的下一级方块Holder
     * <br/>
     * EM等级在编译时不存在，通过name()字符串匹配确定当前等级，返回下一级的DeferredHolder。
     * CREATIVE是最高级，返回自身（避免null导致AttributeUpgradeable构造失败）。
     */
    private static DeferredHolder<Block, ?> getEMNextTierBlock(FactoryTier currentTier) {
        String name = currentTier.name();
        return switch (name) {
            case "OVERCLOCKED" -> getEMFactoryBlock("quantum");
            case "QUANTUM" -> getEMFactoryBlock("dense");
            case "DENSE" -> getEMFactoryBlock("multiversal");
            case "MULTIVERSAL" -> getEMFactoryBlock("creative");
            // CREATIVE是最高级，返回自身
            default -> getEMFactoryBlock("creative");
        };
    }

    /**
     * 通过注册名创建EM工厂方块的DeferredHolder
     * <br/>
     * EM工厂方块由ModBlocks在后续任务中注册，此处通过DeferredHolder.create按注册名创建延迟Holder。
     * Holder是懒解析的，方块注册后自动解析；注册名遵循 {tier}_mek_centrifuge_factory 命名约定。
     */
    private static DeferredHolder<Block, ?> getEMFactoryBlock(String tierName) {
        String registryName = tierName + "_mek_centrifuge_factory";
        return DeferredHolder.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
    }

    /**
     * 通过注册名创建ME工厂方块的DeferredHolder
     * <br/>
     * ME工厂方块由ModBlocks注册，此处通过DeferredHolder.create按注册名创建延迟Holder。
     * 注册名遵循 {tier}_extra_mek_centrifuge_factory 命名约定（使用ME的tier小写名）。
     */
    private static DeferredHolder<Block, ?> getMEFactoryBlock(String tierName) {
        String registryName = tierName + "_extra_mek_centrifuge_factory";
        return DeferredHolder.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
    }

    /**
     * 通过注册名创建EME工厂方块的DeferredHolder
     * <br/>
     * EME工厂方块由ModBlocks注册，此处通过DeferredHolder.create按注册名创建延迟Holder。
     * 注册名遵循 {tier}_emextra_mek_centrifuge_factory 命名约定（使用EME的tier小写名）。
     */
    private static DeferredHolder<Block, ?> getEMEFactoryBlock(String tierName) {
        String registryName = tierName + "_emextra_mek_centrifuge_factory";
        return DeferredHolder.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
    }

    /**
     * 将DeferredBlock包装为BlockRegistryObject
     * <br/>
     * AttributeUpgradeable构造器需要Supplier&lt;BlockRegistryObject&lt;?, ?&gt;&gt;，
     * 而我们的方块是DeferredBlock。通过创建BlockRegistryObject包装器解决类型不匹配。
     * BlockRegistryObject需要block和item两个DeferredHolder，item部分通过block的注册名查找。
     */
    @SuppressWarnings("unchecked")
    private static Supplier<BlockRegistryObject<?, ?>> wrapAsBlockRegistryObject(DeferredHolder<Block, ?> blockHolder) {
        return () -> {
            // 通过block的注册名创建item的DeferredHolder
            DeferredHolder<Item, ?> itemHolder = DeferredHolder.create(
                    net.minecraft.core.registries.Registries.ITEM, blockHolder.getKey().location());
            return new BlockRegistryObject<>((DeferredHolder<Block, Block>) blockHolder,
                    (DeferredHolder<Item, Item>) itemHolder);
        };
    }

    /**
     * 根据工厂等级获取对应的BlockEntityType
     * <br/>
     * 原版4等级走固定映射；EM等级从ModBlockEntitiesHolder.EM_FACTORY_TILES获取。
     * 必须有default分支：EM运行时扩展的枚举值会落入default。
     */
    private static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> getFactoryTileEntityType(FactoryTier tier) {
        return switch (tier) {
            case BASIC -> ModBlockEntitiesHolder.BASIC_MEK_CENTRIFUGE_FACTORY;
            case ADVANCED -> ModBlockEntitiesHolder.ADVANCED_MEK_CENTRIFUGE_FACTORY;
            case ELITE -> ModBlockEntitiesHolder.ELITE_MEK_CENTRIFUGE_FACTORY;
            case ULTIMATE -> ModBlockEntitiesHolder.ULTIMATE_MEK_CENTRIFUGE_FACTORY;
            // EM等级从EM_FACTORY_TILES映射获取（由ModBlockEntities在EM加载时填充）
            default -> ModBlockEntitiesHolder.EM_FACTORY_TILES.get(tier);
        };
    }

    private static mekanism.api.text.ILangEntry lang(String key) {
        return () -> "block.productivebeesgenesis." + key;
    }

    /**
     * 创建离心机工厂描述ILangEntry
     * <br/>
     * 替代MekanismLang.DESCRIPTION_FACTORY（通用"Factory"描述），
     * 使Shift+N显示离心机工厂专属描述文本。
     * key格式：description.productivebeesgenesis.{key}
     */
    private static mekanism.api.text.ILangEntry descriptionLang(String key) {
        return () -> "description.productivebeesgenesis." + key;
    }

    /**
     * 初始化EM工厂等级的BlockType
     * <br/>
     * 当EM加载时，为每个EM FactoryTier（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
     * 创建BlockType并存入EM_FACTORY_TYPES。使用computeIfAbsent保证线程安全的单次创建。
     * 复用createFactoryBlockType，确保EM等级也走without+with路径，覆盖EM FactoryMixin
     * 注入的错误升级目标。
     * <p>
     * 调用时机：EM加载且EM工厂方块/TileEntity注册完成后调用（由后续任务在ModBlocks/
     * ModBlockEntities初始化后触发）。
     */
    public static void initEMTiers() {
        if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
            return;
        }
        List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
        if (emTiers.isEmpty()) {
            // 反射获取失败（EM类路径变更或字段缺失），已由MekCompatHooks记录error日志
            return;
        }
        for (FactoryTier tier : emTiers) {
            // 使用离心机专属description key替代通用DESCRIPTION_FACTORY
            // key格式：description.productivebeesgenesis.{tier小写}_mek_centrifuge_factory
            EM_FACTORY_TYPES.computeIfAbsent(tier,
                    t -> createFactoryBlockType(t, descriptionLang(t.name().toLowerCase() + "_mek_centrifuge_factory")));
        }
    }

    /**
     * 初始化ME工厂等级的BlockType
     * <br/>
     * 当ME加载时，为每个ExtraFactoryTier（ABSOLUTE/SUPREME/COSMIC/INFINITE）
     * 创建BlockType并存入ME_FACTORY_TYPES。使用computeIfAbsent保证线程安全的单次创建。
     * <p>
     * ME等级使用ExtraFactoryMachine基类，而非原版的FactoryMachine，因为：
     * 1. TileEntityExtraMekCentrifugeFactory继承自ME的TileEntityExtraFactory
     * 2. ME的升级系统使用ExtraAttributeUpgradeable（而非Mekanism的AttributeUpgradeable）
     * 3. ME的等级系统使用ExtraAttributeTier（而非Mekanism的AttributeTier）
     * <p>
     * 能量配置遵循ME的ExtraFactory.setMachineData模式：
     * storage = max(origStorage, origUsage) * tier.processes
     * 原版离心机：usage=50L, storage=20000L，所以storage = max(20000, 50) * processes
     * <p>
     * 调用时机：ME加载且ME工厂方块/TileEntity注册完成后调用。
     */
    public static void initMETiers() {
        if (!MekCompatHooks.isMekanismExtrasLoaded()) {
            return;
        }
        for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
            ME_FACTORY_TYPES.computeIfAbsent(tier, MekCentrifugeBlockType::createMEFactoryBlockType);
        }
        // 为ULTIMATE离心机工厂添加ExtraAttributeUpgradeable，使其能通过ME的ABSOLUTE Tier Installer升级
        // ULTIMATE离心机原本只有AttributeUpgradeable（Mekanism升级系统），需要额外添加ExtraAttributeUpgradeable
        // 指向ABSOLUTE离心机工厂，使ME的ItemExtraTierInstaller能识别并执行升级
        ULTIMATE_MEK_CENTRIFUGE_FACTORY.add(new ExtraAttributeUpgradeable(
                wrapAsBlockRegistryObject(getMEFactoryBlock("absolute"))));
    }

    /**
     * 初始化EME工厂等级的BlockType
     * <br/>
     * 当EME加载时，为每个EMExtraFactoryTier（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
     * 创建BlockType并存入EME_FACTORY_TYPES。使用computeIfAbsent保证线程安全的单次创建。
     * <p>
     * EME等级使用EMExtraFactoryMachine基类，手动添加所有属性（能量、侧面配置、安全、GUI、声音、tier、upgradeable）。
     * 不使用EME的EMExtraFactory，因为EMExtraFactory需要origMachine参数（EMExtraFactoryMachine类型），
     * 而我们的离心机原版机器是FactoryMachine/ExtraFactoryMachine类型，不兼容。
     * <p>
     * 能量配置遵循EME的EMExtraFactory.setMachineData模式：
     * storage = max(origStorage, origUsage) * tier.processes
     * 原版离心机：usage=50L, storage=20000L，所以storage = max(20000, 50) * processes
     * <p>
     * 升级链处理：
     * 1. ULTIMATE离心机工厂：移除EME MixinFactory注入的EMExtraAttributeUpgradeable（指向EME原版ABSOLUTE_OVERCLOCKED电力熔炼炉工厂），
     *    替换为指向我们的ABSOLUTE_OVERCLOCKED离心机工厂
     * 2. ME ABSOLUTE离心机工厂：添加EMExtraAttributeUpgradeable指向ABSOLUTE_OVERCLOCKED离心机工厂，
     *    使ME ABSOLUTE离心机可以升级到EME ABSOLUTE_OVERCLOCKED离心机
     * <p>
     * 调用时机：EME加载且EME工厂方块/TileEntity注册完成后调用。
     */
    public static void initEMETiers() {
        if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
            return;
        }
        for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
            EME_FACTORY_TYPES.computeIfAbsent(tier, MekCentrifugeBlockType::createEMEFactoryBlockType);
        }

        // 为ULTIMATE离心机工厂替换EME MixinFactory注入的EMExtraAttributeUpgradeable
        // EME的MixinFactory为ULTIMATE工厂注入EMExtraAttributeUpgradeable指向EME原版ABSOLUTE_OVERCLOCKED电力熔炼炉工厂
        // 移除并替换为指向我们的ABSOLUTE_OVERCLOCKED离心机工厂
        ULTIMATE_MEK_CENTRIFUGE_FACTORY.remove(EMExtraAttributeUpgradeable.class);
        ULTIMATE_MEK_CENTRIFUGE_FACTORY.add(new EMExtraAttributeUpgradeable(
                wrapAsBlockRegistryObject(getEMEFactoryBlock("absolute_overclocked"))));

        // 为ME ABSOLUTE离心机工厂添加EMExtraAttributeUpgradeable
        // 使ME ABSOLUTE离心机可以升级到EME ABSOLUTE_OVERCLOCKED离心机（跨升级系统升级）
        ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> absoluteType =
                ME_FACTORY_TYPES.get(ExtraFactoryTier.ABSOLUTE);
        if (absoluteType != null) {
            absoluteType.add(new EMExtraAttributeUpgradeable(
                    wrapAsBlockRegistryObject(getEMEFactoryBlock("absolute_overclocked"))));
        }
    }

    /**
     * 创建EME等级的工厂BlockType
     * <br/>
     * 使用EMExtraFactoryMachine基类，手动添加所有属性。
     * 不使用EME的EMExtraFactory，因为EMExtraFactory需要origMachine参数（EMExtraFactoryMachine类型），
     * 而我们的离心机原版机器不兼容。
     * <p>
     * 关键：使用without(EMExtraAttributeUpgradeable.class)移除EME Mixin可能注入的错误升级属性
     * （指向EME原版电力熔炼炉工厂），然后添加自己的EMExtraAttributeUpgradeable指向离心机工厂。
     * <p>
     * 配置卡兼容性：额外添加AttributeFactoryType(SMELTING)，使EME工厂方块同时拥有
     * AttributeFactoryType和EMExtraAttributeFactoryType两种属性。
     * MekanismUtils.isSameTypeFactory()只检查AttributeFactoryType，若EME方块缺少此属性，
     * 配置卡无法在EME工厂与原版/EM/ME工厂之间互相粘贴。添加后三个TileEntity的
     * isConfigurationDataCompatible()能正确识别EME方块并允许跨等级粘贴配置。
     */
    @SuppressWarnings("unchecked")
    private static EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory> createEMEFactoryBlockType(
            EMExtraFactoryTier tier) {
        // 能量配置：EME模式，storage = max(origStorage, origUsage) * tier.processes
        long usage = 50L;
        long storage = Math.max(20_000L, usage) * tier.processes;

        // 使用离心机专属description key替代通用DESCRIPTION_FACTORY
        // key格式：description.productivebeesgenesis.{tier小写}_emextra_mek_centrifuge_factory
        var builder = EMExtraMachine.EMExtraMachineBuilder
                .createEMExtraFactoryMachine(() -> ModBlockEntitiesHolder.EME_FACTORY_TILES.get(tier),
                        descriptionLang(tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory"), EMExtraFactoryType.SMELTING)
                .withEnergyConfig(() -> usage, () -> storage)
                .withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
                .with(Attributes.SECURITY)
                .withGui(() -> ModMenuTypes.EMEXTRA_MEK_CENTRIFUGE_FACTORY)
                .withSound(MekanismSounds.ENERGIZED_SMELTER)
                // 添加原版AttributeFactoryType(SMELTING)，使EME方块能被MekanismUtils.isSameTypeFactory()
                // 识别，从而支持配置卡在EME工厂与原版/EM/ME工厂之间跨等级粘贴
                .with(new AttributeFactoryType(FactoryType.SMELTING))
                .with(new EMExtraAttributeTier<>(tier));

        // 移除EME Mixin可能注入的EMExtraAttributeUpgradeable（安全措施）
        builder.without(EMExtraAttributeUpgradeable.class);

        // 添加自定义EMExtraAttributeUpgradeable，指向下一级离心机工厂
        EMExtraFactoryTier[] tiers = EMExtraFactoryTier.values();
        if (tier.ordinal() < tiers.length - 1) {
            EMExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
            builder.with(new EMExtraAttributeUpgradeable(wrapAsBlockRegistryObject(getEMEFactoryBlock(
                    nextTier.getEMExtraTier().getLowerName()))));
        }
        // INFINITE_MULTIVERSAL是最高级，不添加升级属性

        return builder.build();
    }

    /**
     * 创建ME等级的工厂BlockType
     * <br/>
     * 使用ExtraFactoryMachine基类，手动添加所有属性（能量、侧面配置、安全、GUI、声音、tier、upgradeable）。
     * 不使用ME的ExtraFactory，因为ExtraFactory需要origMachine参数（ExtraFactoryMachine类型），
     * 而我们的离心机原版机器是FactoryMachine类型，不兼容。
     * <p>
     * 关键：使用without(ExtraAttributeUpgradeable.class)移除ME Mixin注入的错误升级属性
     * （指向ME原版电力熔炼炉工厂），然后添加自己的ExtraAttributeUpgradeable指向离心机工厂。
     */
    @SuppressWarnings("unchecked")
    private static ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> createMEFactoryBlockType(
            ExtraFactoryTier tier) {
        // 能量配置：ME模式，storage = max(origStorage, origUsage) * tier.processes
        long usage = 50L;
        long storage = Math.max(20_000L, usage) * tier.processes;

        // 使用离心机专属description key替代通用DESCRIPTION_FACTORY
        // key格式：description.productivebeesgenesis.{tier小写}_extra_mek_centrifuge_factory
        var builder = ExtraMachine.ExtraMachineBuilder
                .createExtraFactoryMachine(() -> ModBlockEntitiesHolder.ME_FACTORY_TILES.get(tier),
                        descriptionLang(tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory"), FactoryType.SMELTING)
                .withEnergyConfig(() -> usage, () -> storage)
                .withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
                .with(Attributes.SECURITY)
                .withGui(() -> ModMenuTypes.EXTRA_MEK_CENTRIFUGE_FACTORY)
                .withSound(MekanismSounds.ENERGIZED_SMELTER)
                .with(new ExtraAttributeTier<>(tier));

        // 移除ME Mixin注入的ExtraAttributeUpgradeable（指向ME原版工厂，不是离心机）
        builder.without(ExtraAttributeUpgradeable.class);

        // 添加自定义ExtraAttributeUpgradeable，指向下一级离心机工厂
        ExtraFactoryTier[] tiers = ExtraFactoryTier.values();
        if (tier.ordinal() < tiers.length - 1) {
            ExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
            builder.with(new ExtraAttributeUpgradeable(wrapAsBlockRegistryObject(getMEFactoryBlock(
                    nextTier.getAdvanceTier().getLowerName()))));
        }

        return builder.build();
    }

    /**
     * 获取ME等级的工厂BlockType
     *
     * @param tier ME工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
     * @return 对应的ExtraFactoryMachine BlockType，不存在时返回null
     */
    public static ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> getMEFactoryType(ExtraFactoryTier tier) {
        return ME_FACTORY_TYPES.get(tier);
    }

    /**
     * 获取EME等级的工厂BlockType
     *
     * @param tier EME工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
     * @return 对应的EMExtraFactoryMachine BlockType，不存在时返回null
     */
    public static EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory> getEMEFactoryType(EMExtraFactoryTier tier) {
        return EME_FACTORY_TYPES.get(tier);
    }

    /**
     * 获取EM等级的工厂BlockType
     * <br/>
     * 供ModBlocks注册EM工厂方块时使用，通过BlockType引用构建MekCentrifugeBlock。
     * EM未加载或未初始化时返回null。
     *
     * @param tier EM工厂等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
     * @return 对应的FactoryMachine BlockType，不存在时返回null
     */
    public static Machine.FactoryMachine<TileEntityMekCentrifugeFactory> getEMFactoryType(FactoryTier tier) {
        return EM_FACTORY_TYPES.get(tier);
    }

    /** TileEntityType持有者 — 由ModBlockEntities静态初始化时设置，每个等级独立 */
    public static class ModBlockEntitiesHolder {
        public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifuge> MEK_CENTRIFUGE;
        public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> BASIC_MEK_CENTRIFUGE_FACTORY;
        public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ADVANCED_MEK_CENTRIFUGE_FACTORY;
        public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ELITE_MEK_CENTRIFUGE_FACTORY;
        public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory> ULTIMATE_MEK_CENTRIFUGE_FACTORY;

        /**
         * EM工厂TileEntityType映射 — 由ModBlockEntities在EM加载时填充
         * <br/>
         * Key=FactoryTier（EM运行时扩展的枚举值），Value=对应的TileEntityTypeRegistryObject。
         * 使用ConcurrentHashMap保证线程安全（填充与BlockType查询可能并发）。
         */
        public static final Map<FactoryTier, mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory>> EM_FACTORY_TILES =
                new ConcurrentHashMap<>();

        /**
         * ME工厂TileEntityType映射 — 由ModBlockEntities在ME加载时填充
         * <br/>
         * Key=ExtraFactoryTier（ME的独立枚举），Value=对应的TileEntityTypeRegistryObject。
         * 使用ConcurrentHashMap保证线程安全。
         */
        public static final Map<ExtraFactoryTier, mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityExtraMekCentrifugeFactory>> ME_FACTORY_TILES =
                new ConcurrentHashMap<>();

        /**
         * EME工厂TileEntityType映射 — 由ModBlockEntities在EME加载时填充
         * <br/>
         * Key=EMExtraFactoryTier（EME的独立枚举），Value=对应的TileEntityTypeRegistryObject。
         * 使用ConcurrentHashMap保证线程安全。
         */
        public static final Map<EMExtraFactoryTier, mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityEMExtraMekCentrifugeFactory>> EME_FACTORY_TILES =
                new ConcurrentHashMap<>();
    }
}
