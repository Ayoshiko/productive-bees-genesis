package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * PB配方处理上下文接口
 * <br/>
 * 定义 Factory TileEntity 需要向 {@link PbRecipeProcessor} 提供的依赖。
 * 三个 Factory TileEntity 实现此接口，将 PB 配方处理委托给 PbRecipeProcessor，
 * 消除约400行重复代码。
 * <p>
 * 遵循依赖倒置原则：PbRecipeProcessor 依赖此抽象而非具体 TileEntity，
 * 降低耦合度，便于后续扩展新的工厂类型。
 */
public interface PbRecipeContext {

	/** 获取世界实例（用于配方查找和随机数） */
	Level level();

	/**
	 * 获取能量容器（用于能量检查和消耗）
	 * <br/>
	 * 返回 MachineEnergyContainer 而非 IEnergyContainer，
	 * 因为 PB 处理需要调用 getEnergyPerTick() 获取每 tick 能量消耗。
	 */
	MachineEnergyContainer<?> energyContainer();

	/** 获取指定进程的输入槽 */
	IInventorySlot inputSlot(int process);

	/** 获取指定进程的主输出槽 */
	IInventorySlot primaryOutputSlot(int process);

	/** 获取指定进程的副输出槽1（可能为null） */
	IInventorySlot secondaryOutputSlot(int process);

	/** 获取指定进程的副输出槽2 */
	IInventorySlot tertiaryOutputSlot(int process);

	/** 获取共享流体输出槽 */
	IExtendedFluidTank fluidOutputTank();

	/** 获取进程总数 */
	int processes();

	/** 获取基础处理时间（BASE_TICKS_REQUIRED） */
	int baseTicksRequired();

	/** 工厂是否能运行（红石控制等） */
	boolean canFunction();

	/**
	 * 设置指定进程的PB激活状态
	 * <br/>
	 * 注意：不直接使用 setActiveState 名称，因为父类的 setActiveState 是 protected，
	 * 接口方法必须是 public，会导致访问权限冲突。
	 * 实现方在此方法内部调用 setActiveState 即可。
	 */
	void setPbActiveState(boolean active, int process);

	/** 获取生产力倍率（影响输出数量和输入消耗） */
	int productivityModifier();

	/**
	 * 获取每tick操作次数（受速度升级影响）
	 * <br/>
	 * 对应 MekanismUtils.getOperationsPerTick(this, BASE_TICKS_REQUIRED, 1)，
	 * MU扩展下可大于1，未加载MU时返回1。
	 */
	int operationsPerTick();

	/**
	 * 根据基础时间计算受速度升级影响的实际处理时间
	 * <br/>
	 * 对应 MekanismUtils.getTicks(this, baseTime)。
	 *
	 * @param baseTime 基础处理时间
	 * @return 受升级影响的实际处理时间
	 */
	int getTicksForBase(int baseTime);

	/**
	 * 检查输入物品是否有SMELTING配方（用于PB处理前的优先级判断）
	 * <br/>
	 * 对应 getRecipeType().getInputCache().containsInput(level, input)。
	 *
	 * @param input 输入物品
	 * @return true 如果存在SMELTING配方
	 */
	boolean containsSmeltingInput(ItemStack input);
}
