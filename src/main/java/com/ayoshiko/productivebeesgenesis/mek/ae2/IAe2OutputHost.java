package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;

import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * AE2 输出宿主接口
 * <br/>
 * 定义离心机向 AE2 网络推送输出所需的依赖。<b>不引用任何 AE2 类</b>，
 * 网格节点使用 {@code Object} 类型，确保 AE2 未安装时离心机类仍可正常加载。
 * <p>
 * 继承 {@link PbRecipeContext} 以暴露输出槽访问方法（primaryOutputSlot 等），
 * 供 {@link Ae2OutputPusher} 遍历所有进程的输出槽进行推送。
 * <p>
 * 所有方法使用 {@code productivebeesgenesis$} 前缀，避免与其他模组的 Mixin 冲突。
 */
public interface IAe2OutputHost extends PbRecipeContext {

	/** NBT 中保存 AE2 网格节点的标签名 */
	String AE2_NODE_TAG = "productivebeesgenesis_ae2_node";

	/**
	 * 获取 AE2 网格节点
	 * <br/>
	 * 返回 {@code Object} 类型而非 {@code appeng.api.networking.IManagedGridNode}，
	 * 避免 AE2 未安装时类加载失败。实际类型由 {@link Ae2GridNodeManager} 强制转换。
	 *
	 * @return 网格节点对象，未创建时返回 null
	 */
	Object productivebeesgenesis$getAe2GridNode();

	/**
	 * 设置 AE2 网格节点
	 *
	 * @param node 网格节点对象（实际类型为 IManagedGridNode），可为 null
	 */
	void productivebeesgenesis$setAe2GridNode(Object node);

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
	Object productivebeesgenesis$getAeItemKeyCache();

	/**
	 * 设置 AEItemKey 缓存
	 *
	 * @param cache AeItemKeyCache 实例（实际类型），可为 null
	 */
	void productivebeesgenesis$setAeItemKeyCache(Object cache);

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
}
