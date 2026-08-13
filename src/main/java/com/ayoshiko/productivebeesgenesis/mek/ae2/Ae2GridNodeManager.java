package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
	 * AE2 网格节点生命周期管理器
	 * <br/>
	 * 封装 {@link IManagedGridNode} 的创建、销毁、NBT 持久化逻辑。
	 * 所有方法都进行空检查和 AE2 加载状态检查，AE2 未安装时安全短路返回。
	 * <p>
	 * <b>v2.0.0 解耦</b>：节点创建只受 {@link Ae2IntegrationLoader#isAe2Loaded()} 控制，
	 * 不再受 {@code aeOutputEnabled} 配置影响。与 Mek-Energistics 对齐（节点无条件创建）。
	 * {@code aeOutputEnabled} 仅控制 {@link Ae2OutputPusher} 的输出推送行为。
	 * <p>
	 * <b>节点配置</b>：
	 * <ul>
	 *   <li>{@code setExposedOnSides(ALL)} — 六面暴露，便于从任意方向接入 AE2 网络</li>
	 *   <li>{@code setInWorldNode(true)} — 作为世界内节点（非仅逻辑节点）</li>
	 *   <li>{@code setFlags(REQUIRE_CHANNEL)} — 要求 AE2 频道才能工作</li>
	 *   <li>{@code setTagName} — 指定 NBT 持久化键名，配合单参数 saveToNBT/loadFromNBT</li>
	 *   <li>{@code setIdlePowerUsage(0)} — 不消耗 AE2 网络空闲能量（离心机自身供能）</li>
	 * </ul>
	 * <p>
	 * <b>NBT 持久化</b>：AE2 的 {@code saveToNBT/loadFromNBT} 是单参数版本，
	 * 通过 {@code setTagName} 预设键名后，AE2 会在给定的 CompoundTag 中以该键名存取子标签。
	 */
public final class Ae2GridNodeManager {

	/** 空闲功耗：0，离心机不消耗 AE2 网络空闲能量 */
	private static final double IDLE_POWER_USAGE = 0.0;

	// ===== Grid Node 状态常量（与 AE2 GridNodeState 枚举 ordinal 对齐） =====
	/** 设备离线（节点为空或未供电） */
	public static final int STATE_OFFLINE = 0;
	/** 网络加载中（已供电但网格未启动完成） */
	public static final int STATE_NETWORK_BOOTING = 1;
	/** 设备缺少频道（网格已启动但不满足频道需求） */
	public static final int STATE_MISSING_CHANNEL = 2;
	/** 设备在线（已供电、网格已启动、频道满足）— 唯一允许推送/拉取的状态 */
	public static final int STATE_ONLINE = 3;

	private Ae2GridNodeManager() {}

	/**
	 * 准备网格节点（不接入网格）
	 * <br/>
	 * 创建 {@link IManagedGridNode} 并完成配置，但<b>不调用</b> {@code create(level, pos)}。
	 * 用于 {@code clearRemoved} / {@code loadAdditional} 阶段，避免区块加载时
	 * AE2 连接扫描触发邻近方块实体懒加载，导致 clearRemoved 递归栈溢出。
	 * <p>
	 * 幂等：节点已存在则直接返回。集成未启用时安全短路。
	 * <p>
	 * 线程安全：使用宿主级锁保护 check-then-act 块，避免多线程同时调用 prepareNode
	 * 时创建重复节点导致 AE2 网格泄漏。
	 *
	 * @param host 输出宿主（离心机方块实体）
	 */
	public static void prepareNode(IAe2OutputHostBase host) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// 宿主级锁保护 check-then-act，避免并发创建重复节点
		synchronized (host) {
			// 幂等：节点已存在则不重复创建
			if (host.productivebeesgenesis$getAe2GridNode() != null) return;

			Level level = host.productivebeesgenesis$getAe2Level();
			BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
			if (level == null || pos == null) return;

			IGridNodeListener<IAe2OutputHostBase> listener = new CentrifugeGridNodeListener();
			IManagedGridNode node = GridHelper.createManagedNode(host, listener);
			// 节点配置
			node.setExposedOnSides(EnumSet.allOf(Direction.class));
			node.setInWorldNode(true);
			node.setFlags(GridFlags.REQUIRE_CHANNEL);
			node.setIdlePowerUsage(IDLE_POWER_USAGE);
			node.setTagName(IAe2OutputHostBase.AE2_NODE_TAG);
			// 视觉表现：用离心机方块本身（便于 AE2 网络工具识别）
			if (host instanceof BlockEntity be) {
				node.setVisualRepresentation(be.getBlockState().getBlock());
			}
			// 注意：不调用 node.create()，延迟到 connectNode 避免区块加载递归栈溢出
			// NOTE: do NOT register an IGridTickable service on this node.
			// JDTE 0.5.9-alpha1 classifies blocks exposing IGridTickable as AE2_GRID targets and only
			// accelerates them when the accelerator carries the AE_ACCELERATION upgrade; without it the
			// target is skipped entirely (no fallback to the BLOCK_ENTITY path), so the JDTE Time
			// Accelerator could not speed up apiaries/centrifuges. Without the service, our machines use
			// JDTE's CoalescedAcceleratedMachine path (accumulate + flush) and every accelerator tier
			// works out of the box.
			host.productivebeesgenesis$setAe2GridNode(node);
			// 创建 AEItemKey 缓存，减少推送时 AEItemKey.of(stack) 的重复调用（Task 7）
			host.productivebeesgenesis$setAeItemKeyCache(
					new AeItemKeyCache(host.processes() * AeItemKeyCache.SLOTS_PER_PROCESS));
		}
	}

	/**
	 * 连接网格节点到 AE2 网络
	 * <br/>
	 * 调用 {@code node.create(level, pos)} 将已准备的节点接入 AE2 网格。
	 * 必须在首个 server tick 调用，不能在 {@code clearRemoved} 中调用，
	 * 否则 AE2 连接扫描会触发邻近方块实体懒加载导致递归栈溢出。
	 * <p>
	 * 节点不存在或集成未启用时安全短路。
	 * <p>
	 * 线程安全：使用宿主级锁保护 check-then-act 块，与 {@link #prepareNode} /
	 * {@link #destroyNode} 使用同一把锁保证互斥，避免未来跨上下文调用产生竞态。
	 *
	 * @param host 输出宿主
	 */
	public static void connectNode(IAe2OutputHostBase host) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// 宿主级锁保护 check-then-act，与 prepareNode/destroyNode 保持一致的锁粒度
		synchronized (host) {
			Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
			if (!(nodeObj instanceof IManagedGridNode node)) return;

			// 幂等检查：节点已连接则跳过，避免重复调用 create() 触发 AE2 内部状态混乱
			if (node.getNode() != null) return;

			Level level = host.productivebeesgenesis$getAe2Level();
			BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
			if (level == null || pos == null) return;

			// 接入 AE2 网络（触发连接扫描）
			node.create(level, pos);
		}
	}

	/**
	 * 查询 AE2 网格节点状态（与 AE2 原版 {@code GridNodeState} 完全一致）
	 * <br/>
	 * 返回 ordinal 值，与 AE2 的 {@code GridNodeState} 枚举序号对应：
	 * <ul>
	 *   <li>0 = OFFLINE — 设备离线（节点为空或未供电）</li>
	 *   <li>1 = NETWORK_BOOTING — 网络加载中（已供电但网格未启动完成）</li>
	 *   <li>2 = MISSING_CHANNEL — 设备缺少频道（网格已启动但不满足频道需求）</li>
	 *   <li>3 = ONLINE — 设备在线（已供电、网格已启动、频道满足）</li>
	 * </ul>
	 * 集成未启用或节点不存在时返回 0（OFFLINE）。
	 * <p>
	 * 供 Jade 集成等外部模块查询节点状态，避免直接引用 AE2 类。
	 *
	 * @param host 输出宿主
	 * @return 状态 ordinal（0-3），对应 AE2 GridNodeState
	 */
	public static int getGridNodeState(IAe2OutputHostBase host) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return STATE_OFFLINE;
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return STATE_OFFLINE;

		IGridNode node = managedNode.getNode();
		if (node == null) return STATE_OFFLINE;

		// 未供电 → OFFLINE
		if (!node.isPowered()) return STATE_OFFLINE;

		// 网格未启动 → NETWORK_BOOTING
		if (!node.hasGridBooted()) return STATE_NETWORK_BOOTING;

		// 频道不满足 → MISSING_CHANNEL
		if (!node.meetsChannelRequirements()) return STATE_MISSING_CHANNEL;

		// 全部满足 → ONLINE
		return STATE_ONLINE;
	}

	/**
	 * 销毁网格节点
	 * <br/>
	 * 节点不存在时安全短路。销毁后清空宿主持有的引用，避免内存泄漏。
	 * <p>
	 * 线程安全：使用宿主级锁保护 check-then-act 块，避免与 prepareNode 并发执行
	 * 时出现"节点已销毁但引用仍非空"或"节点已创建但被立即销毁"的竞态。
	 *
	 * @param host 输出宿主
	 */
	public static void destroyNode(IAe2OutputHostBase host) {
		// 宿主级锁保护 check-then-act，与 prepareNode 使用同一把锁
		synchronized (host) {
			Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
			if (!(nodeObj instanceof IManagedGridNode node)) return;
			node.destroy();
			// 清空 AEItemKey 缓存，释放 ItemStack 引用（Task 7）
			Object cacheObj = host.productivebeesgenesis$getAeItemKeyCache();
			if (cacheObj instanceof AeItemKeyCache cache) {
				cache.clear();
			}
			host.productivebeesgenesis$setAeItemKeyCache(null);
			host.productivebeesgenesis$setAe2GridNode(null);
			// Task 12：销毁节点时同步失效 AE2 网格/存储缓存，避免持有已销毁节点的旧 grid 引用
			host.productivebeesgenesis$getAe2StateHolder().onGridChanged();
		}
	}

	// ===== Task 12：AE2 网格/存储缓存访问方法 =====
	// 缓存字段存储在 Ae2OutputStateHolder（Object 类型保持依赖隔离），
	// 本类负责类型转换 + 缓存未命中时查询 AE2 API 并回填。
	// gridChanged 回调（见 CentrifugeGridNodeListener）和 destroyNode 都会失效缓存。

	/**
	 * 获取宿主缓存的 AE2 网格，未命中时查询并回填
	 * <br/>
	 * 缓存由 {@link CentrifugeGridNodeListener#onGridChanged} 在 grid 变化时失效，
	 * 256× 加速场景下避免每 tick 重复调用 {@code managedNode.getGrid()}（约 768 次/gameTick）。
	 * <p>
	 * 线程安全：cache 字段为 volatile，check-then-set 最多导致重复查询一次（无正确性问题），
	 * 主线程独占 tick 路径下无并发。
	 *
	 * @param host 输出宿主
	 * @return 已连接的网格，未连接或节点不存在时返回 null
	 */
	@Nullable
	public static IGrid getCachedGrid(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return null;
		Object cached = holder.getCachedGrid();
		if (cached instanceof IGrid grid) return grid;
		// 缓存未命中 — 查询 AE2 API
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return null;
		IGrid grid = managedNode.getGrid();
		if (grid != null) holder.setCachedGrid(grid);
		return grid;
	}

	/**
	 * 获取宿主缓存的 AE2 存储服务，未命中时查询并回填
	 * <br/>
	 * 依赖 {@link #getCachedGrid} 提供的网格缓存，避免重复 getService 调用。
	 *
	 * @param host 输出宿主
	 * @return 存储服务，网格未连接或服务不存在时返回 null
	 */
	@Nullable
	public static IStorageService getCachedStorage(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return null;
		Object cached = holder.getCachedStorage();
		if (cached instanceof IStorageService storage) return storage;
		// 缓存未命中 — 通过 grid 缓存查询
		IGrid grid = getCachedGrid(host);
		if (grid == null) return null;
		IStorageService storage = grid.getService(IStorageService.class);
		if (storage != null) holder.setCachedStorage(storage);
		return storage;
	}

	/**
	 * 获取宿主缓存的 ME 存储，未命中时查询并回填
	 * <br/>
	 * 依赖 {@link #getCachedStorage} 提供的存储服务缓存。
	 *
	 * @param host 输出宿主
	 * @return ME 存储，存储服务不存在时返回 null
	 */
	@Nullable
	public static MEStorage getCachedMeStorage(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return null;
		Object cached = holder.getCachedMeStorage();
		if (cached instanceof MEStorage meStorage) return meStorage;
		// 缓存未命中 — 通过 storage 缓存查询
		IStorageService storage = getCachedStorage(host);
		if (storage == null) return null;
		MEStorage meStorage = storage.getInventory();
		if (meStorage != null) holder.setCachedMeStorage(meStorage);
		return meStorage;
	}

	// ===== holder 感知重载（Spark 优化：消除高频路径冗余 getAe2StateHolder() 接口分发） =====

	/**
	 * 获取宿主缓存的 AE2 网格（holder 感知重载）
	 * <br/>
	 * 与 {@link #getCachedGrid(IAe2OutputHostBase)} 功能一致，但跳过冗余的
	 * {@code host.productivebeesgenesis$getAe2StateHolder()} 接口分发，
	 * 直接使用调用方已缓存的 holder 引用。Spark 热力图优化：减少每 tick ~73,000 次冗余接口分发。
	 *
	 * @param holder 已缓存的 AE2 状态持有者（非 null）
	 * @param host   输出宿主（用于获取网格节点引用）
	 * @return 已连接的网格，未连接或节点不存在时返回 null
	 */
	@Nullable
	public static IGrid getCachedGrid(Ae2OutputStateHolder holder, IAe2OutputHostBase host) {
		Object cached = holder.getCachedGrid();
		if (cached instanceof IGrid grid) return grid;
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return null;
		IGrid grid = managedNode.getGrid();
		if (grid != null) holder.setCachedGrid(grid);
		return grid;
	}

	/**
	 * 获取宿主缓存的 AE2 存储服务（holder 感知重载）
	 *
	 * @param holder 已缓存的 AE2 状态持有者（非 null）
	 * @param host   输出宿主
	 * @return 存储服务，网格未连接或服务不存在时返回 null
	 */
	@Nullable
	public static IStorageService getCachedStorage(Ae2OutputStateHolder holder, IAe2OutputHostBase host) {
		Object cached = holder.getCachedStorage();
		if (cached instanceof IStorageService storage) return storage;
		IGrid grid = getCachedGrid(holder, host);
		if (grid == null) return null;
		IStorageService storage = grid.getService(IStorageService.class);
		if (storage != null) holder.setCachedStorage(storage);
		return storage;
	}

	/**
	 * 获取宿主缓存的 ME 存储（holder 感知重载）
	 *
	 * @param holder 已缓存的 AE2 状态持有者（非 null）
	 * @param host   输出宿主
	 * @return ME 存储，存储服务不存在时返回 null
	 */
	@Nullable
	public static MEStorage getCachedMeStorage(Ae2OutputStateHolder holder, IAe2OutputHostBase host) {
		Object cached = holder.getCachedMeStorage();
		if (cached instanceof MEStorage meStorage) return meStorage;
		IStorageService storage = getCachedStorage(holder, host);
		if (storage == null) return null;
		MEStorage meStorage = storage.getInventory();
		if (meStorage != null) holder.setCachedMeStorage(meStorage);
		return meStorage;
	}

	/**
	 * 保存网格节点 NBT
	 * <br/>
	 * 节点不存在时安全短路。AE2 会在 tag 中以 {@code setTagName} 指定的键名写入子标签。
	 * <p>
	 * 线程安全：使用宿主级锁保护 save 操作，与 destroyNode 互斥，
	 * 避免在 saveToNBT 执行期间节点被销毁产生不完整 NBT。
	 *
	 * @param host 输出宿主
	 * @param tag 方块实体的 NBT 根标签
	 */
	public static void saveNodeNBT(IAe2OutputHostBase host, CompoundTag tag) {
		synchronized (host) {
			Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
			if (!(nodeObj instanceof IManagedGridNode node)) return;
			node.saveToNBT(tag);
		}
	}

	/**
	 * 加载网格节点 NBT
	 * <br/>
	 * 若节点尚未创建，先准备（不连接）再加载 NBT（处理方块实体加载顺序：loadAdditional 在 clearRemoved 之前）。
	 * 节点存在但 NBT 中无对应键时，AE2 内部会安全跳过。
	 * <p>
	 * 注意：此处只调用 {@link #prepareNode} 而非 {@code createNode}，
	 * 避免区块加载阶段触发 AE2 连接扫描导致递归栈溢出。
	 * <p>
	 * 线程安全：使用宿主级锁保护 load 操作，与 destroyNode 互斥。
	 *
	 * @param host 输出宿主
	 * @param tag  方块实体的 NBT 根标签
	 */
	public static void loadNodeNBT(IAe2OutputHostBase host, CompoundTag tag) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		synchronized (host) {
			// 节点未创建时先准备（不连接，避免区块加载递归）
			if (host.productivebeesgenesis$getAe2GridNode() == null) {
				prepareNode(host);
			}
			Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
			if (!(nodeObj instanceof IManagedGridNode node)) return;
			node.loadFromNBT(tag);
		}
	}

	/**
	 * 离心机网格节点监听器
	 * <br/>
	 * 实现 {@link IGridNodeListener}，在节点状态变化时通知宿主方块实体保存。
	 * <p>
	 * Task 12：重写 {@link #onGridChanged} 失效 {@link Ae2OutputStateHolder} 中的
	 * cachedGrid/cachedStorage/cachedMeStorage 三个缓存，避免持有旧网格的存储服务引用。
	 * onInWorldConnectionChanged/onOwnerChanged/onStateChanged 仍使用默认实现。
	 */
	private static final class CentrifugeGridNodeListener implements IGridNodeListener<IAe2OutputHostBase> {

		@Override
		public void onSaveChanges(IAe2OutputHostBase host, IGridNode node) {
			// 节点状态变化时标记方块实体为 dirty，确保持久化
			if (host instanceof BlockEntity be) {
				be.setChanged();
			}
		}

		@Override
		public void onGridChanged(IAe2OutputHostBase host, IGridNode node) {
			// Task 12：grid 变化时失效 AE2 网格/存储缓存，下次访问时重新查询
			// 避免持有旧网格的 IGrid/IStorageService/MEStorage 引用导致操作到错误网络
			// M4-3 + M11 修复：onGridChanged 可能在 AE2 网格线程调用，原方案 post 到主线程
			//   执行失效以避免竞态。但 executeIfPossible 在服务器关闭期间会通过 CompletableFuture
			//   抛出 CompletionException(RejectedExecutionException)，同步传播到 ChunkMap.processUnloads
			//   导致 "Failed to save chunk" ERROR（区块数据丢失风险）。
			//
			// 综合权衡后采用"直接同步失效"方案：
			// 1. invalidateGridCache 仅写 3 个 volatile 字段，单字段写本身原子
			// 2. 竞态影响有限：主线程 push 可能读到 grid=null 但 storage!=null 的不一致状态，
			//    最多导致一次 push 失败（getCachedStorage 会再次校验 grid），无数据损坏
			// 3. 服务器关闭期间 push 不会再被调用，无竞态风险
			// 4. 避免所有异步异常传播路径，根治 "Failed to save chunk" 问题
			invalidateGridCache(host);
		}
	}

	/**
	 * 失效 AE2 网格/存储缓存（封装供 onGridChanged 回调复用）
	 * <br/>
	 * 仅失效 cachedGrid/cachedStorage/cachedMeStorage 三个字段，
	 * 下次 getCachedGrid/getCachedStorage/getCachedMeStorage 调用时重新查询 AE2 API。
	 */
	private static void invalidateGridCache(IAe2OutputHostBase host) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder != null) holder.onGridChanged();
	}
}
