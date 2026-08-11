package com.ayoshiko.productivebeesgenesis.inventory;

import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.slot.ContainerSlotType;
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
	 * 返回 {@code baseLimit × multiplier}，multiplier 由 {@link IntSupplier} 动态提供，
	 * 按机器等级从配置读取。
	 * <p>
	 * 性能优化：Spark 分析显示 getLimit 在 256× 加速场景下消耗 5.33% CPU，
	 * 主因是每次调用都通过 IntSupplier 读取 ModConfig。
	 * 改为版本号缓存：配置 reload 时递增 {@link TieredInputSlot#MULTIPLIER_VERSION}，
	 * 本实例检测到版本号不匹配时重新读取倍率值。无 reload 期间零开销。
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
	 * MULTIPLIER_VERSION 为 AtomicLong，保证原子递增。
	 */
public class TieredOutputInventorySlot extends BasicInventorySlot {

	/** 堆叠倍率供应商 — 按机器等级从配置读取 */
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
	 * @param multiplierSupplier 堆叠倍率供应商（每次 getLimit 调用时读取）
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
	 * 返回 baseLimit × multiplier（带版本号缓存）
	 * <br/>
	 * {@code super.getLimit(stack)} 返回 {@code Math.min(ABSOLUTE_MAX_STACK_SIZE, stack.getMaxStackSize())}，
	 * 乘以配置的等级倍率后得到实际槽位上限。
	 * <p>
	 * 倍率值在配置 reload 后通过 {@link TieredInputSlot#invalidateMultiplierCache()}
	 * 递增版本号自动失效，无 reload 期间直接返回缓存值，零 ModConfig 读取开销。
	 *
	 * @param stack 待查询的物品栈
	 * @return 槽位上限 = 基础上限 × 等级倍率
	 */
	@Override
	public synchronized int getLimit(@NotNull ItemStack stack) {
		int multiplier = cachedMultiplier;
		long currentVersion = TieredInputSlot.MULTIPLIER_VERSION.get();
		if (cachedVersion != currentVersion || multiplier < 0) {
			multiplier = multiplierSupplier.getAsInt();
			cachedMultiplier = multiplier;
			cachedVersion = currentVersion;
		}
		// 使用单条目缓存避免每次调用 ItemStack.getMaxStackSize() 触发 DataComponent 链
		int baseLimit = limitCache.getBaseLimit(stack, 64, !stack.isEmpty(), multiplier);
		return baseLimit * multiplier;
	}
}
