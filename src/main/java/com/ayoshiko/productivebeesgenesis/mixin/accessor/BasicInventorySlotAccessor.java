package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 Mekanism 10.7.19.85 的真实槽容量与库存字段。
 * 容量用于本模组堆叠倍率，current 用于失败后的实际库存核验。
 */
@Mixin(value = BasicInventorySlot.class, remap = false)
public interface BasicInventorySlotAccessor {

	/**
	 * 访问 BasicInventorySlot 的 protected 字段 `obeyStackLimit`（类型：`boolean`）
	 * <br/>
	 * 用于在 Mixin 中设置该字段为 false，使 getLimit 忽略物品自身 maxStackSize，
	 * 从而让蜂笼槽位可堆叠至 64。
	 *
	 * @param value 要设置的 obeyStackLimit 值
	 */
	@Accessor("obeyStackLimit")
	void productivebeesgenesis$setObeyStackLimit(boolean value);

	/**
	 * 访问 BasicInventorySlot 的 protected 字段 `obeyStackLimit`（类型：`boolean`）
	 * <br/>
	 * 用于在 Mixin 中读取当前 obeyStackLimit 值，判断槽位是否受物品自身 maxStackSize 限制。
	 *
	 * @return 原始 obeyStackLimit 字段值
	 */
	@Accessor("obeyStackLimit")
	boolean productivebeesgenesis$getObeyStackLimit();

	/**
	 * 访问 BasicInventorySlot 的 private final 字段 `limit`（类型：`int`）
	 * <br/>
	 * 用于在 ME/EME 工厂输入槽 Mixin 中读取槽位基础容量上限，
	 * 绕过 ME/EME 自身的 getLimit 倍率计算，使用配置倍率替代。
	 *
	 * @return 原始 limit 字段值
	 */
	@Accessor("limit")
	int productivebeesgenesis$getLimit();

	/** 本地执行抛错后核对真实库存，绕过可能失败的公开读取回调。 */
	@Accessor("current")
	ItemStack productivebeesgenesis$getCurrent();
}
