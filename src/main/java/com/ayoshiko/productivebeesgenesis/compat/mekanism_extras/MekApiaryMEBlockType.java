package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeTier;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeUpgradeable;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import mekanism.api.text.ILangEntry;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * Mekanism Extras (ME) 工厂版通用机械蜂箱 BlockType 定义
	 * <br/>
	 * 此类独立承载对 ME 模组的可选依赖，避免 ME 未加载时触发 NoClassDefFoundError。
	 * 所有 ME 相关的 import、字段和方法集中在此类，仅当
	 * {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isMekanismExtrasLoaded()} 为 true 时
	 * 由 {@link MekApiaryFactoryBlockType} 的包装方法调用。
	 * <p>
	 * 关键设计：
	 * 1. 蜂箱工厂不继承 ME 的 ExtraFactoryMachine（因为不走 CachedRecipe 管线），使用原版
	 *    {@link Machine.MachineBuilder#createMachine} 构建，与 {@link MekApiaryFactoryBlockType} 模式一致。
	 * 2. ME 等级通过 {@link ExtraAttributeTier} 标记，{@link ExtraAttributeUpgradeable} 支持升级链。
	 * 3. 蜜蜂生产逻辑完全复用父类 {@link TileEntityMekApiaryFactory} 的 ApiaryTickHandler。
	 * 4. 能量配置遵循 ME 模式：storage = max(20000, 50) * tier.processes。
	 * 5. GUI 关联 {@link ModMenuTypes#MEK_APIARY_FACTORY}（与原版工厂共用），由 TileEntity 运行时区分等级。
	 */
public final class MekApiaryMEBlockType {

	/**
	 * ME 蜂箱工厂 BlockType 映射 — 由 initMETiers() 在 ME 加载时填充
	 * <br/>
	 * Key={@link ExtraFactoryTier}（ME 的独立枚举），Value=对应的 BlockTypeTile。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	private static final Map<ExtraFactoryTier, BlockTypeTile<TileEntityExtraMekApiaryFactory>> ME_APIARY_FACTORY_TYPES =
			new ConcurrentHashMap<>();

	/**
	 * ME 蜂箱工厂 TileEntityType 映射 — 由 ModBlockEntities 在 ME 加载时填充
	 * <br/>
	 * Key={@link ExtraFactoryTier}（ME 的独立枚举），Value=对应的 TileEntityTypeRegistryObject。
	 * 使用 ConcurrentHashMap 保证线程安全。public 供 ModBlockEntities 写入。
	 */
	public static final Map<ExtraFactoryTier, TileEntityTypeRegistryObject<TileEntityExtraMekApiaryFactory>>
			ME_APIARY_FACTORY_TILES =
			new ConcurrentHashMap<>();

	private MekApiaryMEBlockType() {}

	/**
	 * 初始化 ME 蜂箱工厂等级的 BlockType
	 * <br/>
	 * 当 ME 加载时，为每个 {@link ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE）
	 * 创建 BlockType 并存入 ME_APIARY_FACTORY_TYPES。使用 computeIfAbsent 保证线程安全的单次创建。
	 * <p>
	 * 同时为 ULTIMATE 蜂箱工厂添加 {@link ExtraAttributeUpgradeable}，使其能通过 ME 的
	 * ABSOLUTE Tier Installer 升级。ULTIMATE 蜂箱原本只有 AttributeUpgradeable（Mekanism 升级系统），
	 * 需要额外添加 ExtraAttributeUpgradeable 指向 ABSOLUTE 蜂箱工厂，
	 * 使 ME 的 ItemExtraTierInstaller 能识别并执行升级。
	 *
	 * @param ultimateFactory ULTIMATE 蜂箱工厂 BlockType，用于为其添加 ExtraAttributeUpgradeable
	 */
	public static void initMETiers(BlockTypeTile<TileEntityMekApiaryFactory> ultimateFactory) {
		for (ExtraFactoryTier tier : ExtraFactoryTier.values()) {
			ME_APIARY_FACTORY_TYPES.computeIfAbsent(tier, MekApiaryMEBlockType::createMEApiaryFactoryBlockType);
		}
		// 为 ULTIMATE 蜂箱工厂添加 ExtraAttributeUpgradeable，指向 ABSOLUTE 蜂箱工厂
		ultimateFactory.add(new ExtraAttributeUpgradeable(
				MekCentrifugeBlockType.wrapAsBlockRegistryObject(getMEApiaryFactoryBlock("absolute"))));
	}

	/**
	 * 创建 ME 等级的蜂箱工厂 BlockType
	 * <br/>
	 * 使用 {@link Machine.MachineBuilder#createMachine} 构建（非 ExtraFactoryMachine），
	 * 因为蜂箱不走 CachedRecipe 管线，不需要 AttributeFactoryType。
	 * 手动添加 ME 的 {@link ExtraAttributeTier} 和 {@link ExtraAttributeUpgradeable} 属性。
	 * <p>
	 * 能量配置：ME 模式，storage = max(origStorage, origUsage) * tier.processes。
	 * 原版蜂箱：usage=50L, storage=20000L，所以 storage = max(20000, 50) * processes。
	 *
	 * @param tier ME 工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE）
	 * @return 对应的 BlockTypeTile
	 */
	private static BlockTypeTile<TileEntityExtraMekApiaryFactory> createMEApiaryFactoryBlockType(ExtraFactoryTier tier) {
		// 能量配置：ME 模式，storage = max(origStorage, origUsage) * tier.processes
		long usage = 50L;
		int beeSlots = FactoryApiaryConfig.forMETier(tier).beeSlotCount;
		long storage = SaturatingMath.saturatingMultiply(
				Math.max(20_000L, usage), Math.max(tier.processes, beeSlots));

		// 使用原版 Machine.MachineBuilder.createMachine 构建（非 ExtraFactoryMachine），
		// 因为蜂箱不走 CachedRecipe 管线，不需要 AttributeFactoryType
		var builder = Machine.MachineBuilder
				.createMachine(() -> ME_APIARY_FACTORY_TILES.get(tier),
						descriptionLang(tier.getAdvanceTier().getLowerName() + "_extra_mek_apiary_factory"))
				.withEnergyConfig(() -> MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick(usage),
						() -> MekCentrifugeEnergyScaling.balancedBaseCapacity(storage))
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> ModMenuTypes.MEK_APIARY_FACTORY)
				// Task 4：不添加 withSound，工作声音由 ApiarySoundHandler 播放 PB 蜜蜂声
				.with(new ExtraAttributeTier<>(tier));

		// 添加 ExtraAttributeUpgradeable，指向下一级蜂箱工厂
		// INFINITE 是最高等级，不添加升级目标
		ExtraFactoryTier[] tiers = ExtraFactoryTier.values();
		if (tier.ordinal() < tiers.length - 1) {
			ExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
			builder.with(new ExtraAttributeUpgradeable(MekCentrifugeBlockType.wrapAsBlockRegistryObject(
					getMEApiaryFactoryBlock(nextTier.getAdvanceTier().getLowerName()))));
		}
		// 蜂箱支持CREATIVE升级（TPS风险已由20-tick批量产出聚合消除），STACK仍排除（产出倍率过高）
		builder.with(MekUpgradeSupport.forApiary());

		return builder.build();
	}

	/**
	 * 通过注册名创建 ME 蜂箱工厂方块的 DeferredHolder
	 * <br/>
	 * ME 蜂箱工厂方块由 ModBlocks 注册，此处通过 DeferredHolder.create 按注册名创建延迟 Holder。
	 * 注册名遵循 {tier}_extra_mek_apiary_factory 命名约定（使用 ME 的 tier 小写名）。
	 *
	 * @param tierName ME 等级小写名（如 "absolute"）
	 * @return 对应方块的 DeferredHolder
	 */
	private static DeferredHolder<Block, ?> getMEApiaryFactoryBlock(String tierName) {
		String registryName = tierName + "_extra_mek_apiary_factory";
		return DeferredHolder.create(Registries.BLOCK,
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
	}

	/**
	 * 获取 ME 等级的蜂箱工厂 BlockType
	 * <br/>
	 * 参数类型为 Object 而非 {@link ExtraFactoryTier}，使主类可通过反射获取 tier 实例后直接传入，
	 * 避免主类编译期硬依赖 ME。{@link java.util.Map#get(Object)} 本就接受 Object，运行时类型仍为 ExtraFactoryTier。
	 *
	 * @param tier ME 工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE），运行时须为 ExtraFactoryTier 实例
	 * @return 对应的 BlockTypeTile，不存在时返回 null
	 */
	public static BlockTypeTile<TileEntityExtraMekApiaryFactory> getMEApiaryFactoryType(Object tier) {
		return ME_APIARY_FACTORY_TYPES.get(tier);
	}

	/**
	 * 创建蜂箱工厂描述 ILangEntry
	 * <br/>
	 * key 格式：description.productivebeesgenesis.{key}
	 * 用于 Shift+N 显示方块描述文本。
	 */
	private static ILangEntry descriptionLang(String key) {
		return () -> "description.productivebeesgenesis." + key;
	}
}
