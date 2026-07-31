package com.ayoshiko.productivebeesgenesis.util;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 多花蜜脾蜜蜂适配器接口
 * <br/>
 * 适配 PB 原版的 lumber_bee/quarry_bee/dye_bee/wanna 等多花蜜脾蜜蜂。
 * 这些蜜蜂的产出依赖实体蜜蜂的 savedFlowerPos，万象创世蜂箱无实体无法直接支持。
 * 未来可通过实现此接口，从喂食器中的 BlockItem 推断产物。
 *
 * <h3>集成点（未来实施，当前仅记录）</h3>
 * <ul>
 *   <li>{@code BeeProduceProcessor.getCachedProduce} 查询 {@code MultiFlowerBeeAdapter}，
 *       若命中则使用适配器产出</li>
 *   <li>{@code BeeInfoHelper.getBeeProduce} 增加 {@code MultiFlowerBeeAdapter} 分支</li>
 * </ul>
 *
 * <p>设计原则（ISP 接口隔离）：仅暴露 2 个最小方法，调用方按需实现，不强制依赖其他能力。
 *
 * @since 2.1.0
 */
public interface MultiFlowerBeeAdapter {

	/** 默认无操作实现 — 未注册适配器时使用，避免 NPE 并保持调用路径统一 */
	MultiFlowerBeeAdapter NOOP = new MultiFlowerBeeAdapter() {
		@Override
		public boolean isMultiFlowerBee(ResourceLocation beeTypeKey) {
			return false;
		}

		@Override
		public List<ItemStack> produceFromFeederItem(ResourceLocation beeTypeKey, ItemStack feederItem) {
			return List.of();
		}
	};

	/**
	 * 该蜜蜂类型是否为多花蜜脾蜜蜂
	 *
	 * @param beeTypeKey 蜜蜂类型 ID
	 * @return true 如果该蜜蜂为多花蜜蜂，需要走适配器产出路径
	 */
	boolean isMultiFlowerBee(ResourceLocation beeTypeKey);

	/**
	 * 根据喂食器中的 BlockItem 推断产物
	 *
	 * @param beeTypeKey  蜜蜂类型 ID
	 * @param feederItem  喂食器中的物品栈（通常为 BlockItem）
	 * @return 产物列表，空列表表示无产物
	 */
	List<ItemStack> produceFromFeederItem(ResourceLocation beeTypeKey, ItemStack feederItem);
}
