package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** 只有控制器携带结构状态，普通部件不增加状态组合或独立同步。 */
public final class MachineControllerBlock extends MachinePartBlock {
	public static final EnumProperty<MachineVisualState> STATUS = EnumProperty.create("status", MachineVisualState.class);
	MachineControllerBlock() { super(StructureRole.CONTROLLER); registerDefaultState(defaultBlockState().setValue(STATUS, MachineVisualState.UNFORMED)); }
	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder); builder.add(STATUS);
	}
	@Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
		super.appendHoverText(stack, context, lines, flag);
		lines.add(Component.translatable("tooltip.productivebeesgenesis.machine.prototype").withStyle(ChatFormatting.GRAY));
	}
}
