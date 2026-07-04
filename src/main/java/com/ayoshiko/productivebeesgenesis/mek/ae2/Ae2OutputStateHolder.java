package com.ayoshiko.productivebeesgenesis.mek.ae2;

/**
 * AE2 输出状态持有者
 * <br/>
 * 封装三个工厂类共有的 AE2 网格节点和缓存状态，
 * 消除约 90 行字段/方法重复。
 * <p>
 * <b>线程安全</b>：字段使用 volatile 保证可见性，适用于主线程读写场景。
 * <p>
 * <b>依赖隔离</b>：字段使用 {@code Object} 类型避免强引用 AE2 类，
 * 实际类型由 {@link Ae2GridNodeManager} 强制转换。
 *
 * @since 1.0.0
 */
public final class Ae2OutputStateHolder {

	/** AE2 网格节点（实际类型为 IManagedGridNode，AE2 未安装时为 null） */
	private volatile Object ae2GridNode;

	/** AEItemKey 缓存（实际类型为 AeItemKeyCache，AE2 未安装时为 null） */
	private volatile Object aeItemKeyCache;

	/** 节点是否待创建（clearRemoved 时置 true，首个 server tick 时执行 connectNode） */
	private volatile boolean ae2NodePending;

	/**
	 * 获取 AE2 网格节点
	 *
	 * @return 网格节点对象，未创建时返回 null
	 */
	public Object getAe2GridNode() {
		return ae2GridNode;
	}

	/**
	 * 设置 AE2 网格节点
	 *
	 * @param node 网格节点对象（实际类型为 IManagedGridNode），可为 null
	 */
	public void setAe2GridNode(Object node) {
		this.ae2GridNode = node;
	}

	/**
	 * 获取 AEItemKey 缓存
	 *
	 * @return AeItemKeyCache 实例，或 null
	 */
	public Object getAeItemKeyCache() {
		return aeItemKeyCache;
	}

	/**
	 * 设置 AEItemKey 缓存
	 *
	 * @param cache AeItemKeyCache 实例（实际类型），可为 null
	 */
	public void setAeItemKeyCache(Object cache) {
		this.aeItemKeyCache = cache;
	}

	/**
	 * 节点是否待创建
	 *
	 * @return true 表示 clearRemoved 已调用但节点尚未连接
	 */
	public boolean isAe2NodePending() {
		return ae2NodePending;
	}

	/**
	 * 设置节点待创建标志
	 *
	 * @param pending true 表示待创建
	 */
	public void setAe2NodePending(boolean pending) {
		this.ae2NodePending = pending;
	}

	/**
	 * 清空所有状态（方块销毁时调用）
	 * <br/>
	 * 重置节点、缓存和待创建标志，防止方块重建后残留旧状态。
	 */
	public void clear() {
		ae2GridNode = null;
		aeItemKeyCache = null;
		ae2NodePending = false;
	}
}
