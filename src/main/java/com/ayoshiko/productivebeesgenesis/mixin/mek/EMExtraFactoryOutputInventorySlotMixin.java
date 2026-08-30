package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import io.github.masyumero.emextras.common.inventory.slot.EMExtraFactoryOutputInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * EME 工厂输出槽 getLimit 覆盖拦截 Mixin — 仅在 EME 加载时应用
	 * <br/>
	 * <b>背景</b>：EME 的 {@link EMExtraFactoryOutputInventorySlot} 覆盖了
	 * {@link BasicInventorySlot#getLimit}，按 tier 乘以 8/16/32/64。
	 * 需用我们的输出槽配置倍率（{@code stack_multiplier}）替代 EME 的硬编码倍率。
	 * <p>
	 * <b>策略</b>：与 {@link EMExtraFactoryInputInventorySlotMixin} 相同 —
	 * 在 HEAD 拦截，当 {@link TieredInputSlot} 倍率已设置时完全替换 EME 的倍率逻辑。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 * @see EMExtraFactoryInputInventorySlotMixin 输入槽版本
	 * @see com.ayoshiko.productivebeesgenesis.inventory.CentrifugeOutputStackMultipliers 输出槽倍率来源
	 */
@Mixin(value = EMExtraFactoryOutputInventorySlot.class, remap = false)
public abstract class EMExtraFactoryOutputInventorySlotMixin {

	/**
	 * 在 EME 工厂输出槽 getLimit 的 HEAD 拦截
	 * <br/>
	 * 当我们的配置倍率已设置时，用 {@code baseLimit × 配置倍率} 完全替换 EME 的
	 * {@code super.getLimit(stack) × (8/16/32/64)} 逻辑。
	 *
	 * @param stack 被查询的物品栈
	 * @param cir   返回值回调信息
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$overrideGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		if (this instanceof TieredInputSlot tiered) {
			// 已乘倍率的最终上限缓存（同 Item + 同倍率版本即命中）：
			// 输出槽的 getLimit 在配方产出与 AE2 推送路径上同样每刻被调用数千次。
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
