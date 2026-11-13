package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.apiary.BeeProduceProcessor;
import com.ayoshiko.productivebeesgenesis.command.DevModeCommand;
import com.ayoshiko.productivebeesgenesis.config.ClientConfigMigrationService;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.ServerConfigRuntime;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModCreativeTabs;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.init.ModStats;
import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.mek.MyriadBatchPlanner;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeCompleter;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2CapabilityRegistrar;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.network.DevModeStateSyncPacket;
import com.ayoshiko.productivebeesgenesis.network.ModPayloads;
import com.ayoshiko.productivebeesgenesis.network.PayloadRateLimiter;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.BeeRecipeReloader;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PBReflectionCacheCleaner;
import com.ayoshiko.productivebeesgenesis.util.RecipeReloadRetryManager;
import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import com.ayoshiko.productivebeesgenesis.util.SharedPbRecipeCache;
import com.ayoshiko.productivebeesgenesis.util.SingleIngredientCraftingIndex;
import mekanism.common.attachments.IAttachmentAware;
import mekanism.common.capabilities.ICapabilityAware;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	/** 配方版本号 — 每次 /reload 或数据包重载时递增,通知所有 PB 配方处理器清空缓存。AtomicLong 保证原子递增。 */
	public static final AtomicLong RECIPE_VERSION = new AtomicLong(0L);

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
		com.ayoshiko.productivebeesgenesis.resource.ResourceContent.register(eventBus);
		ModBlocks.BLOCKS.register(eventBus);
		ModBlockEntities.register(eventBus);
		ModItems.ITEMS.register(eventBus);
		ModCreativeTabs.CREATIVE_MODE_TABS.register(eventBus);
		ModStats.register(eventBus);
		ModMenuTypes.register(eventBus);
		com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.register(eventBus);
		com.ayoshiko.productivebeesgenesis.multiblock.world.MachineContent.register(eventBus);
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

	/** 配置回调只准备封闭快照，世界或连接的游戏线程负责发布。 */
	private void registerConfigListeners(IEventBus eventBus) {
		eventBus.addListener((ModConfigEvent.Loading event) -> {
			if (isOwnClientConfig(event.getConfig())) {
				ClientConfigMigrationService.onConfigLoading(event.getConfig());
			}
			ServerConfigRuntime.onConfig(event.getConfig(), false);
		});
		eventBus.addListener((ModConfigEvent.Reloading event) ->
				ServerConfigRuntime.onConfig(event.getConfig(), true));
	}

	private static boolean isOwnClientConfig(net.neoforged.fml.config.ModConfig config) {
		return config != null
				&& MOD_ID.equals(config.getModId())
				&& config.getType() == Type.CLIENT
				&& config.getSpec() == ModConfig.CLIENT_SPEC;
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
		// 普通工厂的已付物品在整刻结束合并交付，成本计入本刻监测。
		NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerTickEvent.Post event) ->
				Ae2IntegrationLoader.flushDeferredItemOutputs(event.getServer()));
		NeoForge.EVENT_BUS.addListener(ServerTickTimeMonitor.getInstance()::onTickPost);
		// 推进全局游戏刻时钟 — 槽位对象拿不到 Level，「外部退回窗口」靠它判断凭据是否过期
		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> ServerTickClock.tick());
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
		NeoForge.EVENT_BUS.addListener(this::onServerStopped);
		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
				Ae2IntegrationLoader.cancelDeferredItemOutputs(event.getServer()));
	}

	/** 标签事件仅在对应资源已被接收时发布；候选管理器不影响当前生产。 */
	private void onTagsReload(TagsUpdatedEvent event) {
		if (!event.shouldUpdateStaticData()) return;
		if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
			var server = ServerLifecycleHooks.getCurrentServer();
			if (server != null && !com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime.reloadInProgress(server)) {
				acceptRecipeManager(server.getRecipeManager(), false);
			}
		} else if (FMLEnvironment.dist.isClient()) {
			try {
				Class<?> clientClass = Class.forName("com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient");
				clientClass.getMethod("rebuildCentrifugeIndex").invoke(null);
			} catch (ReflectiveOperationException failure) {
				LOGGER.warn("客户端离心配方索引重建失败", failure);
			}
		}
	}

	/** 接收当前管理器后统一发布所有依赖缓存；同一接收点不重复推进代际。 */
	public static void acceptRecipeManager(net.minecraft.world.item.crafting.RecipeManager manager, boolean modified) {
		if (!modified && CentrifugeRecipeIndex.isCurrent(manager)) return;
		if (!CentrifugeRecipeIndex.rebuild(manager)) {
			throw new IllegalStateException("Could not publish accepted centrifuge recipes");
		}
		BeeInfoHelper.invalidateCache();
		BeeProduceProcessor.invalidateCache();
		BeeConversionQueries.invalidate();
		SingleIngredientCraftingIndex.invalidate();
		PbRecipeCompleter.invalidateRecipeOutputsCache();
		SharedPbRecipeCache.invalidate();
		EssenceConversionUpgradeHelper.invalidateCache();
		RawOreSmeltingUpgradeHelper.invalidateCache();
		com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper.invalidateCache();
		MyriadBatchPlanner.clearTemplateCache();
		MyriadBeeTypeCache.invalidate();
		PBReflectionCacheCleaner.clearBeeFishingCaches();
		long version = RECIPE_VERSION.incrementAndGet();
		RecipeReloadRetryManager.accepted(manager, version);
		LOGGER.info("已接收配方/标签代际 {}", version);
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
	 * AddReloadListenerEvent 仅注册本轮重载监听器，此时配方尚未完成加载。
	 * BeeRecipeReloader 在 apply 阶段处理候选；整轮成功后再发布已接收的配方代际。
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
		safeClear(() -> ServerConfigRuntime.endServer(event.getServer()), "ServerConfigRuntime");
		// 异常隔离：每个清理操作独立 try-catch，单个失败不中断后续清理，防止跨存档泄漏
		safeClear(CentrifugeRecipeIndex::clear, "CentrifugeRecipeIndex");
		// 清理 PB 离心配方静态共享查找缓存 — 防止跨存档残留旧 RecipeHolder 引用（P1 优化）
		safeClear(SharedPbRecipeCache::invalidate, "SharedPbRecipeCache");
		safeClear(PbRecipeCompleter::invalidateRecipeOutputsCache, "PbRecipeCompleter.recipeOutputsCache");
		safeClear(BeeInfoHelper::invalidateCache, "BeeInfoHelper");
		// 清理机械蜂箱产出配方缓存（Task 16.3 — 静态缓存防止跨存档泄漏）
		safeClear(BeeProduceProcessor::invalidateCache, "BeeProduceProcessor");
		safeClear(EssenceConversionUpgradeHelper::invalidateCache, "EssenceConversionUpgradeHelper");
		safeClear(RawOreSmeltingUpgradeHelper::invalidateCache, "RawOreSmeltingUpgradeHelper");
		safeClear(com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper::invalidateCache,
				"UselessByproductUpgradeHelper");
		// 清理物品/方块转化配方索引 — 防止跨存档残留旧 RecipeHolder 引用（与 onTagsReload 生命周期一致）
		safeClear(BeeConversionQueries::invalidate, "BeeConversionQueries");
		safeClear(SingleIngredientCraftingIndex::invalidate, "SingleIngredientCraftingIndex");
		safeClear(MyriadCreationsEventHandler::clearAllCaches, "MyriadCreationsEventHandler");
		// 清理 BeeRecipeReloader 延迟重试上下文 — 防止持有的 RecipeManager / HolderLookup.Provider 引用阻碍 GC
		safeClear(RecipeReloadRetryManager::clearPendingRetryContext, "RecipeReloadRetryManager");
		// 清理 AbstractCombEventHandler 的 ThreadLocal — 防止线程池复用场景下引用残留
		safeClear(AbstractCombEventHandler::clearThreadLocals, "AbstractCombEventHandler.ThreadLocals");
		// 清理网络包频次限制器映射 — 防止跨存档玩家数据残留
		safeClear(PayloadRateLimiter::clearAll, "PayloadRateLimiter");
		safeClear(ModPayloads::clearFilterSyncRateLimits, "ModPayloads.filterSyncRateLimits");
		// 清理万象批量规划器模板缓存 — 防止跨存档 bee_type 模板残留（Task 19）
		safeClear(MyriadBatchPlanner::clearTemplateCache, "MyriadBatchPlanner.TEMPLATE_CACHE");
		// 清理服务端 tick 时间监测器状态 — 防止跨存档 MSPT 样本与 tpsFactor 缓存残留
		safeClear(ServerTickTimeMonitor.getInstance()::invalidate, "ServerTickTimeMonitor");
		// 复位全局游戏刻时钟 — 槽位的「外部退回窗口」依赖它判定过期，跨存档必须归零
		safeClear(ServerTickClock::reset, "ServerTickClock");
		safeClear(Ae2IntegrationLoader::clearServerCaches, "AE2 network caches");
		safeClear(LogThrottle::clearAll, "LogThrottle");
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
			com.ayoshiko.productivebeesgenesis.resource.UselessResourceIntegration.install();
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
		event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
				com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.CORE_TILE.get(), (core, side) -> core.energyPort());
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
