package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryOutputInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * ME 工厂输出槽 getLimit 覆盖拦截 Mixin — 仅在 ME 加载时应用
	 * <br/>
	 * <b>背景</b>：ME 的 {@link ExtraFactoryOutputInventorySlot} 覆盖了
	 * {@link BasicInventorySlot#getLimit}，乘以 {@code 8 << factory.tier.ordinal()}。
	 * 需用我们的输出槽配置倍率（{@code stack_multiplier}）替代 ME 的硬编码倍率。
	 * <p>
	 * <b>策略</b>：与 {@link ExtraFactoryInputInventorySlotMixin} 相同 —
	 * 在 HEAD 拦截，当 {@link TieredInputSlot} 倍率已设置时完全替换 ME 的倍率逻辑。
	 * 倍率为 null 时（非我们的离心机工厂），ME 原逻辑正常运行。
	 * <p>
	 * <b>注意</b>：{@link TieredInputSlot} 接口名称虽含 "Input"，但实际是通用的
	 * 堆叠倍率注入机制，对输出槽同样适用。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 * @see ExtraFactoryInputInventorySlotMixin 输入槽版本
	 * @see com.ayoshiko.productivebeesgenesis.inventory.CentrifugeOutputStackMultipliers 输出槽倍率来源
	 */
@Mixin(value = ExtraFactoryOutputInventorySlot.class, remap = false)
public abstract class ExtraFactoryOutputInventorySlotMixin {

	/**
	 * 在 ME 工厂输出槽 getLimit 的 HEAD 拦截
	 * <br/>
	 * 当我们的配置倍率已设置时，用 {@code baseLimit × 配置倍率} 完全替换 ME 的
	 * {@code super.getLimit(stack) × (8 << tier.ordinal())} 逻辑。
	 *
	 * @param stack 被查询的物品栈
	 * @param cir   返回值回调信息
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$overrideGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		if (this instanceof TieredInputSlot tiered) {
			// 已乘倍率的最终上限缓存：命中即跳过倍率读取、accessor 字段访问与乘法钳制。
			int cached = tiered.productivebeesgenesis$peekEffectiveLimit(stack);
			if (cached >= 0) {
				cir.setReturnValue(cached);
				return;
			}
			int mult = tiered.productivebeesgenesis$getCachedMultiplier();
			if (mult >= 0) {
				BasicInventorySlotAccessor accessor = (BasicInventorySlotAccessor) this;
				int rawLimit = accessor.productivebeesgenesis$getLimit();
				boolean obeyLimit = accessor.productivebeesgenesis$getObeyStackLimit() && !stack.isEmpty();
				int baseLimit = tiered.productivebeesgenesis$getCachedBaseLimit(stack, rawLimit, obeyLimit, mult);
				int effective;
				try {
					effective = Math.multiplyExact(baseLimit, mult);
				} catch (ArithmeticException ignored) {
					effective = Integer.MAX_VALUE;
				}
				tiered.productivebeesgenesis$storeEffectiveLimit(stack, effective);
				cir.setReturnValue(effective);
			}
		}
	}
}
