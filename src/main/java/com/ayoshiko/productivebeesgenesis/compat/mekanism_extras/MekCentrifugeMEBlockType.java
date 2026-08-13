package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeUpgradeable;
import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.registries.MekanismSounds;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * Mekanism Extras (ME) 离心机工厂BlockType定义
	 * <br/>
	 * 此类独立承载对ME模组的可选依赖，避免ME未加载时MekCentrifugeBlockType触发NoClassDefFoundError。
	 * 所有ME相关的import、字段和方法集中在此类，仅当MekCompatHooks.isMekanismExtrasLoaded()为true时
	 * 由MekCentrifugeBlockType.initMETiers()包装方法调用。
	 * <p>
	 * 关键设计：
	 * 1. ME等级使用ExtraFactoryMachine基类+ExtraAttributeTier/ExtraAttributeUpgradeable属性
	 * 2. 能量配置遵循ME的ExtraFactory.setMachineData模式（storage=max(origStorage,usage)*processes）
	 * 3. 使用without(ExtraAttributeUpgradeable.class)移除ME Mixin注入的错误升级目标，
	 *    然后添加自己的ExtraAttributeUpgradeable指向下一级离心机工厂
	 */
public final class MekCentrifugeMEBlockType {

	/**
	 * ME工厂BlockType映射 — 由initMETiers()在ME加载时填充
	 * <br/>
	 * Key=ExtraFactoryTier（ME的独立枚举），Value=对应的ExtraFactoryMachine BlockType。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	private static final Map<ExtraFactoryTier, ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory>> ME_FACTORY_TYPES =
			new ConcurrentHashMap<>();

	/**
	 * ME工厂TileEntityType映射 — 由ModBlockEntities在ME加载时填充
	 * <br/>
	 * Key=ExtraFactoryTier（ME的独立枚举），Value=对应的TileEntityTypeRegistryObject。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	public static final Map<ExtraFactoryTier, TileEntityTypeRegistryObject<TileEntityExtraMekCentrifugeFactory>> ME_FACTORY_TILES =
			new ConcurrentHashMap<>();

	private MekCentrifugeMEBlockType() {}

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
	 *
	 * @param ultimateFactory ULTIMATE离心机工厂BlockType，用于为其添加ExtraAttributeUpgradeable
	 */
	public static void initMETiers(Machine.FactoryMachine<TileEntityMekCentrifugeFactory> ultimateFactory) {
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			ME_FACTORY_TYPES.computeIfAbsent(tier, MekCentrifugeMEBlockType::createMEFactoryBlockType);
		}
		// 为ULTIMATE离心机工厂添加ExtraAttributeUpgradeable，使其能通过ME的ABSOLUTE Tier Installer升级
		// ULTIMATE离心机原本只有AttributeUpgradeable（Mekanism升级系统），需要额外添加ExtraAttributeUpgradeable
		// 指向ABSOLUTE离心机工厂，使ME的ItemExtraTierInstaller能识别并执行升级
		ultimateFactory.add(new ExtraAttributeUpgradeable(
				MekCentrifugeBlockType.wrapAsBlockRegistryObject(MekCentrifugeBlockType.getMEFactoryBlock("absolute"))));
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
		// Task 3: origStorage/origUsage 从 config 读取（默认 20000L/50L）
		// 使用离心机专属description key替代通用DESCRIPTION_FACTORY
		// key格式：description.productivebeesgenesis.{tier小写}_extra_mek_centrifuge_factory
		var builder = ExtraMachine.ExtraMachineBuilder
				.createExtraFactoryMachine(() -> ME_FACTORY_TILES.get(tier),
				MekCentrifugeBlockType.descriptionLang(tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory"),
							FactoryType.SMELTING)
				.withEnergyConfig(() -> ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue(),
						() -> Math.max(ModConfig.SERVER.mekCentrifugeEnergyStorage.get(),
								ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue()) * tier.processes)
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> MEMenuTypeRegistration.ME_CENTRIFUGE_FACTORY)
				.withSound(MekanismSounds.ENERGIZED_SMELTER)
				.with(new ExtraAttributeTier<>(tier));

		// 移除ME Mixin注入的ExtraAttributeUpgradeable（指向ME原版工厂，不是离心机）
		builder.without(ExtraAttributeUpgradeable.class);

		// 添加自定义ExtraAttributeUpgradeable，指向下一级离心机工厂
		ExtraFactoryTier[] tiers = ExtraFactoryTier.values();
		if (tier.ordinal() < tiers.length - 1) {
			ExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
			builder.with(new ExtraAttributeUpgradeable(MekCentrifugeBlockType.wrapAsBlockRegistryObject(
					MekCentrifugeBlockType.getMEFactoryBlock(nextTier.getAdvanceTier().getLowerName()))));
		}
		// 替换默认升级支持：ME已加载，支持STACK/CREATIVE + 原版SPEED/ENERGY/MUFFLING
		builder.with(MekUpgradeSupport.forMachine());

		return builder.build();
	}

	/**
	 * 获取ME等级的工厂BlockType
	 *
	 * @param tier ME工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
	 * @return 对应的ExtraFactoryMachine BlockType，不存在时返回null
	 */
	public static ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> getMEFactoryType(
		ExtraFactoryTier tier
	) {
		return ME_FACTORY_TYPES.get(tier);
	}

	/**
	 * 获取ME ABSOLUTE等级的工厂BlockType — 供 EME 升级链使用
	 * <br/>
	 * 封装 {@link #getMEFactoryType(ExtraFactoryTier.ABSOLUTE)} 调用，
	 * 避免调用方（如 {@link MekCentrifugeBlockType#initEMETiers()}）直接引用 {@link ExtraFactoryTier}，
	 * 实现软依赖隔离。调用方仅需通过本方法获取 ABSOLUTE 等级的 BlockType，
	 * 无需 import ME 类。
	 *
	 * @return ME ABSOLUTE 等级的 ExtraFactoryMachine BlockType，未初始化时返回 null
	 */
	public static ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> getAbsoluteFactoryType() {
		return getMEFactoryType(ExtraFactoryTier.ABSOLUTE);
	}
}
