package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlockType;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryBlockType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.tier.FactoryTier;
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
	 * <p>
	 * EME扩展：当EvolvedMekanismExtras加载时，委托 {@link EMECompatLoader} 完成 EME 工厂方块注册，
	 * 结果存入 EME_FACTORIES 和 EME_APIARY_FACTORIES（通配类型，避免编译期依赖 EME 类）。
	 */
public final class ModBlocks {

	public static final DeferredRegister.Blocks BLOCKS =
			DeferredRegister.createBlocks(ProductiveBeesGenesis.MOD_ID);

	/** 基础MEK离心机 */
	public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifuge,
		BlockTypeTile<TileEntityMekCentrifuge>>> MEK_CENTRIFUGE =
			BLOCKS.register("mek_centrifuge", () -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.MEK_CENTRIFUGE));

	/** MEK通用机械蜂箱 — 基础机器版本，生产周期1200 ticks */
	public static final DeferredBlock<MekApiaryBlock<TileEntityMekApiary, BlockTypeTile<TileEntityMekApiary>>> MEK_APIARY =
			BLOCKS.register("mek_apiary", () -> new MekApiaryBlock<>(MekApiaryBlockType.MEK_APIARY));

	/** 通用机械蜂箱 — 基础工厂版（5蜜蜂/9输出/128,000 FE） */
	public static final DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>> BASIC_MEK_APIARY_FACTORY =
			BLOCKS.register("basic_mek_apiary_factory",
					() -> new MekApiaryBlock<>(MekApiaryFactoryBlockType.BASIC_MEK_APIARY_FACTORY));

	/** 通用机械蜂箱 — 高级工厂版（10蜜蜂/12输出/256,000 FE） */
	public static final DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>> ADVANCED_MEK_APIARY_FACTORY =
			BLOCKS.register("advanced_mek_apiary_factory",
					() -> new MekApiaryBlock<>(MekApiaryFactoryBlockType.ADVANCED_MEK_APIARY_FACTORY));

	/** 通用机械蜂箱 — 精英工厂版（15蜜蜂/15输出/512,000 FE） */
	public static final DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>> ELITE_MEK_APIARY_FACTORY =
			BLOCKS.register("elite_mek_apiary_factory",
					() -> new MekApiaryBlock<>(MekApiaryFactoryBlockType.ELITE_MEK_APIARY_FACTORY));

	/** 通用机械蜂箱 — 终极工厂版（20蜜蜂/18输出/1,024,000 FE） */
	public static final DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>> ULTIMATE_MEK_APIARY_FACTORY =
			BLOCKS.register("ultimate_mek_apiary_factory",
					() -> new MekApiaryBlock<>(MekApiaryFactoryBlockType.ULTIMATE_MEK_APIARY_FACTORY));

	/** 基础工厂 */
	public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> BASIC_MEK_CENTRIFUGE_FACTORY =
			BLOCKS.register("basic_mek_centrifuge_factory",
					() -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.BASIC_MEK_CENTRIFUGE_FACTORY));

	/** 高级工厂 */
	public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ADVANCED_MEK_CENTRIFUGE_FACTORY =
			BLOCKS.register("advanced_mek_centrifuge_factory",
					() -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ADVANCED_MEK_CENTRIFUGE_FACTORY));

	/** 精英工厂 */
	public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ELITE_MEK_CENTRIFUGE_FACTORY =
			BLOCKS.register("elite_mek_centrifuge_factory",
					() -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ELITE_MEK_CENTRIFUGE_FACTORY));

	/** 终极工厂 */
	public static final DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> ULTIMATE_MEK_CENTRIFUGE_FACTORY =
			BLOCKS.register("ultimate_mek_centrifuge_factory",
					() -> new MekCentrifugeBlock<>(MekCentrifugeBlockType.ULTIMATE_MEK_CENTRIFUGE_FACTORY));

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
	public static final Map<FactoryTier, DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>>> EM_FACTORIES = new ConcurrentHashMap<>();

	/**
	 * ME工厂方块映射 — 由registerMEFactories()在ME加载时填充
	 * <br/>
	 * Key=ExtraFactoryTier（ME独立枚举，运行时由 compat 包写入），Value=对应的DeferredBlock。
	 * 使用通配类型 {@code Map<Object, DeferredBlock<?>>} 避免主注册类编译期依赖 ME 的类
	 * （ExtraFactoryTier/ExtraMachine/TileEntityExtraMekCentrifugeFactory 等）。
	 * 实际的具体泛型类型在 compat 包的隔离类中通过强制转换恢复，仅在 ME 加载时被调用。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	public static final Map<Object, DeferredBlock<?>> ME_FACTORIES = new ConcurrentHashMap<>();

	/**
	 * EME工厂方块映射 — 由registerEMEFactories()在EME加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME 独立枚举，编译时不存在），Value=对应的DeferredBlock。
	 * 使用通配类型 {@code Object}/{@code DeferredBlock<?>}，避免主注册类编译期依赖 EME 类。
	 * 实际填充由 {@link EMECompatLoader#registerCentrifugeBlocks()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEBlockRegistration} 完成。
	 * 使用ConcurrentHashMap保证线程安全。
	 */
	public static final Map<Object, DeferredBlock<?>> EME_FACTORIES = new ConcurrentHashMap<>();

	/**
	 * ME 蜂箱工厂方块映射 — 由 registerMEApiaryFactories() 在 ME 加载时填充
	 * <br/>
	 * Key=ExtraFactoryTier（ME 独立枚举，运行时由 compat 包写入），Value=对应的 DeferredBlock。
	 * 使用通配类型 {@code Map<Object, DeferredBlock<?>>} 避免主注册类编译期依赖 ME 的类。
	 * 实际的具体泛型类型在 compat 包的隔离类中通过强制转换恢复。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredBlock<?>> ME_APIARY_FACTORIES = new ConcurrentHashMap<>();

	/**
	 * EM 蜂箱工厂方块映射 — 由 registerEMApiaryFactories() 在 EM 加载时填充
	 * <br/>
	 * Key=FactoryTier（EM 运行时扩展的枚举值），Value=对应的 DeferredBlock。
	 * EM 蜂箱工厂复用 BlockTypeTile 和 TileEntityMekApiaryFactory（与原版 4 等级相同），
	 * 因为 EM 通过 Mixin 扩展 FactoryTier 枚举，不使用独立的 TileEntity 类。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<FactoryTier, DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>>> EM_APIARY_FACTORIES = new ConcurrentHashMap<>();

	/**
	 * EME 蜂箱工厂方块映射 — 由 registerEMEApiaryFactories() 在 EME 加载时填充
	 * <br/>
	 * Key=EMExtraFactoryTier（EME 独立枚举，编译时不存在），Value=对应的 DeferredBlock。
	 * 使用通配类型 {@code Object}/{@code DeferredBlock<?>}，避免主注册类编译期依赖 EME 类。
	 * 实际填充由 {@link EMECompatLoader#registerApiaryBlocks()} 委托
	 * {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEBlockRegistration} 完成。
	 * 使用 ConcurrentHashMap 保证线程安全。
	 */
	public static final Map<Object, DeferredBlock<?>> EME_APIARY_FACTORIES = new ConcurrentHashMap<>();

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
			DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
				Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> deferredBlock =
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
	public static DeferredBlock<MekCentrifugeBlock<TileEntityMekCentrifugeFactory,
		Machine.FactoryMachine<TileEntityMekCentrifugeFactory>>> getEMFactoryBlock(FactoryTier tier) {
		return EM_FACTORIES.get(tier);
	}

	/**
	 * 注册ME等级的工厂方块
	 * <br/>
	 * 当MekanismExtras加载时，委托给 {@link MECompatLoader#registerFactories()} 执行实际注册。
	 * 注册名格式：{tier.getAdvanceTier().getLowerName()}_extra_mek_centrifuge_factory
	 * （如absolute_extra_mek_centrifuge_factory），与MekCentrifugeMEBlockType.getMEFactoryBlock()
	 * 中的命名约定保持一致，确保ExtraAttributeUpgradeable的DeferredHolder能正确解析。
	 * <p>
	 * 调用时机：必须在initMETiers()之后、BLOCKS.register(eventBus)之前调用。
	 * <p>
	 * 本方法仅保留运行时守卫与委托调用，避免主注册类编译期依赖 ME 的类
	 * （ExtraFactoryTier/ExtraMachine/TileEntityExtraMekCentrifugeFactory 等）。
	 * 具体注册逻辑在 compat 包的隔离类中实现，仅在 ME 加载时被加载和执行。
	 */
	public static void registerMEFactories() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerFactories();
		}
	}

	/**
	 * 获取ME等级工厂方块
	 *
	 * @param tier ME工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE），运行时类型为 ExtraFactoryTier
	 * @return 对应的DeferredBlock，不存在时返回null
	 */
	public static DeferredBlock<?> getMEFactoryBlock(Object tier) {
		return ME_FACTORIES.get(tier);
	}

	/**
	 * 注册EME等级的工厂方块
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerCentrifugeBlocks()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 {@link #EME_FACTORIES}（通配类型）。
	 * <p>
	 * 调用时机：必须在 {@code MekCentrifugeBlockType.initEMETiers()} 之后、BLOCKS.register(eventBus) 之前调用。
	 */
	public static void registerEMEFactories() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerCentrifugeBlocks();
	}

	/**
	 * 获取EME等级工厂方块
	 *
	 * @param tier EME工厂等级（运行时为 EMExtraFactoryTier，主注册类不依赖具体类型）
	 * @return 对应的 DeferredBlock（通配类型），不存在时返回 null
	 */
	public static DeferredBlock<?> getEMEFactoryBlock(Object tier) {
		return EME_FACTORIES.get(tier);
	}

	/**
	 * 注册 EM 等级的蜂箱工厂方块
	 * <br/>
	 * 当 EvolvedMekanism 加载时，遍历 5 个 EM FactoryTier（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE），
	 * 为每个 tier 注册一个 MekApiaryBlock。
	 * 注册名格式：{tier.getBaseTier().getLowerName()}_mek_apiary_factory
	 * （如 overclocked_mek_apiary_factory），与 MekApiaryFactoryBlockType.getEMApiaryFactoryBlock()
	 * 中的命名约定保持一致，确保 AttributeUpgradeable 的 DeferredHolder 能正确解析。
	 * <p>
	 * EM 蜂箱工厂复用 TileEntityMekApiaryFactory（不新建 TileEntity 类），
	 * 因为 EM 通过 Mixin 扩展 FactoryTier 枚举，不使用独立的 TileEntity 类型。
	 * <p>
	 * 调用时机：必须在 MekApiaryFactoryBlockType.initEMTiers() 之后、BLOCKS.register(eventBus) 之前调用。
	 */
	public static void registerEMApiaryFactories() {
		if (!MekCompatHooks.isEvolvedMekanismLoaded()) {
			return;
		}
		for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
			String registryName = tier.getBaseTier().getLowerName() + "_mek_apiary_factory";
			BlockTypeTile<TileEntityMekApiaryFactory> blockType = MekApiaryFactoryBlockType.getEMApiaryFactoryType(tier);
			if (blockType == null) {
				ProductiveBeesGenesis.LOGGER.warn("EM 蜂箱工厂 BlockType 未初始化，跳过方块注册: {}", tier.name());
				continue;
			}
			DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory, BlockTypeTile<TileEntityMekApiaryFactory>>> deferredBlock =
					BLOCKS.register(registryName, () -> new MekApiaryBlock<>(blockType));
			EM_APIARY_FACTORIES.put(tier, deferredBlock);
		}
	}

	/**
	 * 获取 EM 等级蜂箱工厂方块
	 *
	 * @param tier EM 工厂等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
	 * @return 对应的 DeferredBlock，不存在时返回 null
	 */
	public static DeferredBlock<MekApiaryBlock<TileEntityMekApiaryFactory,
		BlockTypeTile<TileEntityMekApiaryFactory>>> getEMApiaryFactoryBlock(FactoryTier tier) {
		return EM_APIARY_FACTORIES.get(tier);
	}

	/**
	 * 注册 ME 等级的蜂箱工厂方块
	 * <br/>
	 * 当 MekanismExtras 加载时，委托给 {@link MECompatLoader#registerApiaryFactories()} 执行实际注册。
	 * 注册名格式：{tier.getAdvanceTier().getLowerName()}_extra_mek_apiary_factory
	 * （如 absolute_extra_mek_apiary_factory），与 MekApiaryMEBlockType.getMEApiaryFactoryBlock()
	 * 中的命名约定保持一致，确保 ExtraAttributeUpgradeable 的 DeferredHolder 能正确解析。
	 * <p>
	 * 调用时机：必须在 MekApiaryMEBlockType.initMETiers() 之后、BLOCKS.register(eventBus) 之前调用。
	 * <p>
	 * 本方法仅保留运行时守卫与委托调用，避免主注册类编译期依赖 ME 的类。
	 */
	public static void registerMEApiaryFactories() {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MECompatLoader.registerApiaryFactories();
		}
	}

	/**
	 * 获取 ME 等级蜂箱工厂方块
	 *
	 * @param tier ME 工厂等级（ABSOLUTE/SUPREME/COSMIC/INFINITE），运行时类型为 ExtraFactoryTier
	 * @return 对应的 DeferredBlock，不存在时返回 null
	 */
	public static DeferredBlock<?> getMEApiaryFactoryBlock(Object tier) {
		return ME_APIARY_FACTORIES.get(tier);
	}

	/**
	 * 注册 EME 等级的蜂箱工厂方块
	 * <br/>
	 * 当 EvolvedMekanismExtras 加载时，委托 {@link EMECompatLoader#registerApiaryBlocks()}
	 * 完成实际注册（避免主注册类编译期依赖 EME 类）。
	 * 注册结果填充到 {@link #EME_APIARY_FACTORIES}（通配类型）。
	 * <p>
	 * 调用时机：必须在 {@code MekApiaryEMEBlockType.initEMETiers(...)} 之后、BLOCKS.register(eventBus) 之前调用。
	 */
	public static void registerEMEApiaryFactories() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		EMECompatLoader.registerApiaryBlocks();
	}

	/**
	 * 获取 EME 等级蜂箱工厂方块
	 *
	 * @param tier EME 工厂等级（运行时为 EMExtraFactoryTier，主注册类不依赖具体类型）
	 * @return 对应的 DeferredBlock（通配类型），不存在时返回 null
	 */
	public static DeferredBlock<?> getEMEApiaryFactoryBlock(Object tier) {
		return EME_APIARY_FACTORIES.get(tier);
	}
}
