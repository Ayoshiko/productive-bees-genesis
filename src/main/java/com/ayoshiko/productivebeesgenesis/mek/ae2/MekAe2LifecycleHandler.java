package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IManagedGridNode;
import net.minecraft.nbt.CompoundTag;

/**
	 * MEK 离心机 AE2 生命周期处理器
	 * <br/>
	 * 封装 {@link Ae2OutputStateHolder} 和 AE2 网格节点的生命周期管理逻辑，
	 * 消除 4 个 TileEntity 类（基础离心机 + 3 个工厂）的 AE2 代码重复（约 120 行）。
	 * <p>
	 * <b>职责</b>：
	 * <ul>
	 *   <li>持有 {@link Ae2OutputStateHolder} 实例（网格节点、AEItemKey 缓存、待连接标志、复用缓冲区）</li>
	 *   <li>提供节点准备、连接、销毁、NBT 持久化方法</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：所有 public 方法使用宿主级锁 {@code synchronized(host)} 保护，
	 * 与 {@link Ae2GridNodeManager} 的 prepareNode/connectNode/destroyNode 使用同一把锁保证互斥。
	 * Java 的 synchronized 是可重入锁，handler 方法调用 Ae2GridNodeManager 的 synchronized 方法不会自死锁。
	 * <p>
	 * <b>设计原则</b>：
	 * <ul>
	 *   <li>单一职责：只负责 AE2 生命周期管理，不涉及输出推送或配方处理</li>
	 *   <li>依赖倒置：通过 {@link IAe2OutputHostBase} 抽象访问宿主，不依赖具体 TileEntity</li>
	 *   <li>开闭原则：新增 TileEntity 类型只需持有本类实例并实现 getter</li>
	 * </ul>
	 *
	 * @since 1.5.3
	 */
public final class MekAe2LifecycleHandler {

	/** AE2 状态持有者 — 封装网格节点、AEItemKey 缓存、待连接标志、复用缓冲区 */
	private final Ae2OutputStateHolder stateHolder = new Ae2OutputStateHolder();

	/**
	 * AE2 节点是否已连接到网格（Task 13）
	 * <br/>
	 * volatile 保证跨线程可见性。节点首次连接成功后置 true，供 {@link #tryConnectNode}
	 * 快速路径短路，避免 256× 加速下每 gameTick 256 次进入 {@code synchronized(host)} 块的开销。
	 * 在 {@link #destroyForRemoval} / {@link #destroyForChunkUnload} 时重置为 false。
	 */
	private volatile boolean ae2NodeConnected = false;

	/**
	 * 获取状态持有者
	 * <br/>
	 * 供 {@link IAe2OutputHostBase} 接口的 default 方法委托字段访问（网格节点、AEItemKey 缓存等）。
	 *
	 * @return 状态持有者实例，不应为 null
	 */
	public Ae2OutputStateHolder getStateHolder() {
		return stateHolder;
	}

	/**
	 * 准备 AE2 网格节点（不接入网格）
	 * <br/>
	 * 用于 {@code clearRemoved} 阶段，创建节点并配置但<b>不调用</b> {@code create(level, pos)}，
	 * 避免区块加载时 AE2 连接扫描触发邻近方块实体懒加载导致递归栈溢出。
	 * 同时标记待连接标志，由首个 server tick 的 {@link #tryConnectNode} 执行实际连接。
	 * <p>
	 * 线程安全：在宿主级锁内执行"创建节点 + 置 pending"原子操作，
	 * 避免与 destroyForRemoval 并发时出现"节点已销毁但 pending=true"的状态不一致。
	 *
	 * @param host 输出宿主（离心机方块实体）
	 */
	public void prepareForLoad(IAe2OutputHostBase host) {
		// AE2 未安装守卫：必须在调用 Ae2GridNodeManager 之前拦截，
		// 否则类加载验证会解析 IGridNodeListener 触发 NoClassDefFoundError（Issue #8）
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		synchronized (host) {
			Ae2GridNodeManager.prepareNode(host);
			stateHolder.setAe2NodePending(true);
		}
	}

	/**
	 * 销毁 AE2 网格节点并清空状态（方块移除时调用）
	 * <br/>
	 * 调用 {@link Ae2GridNodeManager#destroyNode} 销毁节点并释放引用，
	 * 然后清空 {@link Ae2OutputStateHolder} 的所有状态，防止方块重建后残留旧状态。
	 * <p>
	 * 线程安全：在宿主级锁内执行"销毁节点 + 清空状态"原子操作，
	 * 避免 destroyNode 与 clear 之间观察到中间状态（node 已 null 但 cache 仍非 null）。
	 *
	 * @param host 输出宿主
	 */
	public void destroyForRemoval(IAe2OutputHostBase host) {
		// AE2 未安装守卫：节点从未创建，无需销毁，同时避免触发 Ae2GridNodeManager 类加载
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		synchronized (host) {
			Ae2GridNodeManager.destroyNode(host);
			stateHolder.clear();
			// 重置连接标志，方块重建后 handler 实例可能复用，确保重新走连接流程
			ae2NodeConnected = false;
		}
	}

	/**
	 * 销毁 AE2 网格节点并清空状态（区块卸载时调用）
	 * <br/>
	 * 与 {@link #destroyForRemoval} 行为一致，{@link Ae2GridNodeManager#destroyNode} 幂等，
	 * 与 setRemoved 重复调用安全。
	 *
	 * @param host 输出宿主
	 */
	public void destroyForChunkUnload(IAe2OutputHostBase host) {
		// AE2 未安装守卫：与 destroyForRemoval 一致，避免触发 Ae2GridNodeManager 类加载
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		synchronized (host) {
			Ae2GridNodeManager.destroyNode(host);
			stateHolder.clearForChunkUnload();
			// 重置连接标志，与 destroyForRemoval 行为一致
			ae2NodeConnected = false;
		}
	}

	/**
	 * 尝试连接 AE2 网格节点到网络
	 * <br/>
	 * 在 onUpdateServer 中每 tick 调用，委托 {@link Ae2GridNodeManager#connectNode} 执行实际连接。
	 * connectNode 内部有 {@code node.getNode() != null} 幂等检查，已连接时直接返回，调用安全且廉价。
	 * <p>
	 * <b>Task 13 快速路径</b>：使用 {@code volatile ae2NodeConnected} 标志在 handler 层提前短路，
	 * 节点已连接时直接返回，避免 256× 加速下每 gameTick 256 次进入 {@code synchronized(host)} 块。
	 * 采用双重检查锁定模式（volatile + synchronized）保证线程安全；连接成功后才置 true，
	 * 避免假阳性；{@link #destroyForRemoval} / {@link #destroyForChunkUnload} 时重置为 false。
	 * <p>
	 * <b>修复重试缺陷</b>：原实现使用 {@code isAe2NodePending} 标志门控，首次调用后清除标志，
	 * 若首次 {@code create()} 失败则永不重试。改为每 tick 直接调用 connectNode，
	 * 由其内部幂等检查保证不重复 create，同时实现首次失败后的自动重试。
	 * <p>
	 * 必须在 server tick 中调用，不能在 clearRemoved 中调用，
	 * 否则 AE2 连接扫描会触发邻近方块实体懒加载导致递归栈溢出。
	 *
	 * @param host 输出宿主
	 */
	public void tryConnectNode(IAe2OutputHostBase host) {
		// AE2 未安装守卫：方法体内 IManagedGridNode 模式匹配与 connectNode 调用均会触发 AE2 类解析
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// 快速路径：已连接则跳过 synchronized，避免 256× 加速下每 tick 256 次锁竞争
		if (ae2NodeConnected) {
			return;
		}
		synchronized (host) {
			// 双重检查：防止多线程同时通过第一次检查
			if (ae2NodeConnected) {
				return;
			}
			Ae2GridNodeManager.connectNode(host);
			// 连接成功才置 true，避免假阳性（节点未创建或 create 失败时保持 false 以便下 tick 重试）
			Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
			if (nodeObj instanceof IManagedGridNode node && node.getNode() != null) {
				ae2NodeConnected = true;
			}
		}
	}

	/**
	 * 保存 AE2 网格节点 NBT
	 *
	 * @param host 输出宿主
	 * @param tag  方块实体的 NBT 根标签
	 */
	public void saveNodeNBT(IAe2OutputHostBase host, CompoundTag tag) {
		// AE2 未安装守卫：节点为 null 无需持久化，同时避免触发 Ae2GridNodeManager 类加载
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		Ae2GridNodeManager.saveNodeNBT(host, tag);
	}

	/**
	 * 加载 AE2 网格节点 NBT
	 * <br/>
	 * 若节点尚未创建，{@link Ae2GridNodeManager#loadNodeNBT} 内部会先准备（不连接）再加载 NBT。
	 *
	 * @param host 输出宿主
	 * @param tag  方块实体的 NBT 根标签
	 */
	public void loadNodeNBT(IAe2OutputHostBase host, CompoundTag tag) {
		// AE2 未安装守卫：无需恢复节点状态，同时避免触发 Ae2GridNodeManager 类加载
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		Ae2GridNodeManager.loadNodeNBT(host, tag);
	}

	// ===== 高层生命周期方法（语义别名，供 TileEntity 委托调用减少样板代码） =====

	/**
	 * 处理方块实体加载（clearRemoved 阶段）
	 * <br/>
	 * 语义入口：创建 AE2 网格节点但不接入网格，避免区块加载时递归栈溢出。
	 * 详见 {@link #prepareForLoad}。
	 *
	 * @param host 输出宿主
	 * @since 1.5.3
	 */
	public void handleLoad(IAe2OutputHostBase host) {
		prepareForLoad(host);
	}

	/**
	 * 处理方块移除（setRemoved 阶段）
	 * <br/>
	 * 语义入口：销毁 AE2 网格节点并清空状态，避免内存泄漏。
	 * 详见 {@link #destroyForRemoval}。
	 *
	 * @param host 输出宿主
	 * @since 1.5.3
	 */
	public void handleRemove(IAe2OutputHostBase host) {
		destroyForRemoval(host);
	}

	/**
	 * 处理区块卸载（onChunkUnloaded 阶段）
	 * <br/>
	 * 语义入口：销毁 AE2 网格节点，与 {@link #handleRemove} 幂等。
	 * 详见 {@link #destroyForChunkUnload}。
	 *
	 * @param host 输出宿主
	 * @since 1.5.3
	 */
	public void handleChunkUnload(IAe2OutputHostBase host) {
		destroyForChunkUnload(host);
	}
}
