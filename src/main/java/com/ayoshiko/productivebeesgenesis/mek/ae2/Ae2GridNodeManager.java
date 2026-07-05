package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;

/**
 * AE2 网格节点生命周期管理器
 * <br/>
 * 封装 {@link IManagedGridNode} 的创建、销毁、NBT 持久化逻辑。
 * 所有方法都进行空检查和 AE2 加载状态检查，AE2 未安装时安全短路返回。
 * <p>
 * <b>v1.8.2 解耦</b>：节点创建只受 {@link Ae2IntegrationLoader#isAe2Loaded()} 控制，
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
	public static void prepareNode(IAe2OutputHost host) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// 宿主级锁保护 check-then-act，避免并发创建重复节点
		synchronized (host) {
			// 幂等：节点已存在则不重复创建
			if (host.productivebeesgenesis$getAe2GridNode() != null) return;

			Level level = host.productivebeesgenesis$getAe2Level();
			BlockPos pos = host.productivebeesgenesis$getAe2BlockPos();
			if (level == null || pos == null) return;

			IGridNodeListener<IAe2OutputHost> listener = new CentrifugeGridNodeListener();
			IManagedGridNode node = GridHelper.createManagedNode(host, listener);
			// 节点配置
			node.setExposedOnSides(EnumSet.allOf(Direction.class));
			node.setInWorldNode(true);
			node.setFlags(GridFlags.REQUIRE_CHANNEL);
			node.setIdlePowerUsage(IDLE_POWER_USAGE);
			node.setTagName(IAe2OutputHost.AE2_NODE_TAG);
			// 视觉表现：用离心机方块本身（便于 AE2 网络工具识别）
			if (host instanceof BlockEntity be) {
				node.setVisualRepresentation(be.getBlockState().getBlock());
			}
			// 注意：不调用 node.create()，延迟到 connectNode 避免区块加载递归栈溢出
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
	public static void connectNode(IAe2OutputHost host) {
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
	public static int getGridNodeState(IAe2OutputHost host) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return 0;
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return 0;

		IGridNode node = managedNode.getNode();
		if (node == null) return 0;

		// 未供电 → OFFLINE
		if (!node.isPowered()) return 0;

		// 网格未启动 → NETWORK_BOOTING
		if (!node.hasGridBooted()) return 1;

		// 频道不满足 → MISSING_CHANNEL
		if (!node.meetsChannelRequirements()) return 2;

		// 全部满足 → ONLINE
		return 3;
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
	public static void destroyNode(IAe2OutputHost host) {
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
		}
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
	public static void saveNodeNBT(IAe2OutputHost host, CompoundTag tag) {
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
	public static void loadNodeNBT(IAe2OutputHost host, CompoundTag tag) {
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
	 * 其他事件（gridChanged/stateChanged）使用默认实现，避免不必要的逻辑。
	 */
	private static final class CentrifugeGridNodeListener implements IGridNodeListener<IAe2OutputHost> {

		@Override
		public void onSaveChanges(IAe2OutputHost host, IGridNode node) {
			// 节点状态变化时标记方块实体为 dirty，确保持久化
			if (host instanceof BlockEntity be) {
				be.setChanged();
			}
		}
	}
}
