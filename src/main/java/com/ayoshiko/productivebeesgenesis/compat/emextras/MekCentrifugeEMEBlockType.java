package com.ayoshiko.productivebeesgenesis.compat.emextras;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeUpgradeable;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraFactoryType;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;

import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

/**
 * EvolvedMekanismExtras (EME) 离心机工厂BlockType定义
 * <br/>
 * 此类独立承载对EME模组的可选依赖，避免EME未加载时MekCentrifugeBlockType触发NoClassDefFoundError。
 * 所有EME相关的import、字段和方法集中在此类，仅当MekCompatHooks.isEvolvedMekanismExtrasLoaded()为true时
 * 由MekCentrifugeBlockType.initEMETiers()包装方法调用。
 * <p>
 * 关键设计：
 * 1. EME等级使用EMExtraFactoryMachine基类，手动添加所有属性
 * 2. 能量配置遵循EME的EMExtraFactory.setMachineData模式（storage=max(origStorage,usage)*processes）
 * 3. 使用without(EMExtraAttributeUpgradeable.class)移除EME Mixin注入的错误升级目标，
 *    然后添加自己的EMExtraAttributeUpgradeable指向下一级离心机工厂
 * 4. 额外添加AttributeFactoryType(SMELTING)使EME方块能被MekanismUtils.isSameTypeFactory()识别
 */
public final class MekCentrifugeEMEBlockType {

	/**
	 * EME工厂BlockType映射 — 由initEMETiers()在EME加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME的独立枚举），Value=对应的EMExtraFactoryMachine BlockType。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	private static final Map<EMExtraFactoryTier, EMExtraMachine.EMExtraFactoryMachine<TileEntityEMExtraMekCentrifugeFactory>> EME_FACTORY_TYPES =
			new ConcurrentHashMap<>();

	/**
	 * EME工厂TileEntityType映射 — 由ModBlockEntities在EME加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME的独立枚举），Value=对应的TileEntityTypeRegistryObject。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	public static final Map<EMExtraFactoryTier, TileEntityTypeRegistryObject<TileEntityEMExtraMekCentrifugeFactory>> EME_FACTORY_TILES =
			new ConcurrentHashMap<>();

	private MekCentrifugeEMEBlockType() {}

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
	 *
	 * @param ultimateFactory  ULTIMATE离心机工厂BlockType，用于替换EME Mixin注入的EMExtraAttributeUpgradeable
	 * @param meAbsoluteFactory ME的ABSOLUTE离心机工厂BlockType，可能为null（ME未加载或未初始化时）
	 */
	public static void initEMETiers(Machine.FactoryMachine<TileEntityMekCentrifugeFactory> ultimateFactory,
									ExtraMachine.ExtraFactoryMachine<TileEntityExtraMekCentrifugeFactory> meAbsoluteFactory) {
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			EME_FACTORY_TYPES.computeIfAbsent(tier, MekCentrifugeEMEBlockType::createEMEFactoryBlockType);
		}

		// 为ULTIMATE离心机工厂替换EME MixinFactory注入的EMExtraAttributeUpgradeable
		// EME的MixinFactory为ULTIMATE工厂注入EMExtraAttributeUpgradeable指向EME原版ABSOLUTE_OVERCLOCKED电力熔炼炉工厂
		// 移除并替换为指向我们的ABSOLUTE_OVERCLOCKED离心机工厂
		ultimateFactory.remove(EMExtraAttributeUpgradeable.class);
		ultimateFactory.add(new EMExtraAttributeUpgradeable(
				MekCentrifugeBlockType.wrapAsBlockRegistryObject(MekCentrifugeBlockType.getEMEFactoryBlock("absolute_overclocked"))));

		// 为ME ABSOLUTE离心机工厂添加EMExtraAttributeUpgradeable
		// 使ME ABSOLUTE离心机可以升级到EME ABSOLUTE_OVERCLOCKED离心机（跨升级系统升级）
		if (meAbsoluteFactory != null) {
			meAbsoluteFactory.add(new EMExtraAttributeUpgradeable(
					MekCentrifugeBlockType.wrapAsBlockRegistryObject(MekCentrifugeBlockType.getEMEFactoryBlock("absolute_overclocked"))));
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
		// Task 3: origStorage/origUsage 从 config 读取（默认 20000L/50L）
		// 使用离心机专属description key替代通用DESCRIPTION_FACTORY
		// key格式：description.productivebeesgenesis.{tier小写}_emextra_mek_centrifuge_factory
		var builder = EMExtraMachine.EMExtraMachineBuilder
				.createEMExtraFactoryMachine(() -> EME_FACTORY_TILES.get(tier),
						MekCentrifugeBlockType.descriptionLang(tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory"), EMExtraFactoryType.SMELTING)
				.withEnergyConfig(() -> ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue(),
						() -> Math.max(ModConfig.SERVER.mekCentrifugeEnergyStorage.get(),
								ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue()) * tier.processes)
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> EMEMenuTypeRegistration.EME_CENTRIFUGE_FACTORY)
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
			builder.with(new EMExtraAttributeUpgradeable(MekCentrifugeBlockType.wrapAsBlockRegistryObject(
					MekCentrifugeBlockType.getEMEFactoryBlock(nextTier.getEMExtraTier().getLowerName()))));
		}
		// INFINITE_MULTIVERSAL是最高级，不添加升级属性
		// 替换默认升级支持：EME依赖ME，支持STACK/CREATIVE + 原版SPEED/ENERGY/MUFFLING
		builder.with(MekUpgradeSupport.forMachine());

		return builder.build();
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
}
