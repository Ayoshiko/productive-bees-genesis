package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import com.jerry.mekextras.common.inventory.slot.ExtraFactoryInputInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * ME 工厂输入槽 getLimit 覆盖拦截 Mixin — 仅在 ME 加载时应用
	 * <br/>
	 * <b>背景</b>：ME 的 {@link ExtraFactoryInputInventorySlot} 覆盖了
	 * {@link BasicInventorySlot#getLimit}，乘以 {@code 8 << factory.tier.ordinal()}。
	 * 此硬编码倍率无法通过配置调整，且不与我们离心机的输入槽堆叠倍率配置联动。
	 * <p>
	 * <b>问题</b>：{@link BasicInventorySlotMixin} 的 {@code @Inject(at=@At("RETURN"))}
	 * 注入到 {@code BasicInventorySlot.getLimit()}，但由于子类直接覆盖了 getLimit，
	 * Mixin 注入的 RETURN 钩子不会被触发（子类方法不会调用父类被注入的方法）。
	 * 因此需要专用 Mixin 在子类 getLimit 的 HEAD 拦截。
	 * <p>
	 * <b>策略</b>：
	 * <ul>
	 *   <li>当 {@link TieredInputSlot#getInputStackMultiplier} 非 null 时
	 *       （即我们的离心机工厂输入槽），用我们的配置倍率完全替换 ME 的硬编码倍率，
	 *       取消原方法执行避免双重乘法</li>
	 *   <li>当倍率为 null 时（非我们的离心机工厂，如 ME 原版机器），
	 *       不取消，ME 原逻辑正常运行</li>
	 * </ul>
	 * <p>
	 * <b>baseLimit 计算</b>：通过 {@link BasicInventorySlotAccessor} 读取
	 * {@code limit} 和 {@code obeyStackLimit} 字段，复现
	 * {@code BasicInventorySlot.getLimit} 的基础逻辑（不乘任何倍率），
	 * 再乘以我们的配置倍率。
	 * <p>
	 * <b>线程安全</b>：单线程（服务端 tick）访问，无并发问题。
	 * <p>
	 * <b>类加载安全</b>：本类引用 {@link ExtraFactoryInputInventorySlot}（ME 类），
	 * 仅在 ME 加载时由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 应用。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
@Mixin(value = ExtraFactoryInputInventorySlot.class, remap = false)
public abstract class ExtraFactoryInputInventorySlotMixin {

	/**
	 * 在 ME 工厂输入槽 getLimit 的 HEAD 拦截
	 * <br/>
	 * 当我们的配置倍率已设置时，用 {@code baseLimit × 配置倍率} 完全替换 ME 的
	 * {@code super.getLimit(stack) × (8 << tier.ordinal())} 逻辑。
	 * 倍率为 null 时不取消，ME 原逻辑正常运行（不影响 ME 原版机器）。
	 *
	 * @param stack 被查询的物品栈
	 * @param cir   返回值回调信息
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$overrideGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		// 仅当 TieredInputSlot 接口已注入（BasicInventorySlotMixin 已应用）时处理
		if (this instanceof TieredInputSlot tiered) {
			// 已乘倍率的最终上限缓存：命中即跳过倍率读取、accessor 字段访问与乘法钳制。
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
		// 倍率为 -1 时（非我们的离心机工厂），ME 原逻辑正常运行
	}
}
