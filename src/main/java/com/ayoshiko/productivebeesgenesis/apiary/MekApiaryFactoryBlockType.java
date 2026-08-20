package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.common.block.attribute.AttributeTier;
import mekanism.common.block.attribute.AttributeUpgradeable;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * 工厂版通用机械蜂箱 BlockType 定义
	 * <br/>
	 * 参考 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType} 的工厂版模式，
	 * 但使用 {@link Machine.MachineBuilder#createMachine} 而非 {@code createFactoryMachine}。
	 * <p>
	 * 关键设计决策：蜂箱工厂不继承 {@code TileEntityFactory}，因此不走 MEK 的 CachedRecipe 管线。
	 * 蜜蜂生产逻辑完全复用父类 {@link TileEntityMekApiary} 的 {@code ApiaryTickHandler}。
	 * 工厂等级仅通过 {@link AttributeTier} 区分（LED 颜色 + 物品名称颜色），不通过边框或材质区分。
	 * <p>
	 * 能量配置按 spec.md 表 2.1 等级递增：
	 * <ul>
	 *   <li>Basic: usage=20 FE/t（每蜜蜂），storage=128,000 FE</li>
	 *   <li>Advanced: usage=22 FE/t，storage=256,000 FE</li>
	 *   <li>Elite: usage=25 FE/t，storage=512,000 FE</li>
	 *   <li>Ultimate: usage=30 FE/t，storage=1,024,000 FE</li>
	 * </ul>
	 * <p>
	 * 设计原则：单一职责，本类仅负责 BlockType 定义，方块/方块实体/物品注册由 init 包负责。
	 */
public final class MekApiaryFactoryBlockType {

	/** 基础工厂 BlockType（5 蜜蜂 / 15 输出 / 128,000 FE） */
	public static final BlockTypeTile<TileEntityMekApiaryFactory> BASIC_MEK_APIARY_FACTORY =
			createFactoryBlockType(FactoryTier.BASIC, 20L, 128_000L, descriptionLang("basic_mek_apiary_factory"));

	/** 高级工厂 BlockType（10 蜜蜂 / 15 输出 / 256,000 FE） */
	public static final BlockTypeTile<TileEntityMekApiaryFactory> ADVANCED_MEK_APIARY_FACTORY =
			createFactoryBlockType(FactoryTier.ADVANCED, 22L, 256_000L, descriptionLang("advanced_mek_apiary_factory"));

	/** 精英工厂 BlockType（15 蜜蜂 / 15 输出 / 512,000 FE） */
	public static final BlockTypeTile<TileEntityMekApiaryFactory> ELITE_MEK_APIARY_FACTORY =
			createFactoryBlockType(FactoryTier.ELITE, 25L, 512_000L, descriptionLang("elite_mek_apiary_factory"));

	/** 终极工厂 BlockType（20 蜜蜂 / 30 输出 / 1,024,000 FE） */
	public static final BlockTypeTile<TileEntityMekApiaryFactory> ULTIMATE_MEK_APIARY_FACTORY =
			createFactoryBlockType(FactoryTier.ULTIMATE, 30L, 1_024_000L, descriptionLang("ultimate_mek_apiary_factory"));

	/**
	 * EM 蜂箱工厂 BlockType 映射 — 由 initEMTiers() 在 EM 加载时填充
	 * <br/>
	 * Key=FactoryTier（EM 运行时扩展的枚举值），Value=对应的 BlockTypeTile。
	 * EM 通过 Mixin 在运行时扩展 FactoryTier 枚举，编译时不存在，必须反射获取。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	private static final Map<FactoryTier, BlockTypeTile<TileEntityMekApiaryFactory>> EM_APIARY_FACTORY_TYPES =
			new ConcurrentHashMap<>();

	private MekApiaryFactoryBlockType() {}

	/**
	 * 创建工厂版 BlockType
	 * <br/>
	 * 使用 createMachine 构建，手动添加：
	 * <ul>
	 *   <li>{@link AttributeTier} — 工厂等级（用于 LED 颜色和物品名称颜色）</li>
	 *   <li>{@link AttributeUpgradeable} — 指向下一等级工厂（支持 Tier Installer 升级）</li>
	 * </ul>
	 * 不添加 AttributeFactoryType，因为蜂箱工厂不使用 MEK CachedRecipe 管线。
	 * <p>
	 * CREATIVE 是最高级，getNextTierBlock 返回 null，不添加 AttributeUpgradeable
	 * （与 EME 的 INFINITE_MULTIVERSAL 一致，避免自指导致 ItemMaxTierInstaller 死循环）。
	 *
	 * @param tier        工厂等级
	 * @param usage       每蜜蜂每 tick 基础能耗（FE）
	 * @param storage     能量容量（FE）
	 * @param description 描述 ILangEntry
	 */
	private static BlockTypeTile<TileEntityMekApiaryFactory> createFactoryBlockType(
			FactoryTier tier, long usage, long storage, mekanism.api.text.ILangEntry description) {
		var builder = Machine.MachineBuilder
				.createMachine(() -> getFactoryTileEntityType(tier), description)
				.withEnergyConfig(() -> usage, () -> storage)
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> ModMenuTypes.MEK_APIARY_FACTORY)
				// Task 4：移除 withSound(ENERGIZED_SMELTER)，工作声音由 ApiarySoundHandler 播放 PB 蜜蜂声
				.with(new AttributeTier<>(tier));

		// 添加升级属性 — 最高级（CREATIVE）无下一级，跳过添加避免自指
		DeferredHolder<Block, ?> nextTierBlock = getNextTierBlock(tier);
		if (nextTierBlock != null) {
			builder.with(new AttributeUpgradeable(MekCentrifugeBlockType.wrapAsBlockRegistryObject(nextTierBlock)));
		}
		// 蜂箱支持CREATIVE升级（TPS风险已由20-tick批量产出聚合消除），STACK仍排除（产出倍率过高）
		builder.with(MekUpgradeSupport.forApiary());

		return builder.build();
	}

	/**
	 * 获取下一等级工厂的 DeferredHolder
	 * <br/>
	 * 原版 4 等级走固定映射；EM 加载时 ULTIMATE 指向 OVERCLOCKED（EM 优先于 ME），
	 * 仅 ME 加载时 ULTIMATE 指向 ABSOLUTE；EM 等级走 getEMNextTierBlock。
	 * 必须有 default 分支：EM 通过 Mixin 在运行时扩展 FactoryTier 枚举。
	 * <p>
	 * 升级链优先级：EM 优先于 ME。AttributeUpgradeable 供 EM/Mekanism installer 使用，
	 * 必须指向 EM 链（OVERCLOCKED）；ME 链通过 {@link MekApiaryMEBlockType#initMETiers}
	 * 添加的 ExtraAttributeUpgradeable 实现（ME installer 使用 ExtraAttributeUpgradeable）。
	 * <p>
	 * 修复 v14 ULTIMATE 自指死循环：当 EM 和 ME 都未加载时，ULTIMATE 是最高级，
	 * 返回 null 表示无下一级（createFactoryBlockType 会跳过添加 AttributeUpgradeable）。
	 * 原实现返回 ModBlocks.ULTIMATE_MEK_APIARY_FACTORY（自指），
	 * 导致 ItemMaxTierInstaller 的 while 循环无法终止，游戏卡死。
	 */
	private static DeferredHolder<Block, ?> getNextTierBlock(FactoryTier currentTier) {
		return switch (currentTier) {
			case BASIC -> ModBlocks.ADVANCED_MEK_APIARY_FACTORY;
			case ADVANCED -> ModBlocks.ELITE_MEK_APIARY_FACTORY;
			case ELITE -> ModBlocks.ULTIMATE_MEK_APIARY_FACTORY;
			// EM 优先于 ME：EM 加载时升级到 EM OVERCLOCKED 蜂箱工厂
			// 修复 v14: EM 和 ME 都未加载时返回 null，避免自指导致 ItemMaxTierInstaller 死循环
			case ULTIMATE -> MekCompatHooks.isEvolvedMekanismLoaded()
					? getEMApiaryFactoryBlock("overclocked")
					: MekCompatHooks.isMekanismExtrasLoaded()
					? getMEApiaryFactoryBlock("absolute")
					: null;
			// EM 运行时扩展的等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
			default -> getEMNextTierBlock(currentTier);
		};
	}

	/**
	 * 获取 EM 等级的下一级蜂箱工厂方块 Holder
	 * <br/>
	 * EM 等级在编译时不存在，通过 name() 字符串匹配确定当前等级，返回下一级的 DeferredHolder。
	 * CREATIVE 是最高级，返回 null 表示无下一级（与 EME 的 INFINITE_MULTIVERSAL 一致，不添加升级属性，
	 * 避免 AttributeUpgradeable 自指导致 ItemMaxTierInstaller 的 while 循环死循环）。
	 */
	private static DeferredHolder<Block, ?> getEMNextTierBlock(FactoryTier currentTier) {
		String name = currentTier.name();
		return switch (name) {
			case "OVERCLOCKED" -> getEMApiaryFactoryBlock("quantum");
			case "QUANTUM" -> getEMApiaryFactoryBlock("dense");
			case "DENSE" -> getEMApiaryFactoryBlock("multiversal");
			case "MULTIVERSAL" -> getEMApiaryFactoryBlock("creative");
			// CREATIVE 是最高级，返回 null（createFactoryBlockType 会跳过添加 AttributeUpgradeable）
			default -> null;
		};
	}

	/**
	 * 通过注册名创建 EM 蜂箱工厂方块的 DeferredHolder
	 * <br/>
	 * EM 蜂箱工厂方块由 ModBlocks 在 EM 加载时注册，此处通过 DeferredHolder.create 按注册名创建延迟 Holder。
	 * 注册名遵循 {tier}_mek_apiary_factory 命名约定（使用 EM 的 tier 小写名）。
	 */
	private static DeferredHolder<Block, ?> getEMApiaryFactoryBlock(String tierName) {
		String registryName = tierName + "_mek_apiary_factory";
		return DeferredHolder.create(Registries.BLOCK,
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
	}

	/**
	 * 通过注册名创建 ME 蜂箱工厂方块的 DeferredHolder
	 * <br/>
	 * ME 蜂箱工厂方块由 ModBlocks 注册，此处通过 DeferredHolder.create 按注册名创建延迟 Holder。
	 * 注册名遵循 {tier}_extra_mek_apiary_factory 命名约定（使用 ME 的 tier 小写名），
	 * 与 {@link MekApiaryMEBlockType#getMEApiaryFactoryBlock} 一致。
	 * <p>
	 * 本方法不引用任何 ME 类（仅用 String 参数），可安全在守卫块外定义，
	 * 避免触发 ME 类加载导致 NoClassDefFoundError。
	 */
	private static DeferredHolder<Block, ?> getMEApiaryFactoryBlock(String tierName) {
		String registryName = tierName + "_extra_mek_apiary_factory";
		return DeferredHolder.create(Registries.BLOCK,
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, registryName));
	}

	/**
	 * 根据工厂等级获取对应的 TileEntityType
	 * <br/>
	 * 原版 4 等级走固定映射；EM 等级从 ModBlockEntitiesHolder.EM_APIARY_FACTORY_TILES 获取。
	 * 必须有 default 分支：EM 运行时扩展的枚举值会落入 default。
	 */
	private static TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> getFactoryTileEntityType(FactoryTier tier) {
		return switch (tier) {
			case BASIC -> ModBlockEntitiesHolder.BASIC_MEK_APIARY_FACTORY;
			case ADVANCED -> ModBlockEntitiesHolder.ADVANCED_MEK_APIARY_FACTORY;
			case ELITE -> ModBlockEntitiesHolder.ELITE_MEK_APIARY_FACTORY;
			case ULTIMATE -> ModBlockEntitiesHolder.ULTIMATE_MEK_APIARY_FACTORY;
			// EM 等级从 EM_APIARY_FACTORY_TILES 映射获取（由 ModBlockEntities 在 EM 加载时填充）
			default -> ModBlockEntitiesHolder.EM_APIARY_FACTORY_TILES.get(tier);
		};
	}

	/**
	 * 创建蜂箱工厂描述 ILangEntry
	 * <br/>
	 * key 格式：description.productivebeesgenesis.{key}
	 * 用于 Shift+N 显示方块描述文本。
	 */
	public static mekanism.api.text.ILangEntry descriptionLang(String key) {
		return () -> "description.productivebeesgenesis." + key;
	}

	/**
	 * 初始化 EM 蜂箱工厂等级的 BlockType
	 * <br/>
	 * 当 EM 加载时，为每个 EM FactoryTier（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
	 * 创建 BlockType 并存入 EM_APIARY_FACTORY_TYPES。使用 computeIfAbsent 保证线程安全的单次创建。
	 * 复用 createFactoryBlockType，确保 EM 等级也走完整的 BlockType 创建路径。
	 * <p>
	 * EM 等级的能量配置遵循 EM 模式：usage=50L，storage=max(20000, 50)×processes（与 ME/EME 一致）。
	 * 但 createFactoryBlockType 需要显式传入 usage 和 storage，这里使用 EM 的标准能量值。
	 * <p>
	 * 调用时机：EM 加载且 EM 蜂箱工厂方块/TileEntity 注册完成后调用。
	 */
	public static void initEMTiers() {
		if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
			return;
		}
		List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
		if (emTiers.isEmpty()) {
			return;
		}
		for (FactoryTier tier : emTiers) {
			EM_APIARY_FACTORY_TYPES.computeIfAbsent(tier,
					t -> createFactoryBlockType(t, 50L,
							SaturatingMath.saturatingMultiply(Math.max(20_000L, 50L),
									Math.max(t.processes, FactoryApiaryConfig.forTier(t).beeSlotCount)),
							descriptionLang(t.name().toLowerCase() + "_mek_apiary_factory")));
		}
	}

	/**
	 * 获取 EM 等级的蜂箱工厂 BlockType
	 * <br/>
	 * 供 ModBlocks 注册 EM 蜂箱工厂方块时使用，通过 BlockType 引用构建 MekApiaryBlock。
	 * EM 未加载或未初始化时返回 null。
	 *
	 * @param tier EM 工厂等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
	 * @return 对应的 BlockTypeTile，不存在时返回 null
	 */
	public static BlockTypeTile<TileEntityMekApiaryFactory> getEMApiaryFactoryType(FactoryTier tier) {
		return EM_APIARY_FACTORY_TYPES.get(tier);
	}

	/**
	 * TileEntityType 持有者 — 由 ModBlockEntities 静态初始化时设置
	 * <br/>
	 * 解决 BlockType 与 TileEntityType 之间的循环依赖：
	 * BlockType 需要引用 TileEntityType（用于 getTileType()），
	 * 而 TileEntityType 注册又需要引用 BlockType（通过 BlockType 的 get() 方法）。
	 * 通过 Holder 静态字段延迟绑定，打破循环。
	 */
	public static class ModBlockEntitiesHolder {
		public static TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> BASIC_MEK_APIARY_FACTORY;
		public static TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ADVANCED_MEK_APIARY_FACTORY;
		public static TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ELITE_MEK_APIARY_FACTORY;
		public static TileEntityTypeRegistryObject<TileEntityMekApiaryFactory> ULTIMATE_MEK_APIARY_FACTORY;

		/**
		 * EM 蜂箱工厂 TileEntityType 映射 — 由 ModBlockEntities 在 EM 加载时填充
		 * <br/>
		 * Key=FactoryTier（EM 运行时扩展的枚举值），Value=对应的 TileEntityTypeRegistryObject。
		 * 使用 ConcurrentHashMap 保证线程安全。
		 */
		public static final Map<FactoryTier, TileEntityTypeRegistryObject<TileEntityMekApiaryFactory>>
				EM_APIARY_FACTORY_TILES =
				new ConcurrentHashMap<>();
	}
}
