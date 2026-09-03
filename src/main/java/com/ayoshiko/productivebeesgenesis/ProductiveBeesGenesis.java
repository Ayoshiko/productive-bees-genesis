package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.apiary.BeeProduceProcessor;
import com.ayoshiko.productivebeesgenesis.command.DevModeCommand;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ClientConfigMigrationService;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.ServerConfigMigrationService;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModCreativeTabs;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.init.ModStats;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.mek.MyriadBatchPlanner;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeCompleter;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2CapabilityRegistrar;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.network.DevModeStateSyncPacket;
import com.ayoshiko.productivebeesgenesis.network.ModPayloads;
import com.ayoshiko.productivebeesgenesis.network.PayloadRateLimiter;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.BeeRecipeReloader;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RecipeReloadRetryManager;
import com.ayoshiko.productivebeesgenesis.util.SingleIngredientCraftingIndex;
import mekanism.common.attachments.IAttachmentAware;
import mekanism.common.capabilities.ICapabilityAware;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

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
	private static final LevelResource SERVER_CONFIG_DIRECTORY = new LevelResource("serverconfig");

	/** 配方版本号 — 每次 /reload 或数据包重载时递增,通知所有 PB 配方处理器清空缓存。AtomicLong 保证原子递增。 */
	public static final AtomicLong RECIPE_VERSION = new AtomicLong(0L);
	private boolean serverConfigsInitialized;

	public ProductiveBeesGenesis(IEventBus eventBus, ModContainer modContainer) {
		LOGGER.info("资源蜜蜂：创世模组初始化中...");

		// 初始化 Mek 离心机扩展（EM/ME/EME 三层工厂）— 必须在 DeferredRegister.register() 之前
		initMekCentrifugeExtensions();

		// 初始化 Mek 蜂箱扩展（ME/EME 两层工厂蜂箱）— 必须在 DeferredRegister.register() 之前
		initMekApiaryExtensions();

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
	 * 初始化 Mek 离心机扩展（EM/ME/EME 三层工厂）— 必须在 registerDeferredRegisters 之前完成。
	 */
	private void initMekCentrifugeExtensions() {
		MekCompatInitializer.initMekCentrifugeExtensions();
	}

	/**
	 * 初始化 Mek 蜂箱扩展（ME/EME 两层工厂蜂箱）— 必须在 registerDeferredRegisters 之前完成。
	 */
	private void initMekApiaryExtensions() {
		MekCompatInitializer.initMekApiaryExtensions();
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
	 * 注册配置文件（CLIENT / COMMON / 三个 SERVER 领域文件）
	 */
	private void registerConfigs(ModContainer modContainer) {
		modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
		modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
		modContainer.registerConfig(
				Type.SERVER, ModConfig.GAMEPLAY_SERVER_SPEC, ModConfig.GAMEPLAY_SERVER_FILE_NAME);
		modContainer.registerConfig(
				Type.SERVER, ModConfig.MACHINES_SERVER_SPEC, ModConfig.MACHINES_SERVER_FILE_NAME);
		modContainer.registerConfig(
				Type.SERVER, ModConfig.CAPACITIES_SERVER_SPEC, ModConfig.CAPACITIES_SERVER_FILE_NAME);
	}

	/**
	 * 注册配置加载/重载监听器
	 * <br/>
	 * 服务端配置加载/重载时：
	 * <ol>
	 *   <li>跨字段联合校验并自动修正无效组合（Task 13）</li>
	 *   <li>应用蜜蜂属性覆盖（按存档生效）</li>
	 *   <li>重载时额外失效万象创世过滤缓存（Task 15）</li>
	 *   <li>重载时失效蜂箱槽位上限缓存（Task 3 — stackMultiplier 依赖配置，需主动失效）</li>
	 * </ol>
	 */
	private void registerConfigListeners(IEventBus eventBus) {
		eventBus.addListener((ModConfigEvent.Loading event) -> {
			if (isOwnClientConfig(event.getConfig())) {
				ClientConfigMigrationService.onConfigLoading(event.getConfig());
			}
			if (isOwnServerConfig(event.getConfig())) {
				ServerConfigMigrationService.onConfigLoading(event.getConfig());
				initializeServerConfigs();
			}
		});
		eventBus.addListener((ModConfigEvent.Reloading event) -> {
			if (isOwnServerConfig(event.getConfig()) && ModConfig.areServerSpecsLoaded()) {
				boolean changed = ModConfig.validateAndFixCrossFields();
				changed |= BalanceConfig.refresh(true);
				if (changed) {
					ModConfig.saveServerSpecs();
				}
				BeeConfigApplier.applyOverrides();
				MekCentrifugeFactoryHelper.refreshSmeltingCompatConfig();
				// The master recipe-mode switch can invalidate already cached Mekanism recipes.
				mekanism.common.CommonWorldTickHandler.flushTagAndRecipeCaches = true;
				MyriadCreationsEventHandler.invalidateFilterCache();
				// 同步万象创世启用状态缓存（避免每 tick 32 次 volatile read 配置查询）
				MyriadCreationsEventHandler.invalidateEnabledCache();
				// 工厂倍率快照只在 Loading 构建，Reloading 不替换；修改后仍需重启游戏生效。
			}
			// 同步到客户端的配置是内存对象，没有本地路径；重试检测只需要 mod id。
			RecipeReloadRetryManager.onConfigChanged(event.getConfig().getModId());
		});
	}

	private static boolean isOwnClientConfig(net.neoforged.fml.config.ModConfig config) {
		return config != null
				&& MOD_ID.equals(config.getModId())
				&& config.getType() == Type.CLIENT
				&& config.getSpec() == ModConfig.CLIENT_SPEC;
	}

	/** 三个服务端规格全部就绪后只初始化一次运行时配置快照。 */
	private synchronized void initializeServerConfigs() {
		if (serverConfigsInitialized || !ModConfig.areServerSpecsLoaded()) return;
		serverConfigsInitialized = true;
		boolean changed = ModConfig.validateAndFixCrossFields();
		changed |= BalanceConfig.refresh(false);
		FactoryTierConfigService.load(ModConfig.SERVER);
		if (changed) ModConfig.saveServerSpecs();
		BeeConfigApplier.applyOverrides();
		MekCentrifugeFactoryHelper.refreshSmeltingCompatConfig();
	}

	/**
	 * 仅处理本模组注册的 SERVER 配置，避免其他模组的配置事件触发本模组逻辑。
	 * 配置同步到客户端后 spec 身份仍保持不变，mod id 和类型则提供额外边界校验。
	 */
	private static boolean isOwnServerConfig(net.neoforged.fml.config.ModConfig config) {
		return config != null
				&& MOD_ID.equals(config.getModId())
				&& config.getType() == Type.SERVER
				&& ModConfig.isServerSpec(config.getSpec());
	}

	/**
	 * 注册 mod 事件总线监听器（FML 生命周期事件）
	 */
	private void registerModEventBusListeners(IEventBus eventBus) {
		eventBus.addListener(this::onCommonSetup);
		// 注册 MEK 离心机的 Capability（安全、能量等）— 使 tooltip 能正确显示拥有者/安全等级/储能
		eventBus.addListener(this::onRegisterCapabilities);
		// 模块 3 修复：在物品注册完成后调用 IAttachmentAware.attachAttachments，
		// 补全 MEK 原版的附件注册流程（项目使用标准 DeferredRegister.Items，不会自动调用）
		eventBus.addListener(EventPriority.LOWEST, (RegisterEvent event) -> onRegisterItemAttachments(event, eventBus));
	}

	/**
	 * 物品注册完成后调用 IAttachmentAware.attachAttachments — 模块 3 修复
	 * <br/>
	 * 原理：MEK 原版 {@code ItemDeferredRegister.register} 在 {@code RegisterEvent(LOWEST)} 期间
	 * 调用 {@code ItemRegistryObject.attachDefaultContainers(bus)}，进而调用
	 * {@code IAttachmentAware.attachAttachments(eventBus)}。项目使用标准 {@code DeferredRegister.Items}
	 * 不会自动调用，需手动补全。
	 * <p>
	 * {@code attachAttachments} 通过 {@code ContainerType.ENERGY.addDefaultCreators(eventBus, ...)}
	 * 注册 {@code RegisterCapabilitiesEvent} 监听器，使 ENERGY capability 能在
	 * {@code RegisterCapabilitiesEvent} 触发时注册到 ItemStack（合成升级后机器可被电缆充能）。
	 * <p>
	 * 顺序安全性：{@code addDefaultCreators} 使用 put 语义覆盖创建器，但监听器只注册一次。
	 * 与 {@link com.ayoshiko.productivebeesgenesis.apiary.MekApiaryContainerRegistrar} 和
	 * {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeContainerRegistrar} 的 ENERGY 创建器
	 * 注册(传 null)互不冲突，无论执行顺序如何，ENERGY capability 监听器最终都会注册。
	 */
	private void onRegisterItemAttachments(RegisterEvent event, IEventBus eventBus) {
		if (!event.getRegistryKey().equals(Registries.ITEM)) {
			return;
		}
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof IAttachmentAware aware) {
				aware.attachAttachments(eventBus);
			}
		}
	}

	/**
	 * 注册 NeoForge 事件总线监听器（游戏运行时事件）
	 */
	private void registerNeoForgeEventBusListeners() {
		// 监听数据重载事件（/reload、数据包变更、服务器启动）— 递增 RECIPE_VERSION，
		// 通知所有 PB 配方处理器清空 SMELTING/PB 配方缓存。
		// TagsUpdatedEvent 在所有 reload listener（含配方重载）完成后触发，是重置缓存的可靠信号。
		NeoForge.EVENT_BUS.addListener(this::onTagsReload);
		// 注册蜜蜂配方重载器 — 在 RecipeManager 加载完成后动态修改 PB 的 bee_fishing/bee_breeding/bee_spawning/bee_conversion 配方
		NeoForge.EVENT_BUS.addListener(this::onAddReloadListener);
		// 注册配方重载器的延迟重试 tick 处理器 — 处理首次进入世界时配置未加载的情况
		NeoForge.EVENT_BUS.addListener(BeeRecipeReloader::onServerTick);
		// 注册服务端 tick 时间监测器 — 通过 Pre/Post 监听器记录每 tick 实际耗时（MSPT），
		// 维护最近 100 tick 滚动平均，暴露 getTpsFactor() 供所有节流逻辑使用
		NeoForge.EVENT_BUS.addListener(ServerTickTimeMonitor.getInstance()::onTickPre);
		NeoForge.EVENT_BUS.addListener(ServerTickTimeMonitor.getInstance()::onTickPost);
		// 注册开发者模式命令 — /productivebeesgenesis dev on|off|status|<feature> on|off
		// 使用内存状态而非配置文件，避免生产环境意外持久化
		NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
		// 玩家登录时同步开发者模式状态到客户端（控制创造标签页开发物品可见性）
		NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
		// 玩家下线时清理 PayloadRateLimiter 与 ModPayloads 频次限制缓存（防止离线玩家 UUID 残留）（Task 9）
		NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
			PayloadRateLimiter.onPlayerLogout(event.getEntity().getUUID());
			ModPayloads.clearFilterSyncRateLimit(event.getEntity().getUUID());
		});
		// 合成升级数据转移已迁移至 ApiaryShapedRecipe.assemble（recipe 包），
		// 通过重写 MekanismShapedRecipe.assemble 在输入消耗前转移 BLOCK_ENTITY_DATA，
		// 避免 ItemCraftedEvent 在输入被消耗后读到空物品的时序问题。
		// 服务器停止时清理静态缓存，防止跨存档数据泄漏
		NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
		NeoForge.EVENT_BUS.addListener(this::onServerStopped);
	}

	/**
	 * 迁移落盘后重新按 NeoForge 的存档覆盖规则加载三个 SERVER 配置。
	 * <p>
	 * 仅当存档 {@code serverconfig/} 里原本没有拆分文件、NeoForge 已把配置绑定到全局
	 * config 目录时才需要重载；整合包只改全局 config 的情况由迁移服务直接替换内存对象。
	 * {@code unloadConfigs} 是全局操作，会让所有模组多收一轮 Unloading/Loading 事件，
	 * 因此这里限定为“确实发生了存档级迁移”这一次，并捕获异常避免拖垮开服流程。
	 */
	private void onServerAboutToStart(ServerAboutToStartEvent event) {
		if (!ServerConfigMigrationService.consumeReloadRequired()) return;
		Path serverConfigDirectory = event.getServer().getWorldPath(SERVER_CONFIG_DIRECTORY);
		try {
			serverConfigsInitialized = false;
			ConfigTracker.INSTANCE.unloadConfigs(Type.SERVER);
			ConfigTracker.INSTANCE.loadConfigs(
					Type.SERVER, FMLPaths.CONFIGDIR.get(), serverConfigDirectory);
			LOGGER.info("存档级配置迁移完成，已按存档覆盖规则重新加载服务端配置：{}",
					serverConfigDirectory);
		} catch (RuntimeException exception) {
			LOGGER.error("重新加载存档级服务端配置失败，本次会话使用迁移前的配置对象：{}",
					serverConfigDirectory, exception);
		} finally {
			initializeServerConfigs();
		}
	}

	/**
	 * 标签/配方重载完成回调 — 递增配方版本号、失效缓存、重建离心配方索引。
	 * 失效 BeeInfoHelper/BeeProduceProcessor/PbRecipeCompleter/MyriadBatchPlanner 缓存。
	 * Task 16.3 同步失效 BeeProduceProcessor 产出配方缓存(静态共享)。
	 */
	private void onTagsReload(TagsUpdatedEvent event) {
		long newVersion = RECIPE_VERSION.incrementAndGet();
		BeeInfoHelper.invalidateCache();
		// 失效机械蜂箱产出配方缓存（Task 16.3 — 静态缓存全局失效）
		BeeProduceProcessor.invalidateCache();
		// 失效物品/方块转化配方索引（配方重载后转化原料花朵判定需重建）
		BeeConversionQueries.invalidate();
		// 失效单原料合成配方索引（染料蜜蜂花→染料查找，配方重载后产出映射可能变化）
		SingleIngredientCraftingIndex.invalidate();
		// 失效 PB 离心配方输出表缓存（防止 getRecipeOutputs 返回过期 LinkedHashMap）
		PbRecipeCompleter.invalidateRecipeOutputsCache();
		// 失效万象批量规划器模板缓存（标签重载后 bee_type 可能变化）（Task 19）
		MyriadBatchPlanner.clearTemplateCache();
		// CombFuzzyMatcher 已改为无缓存直读组件（AEItemKey.equals 比重算更贵），无需失效
		// 失效万象创世 bee_type 缓存（标签重载可能变更 BeeReloadListener 数据，5 秒过期窗口消除）
		MyriadBeeTypeCache.invalidate();
		// 重建离心配方索引 — 服务端用 MinecraftServer，客户端用 ClientLevel
		// Bug 1 修复：专用服务器客户端无本地服务器，此前跳过重建导致索引永远为 EMPTY，
		// 客户端 containsRecipe 校验失败无法放入蜜脾。现在客户端也重建索引。
		var server = ServerLifecycleHooks.getCurrentServer();
		if (server != null) {
			CentrifugeRecipeIndex.rebuild(server.getRecipeManager());
		} else if (FMLEnvironment.dist.isClient()) {
			// 客户端场景：通过反射安全调用客户端类方法，避免服务端加载 ProductiveBeesGenesisClient
			// （该类引用 net.minecraft.client.Minecraft，服务端加载会导致 ClassNotFoundException）
			try {
				Class<?> clientClass = Class.forName(
						"com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient");
				java.lang.reflect.Method method = clientClass.getMethod("rebuildCentrifugeIndex");
				method.invoke(null);
			} catch (Exception e) {
				LOGGER.warn("客户端离心配方索引重建失败，降级到全量遍历", e);
			}
		}
		LOGGER.info("配方/标签重载完成，recipeVersion 递增至 {}", newVersion);
	}

	/**
	 * 注册命令 — 开发者模式命令树
	 * <br/>
	 * 提供统一的开发调试入口，命令树详见 {@link DevModeCommand#register}。
	 */
	private void onRegisterCommands(RegisterCommandsEvent event) {
		DevModeCommand.register(event);
	}

	/**
	 * 玩家登录回调 — 同步开发者模式状态到新加入的客户端
	 * <br/>
	 * 由于 DevModeManager 状态仅存在于服务端内存，新加入的客户端默认 masterEnabled=false。
	 * 通过登录事件推送当前状态，确保创造标签页开发物品可见性与服务端一致。
	 */
	private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
			return;
		}
		DevModeStateSyncPacket packet = new DevModeStateSyncPacket(
				DevModeManager.isEnabled(),
				DevModeManager.getFeatureStates()
		);
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer, packet);
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
	 * 服务器停止回调 — 清理静态缓存防止跨存档数据泄漏:
	 * CentrifugeRecipeIndex/BeeInfoHelper/BeeProduceProcessor/MyriadCreationsEventHandler/
	 * RecipeReloadRetryManager/AbstractCombEventHandler.ThreadLocals/PayloadRateLimiter/MyriadBatchPlanner/
	 * ServerTickTimeMonitor。每个清理独立 try-catch,单个失败不中断后续。
	 */
	private void onServerStopped(ServerStoppedEvent event) {
		serverConfigsInitialized = false;
		safeClear(ServerConfigMigrationService::reset, "ServerConfigMigrationService");
		// 异常隔离：每个清理操作独立 try-catch，单个失败不中断后续清理，防止跨存档泄漏
		safeClear(CentrifugeRecipeIndex::clear, "CentrifugeRecipeIndex");
		safeClear(BeeInfoHelper::invalidateCache, "BeeInfoHelper");
		// 清理机械蜂箱产出配方缓存（Task 16.3 — 静态缓存防止跨存档泄漏）
		safeClear(BeeProduceProcessor::invalidateCache, "BeeProduceProcessor");
		// 清理物品/方块转化配方索引 — 防止跨存档残留旧 RecipeHolder 引用（与 onTagsReload 生命周期一致）
		safeClear(BeeConversionQueries::invalidate, "BeeConversionQueries");
		safeClear(MyriadCreationsEventHandler::clearAllCaches, "MyriadCreationsEventHandler");
		// 清理 BeeRecipeReloader 延迟重试上下文 — 防止持有的 RecipeManager / HolderLookup.Provider 引用阻碍 GC
		safeClear(RecipeReloadRetryManager::clearPendingRetryContext, "RecipeReloadRetryManager");
		// 清理 AbstractCombEventHandler 的 ThreadLocal — 防止线程池复用场景下引用残留
		safeClear(AbstractCombEventHandler::clearThreadLocals, "AbstractCombEventHandler.ThreadLocals");
		// 清理网络包频次限制器映射 — 防止跨存档玩家数据残留
		safeClear(PayloadRateLimiter::clearAll, "PayloadRateLimiter");
		// 清理万象批量规划器模板缓存 — 防止跨存档 bee_type 模板残留（Task 19）
		safeClear(MyriadBatchPlanner::clearTemplateCache, "MyriadBatchPlanner.TEMPLATE_CACHE");
		// 清理万象批量规划器 ThreadLocal 快照缓存 — 防止线程池复用场景下的引用残留
		safeClear(MyriadBatchPlanner::clearThreadLocals, "MyriadBatchPlanner.snapshotCache");
		// 清理服务端 tick 时间监测器状态 — 防止跨存档 MSPT 样本与 tpsFactor 缓存残留
		safeClear(ServerTickTimeMonitor.getInstance()::invalidate, "ServerTickTimeMonitor");
		safeClear(LogThrottle::clearAll, "LogThrottle");
		safeClear(FactoryTierConfigService::resetToDefaults, "FactoryTierConfigService");
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

	/**
	 * 注册 MEK 离心机物品与方块的 Capability
	 * <br/>
	 * 原理：ItemBlockTooltip 实现了 ICapabilityAware 接口，需要通过 RegisterCapabilitiesEvent
	 * 注册安全 Capability（拥有者/安全等级 tooltip）和能量 Capability（储能 tooltip）。
	 * Mekanism 原版在 Mekanism 主类中遍历自己的物品注册表调用 addCapabilities，
	 * 我们需要对自己的物品做同样的事。
	 * <p>
	 * v1.5.3 新增：AE2 已安装时委托 {@link Ae2CapabilityRegistrar#register} 为全部 18 个离心机
	 * BlockEntityType 注册 {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} capability，
	 * 使 AE2 线缆（含 ExtendedAE/AdvancedAE/ae2cs/ae2lt/Glodium/AppliedFlux 等附属模组线缆）
	 * 能自动发现并连接离心机。AE2 未安装时安全跳过，不触发类加载失败。
	 */
	private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
		for (var entry : ModItems.ITEMS.getEntries()) {
			Item item = entry.get();
			if (item instanceof ICapabilityAware aware) {
				aware.attachCapabilities(event);
			}
		}
		// AE2 capability 注册（仅在 AE2 已安装时执行）
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			Ae2CapabilityRegistrar.register(event);
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
