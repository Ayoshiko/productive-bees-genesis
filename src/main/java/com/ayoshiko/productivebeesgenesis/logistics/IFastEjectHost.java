package com.ayoshiko.productivebeesgenesis.logistics;

import net.minecraft.world.item.ItemStack;

/**
 * 快速弹出宿主 —— 由 {@code TileComponentEjector} 的 Mixin 实现，
 * 让配方输出侧（{@code PbRecipeFlusher} 等）无需知道弹出实现细节即可发起「产物直通」。
 * <p>
 * 弹出器天然持有「哪些侧面是输出面、相邻容器能力缓存、自动弹出是否开启」这些信息，
 * 因此直通输出与逐刻弹出共用同一份目标解析与拒收备忘（DIP：调用方只依赖本接口）。
 *
 * @since 2.1.0
 */
public interface IFastEjectHost {

	/**
	 * 先模拟再放入：把刚生成的产物直接推给已配置输出面的相邻容器。
	 * <p>
	 * 不会修改传入的 {@link ItemStack}；调用方按返回值扣减待提交产物即可。
	 * 机器自动弹出关闭、没有输出面、没有相邻容器或目标塞不下时返回 0。
	 *
	 * @param stack 待推送产物（数量为本次希望推送的总量）
	 * @return 实际被接收的数量
	 */
	int productivebeesgenesis$pushGeneratedItem(ItemStack stack);

	/**
	 * 便捷委托：把机器的弹出器组件当作直通宿主使用。
	 *
	 * @param ejectorComponent Mekanism 弹出器组件（可为 null 或未被 Mixin 增强）
	 * @param stack            待推送产物
	 * @return 实际被接收的数量；宿主不可用时返回 0
	 */
	static int push(Object ejectorComponent, ItemStack stack) {
		return ejectorComponent instanceof IFastEjectHost host
				? host.productivebeesgenesis$pushGeneratedItem(stack) : 0;
	}
}
