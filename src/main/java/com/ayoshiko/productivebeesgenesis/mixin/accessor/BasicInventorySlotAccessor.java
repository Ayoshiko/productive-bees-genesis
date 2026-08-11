package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.slot.BasicInventorySlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
	 * BasicInventorySlot 的 Accessor Mixin
	 * <br/>
	 * 提供对 protected 字段 obeyStackLimit 和 private final 字段 limit 的访问，
	 * 用于蜂笼槽位固定堆叠 64（obeyStackLimit 写访问）和输入槽分等级堆叠倍率计算（limit 读访问）。
	 * <p>
	 * 原理：{@link BasicInventorySlot#getLimit} 在 obeyStackLimit=true 时
	 * 返回 {@code Math.min(limit, stack.getMaxStackSize())}，会受物品自身 maxStackSize 限制
	 * （例如坚固蜂笼 stacksTo(16) 则只能堆叠 16）。
	 * <p>
	 * 设置 obeyStackLimit=false 后，getLimit 返回 limit
	 * （默认为 {@code Item.ABSOLUTE_MAX_STACK_SIZE}=64），蜂笼槽位即可堆叠 64，
	 * 与蜂笼物品自身 maxStackSize 解耦。
	 * <p>
	 * Task 7: limit 字段读取用于 ME/EME 工厂输入槽 Mixin 中直接计算 baseLimit，
	 * 绕过 ME/EME 自身的 getLimit 倍率，使用我们的配置倍率替代。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>OCP：通过 Mixin 扩展 Mekanism 类行为，不修改其源码</li>
	 *   <li>SRP：仅负责暴露 limit 和 obeyStackLimit 字段，不附加其他逻辑</li>
	 * </ul>
	 *
	 * <p>
	 * <b>版本敏感性</b>：本 @Accessor 依赖 Mekanism 10.7.19.85+ 的字段名稳定性。
	 * 通过 {@code @Accessor} 访问 {@code BasicInventorySlot#obeyStackLimit}（protected boolean）
	 * 与 {@code BasicInventorySlot#limit}（private final int）私有字段。
	 * 如果 Mekanism 重命名上述任一字段，本 Mixin 将无法应用，需同步更新本类对应的 @Accessor target。
	 *
	 * @since 1.0.0
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
}
