package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeTier;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeUpgradeable;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
	 * EvolvedMekanismExtras (EME) 工厂蜂箱 BlockType 定义
	 * <br/>
	 * 此类独立承载对 EME 模组的可选依赖，避免 EME 未加载时触发 NoClassDefFoundError。
	 * 所有 EME 相关的 import、字段和方法集中在此类，仅当
	 * {@code MekCompatHooks.isEvolvedMekanismExtrasLoaded()} 为 true 时由调用方加载。
	 * <p>
	 * 关键设计（与离心机 EME 的差异）：
	 * 1. 蜂箱不继承 ME/EME 工厂基类，不走 Mekanism CachedRecipe 管线
	 * 2. 蜜蜂生产逻辑由 ApiaryTickHandler 处理，与原版蜂箱工厂一致
	 * 3. 仅用 {@link EMExtraAttributeTier} 标记等级，{@link EMExtraAttributeUpgradeable} 支持升级链
	 * 4. {@code EMExtraMachineBuilder} 无 createMachine 方法（仅有 createEMExtraFactoryMachine，
	 *    会注入 EMExtraAttributeFactoryType），不适用于蜂箱。改用 {@link Machine.MachineBuilder#createMachine}
	 *    构建 BlockTypeTile，手动添加 EME 属性
	 * 5. GUI 与原版工厂共用 {@link ModMenuTypes#MEK_APIARY_FACTORY}
	 * <p>
	 * 能量配置遵循 EME 的 EMExtraFactory.setMachineData 模式：
	 * storage = max(origStorage, origUsage) * tier.processes
	 * 原版蜂箱工厂基础能耗 usage=50L, storage=20000L
	 */
public final class MekApiaryEMEBlockType {

	/**
	 * EME 工厂蜂箱 BlockType 映射 — 由 initEMETiers() 在 EME 加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME 独立枚举），Value=对应的 BlockTypeTile。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	private static final Map<EMExtraFactoryTier, BlockTypeTile<TileEntityEMExtraMekApiaryFactory>>
			EME_APIARY_FACTORY_TYPES =
			new ConcurrentHashMap<>();

	/**
	 * EME 工厂蜂箱 TileEntityType 映射 — 由 ModBlockEntities 在 EME 加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier，Value=对应的 TileEntityTypeRegistryObject。
	 * 通过 Holder 模式打破 BlockType 与 TileEntityType 的循环依赖：
	 * BlockType 需要引用 TileEntityType（用于 getTileType()），
	 * 而 TileEntityType 注册又需要引用 BlockType（通过 BlockType 的 get() 方法）。
	 * 通过此 Map 延迟绑定，打破循环。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<EMExtraFactoryTier, TileEntityTypeRegistryObject<TileEntityEMExtraMekApiaryFactory>>
			EME_APIARY_FACTORY_TILES =
			new ConcurrentHashMap<>();

	private MekApiaryEMEBlockType() {}

	/**
	 * 初始化 EME 工厂蜂箱等级的 BlockType
	 * <br/>
	 * 当 EME 加载时，为每个 EMExtraFactoryTier（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/
	 * COSMIC_DENSE/INFINITE_MULTIVERSAL）创建 BlockType 并存入 EME_APIARY_FACTORY_TYPES。
	 * 使用 computeIfAbsent 保证线程安全的单次创建。
	 * <p>
	 * 升级链处理（与离心机 EME 一致）：
	 * 1. 为 ULTIMATE 蜂箱工厂替换 EME Mixin 可能注入的 EMExtraAttributeUpgradeable，
	 *    指向 EME ABSOLUTE_OVERCLOCKED 蜂箱（先 remove 再 add，确保升级目标正确）
	 * 2. 为 ME ABSOLUTE 蜂箱工厂添加 {@link EMExtraAttributeUpgradeable}，
	 *    指向 EME ABSOLUTE_OVERCLOCKED 蜂箱，使 ME ABSOLUTE 蜂箱可跨升级系统升级到 EME 等级
	 *
	 * @param ultimateFactory   ULTIMATE 蜂箱工厂 BlockType，用于添加 EME 升级链
	 * @param meAbsoluteFactory ME ABSOLUTE 蜂箱工厂 BlockType，可能为 null（ME 蜂箱未加载或未初始化时）
	 */
	public static void initEMETiers(BlockTypeTile<?> ultimateFactory, BlockTypeTile<?> meAbsoluteFactory) {
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			EME_APIARY_FACTORY_TYPES.computeIfAbsent(tier, MekApiaryEMEBlockType::createEMEApiaryFactoryBlockType);
		}

		// 为 ULTIMATE 蜂箱工厂添加 EMExtraAttributeUpgradeable，指向 EME ABSOLUTE_OVERCLOCKED 蜂箱
		// 先 remove 移除 EME Mixin 可能注入的错误升级目标（安全措施，无则 no-op），再 add 正确目标
		// 使 ULTIMATE 蜂箱可通过 EME 升级器升级到 EME ABSOLUTE_OVERCLOCKED 蜂箱
		ultimateFactory.remove(EMExtraAttributeUpgradeable.class);
		ultimateFactory.add(new EMExtraAttributeUpgradeable(
				wrapAsBlockRegistryObject(getEMEApiaryFactoryBlock("absolute_overclocked"))));

		// 为 ME ABSOLUTE 蜂箱工厂添加 EMExtraAttributeUpgradeable，指向 EME ABSOLUTE_OVERCLOCKED 蜂箱
		// 使 ME ABSOLUTE 蜂箱可以升级到 EME ABSOLUTE_OVERCLOCKED 蜂箱（跨升级系统升级）
		if (meAbsoluteFactory != null) {
			meAbsoluteFactory.add(new EMExtraAttributeUpgradeable(
					wrapAsBlockRegistryObject(getEMEApiaryFactoryBlock("absolute_overclocked"))));
		}
	}

	/**
	 * 创建 EME 等级的工厂蜂箱 BlockType
	 * <br/>
	 * 使用 {@link Machine.MachineBuilder#createMachine} 构建 BlockTypeTile（非 EMExtraFactoryMachine），
	 * 手动添加 EME 属性。原因：蜂箱不走 Mekanism CachedRecipe 管线，不需要 EMExtraAttributeFactoryType；
	 * {@code EMExtraMachineBuilder} 仅有 createEMExtraFactoryMachine 方法，会注入工厂类型属性，不适用于蜂箱。
	 * <p>
	 * 能量配置：EME 模式，storage = max(origStorage, origUsage) * tier.processes
	 * 原版蜂箱工厂基础能耗 usage=50L, storage=20000L
	 * <p>
	 * 升级链：每个 EME 等级指向下一等级，INFINITE_MULTIVERSAL 为最高级不添加升级属性。
	 */
	private static BlockTypeTile<TileEntityEMExtraMekApiaryFactory> createEMEApiaryFactoryBlockType(
			EMExtraFactoryTier tier) {
		// 能量配置：EME 模式，storage = max(origStorage, origUsage) * tier.processes
		long usage = 50L;
		int beeSlots = FactoryApiaryConfig.forEMETier(tier).beeSlotCount;
		long storage = SaturatingMath.saturatingMultiply(
				Math.max(20_000L, usage), Math.max(tier.processes, beeSlots));

		var builder = Machine.MachineBuilder
				.createMachine(() -> EME_APIARY_FACTORY_TILES.get(tier),
						descriptionLang(tier.getEMExtraTier().getLowerName() + "_emextra_mek_apiary_factory"))
				.withEnergyConfig(() -> usage, () -> storage)
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> ModMenuTypes.MEK_APIARY_FACTORY)
				// 不添加 withSound — Task 4 已移除机器声音，由 ApiarySoundHandler 播放蜜蜂声
				.with(new EMExtraAttributeTier<>(tier));

		// 添加自定义 EMExtraAttributeUpgradeable，指向下一级 EME 蜂箱工厂
		EMExtraFactoryTier[] tiers = EMExtraFactoryTier.values();
		if (tier.ordinal() < tiers.length - 1) {
			EMExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
			builder.with(new EMExtraAttributeUpgradeable(wrapAsBlockRegistryObject(
					getEMEApiaryFactoryBlock(nextTier.getEMExtraTier().getLowerName()))));
		}
		// INFINITE_MULTIVERSAL 是最高级，不添加升级属性
		// 蜂箱支持CREATIVE升级（TPS风险已由20-tick批量产出聚合消除），STACK仍排除（产出倍率过高）
		builder.with(MekUpgradeSupport.forApiary());

		return builder.build();
	}

	/**
	 * 获取 EME 等级的工厂蜂箱 BlockType
	 *
	 * @param tier EME 工厂等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）
	 * @return 对应的 BlockTypeTile，不存在时返回 null
	 */
	public static BlockTypeTile<TileEntityEMExtraMekApiaryFactory> getEMEApiaryFactoryType(EMExtraFactoryTier tier) {
		return EME_APIARY_FACTORY_TYPES.get(tier);
	}

	/**
	 * 根据 EME 等级名称获取对应的方块 DeferredHolder
	 * <br/>
	 * 通过注册名创建 DeferredHolder，不依赖 ModBlocks 的 EME 蜂箱 Map 是否已填充。
	 * 命名约定：{tierName}_emextra_mek_apiary_factory
	 *
	 * @param tierName EME 等级小写名称（如 absolute_overclocked）
	 * @return 对应方块的 DeferredHolder
	 */
	public static DeferredHolder<Block, ?> getEMEApiaryFactoryBlock(String tierName) {
		String registryName = tierName + "_emextra_mek_apiary_factory";
		return DeferredHolder.create(Registries.BLOCK,
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
	}

	/**
	 * 将 DeferredHolder 包装为 BlockRegistryObject 供应商
	 * <br/>
	 * {@link EMExtraAttributeUpgradeable} 需要 {@code Supplier<BlockRegistryObject<?, ?>>} 参数。
	 * 通过 block 的注册名创建 item 的 DeferredHolder，组合为 BlockRegistryObject。
	 *
	 * @param blockHolder 方块的 DeferredHolder
	 * @return BlockRegistryObject 供应商
	 */
	@SuppressWarnings("unchecked")
	public static Supplier<BlockRegistryObject<?, ?>> wrapAsBlockRegistryObject(DeferredHolder<Block, ?> blockHolder) {
		return () -> {
			DeferredHolder<Item, ?> itemHolder = DeferredHolder.create(
					Registries.ITEM, blockHolder.getKey().location());
			return new BlockRegistryObject<>((DeferredHolder<Block, Block>) blockHolder,
					(DeferredHolder<Item, Item>) itemHolder);
		};
	}

	/**
	 * 创建蜂箱工厂描述 ILangEntry
	 * <br/>
	 * key 格式：description.productivebeesgenesis.{key}
	 * 用于 Shift+N 显示方块描述文本。
	 */
	public static ILangEntry descriptionLang(String key) {
		return () -> "description.productivebeesgenesis." + key;
	}
}
