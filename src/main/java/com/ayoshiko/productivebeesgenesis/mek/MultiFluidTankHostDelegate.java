package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 多流体槽委托 — 封装 {@link IMultiFluidTankHost} 和 {@link PbRecipeContext} 流体相关方法
 * <br/>
 * 从 {@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory}
 * 和 {@link com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory} 抽取,
 * 遵循单一职责原则:集中管理多流体槽状态字段与查询逻辑,降低工厂方块实体的行数至 500 以内。
 * <p>
 * 设计原则:
 * <ul>
 *   <li>SRP — 仅负责多流体槽状态管理与查询,不涉及配方处理或 GUI 逻辑</li>
 *   <li>DIP — 通过 {@link Supplier}&lt;{@link Level}&gt; 依赖抽象,不直接持有 TileEntity 引用</li>
 *   <li>DRY — 消除 ME/EME 两个工厂类中重复的多流体槽方法实现</li>
 * </ul>
 * <p>
 * 线程安全:服务端单线程执行,字段无需同步。
 * {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder} 内部使用
 * ConcurrentHashMap + CopyOnWriteArrayList 保证并发安全。
 *
 * @since M1-2/M1-3 文件拆分
 */
public final class MultiFluidTankHostDelegate {

	/** 流体输出槽 — 共享,接收 PB 配方的流体输出 */
	private IExtendedFluidTank fluidOutputTank;
	/** Task 9/13: 流体输出槽持有者 — MULTI_PER_FLUID 持有 MultiFluidTankHolder;SINGLE 持有 FluidTankHelper */
	private IFluidTankHolder fluidOutputHolder;
	/** Task 8: 客户端同步的流体槽位数;Task 1: 初始值由 tankCountSetter 构造时设置 */
	private int fluidOutputTankCount;
	/** Task 8: 客户端同步的多流体槽模式状态(由 SyncableBoolean 同步,确保 Tab 显示与服务端一致) */
	private boolean isMultiFluidModeSynced = false;
	/** Task 6: 孤儿多流体槽 NBT — MULTI→SINGLE 降级时保留的多流体槽数据,saveAdditional 显式写出确保持久化 */
	@Nullable
	private CompoundTag orphanedMultiFluidTanksNbt;

	/** 宿主 Level 提供器 — 用于判断客户端/服务端,不直接持有 TileEntity 引用避免循环依赖 */
	private final Supplier<Level> levelSupplier;

	public MultiFluidTankHostDelegate(Supplier<Level> levelSupplier) {
		this.levelSupplier = levelSupplier;
	}

	// ===== Setter — 供 TileEntity 在 getInitialFluidTanks 等方法中赋值 =====

	public void setFluidOutputTank(IExtendedFluidTank tank) { this.fluidOutputTank = tank; }
	public void setFluidOutputHolder(IFluidTankHolder holder) { this.fluidOutputHolder = holder; }
	public void setFluidOutputTankCount(int count) { this.fluidOutputTankCount = count; }
	public void setMultiFluidModeSynced(boolean synced) { this.isMultiFluidModeSynced = synced; }

	// ===== Getter — 供 TileEntity 在 onUpdateServer/addContainerTrackers 等方法中访问 =====

	public IExtendedFluidTank getFluidOutputTank() { return fluidOutputTank; }
	public IFluidTankHolder getFluidOutputHolder() { return fluidOutputHolder; }
	public int getFluidOutputTankCount() { return fluidOutputTankCount; }

	/** @return 是否为客户端(level 为 null 时按服务端处理) */
	private boolean isClientSide() {
		Level level = levelSupplier.get();
		return level != null && level.isClientSide();
	}

	// ===== IMultiFluidTankHost 接口实现(供 GUI 渲染层查询) =====

	public int getFluidTankCount() {
		return MultiFluidTankHostHelper.getFluidTankCount(fluidOutputHolder, fluidOutputTankCount, isClientSide());
	}

	public IExtendedFluidTank getFluidTank(int index) {
		return MultiFluidTankHostHelper.getFluidTank(fluidOutputHolder, fluidOutputTank, index);
	}

	public List<IExtendedFluidTank> getFluidTanks() {
		return MultiFluidTankHostHelper.getFluidTanks(fluidOutputHolder, fluidOutputTank);
	}

	/**
	 * 客户端返回同步值,服务端基于 holder 类型判断
	 * <br/>
	 * 字段隐藏修复:使用自身 fluidOutputHolder 而非父类字段
	 */
	public boolean isMultiFluidMode() {
		return isClientSide() ? isMultiFluidModeSynced : MultiFluidTankHostHelper.isMultiFluidMode(fluidOutputHolder);
	}

	/** 返回 SyncableBoolean 同步值,绕过 level 判断确保 GUI 构造期(level 为 null)也能获取正确值 */
	public boolean isMultiFluidModeSynced() {
		return isMultiFluidModeSynced;
	}

	public void setOrphanedMultiFluidTanksNbt(@Nullable CompoundTag nbt) {
		this.orphanedMultiFluidTanksNbt = nbt;
	}

	public @Nullable CompoundTag getOrphanedMultiFluidTanksNbt() {
		return orphanedMultiFluidTanksNbt;
	}

	// ===== PbRecipeContext 流体相关方法(供配方处理逻辑查询) =====

	public IExtendedFluidTank fluidOutputTank() {
		return fluidOutputTank;
	}

	public IExtendedFluidTank fluidOutputTankForInsert(FluidStack stack) {
		return MultiFluidTankHostHelper.fluidOutputTankForInsert(fluidOutputHolder, fluidOutputTank, stack);
	}

	public int fluidOutputTankCount() {
		return MultiFluidTankHostHelper.fluidOutputTankCount(fluidOutputHolder);
	}

	public IExtendedFluidTank fluidOutputTank(int index) {
		return MultiFluidTankHostHelper.fluidOutputTank(fluidOutputHolder, fluidOutputTank, index);
	}

	public boolean isFluidTankTypeMismatch(FluidStack stack) {
		return MultiFluidTankHostHelper.isFluidTankTypeMismatch(fluidOutputHolder, fluidOutputTank, stack);
	}

	public boolean areAllFluidTanksFull() {
		return MultiFluidTankHostHelper.areAllFluidTanksFull(fluidOutputHolder, fluidOutputTank);
	}

	public boolean canAllocateNewFluidTank() {
		return MultiFluidTankHostHelper.canAllocateNewFluidTank(fluidOutputHolder);
	}
}
