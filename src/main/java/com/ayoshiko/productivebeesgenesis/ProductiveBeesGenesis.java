package com.ayoshiko.productivebeesgenesis;

import java.util.concurrent.atomic.AtomicLong;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.datagen.ModBlockTagsProvider;
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
import com.ayoshiko.productivebeesgenesis.util.RecipeReloadRetryManager;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
	 * 使用 {@link AtomicLong} 保证原子递增：虽然 TagsUpdatedEvent 在主线程触发，
	 * 但部分模组或自定义集成可能在异步上下文触发重载事件。AtomicLong 防御性地保证
	 * 递增操作在所有场景下都是原子的，避免丢失更新。
	 */
	public static final AtomicLong recipeVersion = new AtomicLong(0L);

	public ProductiveBeesGenesis(IEventBus eventBus, ModContainer modContainer) {
		LOGGER.info("资源蜜蜂：创世模组初始化中...");

		// 初始化 Mek 离心机扩展（EM/ME/EME 三层工厂）— 必须在 DeferredRegister.register() 之前
		initMekCentrifugeExtensions();

		// 注册 DeferredRegister 到 mod 事件总线
		registerDeferredRegisters(eventBus);

		// 注册配置文件
		registerConfigs(modContainer);

		// 注册配置加载/重载监听器（跨字段校验 + 蜜蜂属性覆盖 + 缓存失效）
		registerConfigListeners(eventBus);

		// 注册 mod 事件总线监听器（FML 生命周期）
		registerModEventBusListeners(eventBus);

		// 注册 NeoForge 事件总线监听器（运行时事件）
		registerNeoForgeEventBusListeners();

		LOGGER.info("资源蜜蜂：创世模组初始化完成");
	}

	/**
	 * 初始化 Mek 离心机扩展（EM/ME/EME 三层工厂）
	 * <br/>
	 * 必须在 {@link #registerDeferredRegisters(IEventBus)} 之前完成所有动态注册，
	 * 因为 registerXxxFactories 等方法会动态向 ModBlocks.BLOCKS 等 DeferredRegister 添加条目，
	 * 必须在 BLOCKS.register(eventBus) 之前完成。
	 * <p>
	 * 顺序依赖（每层）：
	 * initXxxTiers(创建BlockType，使用懒加载Supplier) → registerXxxFactories(需要BlockType)
	 * → registerXxxFactoryTiles(需要DeferredBlock) → registerXxxFactoryItems(需要DeferredBlock)
	 */
	private void initMekCentrifugeExtensions() {
		// EM 扩展
		MekCentrifugeBlockType.initEMTiers();
		ModBlocks.registerEMFactories();
		ModBlockEntities.registerEMFactoryTiles();
		ModItems.registerEMFactoryItems();

		// ME 扩展（initMETiers 为 ULTIMATE 添加 ExtraAttributeUpgradeable）
		MekCentrifugeBlockType.initMETiers();
		ModBlocks.registerMEFactories();
		ModBlockEntities.registerMEFactoryTiles();
		ModItems.registerMEFactoryItems();

		// EME 扩展（initEMETiers 为 ULTIMATE/ME ABSOLUTE 添加 EMExtraAttributeUpgradeable）
		MekCentrifugeBlockType.initEMETiers();
		ModBlocks.registerEMEFactories();
		ModBlockEntities.registerEMEFactoryTiles();
		ModItems.registerEMEFactoryItems();
	}

	/**
	 * 注册 DeferredRegister 到 mod 事件总线
	 */
	private void registerDeferredRegisters(IEventBus eventBus) {
		ModBlocks.BLOCKS.register(eventBus);
		ModBlockEntities.register(eventBus);
		ModItems.ITEMS.register(eventBus);
		ModCreativeTabs.CREATIVE_MODE_TABS.register(eventBus);
		ModStats.register(eventBus);
		ModMenuTypes.register(eventBus);
	}

	/**
	 * 注册配置文件（CLIENT / COMMON / SERVER）
	 */
	private void registerConfigs(ModContainer modContainer) {
		modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
		modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
		modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
	}

	/**
	 * 注册配置加载/重载监听器
	 * <br/>
	 * 服务端配置加载/重载时：
	 * <ol>
	 *   <li>跨字段联合校验并自动修正无效组合（Task 13）</li>
	 *   <li>应用蜜蜂属性覆盖（按存档生效）</li>
	 *   <li>重载时额外失效万象创世过滤缓存（Task 15）</li>
	 * </ol>
	 */
	private void registerConfigListeners(IEventBus eventBus) {
		eventBus.addListener((ModConfigEvent.Loading event) -> {
			if (event.getConfig().getSpec() == ModConfig.SERVER_SPEC) {
				if (ModConfig.validateAndFixCrossFields()) {
					ModConfig.SERVER_SPEC.save();
				}
				BeeConfigApplier.applyOverrides();
			}
		});
		eventBus.addListener((ModConfigEvent.Reloading event) -> {
			if (event.getConfig().getSpec() == ModConfig.SERVER_SPEC) {
				if (ModConfig.validateAndFixCrossFields()) {
					ModConfig.SERVER_SPEC.save();
				}
				BeeConfigApplier.applyOverrides();
				MyriadCreationsEventHandler.invalidateFilterCache();
			}
		});
	}

	/**
	 * 注册 mod 事件总线监听器（FML 生命周期事件）
	 */
	private void registerModEventBusListeners(IEventBus eventBus) {
		eventBus.addListener(this::onCommonSetup);
		// 注册 MEK 离心机的 Capability（安全、能量等）— 使 tooltip 能正确显示拥有者/安全等级/储能
		eventBus.addListener(this::onRegisterCapabilities);
		// 注册数据生成器
		eventBus.addListener(this::gatherData);
	}

	/**
	 * 注册 NeoForge 事件总线监听器（游戏运行时事件）
	 */
	private void registerNeoForgeEventBusListeners() {
		// 监听数据重载事件（/reload、数据包变更、服务器启动）— 递增 recipeVersion，
		// 通知所有 PB 配方处理器清空 SMELTING/PB 配方缓存。
		// TagsUpdatedEvent 在所有 reload listener（含配方重载）完成后触发，是重置缓存的可靠信号。
		NeoForge.EVENT_BUS.addListener(this::onTagsReload);
		// 注册蜜蜂配方重载器 — 在 RecipeManager 加载完成后动态修改 PB 的 bee_fishing/bee_breeding/bee_spawning/bee_conversion 配方
		NeoForge.EVENT_BUS.addListener(this::onAddReloadListener);
		// 注册配方重载器的延迟重试 tick 处理器 — 处理首次进入世界时配置未加载的情况
		NeoForge.EVENT_BUS.addListener(BeeRecipeReloader::onServerTick);
		// 服务器停止时清理静态缓存，防止跨存档数据泄漏
		NeoForge.EVENT_BUS.addListener(this::onServerStopped);
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
		long newVersion = recipeVersion.incrementAndGet();
		BeeInfoHelper.invalidateCache();
		// 重建离心配方索引（仅服务端，客户端无 ServerLevel）
		var server = ServerLifecycleHooks.getCurrentServer();
		if (server != null) {
			CentrifugeRecipeIndex.rebuild(server.overworld());
		}
		LOGGER.info("配方/标签重载完成，recipeVersion 递增至 {}", newVersion);
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

	/**
	 * 服务器停止回调
	 * <br/>
	 * 清理静态缓存防止跨存档数据泄漏：
	 * <ul>
	 *   <li>{@link CentrifugeRecipeIndex} — 离心配方索引（持有 ServerLevel 引用）</li>
	 *   <li>{@link BeeInfoHelper} — 蜜蜂类型缓存和配方索引（可能持有过期配方引用）</li>
	 *   <li>{@link MyriadCreationsEventHandler} — 万象创世类型缓存与模板数组</li>
	 *   <li>{@link RecipeReloadRetryManager} — 延迟重试上下文</li>
	 * </ul>
	 * 这些静态缓存在服务器停止后不再有用，主动清理可防止：
	 * 1) 旧存档的数据泄漏到新存档（例如配方/蜜蜂类型列表）
	 * 2) 持有的 ServerLevel/RecipeManager 引用阻碍 GC
	 */
	private void onServerStopped(ServerStoppedEvent event) {
		// 异常隔离：每个清理操作独立 try-catch，单个失败不中断后续清理，防止跨存档泄漏
		safeClear(CentrifugeRecipeIndex::clear, "CentrifugeRecipeIndex");
		safeClear(BeeInfoHelper::invalidateCache, "BeeInfoHelper");
		safeClear(MyriadCreationsEventHandler::clearAllCaches, "MyriadCreationsEventHandler");
		// 清理 BeeRecipeReloader 延迟重试上下文 — 防止持有的 RecipeManager / HolderLookup.Provider 引用阻碍 GC
		safeClear(RecipeReloadRetryManager::clearPendingRetryContext, "RecipeReloadRetryManager");
		// 清理 AbstractCombEventHandler 的 ThreadLocal — 防止线程池复用场景下引用残留
		safeClear(AbstractCombEventHandler::clearThreadLocals, "AbstractCombEventHandler.ThreadLocals");
	}

	/**
	 * 安全清理包装 — 单个清理操作失败不影响其他清理
	 *
	 * @param action 清理操作
	 * @param name   清理目标名称（用于日志）
	 */
	private void safeClear(Runnable action, String name) {
		try {
			action.run();
		} catch (Exception e) {
			LOGGER.error("清理 {} 时发生异常", name, e);
		}
	}

	private void onCommonSetup(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			checkProductiveBeesCompatibility();
			ModStats.init();
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
		// 方块标签（镐/锄挖掘工具）
		generator.addProvider(event.includeServer(), new ModBlockTagsProvider(packOutput, lookupProvider, event.getExistingFileHelper()));
		// 语言文件：主 lang（src/main/resources）已包含全部键（GUI + configuration + config.*），
		// 不再通过 ModLanguageProvider 生成，避免 generated lang 与主 lang 键重叠触发 DuplicatesStrategy.EXCLUDE。
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
