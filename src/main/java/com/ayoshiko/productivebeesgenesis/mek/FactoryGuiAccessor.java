package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * 工厂 GUI 访问委托 — 封装 GUI/Container 查询方法，减少宿主类行数（Task 9）。
	 * <br/>
	 * 委托模式：持有对 {@link AbstractMekCentrifugeFactory} 的引用，通过同包访问 protected 字段。
	 * 副输出槽1 通过宿主的 {@code secondaryOutputSlot} 方法间接访问（processInfoSlots 来自 MEK 父类，跨包不可见）。
	 *
	 * @author ayoshiko
	 * @since Task 9
	 */
public final class FactoryGuiAccessor {

	/** 宿主工厂引用 */
	private final AbstractMekCentrifugeFactory factory;

	public FactoryGuiAccessor(AbstractMekCentrifugeFactory factory) {
		this.factory = factory;
	}

	/** 获取副输出槽1 — GUI显示用，委托调用宿主的 secondaryOutputSlot 方法（processInfoSlots 跨包不可见） */
	@Nullable
	public IInventorySlot getSecondaryOutputSlot(int processIndex) {
		return factory.secondaryOutputSlot(processIndex);
	}

	/** 获取副输出槽2 — GUI显示用，同包访问宿主 protected 字段 */
	@NotNull
	public IInventorySlot getTertiaryOutputSlot(int processIndex) {
		return factory.tertiaryOutputSlots[processIndex];
	}

	/** 获取流体输出槽 — GUI显示用，同包访问宿主 protected 字段 */
	@NotNull
	public IExtendedFluidTank getFluidOutputTank() {
		return factory.fluidOutputTank;
	}
}
