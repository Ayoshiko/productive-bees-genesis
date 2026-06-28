package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.datagen.ModLanguageProvider;
import com.ayoshiko.productivebeesgenesis.datagen.ModLootTables;
import com.ayoshiko.productivebeesgenesis.datagen.ModRecipes;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModCreativeTabs;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.init.ModStats;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeRecipeReloader;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;
import mekanism.common.capabilities.ICapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 资源蜜蜂：创世模组主类
 * <br/>
 * 为资源蜜蜂模组添加万象创世蜜蜂，可产出所有其他蜜蜂的蜜脾
 * 通过Mixin注入原版离心机实现随机蜜脾产出
 */
@Mod(ProductiveBeesGenesis.MOD_ID)
public final class ProductiveBeesGenesis {
	public static final String MOD_ID = "productivebeesgenesis";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String PRODUCTIVE_BEES_MOD_ID = "productivebees";

	/**
	 * 配方版本号 — 每次 /reload 或数据包重载时递增
	 * <br/>
	 * 用于通知所有 PB 配方处理器（基础离心机和工厂版）清空 SMELTING/PB 配方缓存，
	 * 避免重载后仍使用旧的缓存结果（例如新增/删除配方后缓存未失效导致输入被错误处理）。
	 * <p>
	 * 使用 volatile 保证可见性：主线程（重载事件）写入后，方块实体线程（服务端tick）能立即读到新值。
	 * 写入操作原子（long 在64位JVM上单次写入原子），递增操作在事件回调中单线程执行，无需 AtomicLong。
	 */
	public static volatile long recipeVersion = 0L;

	public ProductiveBeesGenesis(IEventBus eventBus, ModContainer modContainer) {
		LOGGER.info("资源蜜蜂：创世模组初始化中...");

		// EM扩展初始化 — 必须在DeferredRegister.register()之前完成所有动态注册
		// 顺序依赖：initEMTiers(创建BlockType，使用懒加载Supplier) → registerEMFactories(需要BlockType)
		// → registerEMFactoryTiles(需要DeferredBlock) → registerEMFactoryItems(需要DeferredBlock)
		MekCentrifugeBlockType.initEMTiers();
		ModBlocks.registerEMFactories();
		ModBlockEntities.registerEMFactoryTiles();
		ModItems.registerEMFactoryItems();

		// ME扩展初始化 — 必须在DeferredRegister.register()之前完成所有动态注册
		// 顺序依赖：initMETiers(创建BlockType+为ULTIMATE添加ExtraAttributeUpgradeable) → registerMEFactories(需要BlockType)
		// → registerMEFactoryTiles(需要DeferredBlock) → registerMEFactoryItems(需要DeferredBlock)
		MekCentrifugeBlockType.initMETiers();
		ModBlocks.registerMEFactories();
		ModBlockEntities.registerMEFactoryTiles();
		ModItems.registerMEFactoryItems();

		// EME扩展初始化 — 必须在DeferredRegister.register()之前完成所有动态注册
		// 顺序依赖：initEMETiers(创建BlockType+为ULTIMATE/ME ABSOLUTE添加EMExtraAttributeUpgradeable) → registerEMEFactories(需要BlockType)
		// → registerEMEFactoryTiles(需要DeferredBlock) → registerEMEFactoryItems(需要DeferredBlock)
		MekCentrifugeBlockType.initEMETiers();
		ModBlocks.registerEMEFactories();
		ModBlockEntities.registerEMEFactoryTiles();
		ModItems.registerEMEFactoryItems();

		// 注册DeferredRegister到事件总线
        ModBlocks.BLOCKS.register(eventBus);
        ModBlockEntities.register(eventBus);
        ModItems.ITEMS.register(eventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(eventBus);
        ModStats.register(eventBus);
        ModMenuTypes.register(eventBus);

		// 注册配置文件
		modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
		modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
		modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

		// 配置文件加载/重载时重新应用蜜蜂属性覆盖
		eventBus.addListener((ModConfigEvent.Loading event) -> {
			if (event.getConfig().getSpec() == ModConfig.CLIENT_SPEC) {
				BeeConfigApplier.applyOverrides();
			}
		});
		eventBus.addListener((ModConfigEvent.Reloading event) -> {
			if (event.getConfig().getSpec() == ModConfig.CLIENT_SPEC) {
				BeeConfigApplier.applyOverrides();
			}
		});

		eventBus.addListener(this::onCommonSetup);

		// 注册MEK离心机的Capability（安全、能量等）— 使tooltip能正确显示拥有者/安全等级/储能
		eventBus.addListener(this::onRegisterCapabilities);

		// 注册数据生成器
		eventBus.addListener(this::gatherData);

		// 监听数据重载事件（/reload、数据包变更、服务器启动）— 递增 recipeVersion，
		// 通知所有 PB 配方处理器（基础离心机和工厂版）清空 SMELTING/PB 配方缓存，
		// 避免重载后仍使用旧的缓存结果。
		// 使用 TagsUpdatedEvent：它在所有 reload listener（含配方重载）完成后触发，
		// 是 Mekanism 等模组用于重置缓存的可靠信号。
		NeoForge.EVENT_BUS.addListener(this::onTagsReload);

		// 注册蜜蜂配方重载器 — 在 RecipeManager 加载完成后根据 ModConfig
		// 动态修改 PB 的 bee_fishing/bee_breeding/bee_spawning 配方
		NeoForge.EVENT_BUS.addListener(this::onAddReloadListener);

		LOGGER.info("资源蜜蜂：创世模组初始化完成");
	}

	/**
	 * 标签/配方重载完成回调 — 递增配方版本号、失效缓存、重建离心配方索引
	 * <br/>
	 * TagsUpdatedEvent 在所有 reload listener（包括 RecipeManager 和标签重载）完成后触发，
	 * 无论是 /reload 命令、数据包变更还是服务器启动都会触发。
	 * 递增 recipeVersion 使所有 PbRecipeProcessor 和 TileEntityMekCentrifuge 在下次 tick 时
	 * 检测到版本号变化，从而清空 SMELTING/PB 配方缓存，确保使用最新的配方数据。
	 * 同时失效 BeeInfoHelper 的蜜蜂类型缓存，使下次 GUI 查询返回最新注册的蜜蜂类型。
	 * <p>
	 * 重建 {@link CentrifugeRecipeIndex} 离心配方索引，使 findPbRecipe 和 createCombBlockRecipe
	 * 的 O(1) 索引查找使用最新的配方数据。仅服务端重建（客户端无离心配方处理）。
	 */
	private void onTagsReload(TagsUpdatedEvent event) {
		recipeVersion++;
		BeeInfoHelper.invalidateCache();
		// 重建离心配方索引（仅服务端，客户端无 ServerLevel）
		var server = ServerLifecycleHooks.getCurrentServer();
		if (server != null) {
			CentrifugeRecipeIndex.rebuild(server.overworld());
		}
		LOGGER.debug("配方/标签重载完成，recipeVersion 递增至 {}", recipeVersion);
	}

	/**
	 * 注册蜜蜂配方重载器
	 * <br/>
	 * AddReloadListenerEvent 在 RecipeManager 完成数据包加载后触发，
	 * 自定义监听器在所有内置监听器之后执行，此时配方已就绪可被替换。
	 */
	private void onAddReloadListener(AddReloadListenerEvent event) {
		event.addListener(new BeeRecipeReloader(
				event.getServerResources().getRecipeManager(),
				event.getRegistryAccess()
		));
	}

	private void onCommonSetup(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			checkProductiveBeesCompatibility();
			ModStats.init();
			if (ModConfig.COMMON.enablePerformanceMonitor.get()) {
				PerformanceMonitor.getInstance().registerJMX();
			}
		});
	}

	/** 注册数据生成器 */
	private void gatherData(GatherDataEvent event) {
		var generator = event.getGenerator();
		var packOutput = generator.getPackOutput();
		var lookupProvider = event.getLookupProvider();

		// 配方
		generator.addProvider(event.includeServer(), new ModRecipes(packOutput, lookupProvider));
		// 战利品表
		generator.addProvider(event.includeServer(), ModLootTables.create(packOutput, lookupProvider));
		// 语言文件
		generator.addProvider(event.includeClient(), new ModLanguageProvider(packOutput, "en_us"));
		generator.addProvider(event.includeClient(), new ModLanguageProvider(packOutput, "zh_cn"));
	}

	/**
	 * 注册MEK离心机物品的Capability
	 * <br/>
	 * 原理：ItemBlockTooltip实现了ICapabilityAware接口，需要通过RegisterCapabilitiesEvent
	 * 注册安全Capability（拥有者/安全等级tooltip）和能量Capability（储能tooltip）。
	 * Mekanism原版在Mekanism主类中遍历自己的物品注册表调用addCapabilities，
	 * 我们需要对自己的物品做同样的事。
	 */
	private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof ICapabilityAware aware) {
				aware.attachCapabilities(event);
			}
		}
	}

	private static void checkProductiveBeesCompatibility() {
		try {
			if (!net.neoforged.fml.ModList.get().isLoaded(PRODUCTIVE_BEES_MOD_ID)) {
				LOGGER.error("未检测到资源蜜蜂模组 (Productive Bees)，模组无法正常工作！");
				return;
			}
			LOGGER.info("资源蜜蜂模组兼容性检查通过");
		} catch (Exception e) {
			LOGGER.warn("检查资源蜜蜂模组兼容性时发生错误", e);
		}
	}
}
