package com.ayoshiko.productivebeesgenesis.mek.ae2;

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
 *   <li>依赖倒置：通过 {@link IAe2OutputHost} 抽象访问宿主，不依赖具体 TileEntity</li>
 *   <li>开闭原则：新增 TileEntity 类型只需持有本类实例并实现 getter</li>
 * </ul>
 *
 * @since 1.7.0
 */
public final class MekAe2LifecycleHandler {

	/** AE2 状态持有者 — 封装网格节点、AEItemKey 缓存、待连接标志、复用缓冲区 */
	private final Ae2OutputStateHolder stateHolder = new Ae2OutputStateHolder();

	/**
	 * 获取状态持有者
	 * <br/>
	 * 供 {@link IAe2OutputHost} 接口的 default 方法委托字段访问（网格节点、AEItemKey 缓存等）。
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
	public void prepareForLoad(IAe2OutputHost host) {
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
	public void destroyForRemoval(IAe2OutputHost host) {
		synchronized (host) {
			Ae2GridNodeManager.destroyNode(host);
			stateHolder.clear();
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
	public void destroyForChunkUnload(IAe2OutputHost host) {
		synchronized (host) {
			Ae2GridNodeManager.destroyNode(host);
			stateHolder.clear();
		}
	}

	/**
	 * 尝试连接 AE2 网格节点到网络
	 * <br/>
	 * 在 onUpdateServer 中调用，检查待连接标志，若为 true 则执行实际连接并清除标志。
	 * 必须在首个 server tick 调用，不能在 clearRemoved 中调用，
	 * 否则 AE2 连接扫描会触发邻近方块实体懒加载导致递归栈溢出。
	 * <p>
	 * 线程安全：在宿主级锁内执行"检查 pending + 置 false + 连接节点"原子操作，
	 * 避免 check-then-set 竞态导致 connectNode 被重复调用。
	 *
	 * @param host 输出宿主
	 */
	public void tryConnectNode(IAe2OutputHost host) {
		synchronized (host) {
			if (stateHolder.isAe2NodePending()) {
				stateHolder.setAe2NodePending(false);
				Ae2GridNodeManager.connectNode(host);
			}
		}
	}

	/**
	 * 保存 AE2 网格节点 NBT
	 *
	 * @param host 输出宿主
	 * @param tag  方块实体的 NBT 根标签
	 */
	public void saveNodeNBT(IAe2OutputHost host, CompoundTag tag) {
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
	public void loadNodeNBT(IAe2OutputHost host, CompoundTag tag) {
		Ae2GridNodeManager.loadNodeNBT(host, tag);
	}
}
