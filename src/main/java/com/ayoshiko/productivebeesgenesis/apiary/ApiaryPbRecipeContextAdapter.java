package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
	 * 蜂箱 PbRecipeContext 适配器
	 * <br/>
	 * 将 {@link TileEntityMekApiary} 的 {@link PbRecipeContext} 接口实现委托到此适配器，
	 * 降低主类代码量，遵循单一职责原则。
	 * <p>
	 * 蜂箱不走 PbRecipeProcessor 管线，此处实现仅为满足 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase}
	 * 继承的 PbRecipeContext 契约，供 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputPusher} 遍历输出槽。
	 * <p>
	 * 输出槽映射：processes() = outputSlots.size() / 3，每进程暴露3个输出槽。
	 * 蜂笼槽和喂食器槽不通过这些方法暴露，实现"AE2隐藏"。
	 * <p>
	 * no-op 方法（7个）：
	 * <ul>
	 *   <li>{@link #setPbActiveState} — 蜂箱 active 状态由 ApiaryTickHandler 管理</li>
	 *   <li>{@link #productivebeesgenesis$beginOutputBatch} — 蜂箱不使用批量插入优化</li>
	 *   <li>{@link #productivebeesgenesis$endOutputBatch} — 蜂箱不使用批量插入优化</li>
	 *   <li>{@link #productivebeesgenesis$onProcessActivated} — 激活状态由 ApiaryTickHandler 管理</li>
	 *   <li>{@link #productivebeesgenesis$onProcessDeactivated} — 激活状态由 ApiaryTickHandler 管理</li>
	 *   <li>{@link #productivebeesgenesis$hasActiveProcess} — 蜂箱不走 PbRecipeProcessor，始终 false</li>
	 *   <li>{@link #containsSmeltingInput} — 蜂箱不处理 SMELTING 配方</li>
	 * </ul>
	 */
class ApiaryPbRecipeContextAdapter implements PbRecipeContext {

	/** 所属方块实体引用 — 用于访问 slotManager、level、energyContainer 等 */
	private final TileEntityMekApiary tile;

	ApiaryPbRecipeContextAdapter(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	@Override
	public Level level() {
		return tile.getLevel();
	}

	@Override
	public MachineEnergyContainer<?> energyContainer() {
		return tile.accessor().productivebeesgenesis$getEnergyContainer();
	}

	/**
	 * CREATIVE 升级兜底
	 * <br/>
	 * 蜂箱不支持CREATIVE升级，此方法始终返回false。
	 * 仅为满足 PbRecipeContext 接口契约而保留。
	 */
	@Override
	public boolean hasCreativeUpgrade() {
		return false;
	}

	/**
	 * 蜂箱的进程数 — 输出槽数量向上取整 / 3
	 * <br/>
	 * 蜂箱没有离心机的"并行进程"概念，此处将9-18个输出槽按每进程3个分组，
	 * 供 Ae2OutputPusher 遍历所有输出槽进行推送。
	 * 使用向上取整 (outputCount + 2) / 3，确保非 3 倍数的尾部槽位也被 AE2 推送。
	 */
	@Override
	public int processes() {
		int outputCount = tile.getOutputSlots().size();
		return Math.max(1, (outputCount + 2) / 3);
	}

	@Override
	public IInventorySlot inputSlot(int process) {
		// 蜂箱不通过 AE2 推送输入槽，返回蜂笼输入槽仅为满足接口契约
		return tile.getCageInSlot();
	}

	@Override
	public IInventorySlot primaryOutputSlot(int process) {
		return getOutputSlotByIndex(process * 3);
	}

	@Override
	public IInventorySlot secondaryOutputSlot(int process) {
		return getOutputSlotByIndex(process * 3 + 1);
	}

	@Override
	public IInventorySlot tertiaryOutputSlot(int process) {
		return getOutputSlotByIndex(process * 3 + 2);
	}

	/**
	 * 按索引获取输出槽，越界时返回 null
	 * <br/>
	 * 防御性检查：工厂版输出槽数量为9-18，processes() 保证索引不超过 outputSlots.size()，
	 * 但若未来输出槽数量变化，此处仍安全返回 null（Ae2OutputPusher.collectSlot 处理 null）。
	 */
	@Nullable
	private IInventorySlot getOutputSlotByIndex(int index) {
		List<BasicInventorySlot> slots = tile.getOutputSlots();
		if (index < 0 || index >= slots.size()) return null;
		return slots.get(index);
	}

	@Override
	public IExtendedFluidTank fluidOutputTank() {
		return tile.getFluidTank();
	}

	@Override
	public int baseTicksRequired() {
		return TileEntityMekApiary.APIARY_TICKS_REQUIRED;
	}

	/**
	 * 工厂是否能运行 — 委托给 tile 调用父类 canFunction()
	 * <br/>
	 * 适配器无法直接调用 super.canFunction()，通过 {@link TileEntityMekApiary#callSuperCanFunction()}
	 * 间接访问父类红石控制逻辑。
	 */
	@Override
	public boolean canFunction() {
		return tile.callSuperCanFunction();
	}

	@Override
	public void setPbActiveState(boolean active, int process) {
		// no-op：蜂箱的 active 状态由 ApiaryTickHandler 管理
	}

	@Override
	public int productivityModifier() {
		return 1;
	}

	@Override
	public int operationsPerTick() {
		return 1;
	}

	@Override
	public int getTicksForBase(int baseTime) {
		return baseTime;
	}

	@Override
	public boolean containsSmeltingInput(ItemStack input) {
		// 蜂箱不处理 SMELTING 配方
		return false;
	}

	@Override
	public boolean productivebeesgenesis$hasOutputItems() {
		return tile.getSlotManager().hasOutputItems();
	}

	@Override
	public boolean productivebeesgenesis$outputSlotsFull() {
		return tile.getSlotManager().isOutputFull();
	}

	@Override
	public void productivebeesgenesis$updateOutputSlotFlags() {
		tile.getSlotManager().updateOutputSlotFlags();
	}

	@Override
	public void productivebeesgenesis$beginOutputBatch() {
		// no-op：蜂箱不使用批量插入优化
	}

	@Override
	public void productivebeesgenesis$endOutputBatch(int process) {
		// no-op：蜂箱不使用批量插入优化
	}

	@Override
	public void productivebeesgenesis$onProcessActivated(int process) {
		// no-op：蜂箱的激活状态由 ApiaryTickHandler 管理
	}

	@Override
	public void productivebeesgenesis$onProcessDeactivated(int process) {
		// no-op：蜂箱的激活状态由 ApiaryTickHandler 管理
	}

	@Override
	public boolean productivebeesgenesis$hasActiveProcess() {
		// 蜂箱不走 PbRecipeProcessor 管线，始终返回 false
		return false;
	}
}
