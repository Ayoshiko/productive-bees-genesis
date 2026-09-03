package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/**
	 * 分等级堆叠倍率输出槽
	 * <br/>
	 * 继承 {@link BasicInventorySlot} 复刻 {@code OutputInventorySlot} 的行为
	 * （OutputInventorySlot 构造器为 private 无法继承），重写 {@link #getLimit(ItemStack)}
	 * 返回 {@code baseLimit × multiplier}，multiplier 由 {@link IntSupplier} 提供，
	 * 按机器等级从当前游戏会话快照读取。
	 * <p>
	 * 性能优化：Spark 分析显示 getLimit 在 256× 加速场景下消耗 5.33% CPU，
	 * 主因是每次调用都通过 IntSupplier 读取 ModConfig。当前实现首次读取后缓存倍率，
	 * 配置 reload 不失效；如有显式版本失效才重新从会话快照读取。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP：仅负责输出槽的堆叠上限计算，倍率来源通过 IntSupplier 注入</li>
	 *   <li>OCP：通过 IntSupplier 扩展倍率来源，不修改 BasicInventorySlot 代码</li>
	 *   <li>DIP：倍率来源抽象为 IntSupplier，调用方按等级从配置注入</li>
	 * </ul>
	 * <p>
	 * 线程安全：cachedMultiplier / cachedVersion 使用 volatile 保证跨线程可见性
	 * （getLimit 可能从同步线程调用）。{@link #getLimit} 使用 synchronized 守卫
	 * check-then-update 临界区，避免并发线程读到 cachedVersion 已更新但 cachedMultiplier 仍为旧值。
	 * MULTIPLIER_VERSION 为 AtomicLong，保证显式失效时原子递增。
	 */
public class TieredOutputInventorySlot extends BasicInventorySlot {

	/** 堆叠倍率供应商 — 按机器等级从当前游戏会话快照读取。 */
	private final IntSupplier multiplierSupplier;

	/** 缓存的倍率值 — volatile 保证跨线程可见性 */
	private volatile int cachedMultiplier = -1;

	/** 缓存时的版本号 — 与 {@link TieredInputSlot#MULTIPLIER_VERSION} 比较 */
	private volatile long cachedVersion = -1;

	/** 基础上限单条目缓存，避免每次 getLimit 都调用 ItemStack.getMaxStackSize() */
	private final SlotLimitCache limitCache = new SlotLimitCache();

	/**
	 * 创建分等级输出槽
	 *
	 * @param multiplierSupplier 堆叠倍率供应商（首次计算或显式失效后读取）
	 * @param listener           内容变更监听器
	 * @param x                  GUI x 坐标
	 * @param y                  GUI y 坐标
	 * @return 输出槽实例
	 */
	public static TieredOutputInventorySlot at(IntSupplier multiplierSupplier,
			@Nullable IContentsListener listener, int x, int y) {
		return new TieredOutputInventorySlot(multiplierSupplier, listener, x, y);
	}

	private TieredOutputInventorySlot(IntSupplier multiplierSupplier,
			@Nullable IContentsListener listener, int x, int y) {
		// 与 OutputInventorySlot 相同的谓词配置：可提取/不可自动化插入/接受所有物品
		super(ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(),
				ConstantPredicates.alwaysTrue(), listener, x, y);
		setSlotType(ContainerSlotType.OUTPUT);
		this.multiplierSupplier = multiplierSupplier;
		// Also marks this slot for the shared bulk external-extraction path.
		((TieredInputSlot) (Object) this).productivebeesgenesis$setInputStackMultiplier(multiplierSupplier);
	}

	/**
	 * Output inventory is exposed to automation as normal, but the GUI uses a
	 * fixed set of page-aware proxy slots. Returning null prevents Mekanism's
	 * tile container from registering every physical page at the same position.
	 */
	@Nullable
	@Override
	public InventoryContainerSlot createContainerSlot() {
		return null;
	}

	/**
	 * 返回 baseLimit × multiplier（带版本号缓存）
	 * <br/>
	 * {@code super.getLimit(stack)} 返回 {@code Math.min(ABSOLUTE_MAX_STACK_SIZE, stack.getMaxStackSize())}，
	 * 乘以配置的等级倍率后得到实际槽位上限。
	 * <p>
	 * 倍率值在当前游戏会话内保持缓存；配置 reload 不改变快照，修改后重启生效。
	 * <p>
	 * 最终上限（已乘倍率）再做一层按 Item 的记忆化：本方法在 AE2 推送、物流探测与
	 * Mekanism 插入路径上每刻被调用数千次，命中时可跳过 synchronized 块内的
	 * 倍率检查、baseLimit 查表与饱和乘法（同类热点见 spark gUqyZmn5q6 中
	 * {@code getCachedBaseLimit} 自耗 1272ms / 4.24%）。
	 *
	 * @param stack 待查询的物品栈
	 * @return 槽位上限 = 基础上限 × 等级倍率
	 */
	@Override
	public int getLimit(@NotNull ItemStack stack) {
		int cachedEffective = limitCache.peekEffectiveLimit(stack);
		if (cachedEffective >= 0) return cachedEffective;
		return computeLimit(stack);
	}

	/** 缓存未命中时的完整计算；synchronized 保持与原实现一致的写入互斥。 */
	private synchronized int computeLimit(@NotNull ItemStack stack) {
		int multiplier = cachedMultiplier;
		long currentVersion = TieredInputSlot.MULTIPLIER_VERSION.get();
		if (cachedVersion != currentVersion || multiplier < 0) {
			multiplier = multiplierSupplier.getAsInt();
			cachedMultiplier = multiplier;
			cachedVersion = currentVersion;
		}
		// 使用单条目缓存避免每次调用 ItemStack.getMaxStackSize() 触发 DataComponent 链
		int baseLimit = limitCache.getBaseLimit(stack, 64, !stack.isEmpty(), multiplier);
		int effective = SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingMultiply(baseLimit, multiplier));
		limitCache.storeEffectiveLimit(stack, effective);
		return effective;
	}
}
