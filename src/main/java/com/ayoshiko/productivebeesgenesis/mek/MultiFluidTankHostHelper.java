package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Collections;
import java.util.List;

/**
	 * 多流体槽查询委托 Helper(SRP:集中 IMultiFluidTankHost 与 PbRecipeContext 流体槽相关实现)
	 * <br/>
	 * 抽取三个工厂类({@link AbstractMekCentrifugeFactory} / {@link TileEntityExtraMekCentrifugeFactory} /
	 * {@link TileEntityEMExtraMekCentrifugeFactory})中重复的流体槽查询/状态检查逻辑,
	 * 消除代码重复,遵循 DRY 原则,同时降低工厂文件行数至 500 以内。
	 * <p>
	 * <b>线程安全:</b>所有方法均为无状态静态方法,依赖入参的线程安全性。
	 * {@link MultiFluidTankHolder} 内部使用 ConcurrentHashMap + CopyOnWriteArrayList 保证并发安全。
	 * <p>
	 * <b>NPE 防御:</b>所有方法对 holder/primary 入参均做 null 检查,避免 NPE。
	 *
	 * @since Task 11
	 */
public final class MultiFluidTankHostHelper {

	/** 工具类禁止实例化 */
	private MultiFluidTankHostHelper() {}

	// ===== IMultiFluidTankHost 接口实现(供 GUI 渲染层查询) =====

	/**
	 * 判断是否为多流体槽模式(MULTI_PER_FLUID)
	 * <br/>
	 * 基于持有者类型而非当前槽位数判断:GUI 元素在 {@code addGuiElements} 中一次性创建,
	 * 无法动态增删;模式是配置级别的稳定状态,机器空闲时槽位数=1 但模式仍是 MULTI,Tab 应始终可见。
	 *
	 * @param holder 流体槽持有者(可为 null)
	 * @return true 若 holder 是 {@link MultiFluidTankHolder}(MULTI 模式);false 若为 SINGLE 模式
	 */
	public static boolean isMultiFluidMode(IFluidTankHolder holder) {
		return holder instanceof MultiFluidTankHolder;
	}

	/**
	 * 返回流体槽总数(IMultiFluidTankHost.getFluidTankCount)
	 * <br/>
	 * Task 2: 客户端返回同步值 fallbackCount,避免 holder 类型不一致问题;
	 * 服务端 MULTI_PER_FLUID 模式返回 {@link MultiFluidTankHolder#getTankCount()},
	 * SINGLE 模式返回 fallbackCount。
	 *
	 * @param holder        流体槽持有者(可为 null)
	 * @param fallbackCount 客户端同步的槽位数值(SINGLE 模式为 1)
	 * @param clientSide    是否为客户端(客户端返回同步值)
	 * @return 流体槽总数
	 */
	public static int getFluidTankCount(IFluidTankHolder holder, int fallbackCount, boolean clientSide) {
		// 客户端返回同步值,避免 holder 类型不一致问题
		if (clientSide) {
			return fallbackCount;
		}
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			return multiHolder.getTankCount();
		}
		return fallbackCount;
	}

	/**
	 * 按索引返回流体槽(IMultiFluidTankHost.getFluidTank)
	 * <br/>
	 * MULTI_PER_FLUID 模式按索引返回对应槽;越界或 SINGLE 模式返回 primary(主槽)。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(SINGLE 模式或 fallback 使用,可为 null)
	 * @param index   槽位索引(0-based)
	 * @return 指定索引的槽位;SINGLE 模式或越界返回主槽
	 */
	public static IExtendedFluidTank getFluidTank(IFluidTankHolder holder, IExtendedFluidTank primary, int index) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			List<IExtendedFluidTank> tanks = multiHolder.getTanks();
			if (index >= 0 && index < tanks.size()) {
				return tanks.get(index);
			}
		}
		return primary;
	}

	/**
	 * 返回所有流体槽列表(IMultiFluidTankHost.getFluidTanks)
	 * <br/>
	 * MULTI_PER_FLUID 模式返回 {@link MultiFluidTankHolder#getTanks()} 副本;
	 * SINGLE 模式返回包含主槽的单例列表(主槽为 null 时返回空列表)。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(SINGLE 模式使用,可为 null)
	 * @return 槽位列表(按分配顺序,永不返回 null)
	 */
	public static List<IExtendedFluidTank> getFluidTanks(IFluidTankHolder holder, IExtendedFluidTank primary) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			return multiHolder.getTanks();
		}
		return primary != null ? Collections.singletonList(primary) : Collections.emptyList();
	}

	// ===== PbRecipeContext 流体槽相关 override =====

	/**
	 * 返回适合插入指定流体的输出槽(PbRecipeContext.fluidOutputTankForInsert)
	 * <br/>
	 * MULTI_PER_FLUID 模式按流体类型路由到独立槽;SINGLE 模式或路由失败返回 primary。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(fallback 使用,可为 null)
	 * @param stack   待插入流体(仅取类型信息)
	 * @return 目标槽;MULTI 模式无匹配槽且达上限时返回 primary
	 */
	public static IExtendedFluidTank fluidOutputTankForInsert(
		IFluidTankHolder holder,
		IExtendedFluidTank primary,
		FluidStack stack
	) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			IExtendedFluidTank tank = multiHolder.getTankForInsert(stack);
			if (tank != null) {
				return tank;
			}
		}
		return primary;
	}

	/** 预留一种活跃配方的流体槽，不会为已映射类型继续扩容。 */
	public static void reserveFluidOutputType(IFluidTankHolder holder, FluidStack stack) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			multiHolder.reserveTankForType(stack);
		}
	}

	/**
	 * 返回流体输出槽总数(PbRecipeContext.fluidOutputTankCount)
	 * <br/>
	 * MULTI_PER_FLUID 模式返回 {@link MultiFluidTankHolder#getTankCount()};SINGLE 模式返回 1。
	 *
	 * @param holder 流体槽持有者(可为 null)
	 * @return 槽位总数(SINGLE 模式返回 1)
	 */
	public static int fluidOutputTankCount(IFluidTankHolder holder) {
		return holder instanceof MultiFluidTankHolder multiHolder ? multiHolder.getTankCount() : 1;
	}

	/**
	 * 按索引返回流体输出槽(PbRecipeContext.fluidOutputTank(int))
	 * <br/>
	 * MULTI_PER_FLUID 模式按索引返回;越界或 SINGLE 模式返回 primary。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(fallback 使用,可为 null)
	 * @param index   槽位索引(0-based)
	 * @return 指定索引的槽位;越界或 SINGLE 模式返回主槽
	 */
	public static IExtendedFluidTank fluidOutputTank(IFluidTankHolder holder, IExtendedFluidTank primary, int index) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			List<IExtendedFluidTank> tanks = multiHolder.getTanks();
			if (index >= 0 && index < tanks.size()) {
				return tanks.get(index);
			}
		}
		return primary;
	}

	/**
	 * 检查流体槽类型不匹配(PbRecipeContext.isFluidTankTypeMismatch)
	 * <br/>
	 * MULTI_PER_FLUID 模式委托 {@link MultiFluidTankHolder#isTypeMismatch};
	 * SINGLE 模式主槽非空且类型不同返回 true。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(SINGLE 模式检查,可为 null)
	 * @param stack   待检查流体
	 * @return true 若类型不匹配且无法插入;空流体始终返回 false
	 */
	public static boolean isFluidTankTypeMismatch(IFluidTankHolder holder, IExtendedFluidTank primary, FluidStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			return multiHolder.isTypeMismatch(stack);
		}
		if (primary == null) {
			return false;
		}
		FluidStack existing = primary.getFluid();
		return !existing.isEmpty() && !FluidStack.isSameFluidSameComponents(existing, stack);
	}

	/**
	 * 检查所有已分配流体槽是否都满载(PbRecipeContext.areAllFluidTanksFull)
	 * <br/>
	 * MULTI_PER_FLUID 模式遍历所有槽检查满载;SINGLE 模式检查主槽。
	 * Task 4 修复:原 cachedFluidTankFull 仅检查主槽,多槽模式下其他槽可能仍有空间但被错误判定为满。
	 *
	 * @param holder  流体槽持有者(可为 null)
	 * @param primary 主槽(SINGLE 模式检查,可为 null)
	 * @return true 如果所有已分配槽都满载;空槽列表返回 false
	 */
	public static boolean areAllFluidTanksFull(IFluidTankHolder holder, IExtendedFluidTank primary) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			List<IExtendedFluidTank> tanks = multiHolder.getTanks();
			if (tanks.isEmpty()) {
				return false;
			}
			for (IExtendedFluidTank tank : tanks) {
				if (tank.getFluidAmount() < tank.getCapacity()) {
					return false;
				}
			}
			return true;
		}
		return primary != null && primary.getFluidAmount() >= primary.getCapacity();
	}

	/**
	 * 检查是否还能分配新槽接收新流体类型(PbRecipeContext.canAllocateNewFluidTank)
	 * <br/>
	 * MULTI_PER_FLUID 模式检查未映射空槽数量;SINGLE 模式始终返回 false。
	 * v2.0.9 修复 BUG #1:原实现 `getTankCount() < getMaxTanks()` 永远返回 false,
	 * 因为 tanksInOrder 构造时预分配了 maxTanks 个槽,getTankCount() 始终等于 getMaxTanks()。
	 * 改用 getEmptyTankCount() 检查未映射空槽数,正确反映可分配状态。
	 *
	 * @param holder 流体槽持有者(可为 null)
	 * @return true 如果有未映射空槽可分配;SINGLE 模式返回 false
	 */
	public static boolean canAllocateNewFluidTank(IFluidTankHolder holder) {
		if (holder instanceof MultiFluidTankHolder multiHolder) {
			return multiHolder.getEmptyTankCount() > 0;
		}
		return false;
	}
}
