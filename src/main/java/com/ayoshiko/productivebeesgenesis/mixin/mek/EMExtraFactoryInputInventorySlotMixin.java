package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import io.github.masyumero.emextras.common.inventory.slot.EMExtraFactoryInputInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * EME 工厂输入槽 getLimit 覆盖拦截 Mixin — 仅在 EME 加载时应用
	 * <br/>
	 * <b>背景</b>：EME 的 {@link EMExtraFactoryInputInventorySlot} 覆盖了
	 * {@link BasicInventorySlot#getLimit}，按 tier 乘以 8/16/32/64。
	 * 此硬编码倍率无法通过配置调整，且不与我们离心机的输入槽堆叠倍率配置联动。
	 * <p>
	 * <b>问题与策略</b>：与 {@link ExtraFactoryInputInventorySlotMixin} 相同 —
	 * EME 覆盖了 getLimit，{@link BasicInventorySlotMixin} 的 RETURN 注入不生效，
	 * 需在 HEAD 拦截。当我们的配置倍率已设置时完全替换 EME 的硬编码倍率。
	 * <p>
	 * <b>类加载安全</b>：本类引用 {@link EMExtraFactoryInputInventorySlot}（EME 类），
	 * 仅在 EME 加载时由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 应用。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 * @see ExtraFactoryInputInventorySlotMixin ME 版本的同类实现
	 */
@Mixin(value = EMExtraFactoryInputInventorySlot.class, remap = false)
public abstract class EMExtraFactoryInputInventorySlotMixin {

	/**
	 * 在 EME 工厂输入槽 getLimit 的 HEAD 拦截
	 * <br/>
	 * 当我们的配置倍率已设置时，用 {@code baseLimit × 配置倍率} 完全替换 EME 的
	 * {@code super.getLimit(stack) × (8/16/32/64)} 逻辑。
	 * 倍率为 null 时不取消，EME 原逻辑正常运行（不影响 EME 原版机器）。
	 *
	 * @param stack 被查询的物品栈
	 * @param cir   返回值回调信息
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$overrideGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		// 仅当 TieredInputSlot 接口已注入（BasicInventorySlotMixin 已应用）时处理
		if (this instanceof TieredInputSlot tiered) {
			// 先查已乘倍率的最终上限缓存：命中即跳过倍率读取、字段访问与乘法钳制。
			// 本方法在时间加速下每真实刻被 AE2/Mekanism 调用数千次（spark gUqyZmn5q6 全服第 2 热点）。
			int cached = tiered.productivebeesgenesis$peekEffectiveLimit(stack);
			if (cached >= 0) {
				cir.setReturnValue(cached);
				return;
			}
			int mult = tiered.productivebeesgenesis$getCachedMultiplier();
			if (mult >= 0) {
				// 读取 BasicInventorySlot 的 limit 和 obeyStackLimit 字段
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
		// 倍率为 -1 时（非我们的离心机工厂），EME 原逻辑正常运行
	}
}
