package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
	 * TileEntityElectricMachine 访问器：暴露包私有字段供外部包访问
	 * <br/>
	 * TileEntityElectricMachine中的inputSlot/outputSlot/energySlot/energyContainer
	 * 都是包私有的，无法从外部包直接访问。通过Accessor Mixin暴露getter/setter。
	 *
	 * <p>
	 * <b>版本敏感性</b>：本 @Accessor 依赖 Mekanism 10.7.19.85+ 的字段名稳定性。
	 * 通过 {@code @Accessor} 访问 {@code TileEntityElectricMachine#inputSlot}（包私有 InputInventorySlot）、
	 * {@code TileEntityElectricMachine#outputSlot}（包私有 OutputInventorySlot）、
	 * {@code TileEntityElectricMachine#energySlot}（包私有 EnergyInventorySlot）、
	 * {@code TileEntityElectricMachine#energyContainer}（包私有 MachineEnergyContainer）字段。
	 * 如果 Mekanism 重命名上述任一字段，本 Mixin 将无法应用，需同步更新本类对应的 @Accessor target。
	 *
	 * @since 1.0.0
	 */
@Mixin(value = TileEntityElectricMachine.class, remap = false)
public interface TileEntityElectricMachineAccessor {

	@Accessor("inputSlot")
	InputInventorySlot productivebeesgenesis$getInputSlot();

	@Accessor("inputSlot")
	void productivebeesgenesis$setInputSlot(InputInventorySlot slot);

	@Accessor("outputSlot")
	OutputInventorySlot productivebeesgenesis$getOutputSlot();

	@Accessor("outputSlot")
	void productivebeesgenesis$setOutputSlot(OutputInventorySlot slot);

	@Accessor("energySlot")
	EnergyInventorySlot productivebeesgenesis$getEnergySlot();

	@Accessor("energySlot")
	void productivebeesgenesis$setEnergySlot(EnergyInventorySlot slot);

	@Accessor("energyContainer")
	MachineEnergyContainer<?> productivebeesgenesis$getEnergyContainer();

	@Accessor("energyContainer")
	void productivebeesgenesis$setEnergyContainer(MachineEnergyContainer<?> container);
}
