package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.emextras.MekCentrifugeEMEBlockType;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MekCentrifugeMEBlockType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
	 * ME/EME扩展：ME和EME为可选依赖，相关代码已拆分到独立的MekCentrifugeMEBlockType和
	 * MekCentrifugeEMEBlockType类中，避免未安装时触发NoClassDefFoundError。本类仅保留
	 * initMETiers()/initEMETiers()包装方法，先检测模组加载状态再委托给独立类。
	 */
public final class MekCentrifugeBlockType {

	/** 基础MEK离心机BlockType — 不设置AttributeTier，使Basic Tier Installer能正确升级（fromTier=null匹配） */
	public static final BlockTypeTile<TileEntityMekCentrifuge> MEK_CENTRIFUGE = Machine.MachineBuilder
			.createMachine(() -> ModBlockEntitiesHolder.MEK_CENTRIFUGE, descriptionLang("mek_centrifuge"))
			.withEnergyConfig(() -> ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue(),
					() -> ModConfig.SERVER.mekCentrifugeEnergyStorage.get())
			.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
			.with(Attributes.SECURITY)
			.withGui(() -> ModMenuTypes.MEK_CENTRIFUGE)
			.withSound(MekanismSounds.ENERGIZED_SMELTER)
			.with(new AttributeUpgradeable(wrapAsBlockRegistryObject(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY)))
			// 替换默认升级支持：MEKExtras加载时额外支持STACK/CREATIVE，始终支持SPEED/ENERGY/MUFFLING
			.with(MekUpgradeSupport.forMachine())
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
				// Energy: 与Mekanism原版工厂一致，usage/storage 从 config 读取（不随等级变化）
				// 原版Mekanism工厂所有等级（BASIC/ADVANCED/ELITE/ULTIMATE）使用相同的20000L存储
				// EM等级也遵循此规则（EM的FactoryMixin只调整energySlot位置，不修改容量）
				// ME/EME等级才乘以processes（遵循ME的ExtraFactory.setMachineData模式）
				.withEnergyConfig(() -> ModConfig.SERVER.mekCentrifugeEnergyPerTick.get().longValue(),
						() -> ModConfig.SERVER.mekCentrifugeEnergyStorage.get())
				.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
				.with(Attributes.SECURITY)
				.withGui(() -> ModMenuTypes.MEK_CENTRIFUGE_FACTORY)
				.withSound(MekanismSounds.ENERGIZED_SMELTER)
				.with(new AttributeTier<>(tier));

		// 移除FactoryMachine构造器添加的原版AttributeUpgradeable
		builder.without(AttributeUpgradeable.class);
		// 添加自定义AttributeUpgradeable，指向下一等级的离心机工厂
		// CREATIVE是最高级，getNextTierBlock返回null，不添加（避免自指导致ItemMaxTierInstaller死循环）
		DeferredHolder<Block, ?> nextTierBlock = getNextTierBlock(tier);
		if (nextTierBlock != null) {
			// 使用非匿名实例确保getClass()=AttributeUpgradeable.class
			builder.with(new AttributeUpgradeable(wrapAsBlockRegistryObject(nextTierBlock)));
		}
		// 替换默认升级支持：MEKExtras加载时额外支持STACK/CREATIVE，始终支持SPEED/ENERGY/MUFFLING
		builder.with(MekUpgradeSupport.forMachine());

		return builder.build();
	}

	/**
	 * 获取下一等级工厂的DeferredBlock
	 * <br/>
	 * 原版4等级走固定映射；EM加载时ULTIMATE指向OVERCLOCKED（EM优先），
	 * 仅ME加载时ULTIMATE指向ABSOLUTE；EM等级走getEMNextTierBlock。
	 * 必须有default分支：EM通过Mixin在运行时扩展FactoryTier枚举。
	 * <p>
	 * 修复 v14 ULTIMATE 自指死循环：当 EM 和 ME 都未加载时，ULTIMATE 是最高级，
	 * 返回 null 表示无下一级（createFactoryBlockType 会跳过添加 AttributeUpgradeable）。
	 * 原实现返回 ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY（自指），
	 * 导致 ItemMaxTierInstaller 的 while 循环无法终止，游戏卡死。
	 */
	private static DeferredHolder<Block, ?> getNextTierBlock(FactoryTier currentTier) {
		return switch (currentTier) {
			case BASIC -> ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY;
			case ADVANCED -> ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY;
			case ELITE -> ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY;
			// EM优先于ME：AttributeUpgradeable供EM/Mekanism installer使用，必须指向EM链的OVERCLOCKED；
			// ME链通过initMETiers()添加的ExtraAttributeUpgradeable实现（ME installer使用ExtraAttributeUpgradeable）
			// 修复 v14: EM 和 ME 都未加载时返回 null，避免自指导致 ItemMaxTierInstaller 死循环
			case ULTIMATE -> MekCompatHooks.isEvolvedMekanismLoaded()
					? getEMFactoryBlock("overclocked")
					: MekCompatHooks.isMekanismExtrasLoaded()
					? getMEFactoryBlock("absolute")
					: null;
			// EM运行时扩展的等级（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）
			default -> getEMNextTierBlock(currentTier);
		};
	}

	/**
	 * 获取EM等级的下一级方块Holder
	 * <br/>
	 * EM等级在编译时不存在，通过name()字符串匹配确定当前等级，返回下一级的DeferredHolder。
	 * CREATIVE是最高级，返回null表示无下一级（与EME的INFINITE_MULTIVERSAL一致，不添加升级属性，
	 * 避免AttributeUpgradeable自指导致ItemMaxTierInstaller的while循环死循环）。
	 */
	private static DeferredHolder<Block, ?> getEMNextTierBlock(FactoryTier currentTier) {
		String name = currentTier.name();
		return switch (name) {
			case "OVERCLOCKED" -> getEMFactoryBlock("quantum");
			case "QUANTUM" -> getEMFactoryBlock("dense");
			case "DENSE" -> getEMFactoryBlock("multiversal");
			case "MULTIVERSAL" -> getEMFactoryBlock("creative");
			// CREATIVE是最高级，返回null（createFactoryBlockType会跳过添加AttributeUpgradeable）
			default -> null;
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
	public static DeferredHolder<Block, ?> getMEFactoryBlock(String tierName) {
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
	public static DeferredHolder<Block, ?> getEMEFactoryBlock(String tierName) {
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
	public static Supplier<BlockRegistryObject<?, ?>> wrapAsBlockRegistryObject(DeferredHolder<Block, ?> blockHolder) {
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
	private static mekanism.common.registration.impl.TileEntityTypeRegistryObject
			<TileEntityMekCentrifugeFactory> getFactoryTileEntityType(
		FactoryTier tier
	) {
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
	public static mekanism.api.text.ILangEntry descriptionLang(String key) {
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
	 * 初始化ME工厂等级的BlockType — 包装方法
	 * <br/>
	 * 先检测ME加载状态，再委托给MekCentrifugeMEBlockType。ME未加载时直接返回，
	 * 避免触发对ME类的加载导致NoClassDefFoundError。
	 */
	public static void initMETiers() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		MekCentrifugeMEBlockType.initMETiers(ULTIMATE_MEK_CENTRIFUGE_FACTORY);
	}

	/**
	 * 初始化EME工厂等级的BlockType — 包装方法
	 * <br/>
	 * 先检测EME加载状态，再委托给MekCentrifugeEMEBlockType。EME未加载时直接返回，
	 * 避免触发对EME类的加载导致NoClassDefFoundError。
	 * <p>
	 * 修复 v15 软依赖隔离：原实现直接引用 {@code ExtraFactoryTier.ABSOLUTE}，
	 * 虽然方法体在守卫之后执行（惰性解析），但 import 语句和直接引用违反软依赖隔离原则。
	 * 现通过 {@link MekCentrifugeMEBlockType#getAbsoluteFactoryType()} 封装，
	 * 本类完全不再引用 ME 类，符合"软依赖完全隔离"规范。
	 */
	public static void initEMETiers() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		// EME依赖ME，所以ME此时已加载；通过封装方法获取 ABSOLUTE BlockType，避免直接引用 ExtraFactoryTier
		MekCentrifugeEMEBlockType.initEMETiers(ULTIMATE_MEK_CENTRIFUGE_FACTORY,
				MekCentrifugeMEBlockType.getAbsoluteFactoryType());
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
		public static mekanism.common.registration.impl.TileEntityTypeRegistryObject
				<TileEntityMekCentrifugeFactory> BASIC_MEK_CENTRIFUGE_FACTORY;
		public static mekanism.common.registration.impl.TileEntityTypeRegistryObject
				<TileEntityMekCentrifugeFactory> ADVANCED_MEK_CENTRIFUGE_FACTORY;
		public static mekanism.common.registration.impl.TileEntityTypeRegistryObject
				<TileEntityMekCentrifugeFactory> ELITE_MEK_CENTRIFUGE_FACTORY;
		public static mekanism.common.registration.impl.TileEntityTypeRegistryObject
				<TileEntityMekCentrifugeFactory> ULTIMATE_MEK_CENTRIFUGE_FACTORY;

		/**
		 * EM工厂TileEntityType映射 — 由ModBlockEntities在EM加载时填充
		 * <br/>
		 * Key=FactoryTier（EM运行时扩展的枚举值），Value=对应的TileEntityTypeRegistryObject。
		 * 使用ConcurrentHashMap保证线程安全（填充与BlockType查询可能并发）。
		 */
		public static final Map<FactoryTier,
				mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekCentrifugeFactory>> EM_FACTORY_TILES =
				new ConcurrentHashMap<>();
	}
}
