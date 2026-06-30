package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.api.inventory.IInventorySlot;
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
