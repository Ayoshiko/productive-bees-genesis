package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureTemplate;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineRegion;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import com.mojang.authlib.GameProfile;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** 夹具搭建成本不计入正式服务预算；它不直接调用扫描或形成事务。 */
record MachineProbeFixture(StructureTemplate template, BlockPos pos, Direction facing, MachineControllerEntity core) {
	static MachineProbeFixture place(ServerLevel level, StructureTemplate template, BlockPos pos, Direction facing, UUID owner) {
		var transform = template.geometry().at(pos, facing);
		var region = MachineRegion.at(template.geometry(), pos, facing);
		for (var section : region.sections()) { level.setChunkForced(section.x(), section.z(), true); level.getChunk(section.x(), section.z()); }
		for (long i = 0; i < template.geometry().size().volume(); i++) {
			var local = template.geometry().size().positionAt(i); var rule = template.cellAt(local);
			var role = rule.roles().contains(StructureRole.CASING) ? StructureRole.CASING : rule.roles().iterator().next();
			if (role == StructureRole.CASING && local.getY() == 2) role = StructureRole.GLASS;
			var state = role == StructureRole.AIR ? Blocks.AIR.defaultBlockState() : MachineContent.block(role).defaultBlockState();
			if (rule.facing() != null) state = state.setValue(MachinePartBlock.FACING, transform.toWorldDirection(rule.facing()));
			level.setBlockAndUpdate(transform.toWorld(local), state);
		}
		var core = (MachineControllerEntity) level.getBlockEntity(pos);
		var player = FakePlayerFactory.get(level, new GameProfile(owner, "StructureProbe"));
		core.getBlockState().getBlock().setPlacedBy(level, pos, core.getBlockState(), player, new ItemStack(MachineContent.block(StructureRole.CONTROLLER)));
		return new MachineProbeFixture(template, pos, facing, core);
	}
	BlockPos world(BlockPos local) { return template.geometry().at(pos, facing).toWorld(local); }
	void remove(ServerLevel level) {
		for (long i = 0; i < template.geometry().size().volume(); i++) level.removeBlock(world(template.geometry().size().positionAt(i)), false);
	}
}
