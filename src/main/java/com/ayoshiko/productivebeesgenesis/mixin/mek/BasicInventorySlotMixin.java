package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.inventory.ExternalInsertPolicy;
import com.ayoshiko.productivebeesgenesis.inventory.SlotLimitCache;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
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
	 * 线程安全：cachedInputMultiplier / cachedInputVersion 使用 volatile 保证跨线程可见性
	 * （getLimit 可能从同步线程调用）。{@link #productivebeesgenesis$getCachedMultiplier} 使用
	 * synchronized 守卫 check-then-update 临界区，避免并发线程读到版本号已更新但倍率值仍为旧值。
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
	private IntSupplier productivebeesgenesis$inputMultiplier = null;

	@Unique
	private ExternalInsertPolicy productivebeesgenesis$externalInsertPolicy = null;

	/** 缓存的倍率值 — -1 表示未初始化 */
	@Unique
	private volatile int productivebeesgenesis$cachedInputMultiplier = -1;

	/** 缓存时的版本号 — 与 {@link TieredInputSlot#MULTIPLIER_VERSION} 比较 */
	@Unique
	private volatile long productivebeesgenesis$cachedInputVersion = -1;

	/** 基础上限单条目缓存，避免每次 getLimit 都调用 ItemStack.getMaxStackSize() */
	@Unique
	private final SlotLimitCache productivebeesgenesis$limitCache = new SlotLimitCache();

	@Override
	public void productivebeesgenesis$setInputStackMultiplier(IntSupplier supplier) {
		this.productivebeesgenesis$inputMultiplier = supplier;
		// 重置缓存，确保新 supplier 立即生效
		this.productivebeesgenesis$cachedInputMultiplier = -1;
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
	public synchronized int productivebeesgenesis$getCachedMultiplier() {
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
		return productivebeesgenesis$limitCache.getBaseLimit(stack, rawLimit, obeyLimit, multiplier);
	}

	/**
	 * 在 getLimit 返回时乘以输入槽倍率（带版本号缓存）
	 * <br/>
	 * 仅当倍率已设置且大于 1 时生效，避免无倍率槽位的额外计算开销。
	 * 倍率值在配置 reload 后通过 {@link TieredInputSlot#invalidateMultiplierCache()}
	 * 递增版本号自动失效，无 reload 期间直接返回缓存值。
	 * 对于已覆盖 getLimit 的子类（ME/EME 工厂输入槽），此 Mixin 不生效，
	 * 由各自的专用 Mixin（ExtraFactoryInputInventorySlotMixin 等）处理。
	 */
	@Inject(method = "getLimit(Lnet/minecraft/world/item/ItemStack;)I", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$modifyGetLimit(@NotNull ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		int multiplier = productivebeesgenesis$getCachedMultiplier();
		if (multiplier > 1) {
			long scaled = (long) cir.getReturnValue() * multiplier;
			cir.setReturnValue((int) Math.min(Integer.MAX_VALUE, Math.max(0L, scaled)));
		}
	}
}
