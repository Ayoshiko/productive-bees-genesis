package com.ayoshiko.productivebeesgenesis.inventory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.NotNull;

import net.minecraft.world.item.ItemStack;

/**
 * 输入槽分等级堆叠倍率标记接口
 * <br/>
 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.BasicInventorySlotMixin}
 * 注入到 {@link mekanism.common.inventory.slot.BasicInventorySlot} 及其所有子类。
 * <p>
 * 通过此接口可在槽位创建后注入 {@link IntSupplier}，运行时由 getLimit 乘以该倍率，
 * 实现不同等级离心机/工厂输入槽的独立堆叠上限配置。
 * <p>
 * 倍率缓存采用版本号机制：配置 reload 时递增 {@link #MULTIPLIER_VERSION}，
 * 各槽位实例检测到版本号不匹配时重新从 IntSupplier 读取倍率值。
 * 由 {@link com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis} 在
 * {@code ModConfigEvent.Reloading} 事件中调用 {@link #invalidateMultiplierCache()}。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP：通过 Mixin + 接口扩展 BasicInventorySlot 行为，不修改其源码</li>
 *   <li>DIP：倍率来源抽象为 IntSupplier，调用方按等级从配置注入</li>
 * </ul>
 */
public interface TieredInputSlot {

	/** 全局倍率缓存版本号 — 配置 reload 时递增，触发所有实例重新读取倍率 */
	AtomicLong MULTIPLIER_VERSION = new AtomicLong(0);

	/**
	 * 失效所有槽位的倍率缓存
	 * <br/>
	 * 递增 {@link #MULTIPLIER_VERSION}，所有槽位实例下次调用
	 * {@link #productivebeesgenesis$getCachedMultiplier()} 时检测到版本号不匹配
	 * 将主动重新从 IntSupplier 读取倍率值。
	 * <p>
	 * 由主类在 {@code ModConfigEvent.Reloading} 事件中调用。
	 */
	static void invalidateMultiplierCache() {
		MULTIPLIER_VERSION.incrementAndGet();
	}

	/**
	 * 设置输入槽堆叠倍率供应商
	 * <br/>
	 * 设置后，getLimit 返回值将乘以该倍率。
	 * 未设置（null）时 getLimit 行为不变。
	 *
	 * @param supplier 堆叠倍率供应商（每次 getLimit 调用时读取），null 清除倍率
	 */
	void productivebeesgenesis$setInputStackMultiplier(IntSupplier supplier);

	/** Configure a limit that applies only to {@code AutomationType.EXTERNAL} insertion. */
	void productivebeesgenesis$setExternalInsertPolicy(ExternalInsertPolicy policy);

	/** Returns the external-only policy, or {@code null} when normal insertion is unchanged. */
	ExternalInsertPolicy productivebeesgenesis$getExternalInsertPolicy();

	/**
	 * 获取输入槽堆叠倍率供应商
	 *
	 * @return 倍率供应商，未设置时返回 null
	 */
	IntSupplier productivebeesgenesis$getInputStackMultiplier();

	/**
	 * 获取带版本号缓存的倍率值
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.BasicInventorySlotMixin} 实现，
	 * 检测到 {@link #MULTIPLIER_VERSION} 变化时从 {@link IntSupplier} 重新读取。
	 * 所有工厂槽位 Mixin 应调用此方法而非直接调用 {@code supplier.getAsInt()}，
	 * 以共享同一缓存版本号。
	 *
	 * @return 缓存的倍率值，未设置 supplier 时返回 -1
	 */
	default int productivebeesgenesis$getCachedMultiplier() {
		IntSupplier supplier = productivebeesgenesis$getInputStackMultiplier();
		return supplier != null ? supplier.getAsInt() : -1;
	}

	/**
	 * 获取带缓存的基础上限
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.BasicInventorySlotMixin} 实现，
	 * 缓存最近一次的 (Item, componentHash) → baseLimit，避免每次 getLimit 都调用
	 * {@code ItemStack.getMaxStackSize()} 触发 DataComponent 链。
	 * 工厂槽位 Mixin 在 HEAD 拦截时调用此方法替代直接计算 baseLimit。
	 *
	 * @param stack     被查询的物品栈
	 * @param rawLimit  BasicInventorySlot 的 limit 字段值
	 * @param obeyLimit 是否遵守物品最大堆叠限制
	 * @param multiplier 当前倍率值
	 * @return 基础堆叠上限
	 */
	default int productivebeesgenesis$getCachedBaseLimit(@NotNull ItemStack stack,
			int rawLimit, boolean obeyLimit, int multiplier) {
		return obeyLimit ? Math.min(rawLimit, stack.getMaxStackSize()) : rawLimit;
	}
}
