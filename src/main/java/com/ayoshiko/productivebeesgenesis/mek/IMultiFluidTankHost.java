package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;

import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 多流体槽查询接口
 * <br/>
 * SRP:仅暴露 tank 查询能力,供客户端 GUI 通过 IMultiFluidTankHost 抽象访问多流体槽,
 * 不依赖具体 TileEntity 类型(原版/ME/EME 工厂),遵循依赖倒置原则。
 * <p>
 * 与 {@link PbRecipeContext} 的区别:PbRecipeContext 面向配方处理逻辑,
 * 本接口面向 GUI 渲染层,仅暴露必要的查询能力。
 *
 * @since Task 8
 */
public interface IMultiFluidTankHost {

	/**
	 * 返回流体输出槽总数
	 * <br/>
	 * 客户端返回容器同步值(由 addContainerTrackers 同步),服务端返回 MultiFluidTankHolder.getTankCount()。
	 * SINGLE 模式始终返回 1。
	 *
	 * @return 流体槽总数(>=1)
	 */
	int getFluidTankCount();

	/**
	 * 按索引返回流体输出槽
	 * <br/>
	 * MULTI_PER_FLUID 模式按分配顺序返回;SINGLE 模式忽略 index 返回主槽。
	 *
	 * @param index 槽位索引(0-based)
	 * @return 指定索引的槽位
	 */
	IExtendedFluidTank getFluidTank(int index);

	/**
	 * 返回所有流体输出槽列表
	 * <br/>
	 * 供 GuiFluidGauge 构造函数第二个 Supplier 参数使用,MEK 内部 tooltip 比对依赖此列表。
	 *
	 * @return 槽位列表(按分配顺序)
	 */
	List<IExtendedFluidTank> getFluidTanks();

	/**
	 * 是否启用多流体槽模式(MULTI_PER_FLUID)
	 * <br/>
	 * Tab 显示条件基于**模式**(MULTI 已启用)而非**当前槽位数**:
	 * GUI 元素在 {@code addGuiElements} 中一次性创建,无法动态增删;模式是配置级别的稳定状态,
	 * 机器空闲时槽位数 count=1 但模式仍是 MULTI,Tab 应始终可见。
	 * <p>
	 * 默认返回 false(SINGLE 模式),由持有 {@code MultiFluidTankHolder} 的实现覆盖为 true。
	 *
	 * @return true 若当前为 MULTI 模式;false 若为 SINGLE 模式
	 */
	default boolean isMultiFluidMode() {
		return false;
	}

	/**
	 * Task 1(选项 A 决策):返回客户端同步的多流体槽模式状态
	 * <br/>
	 * Tab 显示条件基于 isMultiFluidModeSynced 同步值(选项 A 决策,放弃旧存档隐藏约束):
	 * GUI 构造期 {@code tile.getLevel()} 可能为 null,此时 {@link #isMultiFluidMode()} 走服务端分支
	 * 基于 holder 类型判断,而客户端 holder 类型由本地 ModConfig.SERVER 决定,可能与服务端不一致。
	 * 本方法直接返回 SyncableBoolean 同步的字段值,绕过 level 判断,确保客户端 Tab 显示与服务端一致。
	 * <p>
	 * 默认返回 false,由 {@link AbstractMekCentrifugeFactory} 覆盖返回 {@code isMultiFluidModeSynced} 字段。
	 *
	 * @return true 若服务端同步的模式为 MULTI;false 若为 SINGLE 或未同步
	 */
	default boolean isMultiFluidModeSynced() {
		return false;
	}

	/**
	 * Task 6: 存入孤儿多流体槽 NBT(MULTI→SINGLE 降级数据保护)
	 * <br/>
	 * <b>orphaned NBT 字段方案原理:</b>Minecraft BlockEntity 的 {@code saveAdditional} 接收全新 CompoundTag,
	 * {@code loadAdditional} 接收临时 nbt 实例,两次调用之间不存在 NBT 数据的自动继承。
	 * SINGLE 模式下 {@code saveAdditional} 不会调用 {@code writeToNBT},数据会在第一次保存后永久丢失。
	 * orphaned NBT 字段方案在 BlockEntity 实例中存储孤儿 NBT,{@code saveAdditional} 显式写出,确保持久化。
	 * <p>
	 * 默认空实现(OCP:不破坏现有实现),由 {@link AbstractMekCentrifugeFactory} 覆盖。
	 *
	 * @param nbt 多流体槽 NBT 数据(可为 null 表示清除)
	 */
	default void setOrphanedMultiFluidTanksNbt(@Nullable CompoundTag nbt) {
		// 默认空实现 — 非 AbstractMekCentrifugeFactory 的实现无需关注 orphaned NBT
	}

	/**
	 * Task 6: 获取孤儿多流体槽 NBT(MULTI→SINGLE 降级数据保护)
	 * <br/>
	 * 供 {@code saveAdditional} 在 SINGLE 模式下写出孤儿 NBT,确保持久化到磁盘。
	 * 默认返回 null(无孤儿数据)。
	 *
	 * @return 孤儿 NBT;无孤儿数据时返回 null
	 */
	default @Nullable CompoundTag getOrphanedMultiFluidTanksNbt() {
		return null;
	}
}
