package com.ayoshiko.productivebeesgenesis.inventory;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
	 * 输入槽分等级堆叠倍率标记接口
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.mek.BasicInventorySlotMixin}
	 * 注入到 {@link mekanism.common.inventory.slot.BasicInventorySlot} 及其所有子类。
	 * <p>
	 * 通过此接口可在槽位创建后注入 {@link IntSupplier}，运行时由 getLimit 乘以该倍率，
	 * 实现不同等级离心机/工厂输入槽的独立堆叠上限配置。
	 * <p>
	 * 倍率缓存保留版本号失效机制，但服务端配置 reload 不递增版本号；倍率快照只在
	 * 配置 Loading 时发布，因此当前游戏会话内保持稳定，修改配置后重启生效。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>OCP：通过 Mixin + 接口扩展 BasicInventorySlot 行为，不修改其源码</li>
	 *   <li>DIP：倍率来源抽象为 IntSupplier，调用方按等级从配置注入</li>
	 * </ul>
	 */
public interface TieredInputSlot {

	/** 全局倍率缓存版本号 — 仅供显式失效场景触发所有实例重新读取倍率。 */
	AtomicLong MULTIPLIER_VERSION = new AtomicLong(0);

	/**
	 * 失效所有槽位的倍率缓存
	 * <br/>
	 * 递增 {@link #MULTIPLIER_VERSION}，所有槽位实例下次调用
	 * {@link #productivebeesgenesis$getCachedMultiplier()} 时检测到版本号不匹配
	 * 将主动重新从 IntSupplier 读取倍率值。
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
	 * @param supplier 堆叠倍率供应商（缓存首次读取，显式失效后重读），null 清除倍率
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

	/**
	 * 查询「已乘倍率」的最终上限缓存（命中返回非负值，未命中返回 -1）。
	 * <br/>
	 * {@code getLimit} 是本模组唯一被外部物流高频轰击的方法：AE2 拉取的逐槽探测、
	 * Mekanism 的 {@code insertItem}/{@code setStackSize} 每次都要问一遍上限，
	 * 时间加速把它放大到每真实刻数千次（spark BHSGIz87Uw 中
	 * {@code getCachedBaseLimit} 自耗 1464ms / 2.44%，spark gUqyZmn5q6 中 1272ms / 4.24%，
	 * 均为全服第 2-3 热点）。缓存最终值可在命中时跳过整条
	 * 「倍率读取 → accessor 取字段 → baseLimit 计算 → 乘法钳制」链路。
	 * <p>
	 * 缓存键为 {@link net.minecraft.world.item.Item} 引用 + {@link #MULTIPLIER_VERSION}：
	 * 同一槽位的 {@code limit}/{@code obeyStackLimit} 字段在生命周期内不变，
	 * 最大堆叠数只由 Item 决定，故同键结果恒定。
	 *
	 * @param stack 被查询的物品栈
	 * @return 命中的最终上限，未命中返回 -1
	 */
	default int productivebeesgenesis$peekEffectiveLimit(@NotNull ItemStack stack) {
		return -1;
	}

	/**
	 * 记录本次算出的最终上限（已乘倍率并钳制），供下次同 Item 命中。
	 *
	 * @param stack 被查询的物品栈
	 * @param limit 最终上限；负值忽略
	 */
	default void productivebeesgenesis$storeEffectiveLimit(@NotNull ItemStack stack, int limit) {
	}
}
