package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

/**
 * MEK 离心机统一标记接口。
 * 用于 TileComponentEjectorMixin 通过 instanceof 统一判断所有离心机类型，
 * 避免硬依赖 ME/EME 可选模组类引发 ClassNotFoundException。
 * <p>
 * Task 16: 暴露输出槽内容版本号，供 Ejector Mixin 在输出槽内容未变化时跳过
 * 高频的 outputItems 调用，降低 TimeWand 高倍加速下的 CPU 开销。
 * <p>
 * Step 5: 暴露输出槽物品总数（O(1) 读取），供 Ejector Mixin 替代
 * O(processes×3) 遍历的 {@code countOutputItems}，进一步降低高频弹出时的 CPU 开销。
 * <p>
 * 新增：输入槽访问方法，供蜂箱→离心机直连快速通道使用，
 * 绕过 Ejector 节流和 Capability 系统开销，最大化直连弹出效率。
 */
public interface IMekCentrifugeTile {

	/**
	 * 输出槽内容版本号。
	 * <br/>
	 * 每次输出槽内容发生变化时应递增；Ejector Mixin 通过比较版本号判断是否需要执行
	 * outputItems。服务端单线程访问，实现类可用 volatile 保证可见性。
	 */
	long productivebeesgenesis$outputContentsVersion();

	/**
	 * 返回输出槽是否已满，供 Ejector Mixin 在输出槽满时取消跳过。
	 * <br/>
	 * 当所有物品输出槽均无剩余空间时返回 true；此时若继续跳过 outputItems，可能导致产物积压、机器停机，
	 * 因此 Mixin 会立即重置跳过计数器并尝试输出。
	 */
	boolean productivebeesgenesis$outputSlotsFull();

	/**
	 * 返回所有输出槽的物品总数，供 Ejector Mixin 替代 O(processes×3) 遍历计数。
	 * <br/>
	 * 实现类通过 {@link OutputSlotFlagManager}（工厂）或本地字段（基础机）维护，
	 * 在输出槽内容变更时增量更新，读取为 O(1)。非目标机器默认返回 0。
	 */
	default long productivebeesgenesis$outputItemCount() {
		return 0L;
	}

	/**
	 * 返回输入槽数量（基础机=1，工厂机=tier.processes）。
	 * <br/>
	 * 供蜂箱→离心机直连快速通道使用，用于并行分配不同类型的蜜脾到不同输入槽，
	 * 最大化离心机并行处理效率。
	 *
	 * @return 输入槽总数
	 */
	default int productivebeesgenesis$getInputSlotCount() {
		return 0;
	}

	/**
	 * 获取指定索引的输入槽。
	 * <br/>
	 * 索引范围：0 ~ {@link #productivebeesgenesis$getInputSlotCount()} - 1。
	 * 供蜂箱→离心机直连快速通道直接向输入槽插入物品，绕过 Capability 系统开销。
	 *
	 * @param index 输入槽索引
	 * @return 输入槽引用，越界返回 null
	 */
	default IInventorySlot productivebeesgenesis$getInputSlot(int index) {
		return null;
	}

	/**
	 * 检查物品是否为有效的离心配方输入。
	 * <br/>
	 * 供蜂箱→离心机直连快速通道过滤无效物品，避免无效插入尝试。
	 *
	 * @param stack 待检查的物品栈
	 * @return true 如果该物品可以在离心机中加工
	 */
	default boolean productivebeesgenesis$isValidInput(ItemStack stack) {
		return false;
	}
}
