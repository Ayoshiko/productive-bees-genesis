package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 按服务器隔离的资源运行期状态；无生产调用时不创建账户文件。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceRuntime {
	// 仅映射访问需要同步以隔离多个服务器；每个 State 的世界操作仍由其服务器线程负责。
	private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

	private static final class State {
		ResourceRecipeIndex recipes = ResourceRecipeIndex.build(0, List.of());
		long recipeVersion = Long.MIN_VALUE;
		long failedRecipeVersion = Long.MIN_VALUE;
		Throwable recipeFailure;
		boolean buildingRecipes;
		ResourceAccountsSavedData data;
		// 生命周期观察信号；区块保存失败可能从 IO 线程报告，不访问世界或改账户。
		volatile boolean orderlyStop;
		volatile boolean worldClosed;
		volatile boolean worldSaveFailed;
		int reloads;
		boolean reloadFailed;
		ResourceRecipeIndex catalogIndex;
		PbResourceCatalog catalog;
		Map<net.minecraft.world.level.block.entity.BlockEntityType<?>, com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostAdapter> hosts;
		boolean buildingHosts;
		long keyVersion = Long.MIN_VALUE;
		boolean validatingKey;
		long nativeOutputTick = Long.MIN_VALUE;
		int nativeOutputChecks;
		// 只保留尚未取得预算的已加载接口；接口撤销及停服时清理。
		final java.util.LinkedHashSet<Object> nativeOutputs = new java.util.LinkedHashSet<>();
		final java.util.LinkedHashSet<ResourceOutputAccess> outputHosts = new java.util.LinkedHashSet<>();
		ResourceOutputAccess[] outputSnapshot;
		final java.util.LinkedHashMap<ResourceKey, Boolean> keyAvailability = new java.util.LinkedHashMap<>(16, 0.75F, true);
	}

	private ResourceRuntime() {}

	/** 仅记录资源重载生命周期，允许 IO 调用方发起；不从后台访问世界或配方。 */
	public static synchronized void beginReload(MinecraftServer server) {
		State current = STATES.computeIfAbsent(server, unused -> new State());
		if (current.reloads == 0) {
			current.reloadFailed = false;
		}
		current.reloads++;
	}

	public static synchronized void finishReload(MinecraftServer server, boolean success) {
		State current = STATES.get(server);
		if (current != null) {
			current.reloads = Math.max(0, current.reloads - 1);
			current.reloadFailed |= !success;
		}
	}

	public static synchronized boolean reloadBlocked(MinecraftServer server) {
		State current = STATES.get(server);
		return current != null && (current.reloads > 0 || current.reloadFailed);
	}

	static void trackOutput(MinecraftServer server, ResourceOutputAccess output) {
		var state = state(server);
		if (state.outputHosts.add(output)) state.outputSnapshot = null;
	}

	static void untrackOutput(MinecraftServer server, ResourceOutputAccess output) {
		var state = state(server);
		if (state.outputHosts.remove(output)) state.outputSnapshot = null;
		output.invalidate();
	}

	@SubscribeEvent public static void chunkLoaded(net.minecraftforge.event.level.ChunkEvent.Load event) {
		if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel
				&& event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)
			for (var host : chunk.getBlockEntities().values()) ResourceOutputAccess.find(host);
	}

	@SubscribeEvent public static void chunkUnloaded(net.minecraftforge.event.level.ChunkEvent.Unload event) {
		if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
				&& event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
			for (var host : chunk.getBlockEntities().values()) {
				var state = ResourceHiveHost.existing(host);
				if (state != null && state.outputAccess != null) untrackOutput(level.getServer(), state.outputAccess);
			}
		}
	}

	/** 全服务器共用原生邻接查询预算；未取得预算的接口下刻继续排队，不强制加载区块。 */
	public static boolean claimNativeOutput(MinecraftServer server, Object worker) {
		var state = state(server);
		state.nativeOutputs.add(java.util.Objects.requireNonNull(worker));
		long tick = server.getTickCount();
		if (state.nativeOutputTick != tick) {
			state.nativeOutputTick = tick;
			state.nativeOutputChecks = 0;
		}
		if (state.nativeOutputChecks >= 256 || state.nativeOutputs.iterator().next() != worker) return false;
		state.nativeOutputs.remove(worker);
		state.nativeOutputChecks++;
		return true;
	}

	/** 接口卸载、替换或余额耗尽后取消排队，避免旧引用阻塞后续接口。 */
	public static void releaseNativeOutput(MinecraftServer server, Object worker) {
		if (!server.isSameThread()) throw new IllegalStateException("Native output requires the server thread");
		State current;
		synchronized (ResourceRuntime.class) { current = STATES.get(server); }
		if (current != null) current.nativeOutputs.remove(worker);
	}

	private static synchronized State state(MinecraftServer server) {
		if (!server.isSameThread()) throw new IllegalStateException("Resource runtime requires the server thread");
		return STATES.computeIfAbsent(server, ignored -> new State());
	}

	/** 第三方宿主按服务器会话发布一次，停服随 State 清理；坏监听器不影响内置宿主。 */
	public static Map<net.minecraft.world.level.block.entity.BlockEntityType<?>,
			com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostAdapter> hosts(MinecraftServer server) {
		var state = state(server);
		if (state.buildingHosts) throw new IllegalStateException("Recursive resource host registration");
		if (state.hosts == null) {
			state.buildingHosts = true;
			var event = new com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceHostsEvent();
			try {
				net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
				state.hosts = event.finish();
			} catch (RuntimeException | LinkageError failure) {
				try { event.finish(); } catch (RuntimeException ignored) { }
				state.hosts = Map.of();
				ProductiveBeesGenesis.LOGGER.error("第三方资源宿主注册失败，本服务器会话禁用第三方宿主", failure);
			} finally {
				state.buildingHosts = false;
			}
		}
		return state.hosts;
	}

	/** 资源准入按服务器及配方／标签代际缓存；最多保留 1024 个不可变键，停服清理。 */
	public static boolean available(MinecraftServer server, ResourceKey key) {
		var state = state(server);
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (state.keyVersion != version) {
			state.keyAvailability.clear();
			state.keyVersion = version;
		}
		var cached = state.keyAvailability.get(key);
		if (cached != null) return cached;
		if (state.validatingKey) throw new IllegalStateException("Recursive AE resource validation");
		state.validatingKey = true;
		boolean available;
		try {
			available = ResourceTypes.aeKeyType(key) == null
					? ResourceTypes.available(key) : ResourceTypes.aeKeyAvailable(server.registryAccess(), key);
		} catch (RuntimeException | LinkageError failure) {
			ProductiveBeesGenesis.LOGGER.warn("通用资源键无法解析，保留原始数据：{}", key, failure);
			available = false;
		} finally {
			state.validatingKey = false;
		}
		if (ProductiveBeesGenesis.RECIPE_VERSION.get() != version) return false;
		if (state.keyAvailability.size() >= 1024) com.ayoshiko.productivebeesgenesis.port.ForgeCollections.pollFirst(state.keyAvailability);
		state.keyAvailability.put(key, available);
		return available;
	}

	/** 按现有配方代际懒加载不可变索引；/reload 后下次访问只构建一次。 */
	public static ResourceRecipeIndex recipes(MinecraftServer server) {
		var state = state(server);
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (state.buildingRecipes) throw new IllegalStateException("Recursive resource recipe registration");
		if (state.failedRecipeVersion == version) {
			throw new IllegalStateException("Resource recipe registration failed; reload required", state.recipeFailure);
		}
		if (state.recipeVersion != version) {
			state.buildingRecipes = true;
			try {
				var next = com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi.collect(version,
						server.getRecipeManager().getAllRecipesFor(ResourceProductionRecipe.TYPE).stream().map(com.ayoshiko.productivebeesgenesis.port.RecipeHolder::of).toList(),
						server.getRecipeManager().getAllRecipesFor(ResourceCentrifugeRecipe.TYPE).stream().map(com.ayoshiko.productivebeesgenesis.port.RecipeHolder::of).toList());
				state.recipes = next;
				state.recipeVersion = version;
				state.recipeFailure = null;
			} catch (RuntimeException | LinkageError failure) {
				state.failedRecipeVersion = version;
				state.recipeFailure = failure;
				throw new IllegalStateException("Resource recipe registration failed", failure);
			} finally {
				state.buildingRecipes = false;
			}
		}
		return state.recipes;
	}

	public static PbResourceCatalog pbCatalog(net.minecraft.server.level.ServerLevel level) {
		var state = state(level.getServer());
		var index = recipes(level.getServer());
		if (state.catalogIndex != index) {
			state.catalog = PbResourceCatalog.build(level, index);
			state.catalogIndex = index;
		}
		return state.catalog;
	}

	/** 首次账户访问时加载主世界 SavedData，读取失败状态不会转为空库。 */
	public static ResourceAccountsSavedData accounts(MinecraftServer server) {
		var state = state(server);
		if (state.data == null) {
			var path = server.getWorldPath(LevelResource.ROOT).resolve("data")
					.resolve(ResourceAccountsSavedData.STORAGE_NAME + ".dat");
			var loaded = ResourceAccountsSavedData.open(path);
			server.overworld().getDataStorage().set(ResourceAccountsSavedData.STORAGE_NAME, loaded);
			state.data = loaded;
		}
		state.data.poll();
		if (state.worldSaveFailed) state.data.isolateWorldSaveFailure();
		return state.data;
	}

	/** 服务端处理 IO 完成信箱，不扫描账户或任何外部网络。 */
	@SubscribeEvent
	public static void tick(ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
		State current;
		synchronized (ResourceRuntime.class) {
			current = STATES.get(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
		}
		if (current != null && current.data != null) {
			current.data.poll();
			if (current.worldSaveFailed) current.data.isolateWorldSaveFailure();
		}
		if (current != null && !current.outputHosts.isEmpty()) {
			if (current.outputSnapshot == null) current.outputSnapshot = current.outputHosts.toArray(ResourceOutputAccess[]::new);
			for (var output : current.outputSnapshot) if (!output.tick()) untrackOutput(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer(), output);
		}
		if (net.minecraftforge.fml.ModList.get().isLoaded("projecte")) {
			com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.sync(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
		}
	}

	/** 这些回调仅记录信号；即使错误发生在首次资源访问前也不能遗忘。 */
	public static synchronized void observeOrderlyStop(MinecraftServer server) {
		STATES.computeIfAbsent(server, ignored -> new State()).orderlyStop = true;
	}

	public static synchronized void observeWorldClose(MinecraftServer server, boolean completed) {
		STATES.computeIfAbsent(server, ignored -> new State()).worldClosed = completed;
	}

	public static synchronized void observeWorldSaveFailure(MinecraftServer server) {
		STATES.computeIfAbsent(server, ignored -> new State()).worldSaveFailed = true;
	}

	/** 停服清理引用与未完成保存，防止同一进程再次进世界时复用旧账户。 */
	@SubscribeEvent
	public static void stopped(ServerStoppedEvent event) {
		if (net.minecraftforge.fml.ModList.get().isLoaded("projecte")) {
			com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.clear(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
		}
		State removed;
		synchronized (ResourceRuntime.class) {
			removed = STATES.remove(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
		}
		if (removed != null) {
			try {
				for (var output : removed.outputHosts) {
					try {
						output.invalidate();
					} catch (RuntimeException | LinkageError failure) {
						ProductiveBeesGenesis.LOGGER.error("停服时撤销扩展资源输出接口失败", failure);
					}
				}
			} finally {
				if (removed.data != null) {
					removed.data.finishAndClose(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess(),
							removed.orderlyStop && removed.worldClosed && !removed.worldSaveFailed);
				}
			}
		}
	}
}
