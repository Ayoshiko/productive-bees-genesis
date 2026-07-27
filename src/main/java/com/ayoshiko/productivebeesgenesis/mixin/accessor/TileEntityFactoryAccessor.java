package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.factory.TileEntityFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileEntityFactory 访问器：暴露包私有/私有字段供外部包访问
 * <br/>
 * TileEntityFactory中的energySlot是包私有的，activeStates和lastUsage是私有的，
 * 无法从外部包直接访问。通过Accessor Mixin暴露getter/setter。
 *
 * <p>
 * <b>版本敏感性</b>：本 @Accessor 依赖 Mekanism 10.7.19.85+ 的字段名稳定性。
 * 通过 {@code @Accessor} 访问 {@code TileEntityFactory#energySlot}（包私有 EnergyInventorySlot）、
 * {@code TileEntityFactory#activeStates}（private final boolean[]）、
 * {@code TileEntityFactory#lastUsage}（private long）、
 * {@code TileEntityFactory#sortingNeeded}（private boolean）、
 * {@code TileEntityFactory#operationsPerTick}（private int）、
 * {@code TileEntityFactory#ticksRequired}（private int）字段。
 * 如果 Mekanism 重命名上述任一字段，本 Mixin 将无法应用，需同步更新本类对应的 @Accessor target。
 *
 * @since 1.0.0
 */
@Mixin(value = TileEntityFactory.class, remap = false)
public interface TileEntityFactoryAccessor {

	/**
	 * 访问 TileEntityFactory 的包私有字段 `energySlot`（类型：`EnergyInventorySlot`）
	 * <br/>
	 * 用于在 Mixin 中读取工厂能量槽位实例。
	 *
	 * @return 原始 energySlot 字段值
	 */
	@Accessor("energySlot")
	EnergyInventorySlot productivebeesgenesis$getEnergySlot();

	/**
	 * 访问 TileEntityFactory 的包私有字段 `energySlot`（类型：`EnergyInventorySlot`）
	 * <br/>
	 * 用于在 Mixin 中设置工厂能量槽位实例。
	 *
	 * @param slot 要设置的 EnergyInventorySlot 实例
	 */
	@Accessor("energySlot")
	void productivebeesgenesis$setEnergySlot(EnergyInventorySlot slot);

	/**
	 * 访问 TileEntityFactory 的 private final 字段 `activeStates`（类型：`boolean[]`）
	 * <br/>
	 * 用于在 onUpdateServer 中重算整体激活状态。
	 *
	 * @return 原始 activeStates 字段值
	 */
	@Accessor("activeStates")
	boolean[] productivebeesgenesis$getActiveStates();

	/**
	 * 访问 TileEntityFactory 的 private 字段 `lastUsage`（类型：`long`）
	 * <br/>
	 * 用于更新包含 PB 处理的能量消耗值。
	 *
	 * @param value 要设置的 lastUsage 值
	 */
	@Accessor("lastUsage")
	void productivebeesgenesis$setLastUsage(long value);

	/**
	 * 访问 TileEntityFactory 的 private 字段 `sortingNeeded`（类型：`boolean`）
	 * <br/>
	 * 用于在重写 getInitialInventory 时标记排序需要更新。
	 *
	 * @param value 要设置的 sortingNeeded 值
	 */
	@Accessor("sortingNeeded")
	void productivebeesgenesis$setSortingNeeded(boolean value);

	/**
	 * 访问 TileEntityFactory 的 private 字段 `sorting`（类型：`boolean`）
	 * <br/>
	 * 用于 isSorting()/toggleSorting() 动态读取排序开关，绕过原版 toggleSorting 死锁。
	 *
	 * @return 原始 sorting 字段值
	 */
	@Accessor("sorting")
	boolean productivebeesgenesis$getSorting();

	/**
	 * 访问 TileEntityFactory 的 private 字段 `sorting`（类型：`boolean`）
	 * <br/>
	 * 用于 toggleSorting() 直接设置 sorting 字段，避免原版 sorting = !isSorting() 死锁。
	 *
	 * @param value 要设置的 sorting 值
	 */
	@Accessor("sorting")
	void productivebeesgenesis$setSorting(boolean value);

	/**
	 * 访问 TileEntityFactory 的 private 字段 `operationsPerTick`（类型：`int`）
	 * <br/>
	 * STACK/SPEED 升级时更新缓存字段（参考 MEKExtras TileEntityExtraFactory）。
	 *
	 * @param value 要设置的 operationsPerTick 值
	 */
	@Accessor("operationsPerTick")
	void productivebeesgenesis$setOperationsPerTick(int value);

	/**
	 * 访问 TileEntityFactory 的 private 字段 `ticksRequired`（类型：`int`）
	 * <br/>
	 * CREATIVE/SPEED 升级时更新缓存字段。
	 *
	 * @param value 要设置的 ticksRequired 值
	 */
	@Accessor("ticksRequired")
	void productivebeesgenesis$setTicksRequired(int value);

	/**
	 * 访问 TileEntityFactory 的 private 字段 `operationsPerTick`（类型：`int`）
	 * <br/>
	 * 读取当前缓存值用于 CREATIVE 判断。
	 *
	 * @return 原始 operationsPerTick 字段值
	 */
	@Accessor("operationsPerTick")
	int productivebeesgenesis$getOperationsPerTick();

	/**
	 * 访问 TileEntityFactory 的 private 字段 `ticksRequired`（类型：`int`）
	 * <br/>
	 * 读取当前缓存值用于 CREATIVE 判断。
	 *
	 * @return 原始 ticksRequired 字段值
	 */
	@Accessor("ticksRequired")
	int productivebeesgenesis$getTicksRequired();
}
