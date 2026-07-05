package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.me.InWorldGridNode;

import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;

import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * AE2 输出宿主接口
 * <br/>
 * 定义离心机向 AE2 网格推送输出所需的依赖，并实现 AE2 19.x 标准的
 * {@link IInWorldGridNodeHost} 契约，使离心机能被 AE2 线缆发现并自动建立网格连接。
 * <p>
 * <b>AE2 类引用控制</b>：本接口自 v1.7.0 起强引用 AE2 API 类（{@code IInWorldGridNodeHost}、
 * {@code IGridNode}、{@code AECableType} 等），这是实现 AE2 cable 连接的必要设计权衡。
 * AE2 未安装时通过 {@link Ae2IntegrationLoader#isAe2Loaded()} 在所有调用点短路保护，
 * 防止类加载失败。capability 注册也仅在 AE2 已安装时执行（参见 {@link Ae2CapabilityRegistrar}）。
 * 网格节点字段仍使用 {@code Object} 类型存储，避免在状态持有者层强引用 AE2 类。
 * <p>
 * 继承 {@link PbRecipeContext} 以暴露输出槽访问方法（primaryOutputSlot 等），
 * 供 {@link Ae2OutputPusher} 遍历所有进程的输出槽进行推送。
 * <p>
 * 所有方法使用 {@code productivebeesgenesis$} 前缀，避免与其他模组的 Mixin 冲突。
 * <p>
 * <b>组合模式</b>：纯字段访问的 getter/setter 委托给
 * {@link Ae2OutputStateHolder}（通过 {@link MekAe2LifecycleHandler}），
 * 消除四个 TileEntity 类的字段/方法重复。委托给宿主 {@code this} 的方法（能量源、世界、坐标）
 * 仍由实现类提供。
 *
 * @since 1.0.0
 */
public interface IAe2OutputHost extends PbRecipeContext, IInWorldGridNodeHost {

	/** NBT 中保存 AE2 网格节点的标签名 */
	String AE2_NODE_TAG = "productivebeesgenesis_ae2_node";

	/**
	 * 获取 AE2 生命周期处理器（子类必须实现）
	 * <br/>
	 * 返回由 TileEntity 持有的 {@link MekAe2LifecycleHandler} 实例，
	 * 供本接口的 default 方法委托字段访问和生命周期管理。
	 *
	 * @return 生命周期处理器实例，不应为 null
	 * @since 1.7.0
	 */
	MekAe2LifecycleHandler productivebeesgenesis$getAe2LifecycleHandler();

	/**
	 * 获取 AE2 状态持有者 — 委托给生命周期处理器
	 *
	 * @return 状态持有者实例，不应为 null
	 */
	default Ae2OutputStateHolder productivebeesgenesis$getAe2StateHolder() {
		return productivebeesgenesis$getAe2LifecycleHandler().getStateHolder();
	}

	/**
	 * 获取 AE2 网格节点
	 * <br/>
	 * 返回 {@code Object} 类型而非 {@code appeng.api.networking.IManagedGridNode}，
	 * 避免 AE2 未安装时类加载失败。实际类型由 {@link Ae2GridNodeManager} 强制转换。
	 *
	 * @return 网格节点对象，未创建时返回 null
	 */
	default Object productivebeesgenesis$getAe2GridNode() {
		return productivebeesgenesis$getAe2StateHolder().getAe2GridNode();
	}

	/**
	 * 设置 AE2 网格节点
	 *
	 * @param node 网格节点对象（实际类型为 IManagedGridNode），可为 null
	 */
	default void productivebeesgenesis$setAe2GridNode(Object node) {
		productivebeesgenesis$getAe2StateHolder().setAe2GridNode(node);
	}

	/**
	 * 获取能量源 — 用于 AE2 poweredInsert 的能量消耗
	 * <br/>
	 * 返回离心机自身的 {@link MachineEnergyContainer}，由
	 * {@link Ae2OutputPusher} 内部的适配器包装为 AE2 的 IEnergySource。
	 */
	MachineEnergyContainer<?> productivebeesgenesis$getAe2EnergySource();

	/** 获取方块实体所在世界 */
	Level productivebeesgenesis$getAe2Level();

	/** 获取方块实体位置 */
	BlockPos productivebeesgenesis$getAe2BlockPos();

	/**
	 * 获取 AEItemKey 缓存
	 * <br/>
	 * 返回 {@code Object} 类型而非 {@link AeItemKeyCache}，避免接口强引用 AE2 类。
	 * 实际类型由 {@link Ae2GridNodeManager} 强制转换。AE2 未安装或节点未创建时返回 null。
	 *
	 * @return AeItemKeyCache 实例，或 null
	 */
	default Object productivebeesgenesis$getAeItemKeyCache() {
		return productivebeesgenesis$getAe2StateHolder().getAeItemKeyCache();
	}

	/**
	 * 设置 AEItemKey 缓存
	 *
	 * @param cache AeItemKeyCache 实例（实际类型），可为 null
	 */
	default void productivebeesgenesis$setAeItemKeyCache(Object cache) {
		productivebeesgenesis$getAe2StateHolder().setAeItemKeyCache(cache);
	}

	/**
	 * 推送完成回调
	 * <br/>
	 * 在 {@link Ae2OutputPusher#pushOutputs} 成功推送物品后调用，
	 * 默认实现刷新输出槽状态标志位（继承自 {@link PbRecipeContext}）。
	 * 实现类可覆盖以添加额外逻辑（如版本号递增）。
	 *
	 * @param pushedItems 本次推送的物品总数
	 */
	default void productivebeesgenesis$onAe2PushComplete(int pushedItems) {
		// 推送后输出槽内容变化，刷新标志位避免 Ejector 误判
		productivebeesgenesis$updateOutputSlotFlags();
	}

	// ===== IInWorldGridNodeHost 契约实现（v1.7.0 新增，使离心机能被 AE2 线缆发现） =====

	/**
	 * 返回指定方向暴露的 AE2 网格节点
	 * <br/>
	 * 实现 AE2 19.x 标准 {@link IInWorldGridNodeHost#getGridNode(Direction)} 契约。
	 * AE2 线缆通过 {@code GridHelper.getExposedNode} 调用此方法发现相邻方块上的节点，
	 * 建立 {@code GridConnection}。
	 * <p>
	 * <b>实现细节</b>：
	 * <ol>
	 *   <li>从状态持有者取出 {@link IManagedGridNode}（可能为 null）</li>
	 *   <li>取已连接的 {@link IGridNode}（未连接时为 null）</li>
	 *   <li>校验节点类型为 {@link InWorldGridNode}（与 {@link Ae2GridNodeManager#prepareNode} 中
	 *       {@code setInWorldNode(true)} 设置一致）</li>
	 *   <li>校验查询方向在 {@code setExposedOnSides(ALL)} 暴露的方向集合中</li>
	 * </ol>
	 * <p>
	 * <b>AE2 未安装时</b>：本方法不会被调用（capability 注册时跳过，AE2 线缆也不会查询），
	 * 即使被调用也会因 {@code productivebeesgenesis$getAe2GridNode()} 返回 null 而安全返回 null。
	 * <p>
	 * <b>AE2 已安装但配置关闭时</b>：节点不会被创建（{@link Ae2GridNodeManager#prepareNode} 短路），
	 * 本方法返回 null，AE2 线缆看到的是"无节点方块"。
	 *
	 * @param dir 查询方向
	 * @return 该方向暴露的网格节点，未创建/未连接/方向未暴露时返回 null
	 */
	@Override
	default @Nullable IGridNode getGridNode(Direction dir) {
		Object nodeObj = productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return null;
		IGridNode node = managedNode.getNode();
		// 仅 InWorldGridNode 才暴露给线缆（与 prepareNode 中 setInWorldNode(true) 一致）
		if (!(node instanceof InWorldGridNode inWorldNode)) return null;
		// 校验方向是否在 setExposedOnSides 集合中
		if (!inWorldNode.isExposedOnSide(dir)) return null;
		return node;
	}

	/**
	 * 返回离心机的 AE2 线缆连接类型
	 * <br/>
	 * 返回 {@link AECableType#SMART}，与 Mek-Energistics 的 {@code MeSmartCableConnection} 一致。
	 * 离心机作为高级机器接入智能线缆，AE2 线缆会根据此值决定连接渲染样式和连接优先级。
	 *
	 * @param dir 查询方向（所有方向均返回 SMART）
	 * @return {@link AECableType#SMART}
	 */
	@Override
	default AECableType getCableConnectionType(Direction dir) {
		return AECableType.SMART;
	}
}
