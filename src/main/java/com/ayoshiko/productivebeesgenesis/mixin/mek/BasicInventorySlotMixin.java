package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.ExternalInsertPolicy;
import com.ayoshiko.productivebeesgenesis.inventory.SlotLimitCache;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiPredicate;
import java.util.function.IntSupplier;

/**
	 * BasicInventorySlot 输入槽分等级堆叠倍率 Mixin
	 * <br/>
	 * 通过 {@link TieredInputSlot} 接口为 {@link BasicInventorySlot} 注入可配置的堆叠倍率。
	 * 当倍率已设置时，{@code getLimit} 返回 {@code baseLimit × multiplier}。
	 * <p>
	 * 性能优化：倍率值使用版本号缓存，配置 reload 时递增
	 * {@link TieredInputSlot#MULTIPLIER_VERSION}，本实例检测到版本号不匹配时重新读取。
	 * 无 reload 期间零 ModConfig 读取开销。
	 * <p>
	 * 生效范围：
	 * <ul>
	 *   <li>{@link mekanism.common.inventory.slot.InputInventorySlot} — 基础离心机输入槽（未覆盖 getLimit）</li>
	 *   <li>{@link mekanism.common.inventory.slot.FactoryInputInventorySlot} — 原版工厂输入槽（未覆盖 getLimit）</li>
	 *   <li>其他未覆盖 getLimit 的 BasicInventorySlot 子类</li>
	 * </ul>
	 * <b>不生效</b>于已覆盖 getLimit 的子类（如 ExtraFactoryInputInventorySlot、EMExtraFactoryInputInventorySlot、
	 * TieredOutputInventorySlot），这些类有自己的 Mixin 或直接覆盖实现。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>OCP：通过 Mixin 扩展 BasicInventorySlot 行为，不修改其源码</li>
	 *   <li>SRP：仅负责输入槽倍率注入，不涉及输出槽或配方逻辑</li>
	 * </ul>
	 * <p>
	 * 线程安全：cachedInputMultiplier / cachedInputVersion 使用 volatile 保证跨线程可见性。
	 * 服务端 tick 与外部物流能力调用均在主线程执行，缓存检查/更新无需加锁；
	 * 极端并发调用下最坏仅重复读取一次 supplier，不影响正确性。
	 */
@Mixin(value = BasicInventorySlot.class, remap = false)
public abstract class BasicInventorySlotMixin implements TieredInputSlot {

	@Shadow
	protected ItemStack current;

	@Shadow
	@Final
	private BiPredicate<ItemStack, AutomationType> canExtract;

	@Shadow
	public abstract int getLimit(ItemStack stack);

	@Shadow
	public abstract boolean isItemValidForInsertion(ItemStack stack, AutomationType automationType);

	@Shadow
	public abstract void setStackUnchecked(ItemStack stack);

	@Shadow
	public abstract void onContentsChanged();

	/** 输入槽堆叠倍率供应商 — null 表示未设置，getLimit 行为不变 */
	@Unique
	private IntSupplier productivebeesgenesis$inputMultiplier;

	@Unique
	private ExternalInsertPolicy productivebeesgenesis$externalInsertPolicy;

	/** 缓存的倍率值 — -1 表示未初始化 */
	@Unique
	private volatile int productivebeesgenesis$cachedInputMultiplier;

	/** 缓存时的版本号 — 与 {@link TieredInputSlot#MULTIPLIER_VERSION} 比较 */
	@Unique
	private volatile long productivebeesgenesis$cachedInputVersion;

	@Unique
	private volatile boolean productivebeesgenesis$tieredStateInitialized;

	@Unique
	private void productivebeesgenesis$ensureTieredState() {
		if (!productivebeesgenesis$tieredStateInitialized) {
			synchronized (this) {
				if (!productivebeesgenesis$tieredStateInitialized) {
					productivebeesgenesis$cachedInputMultiplier = -1;
					productivebeesgenesis$cachedInputVersion = -1L;
					productivebeesgenesis$tieredStateInitialized = true;
				}
			}
		}
	}

	/** 基础上限单条目缓存，避免每次 getLimit 都调用 ItemStack.getMaxStackSize() */
	@Unique
	private volatile SlotLimitCache productivebeesgenesis$limitCache;

	@Unique
	private SlotLimitCache productivebeesgenesis$getLimitCache() {
		SlotLimitCache limitCache = productivebeesgenesis$limitCache;
		if (limitCache == null) {
			limitCache = new SlotLimitCache();
			productivebeesgenesis$limitCache = limitCache;
		}
		return limitCache;
	}

	@Override
	public void productivebeesgenesis$setInputStackMultiplier(IntSupplier supplier) {
		productivebeesgenesis$ensureTieredState();
		this.productivebeesgenesis$inputMultiplier = supplier;
		// 重置缓存，确保新 supplier 立即生效
		this.productivebeesgenesis$cachedInputMultiplier = -1;
		// 已乘倍率的最终上限缓存也必须清：替换 supplier 不递增全局 MULTIPLIER_VERSION，
		// 否则旧倍率算出的上限会继续命中。
		SlotLimitCache limitCache = productivebeesgenesis$limitCache;
		if (limitCache != null) limitCache.invalidate();
	}

	@Override
	public IntSupplier productivebeesgenesis$getInputStackMultiplier() {
		return this.productivebeesgenesis$inputMultiplier;
	}

	@Override
	public void productivebeesgenesis$setExternalInsertPolicy(ExternalInsertPolicy policy) {
		this.productivebeesgenesis$externalInsertPolicy = policy;
	}

	@Override
	public ExternalInsertPolicy productivebeesgenesis$getExternalInsertPolicy() {
		return productivebeesgenesis$externalInsertPolicy;
	}

	@Inject(method = "extractItem(ILmekanism/api/Action;"
			+ "Lmekanism/api/AutomationType;)Lnet/minecraft/world/item/ItemStack;",
			at = @At("HEAD"), cancellable = true, order = 1000)
	private void productivebeesgenesis$bulkExtractOverstackedOutput(int amount, Action action,
			AutomationType automationType, CallbackInfoReturnable<ItemStack> cir) {
		if (automationType != AutomationType.EXTERNAL || productivebeesgenesis$inputMultiplier == null
				|| current.isEmpty() || amount < 1 || current.getCount() <= current.getMaxStackSize()
				|| !canExtract.test(current, automationType)) {
			return;
		}
		int extractedAmount = Math.min(amount, current.getCount());
		ItemStack extracted = current.copyWithCount(extractedAmount);
		if (action.execute()) {
			current.shrink(extractedAmount);
			onContentsChanged();
		}
		cir.setReturnValue(extracted);
	}

	@Inject(method = "insertItem(Lnet/minecraft/world/item/ItemStack;Lmekanism/api/Action;"
			+ "Lmekanism/api/AutomationType;)Lnet/minecraft/world/item/ItemStack;",
			at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$limitExternalInsert(ItemStack stack, Action action,
			AutomationType automationType, CallbackInfoReturnable<ItemStack> cir) {
		ExternalInsertPolicy policy = productivebeesgenesis$externalInsertPolicy;
		if (policy == null || automationType != AutomationType.EXTERNAL) return;
		if (stack.isEmpty()) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}

		int normalLimit = getLimit(stack);
		int effectiveLimit = Math.min(normalLimit,
				Math.max(0, policy.getInsertLimit((BasicInventorySlot) (Object) this, stack, normalLimit, action)));
		int needed = effectiveLimit - current.getCount();
		if (needed <= 0 || !isItemValidForInsertion(stack, automationType)) {
			cir.setReturnValue(stack);
			return;
		}

		boolean sameType = !current.isEmpty() && ItemStack.isSameItemSameComponents(current, stack);
		if (!current.isEmpty() && !sameType) {
			cir.setReturnValue(stack);
			return;
		}
		int toAdd = Math.min(stack.getCount(), needed);
		if (action.execute()) {
			if (sameType) {
				current.grow(toAdd);
				onContentsChanged();
			} else {
				setStackUnchecked(stack.copyWithCount(toAdd));
			}
			policy.onInserted((BasicInventorySlot) (Object) this, stack, toAdd);
		}
		cir.setReturnValue(stack.copyWithCount(stack.getCount() - toAdd));
	}

	@Override
	public int productivebeesgenesis$getCachedMultiplier() {
		productivebeesgenesis$ensureTieredState();
		IntSupplier supplier = productivebeesgenesis$inputMultiplier;
		if (supplier == null) return -1;
		int multiplier = productivebeesgenesis$cachedInputMultiplier;
		long currentVersion = TieredInputSlot.MULTIPLIER_VERSION.get();
		if (productivebeesgenesis$cachedInputVersion != currentVersion || multiplier < 0) {
			multiplier = supplier.getAsInt();
			productivebeesgenesis$cachedInputMultiplier = multiplier;
			productivebeesgenesis$cachedInputVersion = currentVersion;
		}
		return multiplier;
	}

	/**
	 * 获取带缓存的基础上限。
	 * <br/>
	 * 工厂槽位 Mixin 在 HEAD 拦截时直接调用此方法，避免重复计算
	 * {@code stack.getMaxStackSize()} 触发 DataComponent 链。
	 */
	@Override
	public int productivebeesgenesis$getCachedBaseLimit(@NotNull ItemStack stack,
														int rawLimit, boolean obeyLimit, int multiplier) {
		return productivebeesgenesis$getLimitCache().getBaseLimit(stack, rawLimit, obeyLimit, multiplier);
	}

	@Override
	public int productivebeesgenesis$peekEffectiveLimit(@NotNull ItemStack stack) {
		return productivebeesgenesis$getLimitCache().peekEffectiveLimit(stack);
	}

	@Override
	public void productivebeesgenesis$storeEffectiveLimit(@NotNull ItemStack stack, int limit) {
		productivebeesgenesis$getLimitCache().storeEffectiveLimit(stack, limit);
	}

	/**
	 * 在 BasicInventorySlot.getLimit 头部用缓存直接替换原始计算。
	 * <br/>
	 * 原始 getLimit 每次都会调用 {@code ItemStack.getMaxStackSize()}，外部物流（AE2/SFM 等）
	 * 高频探测时会放大 DataComponent 链开销。此注入在倍率已配置时直接复用
	 * {@link SlotLimitCache} 缓存的 baseLimit，再乘以配置倍率，完全跳过原始计算。
	 * 倍率为 -1 表示非本模组分等级输入槽，保持 Mekanism 原逻辑不变。
	 * 对于已覆盖 getLimit 的子类（ME/EME 工厂输入槽），本 Mixin 不生效，
	 * 由各自的专用 Mixin（ExtraFactoryInputInventorySlotMixin 等）处理。
	 * <p>
	 * 进一步：先查「已乘倍率的最终上限」缓存（同 Item + 同倍率版本即命中），
	 * 命中时连倍率读取与乘法钳制都跳过。时间加速下本方法每真实刻被调用数千次
	 * （spark gUqyZmn5q6：getCachedBaseLimit 自耗 1272ms / 4.24%，全服第 2 热点）。
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$cachedTieredGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		if (productivebeesgenesis$inputMultiplier == null) {
			return; // 非本模组分等级槽位：不触碰缓存，保持 Mekanism 原逻辑
		}
		SlotLimitCache limitCache = productivebeesgenesis$getLimitCache();
		int cached = limitCache.peekEffectiveLimit(stack);
		if (cached >= 0) {
			cir.setReturnValue(cached);
			return;
		}
		int multiplier = productivebeesgenesis$getCachedMultiplier();
		if (multiplier < 0) {
			return;
		}
		BasicInventorySlotAccessor accessor = (BasicInventorySlotAccessor) this;
		int rawLimit = accessor.productivebeesgenesis$getLimit();
		boolean obeyLimit = accessor.productivebeesgenesis$getObeyStackLimit();
		int baseLimit = limitCache.getBaseLimit(stack, rawLimit, obeyLimit, multiplier);
		int effective;
		if (multiplier > 1) {
			long scaled = (long) baseLimit * multiplier;
			effective = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, scaled));
		} else {
			effective = baseLimit;
		}
		limitCache.storeEffectiveLimit(stack, effective);
		cir.setReturnValue(effective);
	}
}
