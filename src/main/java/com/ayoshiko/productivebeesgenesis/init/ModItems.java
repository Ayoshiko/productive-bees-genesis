package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader;
import com.ayoshiko.productivebeesgenesis.item.ItemInfinityCreationComb;
import com.ayoshiko.productivebeesgenesis.item.ItemInfinityCreationCombBlock;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import mekanism.api.RelativeSide;
import mekanism.api.security.SecurityMode;
import mekanism.common.attachments.component.AttachedEjector;
import mekanism.common.attachments.component.AttachedSideConfig.LightConfigInfo;
import mekanism.common.attachments.component.AttachedSideConfig;
import mekanism.common.attachments.component.UpgradeAware;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.block.attribute.Attributes.AttributeRedstone;
import mekanism.common.block.attribute.Attributes.AttributeSecurity;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.Util;
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
	 * <p>
	 * EME扩展：当EvolvedMekanismExtras加载时，委托 {@link EMECompatLoader} 完成 EME 工厂 BlockItem 注册，
	 * 结果存入 EME_FACTORY_ITEMS 和 EME_APIARY_FACTORY_ITEMS（通配类型，避免编译期依赖 EME 类）。
	 */
public final class ModItems {

	public static final DeferredRegister.Items ITEMS =
			DeferredRegister.createItems(ProductiveBeesGenesis.MOD_ID);

	/**
	 * MEK离心机默认侧面配置：物品标准机器、流体右侧输出（默认不自动弹出）、能量仅输入
	 * <br/>
	 * 修复：流体弹出默认关闭（isEjecting=false），避免离心机产出的蜂蜜流体被自动弹出
	 * 导致用户误以为"不产出"。用户可在 GUI 侧面配置中手动开启自动弹出。
	 * 使用 new LightConfigInfo(Map.of(RIGHT, OUTPUT), false) 创建与 RIGHT_OUTPUT 相同的
	 * sideConfig 但 isEjecting=false 的实例。
	 */
	public static final AttachedSideConfig MEK_CENTRIFUGE_SIDE_CONFIG = Util.make(() -> {
		Map<TransmissionType, LightConfigInfo> configInfo = new EnumMap<>(TransmissionType.class);
		configInfo.put(TransmissionType.ITEM, LightConfigInfo.MACHINE);
		// 流体右侧输出但不自动弹出 — 修复：原 RIGHT_OUTPUT 的 isEjecting=true 导致流体被弹出
		configInfo.put(TransmissionType.FLUID, new LightConfigInfo(Map.of(RelativeSide.RIGHT, DataType.OUTPUT), false));
		configInfo.put(TransmissionType.ENERGY, LightConfigInfo.INPUT_ONLY);
		return new AttachedSideConfig(configInfo);
	});

	/** 基础MEK离心机BlockItem */
	public static final DeferredItem<ItemBlockMekCentrifuge> MEK_CENTRIFUGE =
			ITEMS.register("mek_centrifuge", () -> new ItemBlockMekCentrifuge(ModBlocks.MEK_CENTRIFUGE.get(), machineItemProperties(ModBlocks.MEK_CENTRIFUGE.get())));

	/**
	 * MEK通用机械蜂箱BlockItem
	 * <br/>
	 * 复用machineItemProperties添加Mekanism DataComponents（EJECTOR/SIDE_CONFIG/SECURITY/REDSTONE/UPGRADES）。
	 * 注意：当前复用MEK_CENTRIFUGE_SIDE_CONFIG（物品标准机器/流体右侧输出/能量仅输入），
	 * 后续Task 3将创建蜂箱专属侧面配置（可能调整流体输入侧）。
	 */
	public static final DeferredItem<ItemBlockMekApiary> MEK_APIARY =
			ITEMS.register("mek_apiary", () -> new ItemBlockMekApiary(ModBlocks.MEK_APIARY.get(), machineItemProperties(ModBlocks.MEK_APIARY.get())));

	/** 通用机械蜂箱 — 基础工厂BlockItem（含SORTING组件） */
	public static final DeferredItem<ItemBlockMekApiaryFactory> BASIC_MEK_APIARY_FACTORY =
			ITEMS.register("basic_mek_apiary_factory", () -> new ItemBlockMekApiaryFactory(ModBlocks.BASIC_MEK_APIARY_FACTORY.get(), machineItemProperties(ModBlocks.BASIC_MEK_APIARY_FACTORY.get())));

	/** 通用机械蜂箱 — 高级工厂BlockItem */
	public static final DeferredItem<ItemBlockMekApiaryFactory> ADVANCED_MEK_APIARY_FACTORY =
			ITEMS.register("advanced_mek_apiary_factory", () -> new ItemBlockMekApiaryFactory(ModBlocks.ADVANCED_MEK_APIARY_FACTORY.get(), machineItemProperties(ModBlocks.ADVANCED_MEK_APIARY_FACTORY.get())));

	/** 通用机械蜂箱 — 精英工厂BlockItem */
	public static final DeferredItem<ItemBlockMekApiaryFactory> ELITE_MEK_APIARY_FACTORY =
			ITEMS.register("elite_mek_apiary_factory", () -> new ItemBlockMekApiaryFactory(ModBlocks.ELITE_MEK_APIARY_FACTORY.get(), machineItemProperties(ModBlocks.ELITE_MEK_APIARY_FACTORY.get())));

	/** 通用机械蜂箱 — 终极工厂BlockItem */
	public static final DeferredItem<ItemBlockMekApiaryFactory> ULTIMATE_MEK_APIARY_FACTORY =
			ITEMS.register("ultimate_mek_apiary_factory", () -> new ItemBlockMekApiaryFactory(ModBlocks.ULTIMATE_MEK_APIARY_FACTORY.get(), machineItemProperties(ModBlocks.ULTIMATE_MEK_APIARY_FACTORY.get())));

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

	/** 无尽·创世蜜脾 — 自定义蜜脾物品，预置 bee_type 数据组件，带 tooltip 提示 */
	public static final DeferredItem<ItemInfinityCreationComb> INFINITY_CREATION_COMB =
			ITEMS.register("infinitycreation_comb", () -> new ItemInfinityCreationComb(new Item.Properties()
					.component(ModDataComponents.BEE_TYPE.get(),
							PBConstants.MYRIADCREATIONS_TYPE)));

	/** 无尽·创世蜜脾块 BlockItem，带 tooltip 提示 */
	public static final DeferredItem<ItemInfinityCreationCombBlock> INFINITY_CREATION_COMB_BLOCK_ITEM =
			ITEMS.register("infinitycreation_comb_block", () -> new ItemInfinityCreationCombBlock(ModBlocks.INFINITY_CREATION_COMB_BLOCK.get(), new Item.Properties()));

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
	 * Key=ExtraFactoryTier（ME 独立枚举，运行时由 compat 包写入），Value=对应的 DeferredItem。
	 * 使用通配类型 {@code Map<Object, DeferredItem<?>>} 避免主注册类编译期依赖 ME 的类
	 * （ExtraFactoryTier/ItemBlockMekCentrifuge 的 ME 子类等）。
	 * 实际填充由 {@link MECompatLoader#registerFactoryItems()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEItemRegistration} 完成。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredItem<?>> ME_FACTORY_ITEMS = new ConcurrentHashMap<>();

	/**
	 * EME工厂BlockItem映射 — 由registerEMEFactoryItems()在EME加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME 独立枚举，编译时不存在），Value=对应的 DeferredItem。
	 * 使用通配类型 {@code Object}/{@code DeferredItem<?>}，避免主注册类编译期依赖 EME 类。
	 * 实际填充由 {@link EMECompatLoader#registerCentrifugeItems()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEItemRegistration} 完成。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredItem<?>> EME_FACTORY_ITEMS = new ConcurrentHashMap<>();

	/**
	 * ME 蜂箱工厂 BlockItem 映射 — 由 registerMEApiaryFactoryItems() 在 ME 加载时填充
	 * <br/>
	 * Key=ExtraFactoryTier（ME 独立枚举，运行时由 compat 包写入），Value=对应的 DeferredItem。
	 * 使用通配类型 {@code Map<Object, DeferredItem<?>>} 避免主注册类编译期依赖 ME 的类。
	 * 实际填充由 {@link MECompatLoader#registerApiaryFactoryItems()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEItemRegistration} 完成。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredItem<?>> ME_APIARY_FACTORY_ITEMS = new ConcurrentHashMap<>();

	/**
	 * EME 蜂箱工厂 BlockItem 映射 — 由 registerEMEApiaryFactoryItems() 在 EME 加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME 独立枚举，编译时不存在），Value=对应的 DeferredItem。
	 * 使用通配类型 {@code Object}/{@code DeferredItem<?>}，避免主注册类编译期依赖 EME 类。
	 * 实际填充由 {@link EMECompatLoader#registerApiaryItems()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEItemRegistration} 完成。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredItem<?>> EME_APIARY_FACTORY_ITEMS = new ConcurrentHashMap<>();

	/**
	 * EM 蜂箱工厂 BlockItem 映射 — 由 registerEMApiaryFactoryItems() 在 EM 加载时填充
	 * <br/>
	 * Key=FactoryTier（EM 运行时扩展的枚举值），Value=对应的 DeferredItem。
	 * EM 蜂箱工厂复用 ItemBlockMekApiaryFactory（与原版 4 等级相同）。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<FactoryTier, DeferredItem<ItemBlockMekApiaryFactory>> EM_APIARY_FACTORY_ITEMS = new ConcurrentHashMap<>();

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
	 * 当 MekanismExtras 加载时，委托 {@link MECompatLoader#registerFactoryItems()}
	 * 完成实际注册（避免主注册类编译期依赖 ME 的类）。
	 * 注册名与方块一致（如 absolute_extra_mek_centrifuge_factory），确保 MekCentrifugeMEBlockType
	 * 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 * 注册结果填充到 {@link #ME_FACTORY_ITEMS}（通配类型）。
	 * <p>
	 * 调用时机：必须在 ModBlocks.registerMEFactories() 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerMEFactoryItems() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerFactoryItems();
		}
	}

	/**
	 * 注册EME等级的工厂BlockItem
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerCentrifugeItems()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 {@link #EME_FACTORY_ITEMS}（通配类型）。
	 * <p>
	 * 调用时机：必须在 {@link ModBlocks#registerEMEFactories()} 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerEMEFactoryItems() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerCentrifugeItems();
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 当 MekanismExtras 加载时，委托 {@link MECompatLoader#registerApiaryFactoryItems()}
	 * 完成实际注册（避免主注册类编译期依赖 ME 的类）。
	 * 注册名与方块一致（如 absolute_extra_mek_apiary_factory），确保 MekApiaryMEBlockType
	 * 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 * 使用 ItemBlockMekApiaryFactory（与原版工厂蜂箱相同的 ItemBlock 类）。
	 * 注册结果填充到 {@link #ME_APIARY_FACTORY_ITEMS}（通配类型）。
	 * <p>
	 * 调用时机：必须在 ModBlocks.registerMEApiaryFactories() 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerMEApiaryFactoryItems() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerApiaryFactoryItems();
		}
	}

	/**
	 * 注册 EM 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 当 EvolvedMekanism 加载时，遍历 EM_APIARY_FACTORIES 中的方块，为每个方块注册同名的 BlockItem。
	 * 注册名与方块一致（如 overclocked_mek_apiary_factory），确保 MekApiaryFactoryBlockType
	 * 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 * 使用 ItemBlockMekApiaryFactory（与原版工厂蜂箱相同的 ItemBlock 类）。
	 * <p>
	 * 调用时机：必须在 ModBlocks.registerEMApiaryFactories() 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerEMApiaryFactoryItems() {
		if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
			return;
		}
		for (Map.Entry<FactoryTier, DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory, BlockTypeTile<TileEntityMekApiaryFactory>>>> entry : ModBlocks.EM_APIARY_FACTORIES.entrySet()) {
			FactoryTier tier = entry.getKey();
			DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory, BlockTypeTile<TileEntityMekApiaryFactory>>> deferredBlock = entry.getValue();
			String registryName = tier.getBaseTier().getLowerName() + "_mek_apiary_factory";
			DeferredItem<ItemBlockMekApiaryFactory> deferredItem = ITEMS.register(registryName,
					() -> new ItemBlockMekApiaryFactory(deferredBlock.get(), machineItemProperties(deferredBlock.get())));
			EM_APIARY_FACTORY_ITEMS.put(tier, deferredItem);
		}
	}

	/**
	 * 注册 EME 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerApiaryItems()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 {@link #EME_APIARY_FACTORY_ITEMS}（通配类型）。
	 * <p>
	 * 调用时机：必须在 {@link ModBlocks#registerEMEApiaryFactories()} 之后、ITEMS.register(eventBus) 之前调用。
	 */
	public static void registerEMEApiaryFactoryItems() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerApiaryItems();
	}

	/**
	 * 创建机器BlockItem属性，添加MekanismDataComponents
	 * <br/>
	 * 参考Mek-Energistics的MeBlockDeferredRegister.machineProperties()和blockItemProperties()。
	 * EJECTOR和SIDE_CONFIG在machineProperties中添加，SECURITY/REDSTONE/UPGRADES在blockItemProperties中添加。
	 */
	public static Item.Properties machineItemProperties(net.minecraft.world.level.block.Block block) {
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
