package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.me.InWorldGridNode;

/**
 * AE2 输出宿主接口 — 仅包含 AE2 线缆连接契约
 * <br/>
 * 继承 {@link IAe2OutputHostBase}（无 AE2 引用的基础契约）和
 * {@link IInWorldGridNodeHost}（AE2 19.x 标准线缆发现契约），
 * 使离心机/蜂箱能被 AE2 线缆发现并自动建立网格连接。
 * <p>
 * <b>Task 3 拆分</b>：原接口拆分为 Base + AE2 专用两层：
 * <ul>
 *   <li>{@link IAe2OutputHostBase} — 无 import appeng，包含所有非 AE2 方法（生命周期、
 *       输出推送、能量注入、配置缓存等）。TileEntity 实现此接口即可在 AE2 未安装时正常加载。</li>
 *   <li>{@link IAe2OutputHost}（本接口）— 仅保留 {@link #getGridNode(Direction)} 和
 *       {@link #getCableConnectionType(Direction)} 两个 AE2 契约方法。
 *       由 Mixin（Task 4）在 AE2 已安装时动态添加到 TileEntity，避免 TileEntity
 *       类签名强引用 AE2 类。</li>
 * </ul>
 * <p>
 * <b>AE2 类引用控制</b>：本接口强引用 AE2 API 类（{@code IInWorldGridNodeHost}、
 * {@code IGridNode}、{@code AECableType} 等），这是实现 AE2 cable 连接的必要设计权衡。
 * AE2 未安装时通过 {@link Ae2IntegrationLoader#isAe2Loaded()} 在所有调用点短路保护，
 * 防止类加载失败。capability 注册也仅在 AE2 已安装时执行（参见 {@link Ae2CapabilityRegistrar}）。
 * <p>
 * <b>IInWorldGridNodeHost 契约实现</b>：本接口为 {@code getGridNode} 和
 * {@code getCableConnectionType} 提供 default 实现，使实现类无需手动实现这两个方法。
 * 实现细节：
 * <ol>
 *   <li>从状态持有者取出 {@link IManagedGridNode}（可能为 null）</li>
 *   <li>取已连接的 {@link IGridNode}（未连接时为 null）</li>
 *   <li>校验节点类型为 {@link InWorldGridNode}（与 {@link Ae2GridNodeManager#prepareNode} 中
 *       {@code setInWorldNode(true)} 设置一致）</li>
 *   <li>校验查询方向在 {@code setExposedOnSides(ALL)} 暴露的方向集合中</li>
 * </ol>
 * <p>
 * <b>AE2 未安装时</b>：本接口不会被加载（Mixin 仅在 AE2 已安装时应用），default 方法
 * 不会被调用。capability 注册时也会跳过。
 * <p>
 * <b>AE2 已安装但配置关闭时</b>：节点不会被创建（{@link Ae2GridNodeManager#prepareNode} 短路），
 * {@link #getGridNode} 返回 null，AE2 线缆看到的是"无节点方块"。
 *
 * @since 1.7.0
 */
public interface IAe2OutputHost extends IAe2OutputHostBase, IInWorldGridNodeHost {

	/**
	 * 返回指定方向暴露的 AE2 网格节点
	 * <br/>
	 * 实现 AE2 19.x 标准 {@link IInWorldGridNodeHost#getGridNode(Direction)} 契约。
	 * AE2 线缆通过 {@code GridHelper.getExposedNode} 调用此方法发现相邻方块上的节点，
	 * 建立 {@code GridConnection}。
	 * <p>
	 * <b>AE2 未安装时</b>：本方法不会被调用（capability 注册时跳过，AE2 线缆也不会查询），
	 * 即使被调用也会因 {@code productivebeesgenesis$getAe2GridNode()} 返回 null 而安全返回 null。
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
